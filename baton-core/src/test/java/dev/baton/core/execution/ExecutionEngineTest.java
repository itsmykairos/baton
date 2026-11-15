package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionEngineTest {

    @Test
    void timeoutDiscardsLatePatchAndContinueRunsNextStage() throws Exception {
        ContextKey<String> late = ContextKey.of("late", String.class);
        ContextKey<String> next = ContextKey.of("next", String.class);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "slow",
                        context -> {
                            ignoreInterruptsFor(300);
                            return ContextPatch.builder()
                                    .put(late, "late-value")
                                    .build();
                        },
                        StageOptions.defaults()
                                .withTimeout(Duration.ofMillis(40))
                                .withFailurePolicy(FailurePolicy.CONTINUE))
                .stage(
                        "next",
                        context -> ContextPatch.builder().put(next, "ran").build())
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(result.stages().getFirst().workerTermination()).isEqualTo(WorkerTermination.UNCONFIRMED);
        assertThat(result.context().contains(late)).isFalse();
        assertThat(result.context().get(next)).isEqualTo("ran");
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void parallelLayerWaitsForLogicalTimeoutsNotPhysicalWorkerExit() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch bothExited = new CountDownLatch(2);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("parallel", parallel -> {
                    parallel.stage(
                            "first",
                            context -> {
                                bothStarted.countDown();
                                try {
                                    ignoreInterruptsFor(2_000);
                                    return ContextPatch.empty();
                                } finally {
                                    bothExited.countDown();
                                }
                            },
                            StageOptions.defaults()
                                    .withTimeout(Duration.ofMillis(90))
                                    .withFailurePolicy(FailurePolicy.CONTINUE));
                    parallel.stage(
                            "second",
                            context -> {
                                bothStarted.countDown();
                                try {
                                    ignoreInterruptsFor(2_000);
                                    return ContextPatch.empty();
                                } finally {
                                    bothExited.countDown();
                                }
                            },
                            StageOptions.defaults()
                                    .withTimeout(Duration.ofMillis(40))
                                    .withFailurePolicy(FailurePolicy.CONTINUE));
                })
                .build();

        var run = pipeline.start(ContextView.empty());
        assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
        PipelineResult result = run.await();

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.TIMED_OUT, StageStatus.TIMED_OUT);
        assertThat(result.stages())
                .allSatisfy(stage -> assertThat(stage.workerTermination()).isEqualTo(WorkerTermination.UNCONFIRMED));
        assertThat(bothExited.getCount()).isGreaterThan(0L);
        assertThat(bothExited.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void returningFromStageCancelsUnfinishedChildTasks() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("parent", context -> {
                    context.tasks().fork(() -> {
                        childStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException e) {
                            childInterrupted.countDown();
                            throw e;
                        }
                        return null;
                    });
                    if (!childStarted.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("child did not start");
                    }
                    return ContextPatch.empty();
                })
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.isSuccessful()).isTrue();
        assertThat(childInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void parentTimeoutCancelsItsChildTasksImmediately() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "parent",
                        context -> {
                            context.tasks().fork(() -> {
                                childStarted.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException e) {
                                    childInterrupted.countDown();
                                    throw e;
                                }
                                return null;
                            });
                            if (!childStarted.await(1, TimeUnit.SECONDS)) {
                                throw new AssertionError("child did not start");
                            }
                            ignoreInterruptsFor(300);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withTimeout(Duration.ofMillis(40)))
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        assertThat(childInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sequentialStageRunsOnAVirtualThread() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("virtual", context -> {
                    assertThat(Thread.currentThread().isVirtual()).isTrue();
                    return ContextPatch.empty();
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages().getFirst().workerTermination()).isEqualTo(WorkerTermination.TERMINATED);
    }

    @Test
    void applicationTimeoutExceptionIsAStageFailureNotAFrameworkTimeout() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("application-timeout", context -> {
                    throw new TimeoutException("downstream request timed out");
                })
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.FAILED);
        assertThat(result.stages().getFirst().failure().orElseThrow())
                .isInstanceOf(TimeoutException.class)
                .hasMessage("downstream request timed out");
    }

    @Test
    void explicitCancellationReturnsWithoutWaitingForWorkerExit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("uncooperative", context -> {
                    started.countDown();
                    ignoreInterruptsFor(2_000);
                    return ContextPatch.empty();
                })
                .build();

        var run = pipeline.start(ContextView.empty());
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThat(run.cancel()).isTrue();
        long cancelMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(cancelMillis).isLessThan(250L);
        PipelineResult result = run.await();
        assertThat(result.status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(result.stages().getFirst().workerTermination()).isEqualTo(WorkerTermination.UNCONFIRMED);
    }

    @Test
    void interruptingAwaitCallerDoesNotCancelPipeline() throws Exception {
        CountDownLatch stageStarted = new CountDownLatch(1);
        CountDownLatch allowStageToFinish = new CountDownLatch(1);
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("wait", context -> {
                    stageStarted.countDown();
                    allowStageToFinish.await();
                    return ContextPatch.empty();
                })
                .build();

        var run = pipeline.start(ContextView.empty());
        assertThat(stageStarted.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                run.await();
            } catch (Throwable failure) {
                awaitFailure.set(failure);
            }
        });

        waiter.interrupt();
        waiter.join(1_000);
        assertThat(awaitFailure.get()).isInstanceOf(InterruptedException.class);
        assertThat(run.isDone()).isFalse();

        allowStageToFinish.countDown();
        assertThat(run.await().status()).isEqualTo(PipelineStatus.COMPLETED);
    }

    @Test
    void parallelResultsAndPatchMergeRemainInDefinitionOrder() throws Exception {
        ContextKey<String> first = ContextKey.of("first", String.class);
        ContextKey<String> second = ContextKey.of("second", String.class);
        CountDownLatch slowStarted = new CountDownLatch(1);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("parallel", parallel -> {
                    parallel.stage("first", context -> {
                        slowStarted.countDown();
                        Thread.sleep(100);
                        return ContextPatch.builder().put(first, "one").build();
                    });
                    parallel.stage("second", context -> {
                        if (!slowStarted.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("first stage did not start");
                        }
                        return ContextPatch.builder().put(second, "two").build();
                    });
                })
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages()).extracting("stageId").containsExactly("first", "second");
        assertThat(result.context().get(first)).isEqualTo("one");
        assertThat(result.context().get(second)).isEqualTo("two");
    }

    @Test
    void parallelPatchConflictFailsLayerWithoutPartiallyApplyingOutputs() throws Exception {
        ContextKey<String> shared = ContextKey.of("shared", String.class);
        ContextKey<String> prior = ContextKey.of("prior", String.class);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "prior",
                        context -> ContextPatch.builder().put(prior, "kept").build())
                .parallel("conflict", parallel -> {
                    parallel.stage(
                            "left",
                            context ->
                                    ContextPatch.builder().put(shared, "same").build());
                    parallel.stage(
                            "right",
                            context ->
                                    ContextPatch.builder().put(shared, "same").build());
                })
                .build();

        PipelineResult result = pipeline.start(ContextView.empty()).await();

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.context().get(prior)).isEqualTo("kept");
        assertThat(result.context().contains(shared)).isFalse();
        assertThat(result.stages())
                .extracting("status")
                .containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED, StageStatus.FAILED);
        assertThat(result.stages().get(1).failure().orElseThrow())
                .isInstanceOf(ContextMergeException.class)
                .hasMessageContaining("shared");
        assertThat(result.stages().get(1).output()).isPresent();
        assertThat(result.failure().orElseThrow()).isInstanceOf(ContextMergeException.class);
    }

    @Test
    void mergeConflictStillAppliesNonConflictingOutputsOfTheLayer() throws Exception {
        ContextKey<String> shared = ContextKey.of("shared", String.class);
        ContextKey<String> own = ContextKey.of("own", String.class);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage(
                            "left",
                            context -> ContextPatch.builder().put(shared, "l").build());
                    parallel.stage(
                            "right",
                            context -> ContextPatch.builder().put(shared, "r").build());
                    parallel.stage(
                            "independent",
                            context -> ContextPatch.builder().put(own, "kept").build());
                })
                .stage("after", context -> ContextPatch.empty())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages())
                .extracting("status")
                .containsExactly(StageStatus.FAILED, StageStatus.FAILED, StageStatus.SUCCEEDED);
        assertThat(result.context().get(own)).isEqualTo("kept");
        assertThat(result.context().contains(shared)).isFalse();
    }

    @Test
    void laterLayerOverwritesValueFromEarlierLayer() throws Exception {
        ContextKey<String> value = ContextKey.of("value", String.class);

        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "first",
                        context -> ContextPatch.builder().put(value, "first").build())
                .stage(
                        "second",
                        context -> ContextPatch.builder().put(value, "second").build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(value)).isEqualTo("second");
    }

    @Test
    void failFastFailureCancelsSlowSiblingsInTheSameLayer() throws Exception {
        for (FailurePolicy siblingPolicy : FailurePolicy.values()) {
            IllegalStateException boom = new IllegalStateException("boom");
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch slowInterrupted = new CountDownLatch(1);

            Pipeline pipeline = new DefaultPipelineBuilder()
                    .parallel("layer", parallel -> {
                        parallel.stage(
                                "slow",
                                context -> {
                                    slowStarted.countDown();
                                    try {
                                        Thread.sleep(5_000);
                                    } catch (InterruptedException e) {
                                        slowInterrupted.countDown();
                                        throw e;
                                    }
                                    return ContextPatch.empty();
                                },
                                StageOptions.defaults().withFailurePolicy(siblingPolicy));
                        parallel.stage("fails", context -> {
                            if (!slowStarted.await(1, TimeUnit.SECONDS)) {
                                throw new AssertionError("slow stage did not start");
                            }
                            throw boom;
                        });
                    })
                    .stage("never", context -> ContextPatch.empty())
                    .build();

            long start = System.nanoTime();
            PipelineResult result = pipeline.run(ContextView.empty());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(millis).as("sibling policy %s", siblingPolicy).isLessThan(1_000L);
            assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
            assertThat(result.stages()).extracting("stageId").containsExactly("slow", "fails");
            assertThat(result.stages()).extracting("status").containsExactly(StageStatus.CANCELLED, StageStatus.FAILED);
            assertThat(result.failure()).containsSame(boom);
            assertThat(slowInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void continueFailureDoesNotCancelSiblings() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("slow", context -> {
                        Thread.sleep(150);
                        return ContextPatch.empty();
                    });
                    parallel.stage(
                            "fails",
                            context -> {
                                throw new IllegalStateException("boom");
                            },
                            StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE));
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages()).extracting("status").containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED);
    }

    @Test
    void unjoinedFailedChildTaskFailsTheStage() throws Exception {
        IllegalStateException childFailure = new IllegalStateException("child failed");

        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("parent", context -> {
                    var child = context.tasks().fork(() -> {
                        throw childFailure;
                    });
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!child.isDone() && System.nanoTime() < until) {
                        Thread.sleep(5);
                    }
                    return ContextPatch.empty();
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.FAILED);
        assertThat(result.stages().getFirst().failure()).containsSame(childFailure);
    }

    @Test
    void joinedAndHandledChildFailureDoesNotFailTheStage() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("parent", context -> {
                    var child = context.tasks().fork(() -> {
                        throw new IllegalStateException("child failed");
                    });
                    try {
                        child.join();
                    } catch (IllegalStateException handled) {
                        // recovered by the stage
                    }
                    return ContextPatch.empty();
                })
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    void syncRunReturnsPromptlyAfterTimeoutWhenWorkerIgnoresInterrupts() throws Exception {
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage(
                        "uncooperative",
                        context -> {
                            ignoreInterruptsFor(2_000);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withTimeout(Duration.ofMillis(50)))
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        assertThat(millis).isLessThan(500L);
    }

    private static void ignoreInterruptsFor(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            Thread.interrupted();
        }
    }
}
