package dev.baton.core.deadline;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Schedules deadline actions on a single daemon timer thread.
 *
 * <p>Pipeline runs use the process-wide {@link #shared()} instance, so a run
 * never owns a platform thread for its timeouts. Each registration can be
 * cancelled individually. Actions are handed off to a virtual thread, so a
 * slow action can never delay other deadlines.</p>
 */
public final class DeadlineScheduler implements AutoCloseable {

    private static final class SharedHolder {
        static final DeadlineScheduler INSTANCE = new DeadlineScheduler(true);
    }

    private final ScheduledThreadPoolExecutor scheduler;
    private final boolean shared;

    /** Creates a privately owned scheduler; {@link #close()} shuts it down. */
    public DeadlineScheduler() {
        this(false);
    }

    private DeadlineScheduler(boolean shared) {
        this.shared = shared;
        scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "baton-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /** Returns the process-wide scheduler used by pipeline runs. */
    public static DeadlineScheduler shared() {
        return SharedHolder.INSTANCE;
    }

    /** Schedules an action at an absolute monotonic deadline. */
    public Cancellable schedule(Deadline deadline, Runnable action) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(action, "action");
        if (deadline.isUnlimited()) {
            return () -> false;
        }

        ScheduledFuture<?> future = scheduler.schedule(
                () -> Thread.ofVirtual().name("baton-deadline-action").start(action),
                deadline.remainingNanos(),
                TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    /** Shuts down a privately owned scheduler. Has no effect on {@link #shared()}. */
    @Override
    public void close() {
        if (!shared) {
            scheduler.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface Cancellable {
        boolean cancel();
    }
}
