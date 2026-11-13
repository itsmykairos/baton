package dev.baton.core.execution;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Coordinates cancellation and registration of the run's active stage tasks. */
final class ExecutionControl {

    private final Object lock = new Object();
    private final Set<TrackedTask<?>> activeTasks = new HashSet<>();
    private boolean cancelled;

    boolean cancel() {
        Set<TrackedTask<?>> snapshot;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            snapshot = Set.copyOf(activeTasks);
        }
        // Never invoke task cancellation (which may close child scopes) under
        // the registration lock.
        snapshot.forEach(TrackedTask::cancel);
        return true;
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
