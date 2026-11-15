package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RetryPolicy;
import dev.baton.api.RunOptions;
import dev.baton.api.StageAttemptTimeoutException;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StageRetrying;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilienceTest {

    private static final ContextKey<String> VALUE = ContextKey.of("value", String.class);

    // ---- retries -------------------------------------------------------------------

    @Test
    void retriesUntilAnAttemptSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(events::add)
                .stage(
                        "flaky",
                        context -> {
                            if (calls.incrementAndGet() < 3) {
                                throw new IOException("attempt " + calls.get());
                            }
                            return ContextPatch.builder().put(VALUE, "ok").build();
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ofMillis(20))))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("ok");
        StageResult flaky = result.stages().getFirst();
        assertThat(flaky.attempts()).isEqualTo(3);
        assertThat(millis).isGreaterThanOrEqualTo(40L);

        awaitRunFinished(events);
        assertThat(events)
                .filteredOn(StageRetrying.class::isInstance)
                .map(StageRetrying.class::cast)
                .extracting(StageRetrying::failedAttempt)
                .containsExactly(1, 2);
        assertThat(events)
                .filteredOn(StageFinished.class::isInstance)
                .map(StageFinished.class::cast)
                .extracting(StageFinished::attempts)
                .containsExactly(3);
    }

    @Test
    void exhaustedRetriesFailWithTheLastFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "broken",
                        context -> {
                            throw new IOException("attempt " + calls.incrementAndGet());
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ZERO)))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        StageResult broken = result.stages().getFirst();
        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(broken.status()).isEqualTo(StageStatus.FAILED);
        assertThat(broken.attempts()).isEqualTo(3);
        assertThat(broken.failure().orElseThrow()).hasMessage("attempt 3");
    }

    @Test
    void nonRetryableFailureIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "validation",
                        context -> {
                            calls.incrementAndGet();
                            throw new IllegalArgumentException("bad input");
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(5)
                                        .withFixedDelay(Duration.ZERO)
                                        .retryOn(failure -> failure instanceof IOException)))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(calls).hasValue(1);
        assertThat(result.stages().getFirst().attempts()).isEqualTo(1);
    }

    @Test
    void attemptTimeoutCutsOffASlowAttemptAndRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "slow-once",
                        context -> {
                            if (calls.incrementAndGet() == 1) {
                                Thread.sleep(5_000);
                            }
                            return ContextPatch.builder().put(VALUE, "second").build();
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(2)
                                        .withFixedDelay(Duration.ZERO)
                                        .withAttemptTimeout(Duration.ofMillis(50))))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages().getFirst().attempts()).isEqualTo(2);
        assertThat(result.context().get(VALUE)).isEqualTo("second");
        assertThat(millis).isLessThan(1_000L);
    }

    @Test
    void lastAttemptTimingOutMakesTheStageTimedOut() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "always-slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(2)
                                        .withFixedDelay(Duration.ZERO)
                                        .withAttemptTimeout(Duration.ofMillis(40))))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        StageResult stage = result.stages().getFirst();
        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        assertThat(stage.status()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(stage.attempts()).isEqualTo(2);
        assertThat(stage.failure().orElseThrow()).isInstanceOf(StageAttemptTimeoutException.class);
    }

    @Test
    void stageTimeoutCoversEveryAttemptAndBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "keeps-failing",
                        context -> {
                            calls.incrementAndGet();
                            throw new IOException("down");
                        },
                        StageOptions.defaults()
                                .withTimeout(Duration.ofMillis(150))
                                .withRetry(RetryPolicy.maxAttempts(100).withFixedDelay(Duration.ofMillis(40))))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(millis).isLessThan(400L);
        assertThat(calls.get()).isBetween(2, 5);
        assertThat(result.stages().getFirst().status()).isIn(StageStatus.FAILED, StageStatus.TIMED_OUT);
    }

    @Test
    void cancellingDuringBackoffEndsTheRunPromptly() throws Exception {
        CountDownLatch failedOnce = new CountDownLatch(1);
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "waits-to-retry",
                        context -> {
                            failedOnce.countDown();
                            throw new IOException("down");
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ofSeconds(5))))
                .build();

        var run = pipeline.start(ContextView.empty());
        assertThat(failedOnce.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(20);
        long start = System.nanoTime();
        run.cancel();
        PipelineResult result = run.await();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500L);
        assertThat(result.status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.CANCELLED);
    }

    @Test
    void eachAttemptGetsAFreshChildTaskScope() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "forks",
                        context -> {
                            int attempt = calls.incrementAndGet();
                            var child = context.tasks().fork(() -> {
                                if (attempt == 1) {
                                    throw new IOException("child failed");
                                }
                                return "child " + attempt;
                            });
                            return ContextPatch.builder()
                                    .put(VALUE, child.join())
                                    .build();
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(2).withFixedDelay(Duration.ZERO)))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(VALUE)).isEqualTo("child 2");
    }

    @Test
    void nestedPipelineIsRerunOnRetryAndReportsTheLastAttemptsChildren() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Pipeline child = new DefaultPipelineBuilder()
                .stage("charge", context -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IOException("gateway down");
                    }
                    return ContextPatch.builder().put(VALUE, "charged").build();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage(
                        "payment",
                        child.asStage(),
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(2).withFixedDelay(Duration.ZERO)))
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        StageResult payment = result.stages().getFirst();
        assertThat(result.isSuccessful()).isTrue();
        assertThat(payment.attempts()).isEqualTo(2);
        assertThat(payment.children()).extracting("status").containsExactly(StageStatus.SUCCEEDED);
        assertThat(result.context().get(VALUE)).isEqualTo("charged");
    }

    // ---- fallback ------------------------------------------------------------------

    @Test
    void fallbackRecoversAFailedStageAndTheRunContinues() throws Exception {
        IOException down = new IOException("down");
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "recommend",
                        context -> {
                            throw down;
                        },
                        StageOptions.defaults()
                                .withFallback((context, failure) -> ContextPatch.builder()
                                        .put(VALUE, "default")
                                        .build()))
                .stage("next", context -> ContextPatch.empty())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        StageResult recommend = result.stages().getFirst();
        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(recommend.status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(recommend.failure()).containsSame(down);
        assertThat(result.context().get(VALUE)).isEqualTo("default");
        assertThat(result.stages()).extracting("stageId").containsExactly("recommend", "next");
    }

    @Test
    void fallbackRecoversAStageTimeoutAfterRetries() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withTimeout(Duration.ofMillis(60))
                                .withFallback((context, failure) -> ContextPatch.builder()
                                        .put(VALUE, failure.getClass().getSimpleName())
                                        .build()))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().contains(VALUE)).isTrue();
    }

    @Test
    void failingFallbackFailsTheStageWithTheOriginalAsSuppressed() throws Exception {
        IOException original = new IOException("down");
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "broken",
                        context -> {
                            throw original;
                        },
                        StageOptions.defaults().withFallback((context, failure) -> {
                            throw new IllegalStateException("no default either");
                        }))
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        Throwable failure = result.stages().getFirst().failure().orElseThrow();
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.FAILED);
        assertThat(failure).hasMessage("no default either");
        assertThat(failure.getSuppressed()).containsExactly(original);
    }

    @Test
    void recoveredOutputTakesPartInConflictDetection() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage(
                            "recovered",
                            context -> {
                                throw new IOException("down");
                            },
                            StageOptions.defaults()
                                    .withFallback((context, failure) -> ContextPatch.builder()
                                            .put(VALUE, "a")
                                            .build()));
                    parallel.stage(
                            "writer",
                            context -> ContextPatch.builder().put(VALUE, "b").build());
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.FAILED, StageStatus.FAILED);
    }

    // ---- run timeout ---------------------------------------------------------------

    @Test
    void builderTimeoutEndsTheRunAndSkipsLaterLayers() throws Exception {
        AtomicInteger fallbackCalls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .timeout(Duration.ofMillis(100))
                .stage(
                        "slow",
                        context -> {
                            Thread.sleep(5_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withFailurePolicy(FailurePolicy.CONTINUE)
                                .withFallback((context, failure) -> {
                                    fallbackCalls.incrementAndGet();
                                    return ContextPatch.empty();
                                }))
                .stage("never", context -> ContextPatch.empty())
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(millis).isLessThan(600L);
        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        assertThat(result.stages()).extracting("stageId").containsExactly("slow");
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void runOptionsTimeoutOverridesTheBuilderTimeout() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .timeout(Duration.ofSeconds(30))
                .stage("slow", context -> {
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                })
                .build();

        long start = System.nanoTime();
        PipelineResult result =
                pipeline.run(ContextView.empty(), RunOptions.defaults().withTimeout(Duration.ofMillis(80)));

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(600L);
        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
    }

    @Test
    void runThatFinishesWithinItsTimeoutCompletes() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .timeout(Duration.ofSeconds(5))
                .stage("a", context -> ContextPatch.empty())
                .stage("b", context -> ContextPatch.empty())
                .build();

        assertThat(pipeline.run(ContextView.empty()).isSuccessful()).isTrue();
    }

    @Test
    void nestedPipelineTimeoutIsCappedAndAppliedWhenNested() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .timeout(Duration.ofMillis(80))
                .stage("slow", context -> {
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent =
                new DefaultPipelineBuilder().stage("nested", child.asStage()).build();

        long start = System.nanoTime();
        PipelineResult result = parent.run(ContextView.empty());

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(600L);
        StageResult nested = result.stages().getFirst();
        assertThat(nested.status()).isEqualTo(StageStatus.FAILED);
        assertThat(nested.failure().orElseThrow()).isInstanceOf(PipelineExecutionException.class);
        assertThat(((PipelineExecutionException) nested.failure().orElseThrow())
                        .result()
                        .status())
                .isEqualTo(PipelineStatus.TIMED_OUT);
    }

    private static void awaitRunFinished(List<PipelineEvent> events) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.stream().noneMatch(RunFinished.class::isInstance) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
