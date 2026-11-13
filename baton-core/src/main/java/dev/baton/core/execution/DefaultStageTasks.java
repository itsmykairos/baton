package dev.baton.core.execution;

import dev.baton.api.StageTasks;
import dev.baton.api.TaskHandle;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/** Child tasks owned by one stage; closing the scope cancels unfinished children. */
final class DefaultStageTasks implements StageTasks, AutoCloseable {

    private final ExecutorService executor;
    private final Deadline deadline;
    private final DeadlineScheduler deadlines;
    private final List<TrackedTaskHandle<?>> handles = new ArrayList<>();
    private boolean closed;

    DefaultStageTasks(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    }

    @Override
    public synchronized <T> TaskHandle<T> fork(Callable<T> task) {
        if (closed) {
            throw new IllegalStateException("Stage task scope is closed");
        }

        TrackedTask<T> tracked = TrackedTask.submit(
                executor,
                deadline,
                deadlines,
                Objects.requireNonNull(task, "task")
        );
        TrackedTaskHandle<T> handle = new TrackedTaskHandle<>(tracked);
        handles.add(handle);
        return handle;
    }

    /**
     * Cancels unfinished child tasks immediately; never waits for worker exit
     * and never throws. Used when the owning stage fails, times out or is cancelled.
     */
    @Override
    public void close() {
        List<TrackedTaskHandle<?>> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = List.copyOf(handles);
        }
        cancelUnfinished(snapshot);
    }

    /**
     * Closes the scope after the owning stage returned normally. Unfinished
     * children are cancelled. If a child already completed with a failure that
     * the stage never observed through {@link TaskHandle#join()}, that failure
     * is thrown so it fails the stage instead of being lost.
     */
    void closeAndVerify() throws Exception {
        List<TrackedTaskHandle<?>> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = List.copyOf(handles);
        }
        cancelUnfinished(snapshot);

        Throwable first = null;
        for (TrackedTaskHandle<?> handle : snapshot) {
            if (handle.observed) {
                continue;
            }
            Throwable failure = handle.task.failureIfDone().orElse(null);
            if (failure == null) {
                continue;
            }
            if (first == null) {
                first = failure;
            } else if (first != failure) {
                first.addSuppressed(failure);
            }
        }
        if (first instanceof Exception exception) {
            throw exception;
        }
        if (first instanceof Error error) {
            throw error;
        }
        if (first != null) {
            throw new RuntimeException(first);
        }
    }

    private static void cancelUnfinished(List<TrackedTaskHandle<?>> snapshot) {
        snapshot.stream()
                .map(handle -> handle.task)
                .filter(task -> !task.isDone())
                .forEach(TrackedTask::cancel);
    }

    private static final class TrackedTaskHandle<T> implements TaskHandle<T> {

        private final TrackedTask<T> task;
        private volatile boolean observed;

        private TrackedTaskHandle(TrackedTask<T> task) {
            this.task = task;
        }

        @Override
        public T join() throws Exception {
            observed = true;
            try {
                return task.join();
            } catch (TrackedTask.TaskExecutionException e) {
                if (e.getCause() instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                throw e;
            }
        }

        @Override
        public boolean isDone() {
            return task.isDone();
        }
    }
}
