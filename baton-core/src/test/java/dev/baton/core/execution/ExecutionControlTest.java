package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExecutionControlTest {

    @Test
    void cancellationCannotMissTaskRegisteredConcurrently() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var deadlines = new DeadlineScheduler()) {
            for (int i = 0; i < 250; i++) {
                ExecutionControl control = new ExecutionControl();
                CountDownLatch workerStarted = new CountDownLatch(1);
                CountDownLatch releaseWorker = new CountDownLatch(1);
                CountDownLatch raceStart = new CountDownLatch(1);

                TrackedTask<Void> task = TrackedTask.submit(executor, Deadline.unlimited(), deadlines, () -> {
                    workerStarted.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    }
                    return null;
                });

                Thread registration = Thread.ofVirtual().start(() -> {
                    await(raceStart);
                    control.track(task);
                });
                Thread cancellation = Thread.ofVirtual().start(() -> {
                    await(raceStart);
                    control.cancel();
                });
                raceStart.countDown();
                registration.join();
                cancellation.join();

                assertThat(task.isDone()).isTrue();
                assertThat(control.isCancelled()).isTrue();
                releaseWorker.countDown();
                assertThat(task.lifecycle().awaitExit(1, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
