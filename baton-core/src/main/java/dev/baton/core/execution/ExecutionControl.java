package dev.baton.core.execution;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExecutionControl {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<TrackedTask<?>> activeTasks = ConcurrentHashMap.newKeySet();

    boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        activeTasks.forEach(TrackedTask::cancel);
        return true;
    }

    void track(TrackedTask<?> task) {
        if (cancelled.get()) {
            task.cancel();
            return;
        }
        activeTasks.add(task);
        // Close the race where cancellation happens between the first check and add.
        if (cancelled.get() && activeTasks.remove(task)) {
            task.cancel();
        }
    }

    void untrack(TrackedTask<?> task) {
        activeTasks.remove(task);
    }

    void abort() {
        activeTasks.forEach(TrackedTask::cancel);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("Pipeline execution was cancelled");
        }
    }
}
