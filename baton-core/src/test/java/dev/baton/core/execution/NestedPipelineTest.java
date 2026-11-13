package dev.baton.core.execution;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.core.builder.DefaultPipelineBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class NestedPipelineTest {

    private static final ContextKey<String> VALUE = ContextKey.of("value", String.class);
    private static final ContextKey<String> OTHER = ContextKey.of("other", String.class);

    @Test
    void childWritesReachParentIncludingEqualValueRewrites() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("rewrite", context -> ContextPatch.builder().put(VALUE, "same").build())
                .stage("add", context -> ContextPatch.builder().put(OTHER, "new").build())
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage())
                .build();

        PipelineResult result = parent.run(ContextView.builder().put(VALUE, "same").build());

        assertThat(result.isSuccessful()).isTrue();
        StageResult nested = result.stages().getFirst();
        assertThat(nested.output().orElseThrow().writtenKeys()).containsExactly(VALUE, OTHER);
        assertThat(result.context().get(OTHER)).isEqualTo("new");
        assertThat(nested.children()).extracting("stageId").containsExactly("rewrite", "add");
    }

    @Test
    void equalValueWriteFromNestedStageConflictsWithParallelSibling() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("rewrite", context -> ContextPatch.builder().put(VALUE, "same").build())
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("nested", child.asStage());
                    parallel.stage("sibling", context ->
                            ContextPatch.builder().put(VALUE, "same").build());
                })
                .build();

        PipelineResult result = parent.run(ContextView.builder().put(VALUE, "same").build());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(result.stages()).extracting("status")
                .containsExactly(StageStatus.FAILED, StageStatus.FAILED);
        assertThat(result.failure().orElseThrow()).isInstanceOf(ContextMergeException.class);
    }

    @Test
    void childInheritsNestedStageDeadline() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("uncooperative", context -> {
                    ignoreInterruptsFor(2_000);
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage(), StageOptions.defaults().withTimeout(Duration.ofMillis(100)))
                .build();

        long start = System.nanoTime();
        PipelineResult result = parent.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(millis).isLessThan(600L);
        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
        StageResult nested = result.stages().getFirst();
        assertThat(nested.status()).isEqualTo(StageStatus.TIMED_OUT);
        assertThat(nested.children()).extracting("status").containsExactly(StageStatus.TIMED_OUT);
    }

    @Test
    void cancellingParentRunCancelsGrandchildTwoLevelsDown() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Pipeline grandchild = new DefaultPipelineBuilder()
                .stage("leaf", context -> {
                    started.countDown();
                    ignoreInterruptsFor(2_000);
                    return ContextPatch.empty();
                })
                .build();
        Pipeline child = new DefaultPipelineBuilder()
                .stage("inner", grandchild.asStage())
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("outer", child.asStage())
                .stage("never", context -> ContextPatch.empty())
                .build();

        PipelineRun run = parent.start(ContextView.empty());
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        assertThat(run.cancel()).isTrue();
        PipelineResult result = run.await();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(millis).isLessThan(500L);
        assertThat(result.status()).isEqualTo(PipelineStatus.CANCELLED);
        StageResult outer = result.stages().getFirst();
        assertThat(outer.status()).isEqualTo(StageStatus.CANCELLED);
        StageResult inner = outer.children().getFirst();
        assertThat(inner.stageId()).isEqualTo("inner");
        assertThat(inner.status()).isEqualTo(StageStatus.CANCELLED);
        StageResult leaf = inner.children().getFirst();
        assertThat(leaf.stageId()).isEqualTo("leaf");
        assertThat(leaf.status()).isEqualTo(StageStatus.CANCELLED);
    }

    @Test
    void failFastSiblingCancelsWholeChildRun() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        Pipeline child = new DefaultPipelineBuilder()
                .stage("slow", context -> {
                    childStarted.countDown();
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .parallel("layer", parallel -> {
                    parallel.stage("nested", child.asStage());
                    parallel.stage("fails", context -> {
                        if (!childStarted.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("child did not start");
                        }
                        throw new IllegalStateException("boom");
                    });
                })
                .build();

        long start = System.nanoTime();
        PipelineResult result = parent.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(millis).isLessThan(1_000L);
        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        StageResult nested = result.stages().getFirst();
        assertThat(nested.status()).isEqualTo(StageStatus.CANCELLED);
        assertThat(nested.children()).extracting("status").containsExactly(StageStatus.CANCELLED);
    }

    @Test
    void completedChildWithContinueFailureSucceedsButParentReportsFailure() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("tolerated", context -> {
                    throw new IllegalStateException("tolerated");
                }, StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .stage("writes", context -> ContextPatch.builder().put(VALUE, "written").build())
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage())
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.stages().getFirst().status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(result.context().get(VALUE)).isEqualTo("written");
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.hasFailures()).isTrue();
    }

    @Test
    void failedChildFailsNestedStageWithChildren() throws Exception {
        Pipeline child = new DefaultPipelineBuilder()
                .stage("ok", context -> ContextPatch.builder().put(VALUE, "ignored").build())
                .stage("fails", context -> {
                    throw new IllegalStateException("child failed");
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", child.asStage())
                .build();

        PipelineResult result = parent.run(ContextView.empty());

        assertThat(result.status()).isEqualTo(PipelineStatus.FAILED);
        StageResult nested = result.stages().getFirst();
        assertThat(nested.status()).isEqualTo(StageStatus.FAILED);
        assertThat(nested.failure().orElseThrow()).isInstanceOf(PipelineExecutionException.class);
        assertThat(nested.children()).extracting("status")
                .containsExactly(StageStatus.SUCCEEDED, StageStatus.FAILED);
        assertThat(result.context().contains(VALUE)).isFalse();
    }

    @Test
    void foreignPipelineImplementationUsesStandaloneFallback() throws Exception {
        Pipeline inner = new DefaultPipelineBuilder()
                .stage("rewrite", context -> ContextPatch.builder().put(VALUE, "same").build())
                .build();
        AtomicBoolean delegated = new AtomicBoolean();
        Pipeline foreign = new Pipeline() {
            @Override
            public PipelineResult run(ContextView input, dev.baton.api.RunOptions options) throws InterruptedException {
                delegated.set(true);
                return inner.run(input);
            }

            @Override
            public PipelineRun start(ContextView input, dev.baton.api.RunOptions options) {
                throw new UnsupportedOperationException();
            }
        };
        Pipeline parent = new DefaultPipelineBuilder()
                .stage("nested", foreign.asStage())
                .build();

        PipelineResult result = parent.run(ContextView.builder().put(VALUE, "same").build());

        assertThat(delegated).isTrue();
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stages().getFirst().output().orElseThrow().writtenKeys())
                .containsExactly(VALUE);
        assertThat(result.stages().getFirst().children()).isEmpty();
    }

    private static void ignoreInterruptsFor(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            Thread.interrupted();
        }
    }
}
