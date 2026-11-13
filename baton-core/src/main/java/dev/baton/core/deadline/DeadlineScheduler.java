package dev.baton.core.deadline;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** One lightweight deadline scheduler owned by a pipeline run. */
public final class DeadlineScheduler implements AutoCloseable {

    private final ScheduledExecutorService scheduler;

    public DeadlineScheduler() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "baton-deadlines");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** Schedules an action at an absolute monotonic deadline. */
    public Cancellable schedule(Deadline deadline, Runnable action) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(action, "action");
        if (deadline.isUnlimited()) {
            return () -> false;
        }

        ScheduledFuture<?> future = scheduler.schedule(
                action,
                deadline.remainingNanos(),
                TimeUnit.NANOSECONDS
        );
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface Cancellable {
        boolean cancel();
    }
}
