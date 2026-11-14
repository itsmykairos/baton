package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StageStarted;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConditionalStagesTest {

    private static final ContextKey<Boolean> PREMIUM = ContextKey.of("premium", Boolean.class);
    private static final ContextKey<String> OFFER = ContextKey.of("offer", String.class);
    private static final ContextKey<String> EMAIL = ContextKey.of("email", String.class);

    @Test
    void falseConditionSkipsTheStageWithoutRunningIt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(events::add)
                .stage(
                        "offer",
                        context -> {
                            runs.incrementAndGet();
                            return ContextPatch.builder().put(OFFER, "gold").build();
                        },
                        StageOptions.defaults()
                                .withCondition(context -> context.find(PREMIUM).orElse(false)))
                .stage("next", context -> ContextPatch.empty())
                .build();

        PipelineResult result =
                pipeline.run(ContextView.builder().put(PREMIUM, false).build());

        StageResult offer = result.stages().getFirst();
        assertThat(runs).hasValue(0);
        assertThat(offer.status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(offer.attempts()).isZero();
        assertThat(offer.workerTermination()).isEqualTo(WorkerTermination.NOT_STARTED);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.context().contains(OFFER)).isFalse();
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.SKIPPED, StageStatus.SUCCEEDED);

        awaitRunFinished(events);
        assertThat(events)
                .noneMatch(event -> event instanceof StageStarted started
                        && started.path().stageId().equals("offer"));
        assertThat(events)
                .filteredOn(StageFinished.class::isInstance)
                .map(StageFinished.class::cast)
                .extracting(StageFinished::status)
                .containsExactly(StageStatus.SKIPPED, StageStatus.SUCCEEDED);
    }

    @Test
    void trueConditionRunsTheStage() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "offer",
                        context -> ContextPatch.builder().put(OFFER, "gold").build(),
                        StageOptions.defaults().withCondition(context -> context.get(PREMIUM)))
                .build();

        PipelineResult result =
                pipeline.run(ContextView.builder().put(PREMIUM, true).build());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(result.context().get(OFFER)).isEqualTo("gold");
    }

    @Test
    void conditionSeesValuesWrittenByEarlierLayers() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "classify",
                        context -> ContextPatch.builder().put(PREMIUM, true).build())
                .stage(
                        "offer",
                        context -> ContextPatch.builder().put(OFFER, "gold").build(),
                        StageOptions.defaults().withCondition(context -> context.get(PREMIUM)))
                .build();

        assertThat(pipeline.run(ContextView.empty()).context().get(OFFER)).isEqualTo("gold");
    }

    @Test
    void skipIfInputsMissingTurnsBlockedIntoSkipped() throws Exception {
        Pipeline optional = new DefaultPipelineBuilder()
                .stage("notify", needs(EMAIL), StageOptions.defaults().skipIfInputsMissing())
                .build();
        Pipeline required =
                new DefaultPipelineBuilder().stage("notify", needs(EMAIL)).build();

        PipelineResult skipped = optional.run(ContextView.empty());
        PipelineResult blocked = required.run(ContextView.empty());

        assertThat(skipped.stages().getFirst().status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(skipped.isSuccessful()).isTrue();
        assertThat(blocked.stages().getFirst().status()).isEqualTo(StageStatus.BLOCKED);
        assertThat(blocked.isSuccessful()).isFalse();
    }

    @Test
    void skippedOutputCascadesToDependentStages() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "offer",
                        context -> ContextPatch.builder().put(OFFER, "gold").build(),
                        StageOptions.defaults().withCondition(context -> false))
                .stage("announce", needs(OFFER), StageOptions.defaults().skipIfInputsMissing())
                .stage("audit", needs(OFFER))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages())
                .extracting("status")
                .containsExactly(StageStatus.SKIPPED, StageStatus.SKIPPED, StageStatus.BLOCKED);
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void throwingConditionUnderFailFastStartsNoStageInItsLayer() throws Exception {
        AtomicInteger siblingRuns = new AtomicInteger();
        IllegalStateException broken = new IllegalStateException("condition broke");
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("sibling", context -> {
                        siblingRuns.incrementAndGet();
                        return ContextPatch.empty();
                    });
                    parallel.stage(
                            "guarded",
                            context -> ContextPatch.empty(),
                            StageOptions.defaults().withCondition(context -> {
                                throw broken;
                            }));
                })
                .stage("never", context -> ContextPatch.empty())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.failure()).containsSame(broken);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.CANCELLED, StageStatus.FAILED);
        assertThat(siblingRuns).hasValue(0);
    }

    @Test
    void throwingConditionUnderContinueOnlyFailsThatStage() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("sibling", context -> ContextPatch.empty());
                    parallel.stage(
                            "guarded",
                            context -> ContextPatch.empty(),
                            StageOptions.defaults()
                                    .withFailurePolicy(FailurePolicy.CONTINUE)
                                    .withCondition(context -> {
                                        throw new IllegalStateException("condition broke");
                                    }));
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED);
    }

    @Test
    void fallbackRecoversAThrowingCondition() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "guarded",
                        context -> ContextPatch.empty(),
                        StageOptions.defaults()
                                .withCondition(context -> {
                                    throw new IllegalStateException("condition broke");
                                })
                                .withFallback((context, failure) -> ContextPatch.builder()
                                        .put(OFFER, "none")
                                        .build()))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(OFFER)).isEqualTo("none");
    }

    @Test
    void conditionalNestedPipelineIsSkippedWithoutStartingTheChild() throws Exception {
        AtomicInteger childRuns = new AtomicInteger();
        Pipeline child = new DefaultPipelineBuilder()
                .stage("inner", context -> {
                    childRuns.incrementAndGet();
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage(), StageOptions.defaults().withCondition(context -> false))
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(result.stages().getFirst().children()).isEmpty();
        assertThat(childRuns).hasValue(0);
        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    void skippedChildStagesKeepANestedRunSuccessful() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage(
                        "optional",
                        context -> ContextPatch.empty(),
                        StageOptions.defaults().withCondition(context -> false))
                .build();
        Pipeline parent =
                new DefaultPipelineBuilder().stage("nested", child.asStage()).build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages().getFirst().children()).extracting("status").containsExactly(StageStatus.SKIPPED);
    }

    private static Stage needs(ContextKey<?> key) {
        return new Stage() {
            @Override
            public ContextPatch execute(StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return Set.of(key);
            }
        };
    }

    private static void awaitRunFinished(List<PipelineEvent> events) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.stream().noneMatch(RunFinished.class::isInstance) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
