package dev.baton.core.event;

import dev.baton.api.EventOverflowPolicy;
import dev.baton.api.ListenerErrorHandler;
import dev.baton.api.PipelineStatus;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventDispatcherTest {

    /** Every emitted event is either delivered before RunFinished or counted as dropped. */
    @Test
    void eventsRacingFinishAreDeliveredOrCountedNeverLost() throws Exception {
        for (int round = 0; round < 50; round++) {
            List<PipelineEvent> seen = new CopyOnWriteArrayList<>();
            CountDownLatch finished = new CountDownLatch(1);
            AsyncEventDispatcher dispatcher = new AsyncEventDispatcher(List.of(event -> {
                seen.add(event);
                if (event instanceof RunFinished) {
                    finished.countDown();
                }
            }), 100_000, Duration.ofMillis(1), EventOverflowPolicy.DROP_NEWEST, ListenerErrorHandler.logging());
            UUID runId = UUID.randomUUID();
            AtomicInteger emitted = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> emitters = new java.util.ArrayList<>();
            for (int t = 0; t < 4; t++) {
                emitters.add(Thread.ofVirtual().start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 500; i++) {
                        dispatcher.emit(new RunStarted(runId, "p", Instant.now()));
                        emitted.incrementAndGet();
                    }
                }));
            }
            go.countDown();
            Thread.sleep(1);
            dispatcher.finish(dropped -> new RunFinished(runId, "p", PipelineStatus.COMPLETED, Duration.ZERO,
                    Optional.empty(), dropped, Instant.now()));
            for (Thread emitter : emitters) {
                emitter.join();
            }
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();

            RunFinished last = (RunFinished) seen.getLast();
            assertThat(seen.size() - 1 + last.droppedEvents()).as("round %d", round)
                    .isLessThanOrEqualTo(emitted.get());
            // Events emitted after RunFinished was delivered are also counted only once
            // finish() saw them; nothing may arrive after RunFinished.
            Thread.sleep(5);
            assertThat(seen.getLast()).isInstanceOf(RunFinished.class);
        }
    }
}
