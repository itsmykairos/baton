package dev.baton.core.execution;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Coordinates cancellation and registration of the run's active stage tasks. */
final class ExecutionControl {

    private final Object lock = new Object();
    private final Set<TrackedTask<?>> activeTasks = new HashSet<>();
    private boolean cancelled;

    /**
     * Cancels the run and every registered task. Returns true only for the first call,
     * but every call cancels the registered tasks before returning (task cancellation
     * is idempotent), so no caller returns while a concurrent first call is still
     * cancelling.
     */
    boolean cancel() {
        Cancellation cancellation = beginCancel();
        cancellation.cancelTasks();
        return cancellation.first();
    }

    /**
     * Marks the run cancelled and snapshots its tasks, without cancelling them yet.
     * Lets a caller mark cancellation under its own lock and run the (possibly long)
     * cascade with {@link Cancellation#cancelTasks()} after releasing it.
     */
    Cancellation beginCancel() {
        synchronized (lock) {
            boolean first = !cancelled;
            cancelled = true;
            return new Cancellation(first, Set.copyOf(activeTasks));
        }
    }

    /** A cancellation that has been marked but whose tasks may not be cancelled yet. */
    record Cancellation(boolean first, Set<TrackedTask<?>> tasks) {
        /** Cancels the snapshot's tasks; never call it under a lock. */
        void cancelTasks() {
            tasks.forEach(TrackedTask::cancel);
        }
    }

    void track(TrackedTask<?> task) {
        boolean cancelImmediately;
        synchronized (lock) {
            cancelImmediately = cancelled;
            if (!cancelImmediately) {
                activeTasks.add(task);
            }
        }
        if (cancelImmediately) {
            task.cancel();
        }
    }

    void untrack(TrackedTask<?> task) {
        synchronized (lock) {
            activeTasks.remove(task);
        }
    }

    /** Cancels currently registered tasks after an unexpected engine failure. */
    void abort() {
        Set<TrackedTask<?>> snapshot;
        synchronized (lock) {
            snapshot = Set.copyOf(activeTasks);
        }
        snapshot.forEach(TrackedTask::cancel);
    }

    boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Pipeline execution was cancelled");
        }
    }
}
