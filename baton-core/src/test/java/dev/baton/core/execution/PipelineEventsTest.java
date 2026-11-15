package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RunOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StageStarted;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PipelineEventsTest {

    private static final ContextKey<String> VALUE = ContextKey.of("value", String.class);

    @Test
    void runAndStageEventsArriveInOrderWithNestedPaths() throws Exception {
        Recorder recorder = new Recorder();
        Pipeline child = new DefaultPipelineBuilder()
                .stage("charge", context -> ContextPatch.empty())
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .name("checkout")
                .listener(recorder)
                .stage("load", context -> ContextPatch.builder().put(VALUE, "x").build())
                .stage("payment", child.asStage())
                .build();

        PipelineResult result = parent.run(ContextView.empty());
        List<PipelineEvent> events = recorder.awaitFinished();

        assertThat(result.isSuccessful()).isTrue();
        assertThat(events)
                .extracting(PipelineEventsTest::describe)
                .containsExactly(
                        "RunStarted",
                        "StageStarted load",
                        "StageFinished load SUCCEEDED",
                        "StageStarted payment",
                        "StageStarted payment/charge",
                        "StageFinished payment/charge SUCCEEDED",
                        "StageFinished payment SUCCEEDED",
                        "RunFinished COMPLETED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.pipelineName()).isEqualTo("checkout");
            assertThat(event.runId()).isEqualTo(events.getFirst().runId());
        });
    }

    @Test
    void pipelineAndRunListenersBothReceiveEventsAndEachRunHasItsOwnId() throws Exception {
        Recorder pipelineLevel = new Recorder();
        Recorder runLevel = new Recorder();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(pipelineLevel)
                .stage("only", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty(), RunOptions.defaults().withListener(runLevel));
        pipeline.run(ContextView.empty());
        runLevel.awaitFinished();
        pipelineLevel.awaitRuns(2);

        assertThat(runLevel.events).hasSize(4);
        assertThat(pipelineLevel.events).hasSize(8);
        assertThat(pipelineLevel.events.stream().map(PipelineEvent::runId).distinct())
                .hasSize(2);
        assertThat(pipelineLevel.events.getFirst().pipelineName()).isEqualTo("pipeline");
    }

    @Test
    void blockedConflictingAndFailedStagesReportFinalStatus() throws Exception {
        Recorder recorder = new Recorder();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(recorder)
                .parallel("layer", parallel -> {
                    parallel.stage(
                            "left",
                            context -> ContextPatch.builder().put(VALUE, "l").build());
                    parallel.stage(
                            "right",
                            context -> ContextPatch.builder().put(VALUE, "r").build());
                })
                .build();

        pipeline.run(ContextView.empty());
        List<PipelineEvent> events = recorder.awaitFinished();

        Map<String, StageStatus> finished = events.stream()
                .filter(StageFinished.class::isInstance)
                .map(StageFinished.class::cast)
                .collect(Collectors.toMap(event -> event.path().toString(), StageFinished::status));
        assertThat(finished).containsEntry("left", StageStatus.FAILED).containsEntry("right", StageStatus.FAILED);
        RunFinished last = (RunFinished) events.getLast();
        assertThat(last.status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(last.failure()).isPresent();
    }

    @Test
    void slowListenerNeverSlowsThePipeline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<PipelineEvent> seen = new CopyOnWriteArrayList<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(event -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    seen.add(event);
                })
                .stage("a", context -> ContextPatch.empty())
                .stage("b", context -> ContextPatch.empty())
                .build();

        long start = System.nanoTime();
        PipelineResult result = pipeline.run(ContextView.empty());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(millis).isLessThan(500L);
        assertThat(seen).isEmpty();

        release.countDown();
        awaitSize(seen, 6);
        assertThat(seen.getLast()).isInstanceOf(RunFinished.class);
    }

    @Test
    void fullQueueDropsEventsButAlwaysDeliversRunFinished() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<PipelineEvent> seen = new CopyOnWriteArrayList<>();
        DefaultPipelineBuilder builder = new DefaultPipelineBuilder();
        for (int i = 0; i < 20; i++) {
            builder.stage("s" + i, context -> ContextPatch.empty());
        }
        Pipeline pipeline = builder.build();

        pipeline.run(
                ContextView.empty(),
                RunOptions.defaults().withEventBufferCapacity(4).withListener(event -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    seen.add(event);
                }));
        release.countDown();
        awaitLast(seen);

        RunFinished last = (RunFinished) seen.getLast();
        // 42 events in total; the dispatcher held one in hand and queued four.
        assertThat(last.droppedEvents()).isPositive();
        assertThat(seen.size() - 1 + last.droppedEvents()).isEqualTo(41);
        assertThat(seen.getFirst()).isInstanceOf(RunStarted.class);
    }

    @Test
    void throwingListenerDoesNotAffectOtherListenersOrTheRun() throws Exception {
        Recorder recorder = new Recorder();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(event -> {
                    throw new IllegalStateException("listener bug");
                })
                .listener(recorder)
                .stage("a", context -> ContextPatch.empty())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(recorder.awaitFinished()).hasSize(4);
    }

    @Test
    void stageStartedOnlyForStagesThatWereSubmitted() throws Exception {
        Recorder recorder = new Recorder();
        dev.baton.api.Stage needsValue = new dev.baton.api.Stage() {
            @Override
            public ContextPatch execute(dev.baton.api.StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public java.util.Set<ContextKey<?>> requiredReads() {
                return java.util.Set.of(VALUE);
            }
        };
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(recorder)
                .stage("blocked", needsValue)
                .build();

        pipeline.run(ContextView.empty());

        assertThat(recorder.awaitFinished())
                .extracting(PipelineEventsTest::describe)
                .containsExactly("RunStarted", "StageFinished blocked BLOCKED", "RunFinished COMPLETED");
    }

    @Test
    void cancelledRunEndsWithCancelledRunFinished() throws Exception {
        Recorder recorder = new Recorder();
        CountDownLatch started = new CountDownLatch(1);
        Pipeline pipeline = new DefaultPipelineBuilder()
                .listener(recorder)
                .stage(
                        "waits",
                        context -> {
                            started.countDown();
                            new CountDownLatch(1).await();
                            return ContextPatch.empty();
                        },
                        dev.baton.api.StageOptions.defaults())
                .build();

        var run = pipeline.start(ContextView.empty());
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        run.cancel();

        List<PipelineEvent> events = recorder.awaitFinished();
        assertThat(events)
                .extracting(PipelineEventsTest::describe)
                .containsExactly(
                        "RunStarted", "StageStarted waits", "StageFinished waits CANCELLED", "RunFinished CANCELLED");
    }

    private static String describe(PipelineEvent event) {
        return switch (event) {
            case RunStarted ignored -> "RunStarted";
            case StageStarted started -> "StageStarted " + started.path();
            case dev.baton.api.event.ContextConflict conflict ->
                "ContextConflict " + conflict.conflict().key();
            case dev.baton.api.event.StageRetrying retrying ->
                "StageRetrying " + retrying.path() + " #" + retrying.failedAttempt();
            case StageFinished finished -> "StageFinished " + finished.path() + " " + finished.status();
            case RunFinished finished -> "RunFinished " + finished.status();
        };
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(list).hasSize(size);
    }

    private static void awaitLast(List<PipelineEvent> list) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((list.isEmpty() || !(list.getLast() instanceof RunFinished)) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(list.getLast()).isInstanceOf(RunFinished.class);
    }

    /** Minimal recorder; core tests cannot depend on baton-testkit. */
    private static final class Recorder implements dev.baton.api.event.PipelineListener {
        final List<PipelineEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(PipelineEvent event) {
            events.add(event);
        }

        List<PipelineEvent> awaitFinished() throws InterruptedException {
            return awaitRuns(1);
        }

        List<PipelineEvent> awaitRuns(int runs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.stream().filter(RunFinished.class::isInstance).count() < runs
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(events.stream().filter(RunFinished.class::isInstance).count())
                    .isEqualTo(runs);
            return new ArrayList<>(events);
        }
    }
}
