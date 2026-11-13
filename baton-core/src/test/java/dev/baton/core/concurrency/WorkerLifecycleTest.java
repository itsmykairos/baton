package dev.baton.core.concurrency;

import dev.baton.api.WorkerTermination;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLifecycleTest {

    @Test
    void cancellationBeforeStartReportsNotStarted() {
        WorkerLifecycle lifecycle = new WorkerLifecycle();

        assertThat(lifecycle.requestCancellation()).isTrue();
        assertThat(lifecycle.state()).isEqualTo(WorkerLifecycle.State.TERMINATED);
        assertThat(lifecycle.termination()).isEqualTo(WorkerTermination.NOT_STARTED);
        assertThat(lifecycle.hasExited()).isTrue();
        assertThat(lifecycle.tryStart()).isFalse();
    }

    @Test
    void runningWorkerReportsUnconfirmedUntilExitIsObserved() throws Exception {
        WorkerLifecycle lifecycle = new WorkerLifecycle();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().start(() -> {
            if (!lifecycle.tryStart()) {
                workerFailure.set(new AssertionError("Worker did not start"));
                return;
            }

            started.countDown();
            try {
                for (;;) {
                    try {
                        allowExit.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Deliberately remain alive to model uncooperative user code.
                    }
                }
            } finally {
                lifecycle.workerExited();
            }
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.termination()).isEqualTo(WorkerTermination.UNCONFIRMED);

        assertThat(lifecycle.requestCancellation()).isTrue();
        assertThat(lifecycle.state()).isEqualTo(WorkerLifecycle.State.CANCEL_REQUESTED);
        assertThat(lifecycle.termination()).isEqualTo(WorkerTermination.UNCONFIRMED);

        allowExit.countDown();
        worker.join(1_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(workerFailure.get()).isNull();
        assertThat(lifecycle.state()).isEqualTo(WorkerLifecycle.State.TERMINATED);
        assertThat(lifecycle.termination()).isEqualTo(WorkerTermination.TERMINATED);
        assertThat(lifecycle.awaitExit(0, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void workerCanTerminateNormally() throws Exception {
        WorkerLifecycle lifecycle = new WorkerLifecycle();
        CountDownLatch finished = new CountDownLatch(1);

        Thread worker = Thread.ofVirtual().start(() -> {
            if (!lifecycle.tryStart()) {
                return;
            }
            try {
                // No user work required for this lifecycle test.
            } finally {
                lifecycle.workerExited();
                finished.countDown();
            }
        });

        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        worker.join(1_000);

        assertThat(lifecycle.termination()).isEqualTo(WorkerTermination.TERMINATED);
        assertThat(lifecycle.hasExited()).isTrue();
    }
}
