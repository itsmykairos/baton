package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.StageAttemptTimeoutException;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.StageTimeoutException;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Timeouts are reported with a public exception type, never an internal one. */
class StageTimeoutExceptionTest {

    @Test
    void ownTimeoutIsAStageTimeoutExceptionNamingTheLimit() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withTimeout(Duration.ofMillis(50)))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(result.stages().getFirst().failure().orElseThrow())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Stage 'slow' exceeded its timeout of 50 ms");
        assertThat(result.failure().orElseThrow()).isExactlyInstanceOf(StageTimeoutException.class);
    }

    @Test
    void runDeadlineIsNamedAsTheCause() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .timeout(Duration.ofMillis(60))
                .stage(
                        "slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.stages().getFirst().failure().orElseThrow())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Stage 'slow' was stopped because the deadline of its run expired");
    }

    @Test
    void nestedStageDeadlineIsNamedForStagesInsideIt() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("inner", context -> {
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage(), StageOptions.defaults().withTimeout(Duration.ofMillis(60)))
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        var nested = result.stages().getFirst();
        assertThat(nested.failure().orElseThrow()).hasMessage("Stage 'nested' exceeded its timeout of 60 ms");
        assertThat(nested.children().getFirst().failure().orElseThrow())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Stage 'nested/inner' was stopped because the deadline of its nested pipeline "
                        + "stage 'nested' expired");
    }

    @Test
    void joiningAChildCutOffByTheStageDeadlineThrowsStageTimeoutException() throws Exception {
        AtomicReference<Exception> joinFailure = new AtomicReference<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "parent",
                        context -> {
                            var child = context.tasks().fork(() -> {
                                Thread.sleep(5_000);
                                return "late";
                            });
                            // Ignore our own interrupt until the child is cut off, then join it.
                            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                            while (!child.isDone() && System.nanoTime() < until) {
                                Thread.interrupted();
                            }
                            Thread.interrupted();
                            try {
                                child.join();
                            } catch (Exception failure) {
                                joinFailure.set(failure);
                            }
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withTimeout(Duration.ofMillis(50)))
                .build();

        pipeline.run(ContextView.empty());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (joinFailure.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(joinFailure.get())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Child task was stopped because its stage's deadline expired");
    }

    @Test
    void fallbacksAndAttemptTimeoutsUseThePublicTypes() throws Exception {
        AtomicReference<Throwable> seenByFallback = new AtomicReference<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withTimeout(Duration.ofMillis(50))
                                .withFallback((context, failure) -> {
                                    seenByFallback.set(failure);
                                    return ContextPatch.empty();
                                }))
                .stage(
                        "attempts",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withFailurePolicy(FailurePolicy.CONTINUE)
                                .withRetry(RetryPolicy.maxAttempts(1).withAttemptTimeout(Duration.ofMillis(30))))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(seenByFallback.get()).isExactlyInstanceOf(StageTimeoutException.class);
        assertThat(result.stages().get(1).failure().orElseThrow())
                .isExactlyInstanceOf(StageAttemptTimeoutException.class)
                .isInstanceOf(StageTimeoutException.class);
    }
}
