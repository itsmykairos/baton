package dev.baton.core.execution;

import dev.baton.api.WorkerTermination;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedTaskTest {

    @Test
    void timeoutCompletesLogicallyBeforeInterruptionResistantWorkerExits()
            throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var deadlines = new DeadlineScheduler()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            TrackedTask<Void> task = TrackedTask.submit(
                    executor,
                    Deadline.after(Duration.ofMillis(80)),
                    deadlines,
                    () -> {
                        entered.countDown();
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                // Simulate application code that ignores interruption.
                            }
                        }
                        return null;
                    }
            );

            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(task::join).isInstanceOf(TimeoutException.class);
            assertThat(task.isDone()).isTrue();
            assertThat(task.workerTermination()).isEqualTo(WorkerTermination.UNCONFIRMED);

            release.countDown();
            assertThat(task.lifecycle().awaitExit(2, TimeUnit.SECONDS)).isTrue();
            assertThat(task.workerTermination()).isEqualTo(WorkerTermination.TERMINATED);
        }
    }

    @Test
    void completedTaskRetainsItsResult() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var deadlines = new DeadlineScheduler()) {
            TrackedTask<Integer> task = TrackedTask.submit(
                    executor,
                    Deadline.unlimited(),
                    deadlines,
                    () -> 42
            );

            assertThat(task.join()).isEqualTo(42);
            assertThat(task.lifecycle().awaitExit(2, TimeUnit.SECONDS)).isTrue();
            assertThat(task.workerTermination()).isEqualTo(WorkerTermination.TERMINATED);
        }
    }
}
