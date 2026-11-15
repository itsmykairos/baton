package dev.baton.core.execution;

import dev.baton.api.BatonConfig;
import dev.baton.api.StageTasks;
import dev.baton.api.StageTimeoutException;
import dev.baton.api.TaskHandle;
import dev.baton.api.UnjoinedChildFailurePolicy;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/** Child tasks owned by one stage; closing the scope cancels unfinished children. */
final class DefaultStageTasks implements StageTasks, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("dev.baton");

    private final ExecutorService executor;
    private final Deadline deadline;
    private final DeadlineScheduler deadlines;
    private final List<TrackedTaskHandle<?>> handles = new ArrayList<>();
    /** Limits concurrently running children; null when unlimited. */
    private final Semaphore slots;

    private final UnjoinedChildFailurePolicy unjoinedFailure;
    private final TaskWrapper wrapper;
    private boolean closed;

    DefaultStageTasks(ExecutorService executor, Deadline deadline, DeadlineScheduler deadlines) {
        this(
                executor,
                deadline,
                deadlines,
                BatonConfig.UNLIMITED,
                UnjoinedChildFailurePolicy.FAIL_STAGE,
                TaskWrapper.NONE);
    }

    DefaultStageTasks(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            int maxChildTasks,
            UnjoinedChildFailurePolicy unjoinedFailure,
            TaskWrapper wrapper) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.slots = maxChildTasks == BatonConfig.UNLIMITED ? null : new Semaphore(maxChildTasks);
        this.unjoinedFailure = Objects.requireNonNull(unjoinedFailure, "unjoinedFailure");
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
    }

    @Override
    public synchronized <T> TaskHandle<T> fork(Callable<T> task) {
        if (closed) {
            throw new IllegalStateException("Stage task scope is closed");
        }

        Objects.requireNonNull(task, "task");
        // fork() never blocks: a limited child waits for a slot on its own thread,
        // interruptibly and within the stage's deadline.
        Callable<T> body = slots == null
                ? task
                : () -> {
                    slots.acquire();
                    try {
                        return task.call();
                    } finally {
                        slots.release();
                    }
                };
        TrackedTask<T> tracked = TrackedTask.submit(executor, deadline, deadlines, wrapper.wrap(body));
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
                // Not attached as suppressed: children may throw shared or cached
                // exception instances, which must not be mutated.
                LOG.log(System.Logger.Level.WARNING, "Another unjoined child task also failed", failure);
            }
        }
        if (first != null && unjoinedFailure == UnjoinedChildFailurePolicy.IGNORE) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Ignoring failure of an unjoined child task (unjoinedChildFailure=IGNORE)",
                    first);
            return;
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
            } catch (TrackedTask.DeadlineExceededException expired) {
                throw new StageTimeoutException("Child task was stopped because its stage's deadline expired");
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
