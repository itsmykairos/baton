package dev.baton.core.execution;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.StageTimeoutException;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import dev.baton.core.builder.DefaultPipelineBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for the code-review findings. */
class HardeningTest {

    private static final ContextKey<String> VALUE = ContextKey.of("value", String.class);

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    void hangingFallbackIsStoppedByTheRunTimeout() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .timeout(Duration.ofMillis(200))
                .stage("fails", context -> {
                    throw new IllegalStateException("down");
                }, StageOptions.defaults().withFallback((context, failure) -> {
                    Thread.sleep(10_000);
                    return ContextPatch.empty();
                }))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(millisSince(start)).isLessThan(1_500L);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.FAILED);
        assertThat(result.stages().getFirst().failure().orElseThrow())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Fallback of stage 'fails' was stopped because the deadline of its run expired");
        assertThat(result.stages().getFirst().failure().orElseThrow().getSuppressed())
                .extracting(Throwable::getMessage).containsExactly("down");
    }

    @Test
    void slowFallbackDoesNotDelayFailFastCancellation() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("recovering", context -> {
                        throw new IllegalStateException("first");
                    }, StageOptions.defaults().withFallback((context, failure) -> {
                        Thread.sleep(10_000);
                        return ContextPatch.empty();
                    }));
                    parallel.stage("fails", context -> {
                        Thread.sleep(50);
                        throw new IllegalStateException("second");
                    });
                })
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(millisSince(start)).isLessThan(1_500L);
        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.failure().orElseThrow()).hasMessage("second");
        // The cancelled fallback leaves the stage's own outcome.
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.FAILED);
        assertThat(result.stages().getFirst().failure().orElseThrow()).hasMessage("first");
    }

    @Test
    void fallbackStillRecoversWhileOtherStagesRun() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("recovering", context -> {
                        throw new IllegalStateException("down");
                    }, StageOptions.defaults().withFallback((context, failure) -> {
                        Thread.sleep(50);
                        return ContextPatch.builder().put(VALUE, "fallback").build();
                    }));
                    parallel.stage("other", context -> ContextPatch.empty());
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(result.context().get(VALUE)).isEqualTo("fallback");
    }

    @Test
    void hangingConditionIsStoppedByTheRunTimeoutAndByCancel() throws Exception {
        Pipeline timed = new DefaultPipelineBuilder()
                .timeout(Duration.ofMillis(200))
                .stage("guarded", context -> ContextPatch.empty(), StageOptions.defaults()
                        .withCondition(context -> {
                            try {
                                Thread.sleep(10_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return true;
                        }))
                .build();

        long start = System.nanoTime();
        PipelineResult result = timed.run(ContextView.empty());

        assertThat(millisSince(start)).isLessThan(1_500L);
        assertThat(result.stages().getFirst().failure().orElseThrow())
                .isExactlyInstanceOf(StageTimeoutException.class)
                .hasMessage("Condition of stage 'guarded' was stopped because the deadline of its run expired");

        CountDownLatch evaluating = new CountDownLatch(1);
        Pipeline cancellable = new DefaultPipelineBuilder()
                .stage("guarded", context -> ContextPatch.empty(), StageOptions.defaults()
                        .withCondition(context -> {
                            evaluating.countDown();
                            try {
                                Thread.sleep(10_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return true;
                        }))
                .build();
        var run = cancellable.start(ContextView.empty());
        assertThat(evaluating.await(1, TimeUnit.SECONDS)).isTrue();
        start = System.nanoTime();
        run.cancel();
        assertThat(run.await().status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(millisSince(start)).isLessThan(1_000L);
    }

    @Test
    void recoveredNestedStageCountsAsSuccess() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("broken", context -> {
                    throw new IllegalStateException("child failed");
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage(), StageOptions.defaults()
                        .withFallback((context, failure) -> ContextPatch.empty()))
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(result.stages().getFirst().children()).extracting("status").containsExactly(StageStatus.FAILED);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    void overlayingADerivedConfigDoesNotDuplicatePropagators() throws Exception {
        AtomicInteger restores = new AtomicInteger();
        ContextPropagator<Object> counting = new ContextPropagator<>() {
            @Override
            public Object capture() {
                return null;
            }

            @Override
            public Restoration restore(Object captured, ExecutionScope scope) {
                restores.incrementAndGet();
                return Restoration.NONE;
            }
        };
        BatonConfig app = BatonConfig.builder().contextPropagator(counting).build();
        Pipeline pipeline = new DefaultPipelineBuilder().config(app)
                .stage("a", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty());
        int perRun = restores.getAndSet(0);
        pipeline.run(ContextView.empty(), RunOptions.defaults()
                .withConfig(app.toBuilder().runTimeout(Duration.ofSeconds(5)).build()));

        assertThat(perRun).isEqualTo(2); // the run and the stage
        assertThat(restores.get()).isEqualTo(perRun);
        assertThat(app.merge(app).contextPropagators()).hasSize(1);
    }

    @Test
    void unjoinedFailuresDoNotMutateTheChildrensExceptions() throws Exception {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("parent", context -> {
                    var a = context.tasks().fork(() -> {
                        throw first;
                    });
                    var b = context.tasks().fork(() -> {
                        throw second;
                    });
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while ((!a.isDone() || !b.isDone()) && System.nanoTime() < until) {
                        Thread.sleep(5);
                    }
                    return ContextPatch.empty();
                }, StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());
        reported.set(result.stages().getFirst().failure().orElseThrow());

        assertThat(reported.get()).isIn(first, second);
        assertThat(first.getSuppressed()).isEmpty();
        assertThat(second.getSuppressed()).isEmpty();
    }
}
