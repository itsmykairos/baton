package dev.baton.core.execution;

import dev.baton.api.WorkerTermination;
import dev.baton.core.concurrency.WorkerLifecycle;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks a task's logical result separately from the physical worker lifecycle.
 * A timeout completes the logical result immediately and requests interruption;
 * it does not claim that an interruption-resistant worker has exited.
 */
final class TrackedTask<T> {

    private final WorkerLifecycle lifecycle = new WorkerLifecycle();
    private final Deadline deadline;
    private final CompletableFuture<T> outcome = new CompletableFuture<>();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final DeadlineScheduler.Cancellable deadlineRegistration;
    private volatile Future<?> submitted;

    private TrackedTask(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task
    ) {
        Objects.requireNonNull(executor, "executor");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(deadlines, "deadlines");
        Objects.requireNonNull(task, "task");

        deadlineRegistration = deadlines.schedule(deadline, this::expire);
        try {
            submitted = executor.submit(() -> {
                if (!lifecycle.tryStart()) {
                    return;
                }
                try {
                    if (deadline.isExpired()) {
                        expire();
                        return;
                    }
                    T value = task.call();
                    complete(value);
                } catch (Throwable failure) {
                    fail(failure);
                } finally {
                    lifecycle.workerExited();
                }
            });
        } catch (RuntimeException submissionFailure) {
            deadlineRegistration.cancel();
            lifecycle.markNotStarted();
            outcome.completeExceptionally(submissionFailure);
            terminal.set(true);
            throw submissionFailure;
        }
    }

    static <T> TrackedTask<T> submit(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task
    ) {
        return new TrackedTask<>(executor, deadline, deadlines, task);
    }

    private void complete(T value) {
        if (deadline.isExpired()) {
            expire();
            return;
        }
        if (terminal.compareAndSet(false, true)) {
            deadlineRegistration.cancel();
            outcome.complete(value);
        }
    }

    private void fail(Throwable failure) {
        if (deadline.isExpired()) {
            expire();
            return;
        }
        if (terminal.compareAndSet(false, true)) {
            deadlineRegistration.cancel();
            outcome.completeExceptionally(failure);
        }
    }

    private void expire() {
        TimeoutException timeout = new TimeoutException("Task deadline expired");
        if (terminal.compareAndSet(false, true)) {
            outcome.completeExceptionally(timeout);
            lifecycle.requestCancellation();
            Future<?> future = submitted;
            if (future != null && !lifecycle.hasStarted()) {
                future.cancel(false);
            }
        }
    }

    void cancel() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        deadlineRegistration.cancel();
        outcome.completeExceptionally(new java.util.concurrent.CancellationException(
                "Task was cancelled"
        ));
        lifecycle.requestCancellation();
        Future<?> future = submitted;
        if (future != null && !lifecycle.hasStarted()) {
            future.cancel(false);
        }
    }

    T join() throws Exception {
        try {
            return outcome.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    boolean isDone() {
        return outcome.isDone();
    }

    WorkerTermination workerTermination() {
        return lifecycle.termination();
    }

    WorkerLifecycle lifecycle() {
        return lifecycle;
    }
}
