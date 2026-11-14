package dev.baton.core.execution;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.EventOverflowPolicy;
import dev.baton.api.KeyConflict;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.OverwritePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.TypeClashPolicy;
import dev.baton.api.UnjoinedChildFailurePolicy;
import dev.baton.api.event.ContextConflict;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StagePath;
import dev.baton.core.builder.DefaultPipelineBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {

    private static final ContextKey<String> VALUE = ContextKey.of("value", String.class);
    private static final ContextKey<String> OTHER = ContextKey.of("other", String.class);

    private static DefaultPipelineBuilder builder(BatonConfig config) {
        return (DefaultPipelineBuilder) new DefaultPipelineBuilder().config(config);
    }

    private static BatonConfig layerPolicy(LayerConflictPolicy policy) {
        return BatonConfig.builder().layerConflictPolicy(policy).build();
    }

    private static Pipeline twoWriters(BatonConfig config) {
        return builder(config)
                .parallel("layer", parallel -> {
                    parallel.stage("left", context -> ContextPatch.builder().put(VALUE, "l").put(OTHER, "o").build());
                    parallel.stage("right", context -> ContextPatch.builder().put(VALUE, "r").build());
                })
                .build();
    }

    // ---- same-layer conflicts ------------------------------------------------------

    @Test
    void layerConflictFailIsTheDefaultAndIsRecorded() throws Exception {
        PipelineResult result = twoWriters(BatonConfig.defaults()).run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(KeyConflict.Kind.SAME_LAYER);
            assertThat(conflict.key()).isEqualTo("value");
            assertThat(conflict.stages()).containsExactly(StagePath.of("left"), StagePath.of("right"));
            assertThat(conflict.resolution()).isEqualTo(KeyConflict.Resolution.FAILED);
        });
    }

    @Test
    void firstWinsKeepsTheFirstWriterAndBothStagesSucceed() throws Exception {
        PipelineResult result = twoWriters(layerPolicy(LayerConflictPolicy.FIRST_WINS)).run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("l");
        assertThat(result.context().get(OTHER)).isEqualTo("o");
        assertThat(result.conflicts()).extracting(KeyConflict::resolution)
                .containsExactly(KeyConflict.Resolution.FIRST_WINS);
    }

    @Test
    void lastWinsKeepsTheLastWriter() throws Exception {
        PipelineResult result = twoWriters(layerPolicy(LayerConflictPolicy.LAST_WINS)).run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("r");
        assertThat(result.context().get(OTHER)).isEqualTo("o");
    }

    @Test
    void skipKeyWritesTheKeyForNobodyButKeepsOtherWrites() throws Exception {
        PipelineResult result = twoWriters(layerPolicy(LayerConflictPolicy.SKIP_KEY)).run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().contains(VALUE)).isFalse();
        assertThat(result.context().get(OTHER)).isEqualTo("o");
        assertThat(result.conflicts()).extracting(KeyConflict::resolution)
                .containsExactly(KeyConflict.Resolution.KEY_SKIPPED);
    }

    @Test
    void equalValuesNeedNotConflict() throws Exception {
        Pipeline pipeline = builder(BatonConfig.builder().equalValuesConflict(false).build())
                .parallel("layer", parallel -> {
                    parallel.stage("a", context -> ContextPatch.builder().put(VALUE, "same").build());
                    parallel.stage("b", context -> ContextPatch.builder().put(VALUE, "same").build());
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("same");
        assertThat(result.conflicts()).isEmpty();
    }

    // ---- overwrites and type clashes -------------------------------------------------

    @Test
    void keepExistingIgnoresWritesToExistingKeys() throws Exception {
        Pipeline pipeline = builder(BatonConfig.builder().overwritePolicy(OverwritePolicy.KEEP_EXISTING).build())
                .stage("rewrite", context -> ContextPatch.builder().put(VALUE, "new").put(OTHER, "added").build())
                .build();

        PipelineResult result = pipeline.run(ContextView.builder().put(VALUE, "input").build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("input");
        assertThat(result.context().get(OTHER)).isEqualTo("added");
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(KeyConflict.Kind.OVERWRITE);
            assertThat(conflict.resolution()).isEqualTo(KeyConflict.Resolution.KEPT_EXISTING);
        });
    }

    @Test
    void overwriteFailFailsTheStage() throws Exception {
        Pipeline pipeline = builder(BatonConfig.builder().overwritePolicy(OverwritePolicy.FAIL).build())
                .stage("first", context -> ContextPatch.builder().put(VALUE, "a").build())
                .stage("second", context -> ContextPatch.builder().put(VALUE, "b").build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED);
        assertThat(result.context().get(VALUE)).isEqualTo("a");
    }

    @Test
    void stageOverwritePolicyOverridesThePipelines() throws Exception {
        Pipeline pipeline = builder(BatonConfig.builder().overwritePolicy(OverwritePolicy.FAIL).build())
                .stage("first", context -> ContextPatch.builder().put(VALUE, "a").build())
                .stage("allowed", context -> ContextPatch.builder().put(VALUE, "b").build(),
                        StageOptions.defaults().withOverwritePolicy(OverwritePolicy.OVERRIDE))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("b");
    }

    @Test
    void typeClashReplaceSwapsTheKeyType() throws Exception {
        ContextKey<Integer> valueAsInt = ContextKey.of("value", Integer.class);
        Pipeline pipeline = builder(BatonConfig.builder().typeClashPolicy(TypeClashPolicy.REPLACE).build())
                .stage("asString", context -> ContextPatch.builder().put(VALUE, "7").build())
                .stage("asInt", context -> ContextPatch.builder().put(valueAsInt, 7).build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(valueAsInt)).isEqualTo(7);
        assertThat(result.context().contains(VALUE)).isFalse();
        assertThat(result.context().keyNamed("value")).contains(valueAsInt);
        assertThat(result.conflicts()).extracting(KeyConflict::resolution)
                .containsExactly(KeyConflict.Resolution.REPLACED);
    }

    @Test
    void nestedConflictsUseTheChildsPolicyAndReachTheOuterResultWithFullPaths() throws Exception {
        Pipeline child = builder(layerPolicy(LayerConflictPolicy.LAST_WINS))
                .parallel("layer", parallel -> {
                    parallel.stage("a", context -> ContextPatch.builder().put(VALUE, "a").build());
                    parallel.stage("b", context -> ContextPatch.builder().put(VALUE, "b").build());
                })
                .build();
        // The parent's FAIL policy does not apply inside the child.
        Pipeline parent = builder(BatonConfig.defaults()).stage("nested", child.asStage()).build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("b");
        assertThat(result.conflicts()).singleElement().satisfies(conflict ->
                assertThat(conflict.stages()).containsExactly(StagePath.of("nested", "a"), StagePath.of("nested", "b")));
    }

    @Test
    void conflictsAreSentAsEvents() throws Exception {
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Pipeline pipeline = builder(layerPolicy(LayerConflictPolicy.FIRST_WINS))
                .listener(events::add)
                .parallel("layer", parallel -> {
                    parallel.stage("a", context -> ContextPatch.builder().put(VALUE, "a").build());
                    parallel.stage("b", context -> ContextPatch.builder().put(VALUE, "b").build());
                })
                .build();

        pipeline.run(ContextView.empty());
        awaitRunFinished(events, 1);

        assertThat(events).filteredOn(ContextConflict.class::isInstance).map(ContextConflict.class::cast)
                .singleElement().satisfies(event ->
                        assertThat(event.conflict().resolution()).isEqualTo(KeyConflict.Resolution.FIRST_WINS));
    }

    // ---- precedence --------------------------------------------------------------------

    @Test
    void runConfigOverridesPipelineConfigWhichOverridesAppConfig() throws Exception {
        BatonConfig app = layerPolicy(LayerConflictPolicy.FIRST_WINS);
        DefaultPipelineBuilder builder = new DefaultPipelineBuilder();
        builder.config(app).config(layerPolicy(LayerConflictPolicy.LAST_WINS));
        Pipeline pipeline = builder
                .parallel("layer", parallel -> {
                    parallel.stage("a", context -> ContextPatch.builder().put(VALUE, "a").build());
                    parallel.stage("b", context -> ContextPatch.builder().put(VALUE, "b").build());
                })
                .build();

        PipelineResult pipelineLevel = pipeline.run(ContextView.empty());
        PipelineResult runLevel = pipeline.run(ContextView.empty(),
                RunOptions.defaults().withConfig(layerPolicy(LayerConflictPolicy.SKIP_KEY)));

        assertThat(pipelineLevel.context().get(VALUE)).isEqualTo("b");
        assertThat(runLevel.context().contains(VALUE)).isFalse();
    }

    @Test
    void defaultStageOptionsApplyOnlyToStagesWithoutOptions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StageOptions retrying = StageOptions.defaults().withRetry(
                dev.baton.api.RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ZERO));
        dev.baton.api.Stage flaky = context -> {
            if (calls.incrementAndGet() % 2 == 1) {
                throw new java.io.IOException("flaky");
            }
            return ContextPatch.empty();
        };
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("inherits", flaky)
                .parallel("layer", parallel -> parallel.stage("alsoInherits", flaky))
                .stage("explicit", flaky, StageOptions.defaults().withFailurePolicy(
                        dev.baton.api.FailurePolicy.CONTINUE))
                // Set after the stages: resolved at build().
                .config(BatonConfig.builder().defaultStageOptions(retrying).build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.stages()).extracting("attempts").containsExactly(2, 2, 1);
        assertThat(result.stages().get(2).status()).isEqualTo(StageStatus.FAILED);
    }

    // ---- execution limits ------------------------------------------------------------

    @Test
    void maxParallelStagesCapsConcurrencyAndStillRunsEveryStage() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Pipeline pipeline = builder(BatonConfig.builder().maxParallelStages(2).build())
                .parallel("layer", parallel -> {
                    for (int i = 0; i < 8; i++) {
                        parallel.stage("s" + i, context -> {
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            Thread.sleep(20);
                            running.decrementAndGet();
                            return ContextPatch.empty();
                        });
                    }
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages()).hasSize(8);
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    void failFastWithALimitCancelsStagesStillWaitingForASlot() throws Exception {
        AtomicInteger started = new AtomicInteger();
        Pipeline pipeline = builder(BatonConfig.builder().maxParallelStages(1).build())
                .parallel("layer", parallel -> {
                    parallel.stage("fails", context -> {
                        started.incrementAndGet();
                        throw new IllegalStateException("boom");
                    });
                    parallel.stage("waiting", context -> {
                        started.incrementAndGet();
                        return ContextPatch.empty();
                    });
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(started).hasValue(1);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.FAILED, StageStatus.CANCELLED);
    }

    @Test
    void maxChildTasksCapsRunningChildren() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Pipeline pipeline = builder(BatonConfig.builder().maxChildTasks(3).build())
                .stage("fan-out", context -> {
                    List<dev.baton.api.TaskHandle<Integer>> handles = new java.util.ArrayList<>();
                    for (int i = 0; i < 10; i++) {
                        handles.add(context.tasks().fork(() -> {
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            Thread.sleep(15);
                            running.decrementAndGet();
                            return 1;
                        }));
                    }
                    int sum = 0;
                    for (var handle : handles) {
                        sum += handle.join();
                    }
                    return ContextPatch.builder().put(ContextKey.of("sum", Integer.class), sum).build();
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.context().get(ContextKey.of("sum", Integer.class))).isEqualTo(10);
        assertThat(peak.get()).isEqualTo(3);
    }

    @Test
    void unjoinedChildFailureCanBeIgnored() throws Exception {
        Pipeline pipeline = builder(BatonConfig.builder()
                .unjoinedChildFailure(UnjoinedChildFailurePolicy.IGNORE).build())
                .stage("parent", context -> {
                    var child = context.tasks().fork(() -> {
                        throw new IllegalStateException("child failed");
                    });
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!child.isDone() && System.nanoTime() < until) {
                        Thread.sleep(5);
                    }
                    return ContextPatch.empty();
                })
                .build();

        assertThat(pipeline.run(ContextView.empty()).isSuccessful()).isTrue();
    }

    @Test
    void threadFactoryNamesStageThreads() throws Exception {
        List<String> names = new CopyOnWriteArrayList<>();
        Pipeline pipeline = builder(BatonConfig.builder()
                .threadFactory(Thread.ofVirtual().name("checkout-", 0).factory()).build())
                .stage("a", context -> {
                    names.add(Thread.currentThread().getName());
                    return ContextPatch.empty();
                })
                .build();

        pipeline.run(ContextView.empty());

        assertThat(names).singleElement().asString().startsWith("checkout-");
    }

    // ---- events ----------------------------------------------------------------------

    @Test
    void clockStampsEvents() throws Exception {
        Instant fixed = Instant.parse("2030-01-01T00:00:00Z");
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Pipeline pipeline = builder(BatonConfig.builder().clock(Clock.fixed(fixed, ZoneOffset.UTC)).build())
                .listener(events::add)
                .stage("a", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty());
        awaitRunFinished(events, 1);

        assertThat(events.getFirst()).isInstanceOf(RunStarted.class);
        assertThat(events.getFirst().timestamp()).isEqualTo(fixed);
        assertThat(events.getLast().timestamp()).isEqualTo(fixed);
    }

    @Test
    void listenerErrorHandlerReceivesListenerFailures() throws Exception {
        List<Throwable> handled = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Pipeline pipeline = builder(BatonConfig.builder()
                .listenerErrorHandler((listener, event, failure) -> handled.add(failure)).build())
                .listener(event -> {
                    if (event instanceof RunFinished) {
                        done.countDown();
                    }
                    throw new IllegalStateException("listener bug");
                })
                .stage("a", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty());

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handled.size() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(handled).hasSize(4).allSatisfy(failure -> assertThat(failure).hasMessage("listener bug"));
    }

    @Test
    void dropOldestKeepsTheNewestEvents() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<PipelineEvent> seen = new CopyOnWriteArrayList<>();
        DefaultPipelineBuilder builder = builder(BatonConfig.builder()
                .eventBufferCapacity(4).eventOverflowPolicy(EventOverflowPolicy.DROP_OLDEST).build());
        for (int i = 0; i < 20; i++) {
            builder.stage("s" + i, context -> ContextPatch.empty());
        }
        builder.listener(event -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            seen.add(event);
        });

        builder.build().run(ContextView.empty());
        release.countDown();
        awaitRunFinished(seen, 1);

        RunFinished last = (RunFinished) seen.getLast();
        assertThat(last.droppedEvents()).isPositive();
        // The newest events survive: the last stage's StageFinished is delivered.
        assertThat(seen).anyMatch(event -> event instanceof StageFinished finished
                && finished.path().stageId().equals("s19"));
    }

    @Test
    void nestedPipelineListenersCanBeNotified() throws Exception {
        List<PipelineEvent> childEvents = new CopyOnWriteArrayList<>();
        Pipeline child = new DefaultPipelineBuilder()
                .name("child")
                .listener(childEvents::add)
                .stage("inner", context -> ContextPatch.empty())
                .build();
        Pipeline off = new DefaultPipelineBuilder().stage("nested", child.asStage()).build();
        Pipeline on = builder(BatonConfig.builder().notifyNestedListeners(true).build())
                .stage("nested", child.asStage()).build();

        off.run(ContextView.empty());
        Thread.sleep(50);
        assertThat(childEvents).isEmpty();

        on.run(ContextView.empty());
        awaitRunFinished(childEvents, 1);
        assertThat(childEvents.getFirst()).isInstanceOf(RunStarted.class);
        assertThat(childEvents.getFirst().pipelineName()).isEqualTo("child");
        assertThat(childEvents).filteredOn(StageFinished.class::isInstance).map(StageFinished.class::cast)
                .extracting(finished -> finished.path().toString()).containsExactly("inner");
    }

    @Test
    void defaultPipelineNameAppliesToUnnamedPipelines() throws Exception {
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Pipeline pipeline = builder(BatonConfig.builder().defaultPipelineName("orders").build())
                .listener(events::add)
                .stage("a", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty());
        awaitRunFinished(events, 1);

        assertThat(events).allSatisfy(event -> assertThat(event.pipelineName()).isEqualTo("orders"));
    }

    private static void awaitRunFinished(List<PipelineEvent> events, int runs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.stream().filter(RunFinished.class::isInstance).count() < runs
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(events.stream().filter(RunFinished.class::isInstance).count()).isEqualTo(runs);
    }
}
