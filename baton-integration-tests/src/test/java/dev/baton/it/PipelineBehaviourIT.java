package dev.baton.it;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.PipelineRunStatus;
import dev.baton.api.PipelineStatus;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.testkit.PipelineResults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end behaviour through the public API only. */
class PipelineBehaviourIT {

    private static final ContextKey<Integer> ORDER_ID = ContextKey.of("orderId", Integer.class);
    private static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    private static final ContextKey<Integer> TOTAL = ContextKey.of("total", Integer.class);
    private static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);

    @Test
    void sequentialParallelAndNestedStagesComposeThroughTheContext() throws Exception {
        Pipeline payment = Pipeline.builder()
                .stage("charge", context -> ContextPatch.builder()
                        .put(RECEIPT, "paid " + context.context().get(TOTAL))
                        .build())
                .build();

        Pipeline checkout = Pipeline.builder()
                .stage("load", context -> ContextPatch.builder().put(ORDER_ID, 7).build())
                .parallel("enrich", parallel -> {
                    parallel.stage("customer", context -> ContextPatch.builder()
                            .put(CUSTOMER, "ada").build());
                    parallel.stage("price", context -> {
                        // Children are forked and joined inside the stage.
                        var base = context.tasks().fork(() -> 40);
                        var tax = context.tasks().fork(() -> 2);
                        return ContextPatch.builder().put(TOTAL, base.join() + tax.join()).build();
                    });
                })
                .stage("payment", payment.asStage())
                .build();

        PipelineResult result = checkout.run(ContextView.empty());

        PipelineResults.requireSuccess(result);
        assertThat(result.context().get(RECEIPT)).isEqualTo("paid 42");
        assertThat(result.stages()).extracting("stageId")
                .containsExactly("load", "customer", "price", "payment");
        assertThat(PipelineResults.stage(result, "payment/charge").status())
                .isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void missingRequiredInputBlocksStageWithoutStoppingTheRun() throws Exception {
        Stage needsCustomer = new Stage() {
            @Override
            public ContextPatch execute(dev.baton.api.StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return Set.of(CUSTOMER);
            }
        };
        Pipeline pipeline = Pipeline.builder()
                .stage("greet", needsCustomer)
                .stage("after", context -> ContextPatch.builder().put(ORDER_ID, 1).build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.stages()).extracting("status")
                .containsExactly(StageStatus.BLOCKED, StageStatus.SUCCEEDED);
    }

    @Test
    void failFastStopsTheRunAndContinueDoesNot() throws Exception {
        Stage fails = context -> {
            throw new IllegalStateException("boom");
        };
        Pipeline failFast = Pipeline.builder()
                .stage("fails", fails)
                .stage("next", context -> ContextPatch.empty())
                .build();
        Pipeline tolerant = Pipeline.builder()
                .stage("fails", fails, StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .stage("next", context -> ContextPatch.empty())
                .build();

        PipelineResult stopped = failFast.run(ContextView.empty());
        PipelineResult continued = tolerant.run(ContextView.empty());

        assertThat(stopped.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(stopped.stages()).extracting("stageId").containsExactly("fails");
        assertThat(stopped.failure().orElseThrow()).hasMessage("boom");
        assertThat(continued.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(continued.stages()).extracting("status")
                .containsExactly(StageStatus.FAILED, StageStatus.SUCCEEDED);
        assertThat(continued.hasFailures()).isTrue();
    }

    @Test
    void stageTimeoutEndsTheRunPromptly() throws Exception {
        Pipeline pipeline = Pipeline.builder()
                .stage("slow", context -> {
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                }, StageOptions.defaults().withTimeout(Duration.ofMillis(50)))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000L);
        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.TIMED_OUT);
    }

    @Test
    void asyncRunCanBeCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Pipeline pipeline = Pipeline.builder()
                .stage("waits", context -> {
                    started.countDown();
                    new CountDownLatch(1).await();
                    return ContextPatch.empty();
                })
                .build();

        PipelineRun run = pipeline.start(ContextView.empty());
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(run.status()).isEqualTo(PipelineRunStatus.RUNNING);

        assertThat(run.cancel()).isTrue();
        PipelineResult result = run.await();

        assertThat(result.status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(run.status()).isEqualTo(PipelineRunStatus.CANCELLED);
        assertThat(run.isDone()).isTrue();
        assertThat(run.cancel()).isFalse();
    }

    @Test
    void failedNestedPipelineReportsChildResults() throws Exception {
        Pipeline child = Pipeline.builder()
                .stage("ok", context -> ContextPatch.empty())
                .stage("broken", context -> {
                    throw new IllegalStateException("child broke");
                })
                .build();
        Pipeline parent = Pipeline.builder().stage("child", child.asStage()).build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.failure().orElseThrow()).isInstanceOf(PipelineExecutionException.class);
        assertThat(PipelineResults.stage(result, "child/broken").failure().orElseThrow())
                .hasMessage("child broke");
        assertThat(PipelineResults.describe(result))
                .contains("- child FAILED")
                .contains("    - broken FAILED (")
                .contains("child broke");
    }

    @Test
    void recreatedKeysReadValuesWrittenByOtherStages() throws Exception {
        Pipeline pipeline = Pipeline.builder()
                .stage("write", context -> ContextPatch.builder()
                        .put(ContextKey.of("greeting", String.class), "hi").build())
                .stage("read", context -> ContextPatch.builder()
                        .put(ContextKey.of("echo", String.class),
                                context.context().get(ContextKey.of("greeting", String.class)))
                        .build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.context().get(ContextKey.of("echo", String.class))).isEqualTo("hi");
    }

    @Test
    void stageWritingAnExistingNameWithAnotherTypeFails() throws Exception {
        Pipeline pipeline = Pipeline.builder()
                .stage("asString", context -> ContextPatch.builder()
                        .put(ContextKey.of("id", String.class), "7").build())
                .stage("asInteger", context -> ContextPatch.builder()
                        .put(ContextKey.of("id", Integer.class), 7).build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages()).extracting("status")
                .containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED);
        assertThat(result.context().get(ContextKey.of("id", String.class))).isEqualTo("7");
    }
}
