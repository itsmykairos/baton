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
    private final List<TrackedTask<?>> tasks = new ArrayList<>();
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
        tasks.add(tracked);
        return new TrackedTaskHandle<>(tracked);
    }

    /** Cancels unfinished child tasks immediately; never waits for worker exit. */
    @Override
    public void close() {
        List<TrackedTask<?>> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = List.copyOf(tasks);
        }
        snapshot.stream()
                .filter(task -> !task.isDone())
                .forEach(TrackedTask::cancel);
    }

    private record TrackedTaskHandle<T>(TrackedTask<T> task)
            implements TaskHandle<T> {
        @Override
        public T join() throws Exception {
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
