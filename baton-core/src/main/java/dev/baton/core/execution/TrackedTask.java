package dev.baton.core.execution;

import dev.baton.api.WorkerTermination;
import dev.baton.core.concurrency.WorkerLifecycle;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Logical task outcome and physical worker lifecycle are tracked separately. */
final class TrackedTask<T> {

    private final WorkerLifecycle lifecycle = new WorkerLifecycle();
    private final Deadline deadline;
    private final CompletableFuture<T> outcome = new CompletableFuture<>();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final Object completionLock = new Object();
    private volatile long workerStartedNanos;
    private volatile long logicalCompletedNanos;
    private final DeadlineScheduler.Cancellable deadlineRegistration;
    private final Runnable cancellationAction;
    private volatile Future<?> submitted;

    private TrackedTask(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task,
            Runnable cancellationAction,
            Consumer<TrackedTask<T>> beforeStart
    ) {
        Objects.requireNonNull(executor, "executor");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(deadlines, "deadlines");
        Objects.requireNonNull(task, "task");
        this.cancellationAction = Objects.requireNonNull(cancellationAction, "cancellationAction");

        // Lets the owner register this task before it can start, time out or complete,
        // so observers never miss it. The callback must not cancel the task.
        beforeStart.accept(this);
        deadlineRegistration = deadlines.schedule(deadline, this::expire);
        try {
            submitted = executor.submit(() -> {
                if (!lifecycle.tryStart()) {
                    return;
                }
                workerStartedNanos = System.nanoTime();
                T value = null;
                Throwable failure = null;
                try {
                    if (terminal.get()) {
                        return;
                    }
                    if (deadline.isExpired()) {
                        expire();
                        return;
                    }
                    value = task.call();
                } catch (Throwable taskFailure) {
                    failure = taskFailure;
                } finally {
                    // Publish ordinary completion only after physical worker exit.
                    lifecycle.workerExited();
                }
                if (failure == null) {
                    complete(value);
                } else {
                    fail(failure);
                }
            });
        } catch (RuntimeException submissionFailure) {
            deadlineRegistration.cancel();
            lifecycle.markNotStarted();
            terminal.set(true);
            logicalCompletedNanos = System.nanoTime();
            outcome.completeExceptionally(submissionFailure);
            runCancellationAction();
            throw submissionFailure;
        }

        // Covers a deadline/cancel race that happens before submitted is assigned.
        if (terminal.get() && !lifecycle.hasStarted()) {
            Future<?> future = submitted;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    static <T> TrackedTask<T> submit(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task
    ) {
        return submit(executor, deadline, deadlines, task, () -> { });
    }

    static <T> TrackedTask<T> submit(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task,
            Runnable cancellationAction
    ) {
        return submit(executor, deadline, deadlines, task, cancellationAction, ignored -> { });
    }

    /** As {@link #submit(ExecutorService, Deadline, DeadlineScheduler, Callable, Runnable)},
     *  calling {@code beforeStart} with the task before it is scheduled or submitted. */
    static <T> TrackedTask<T> submit(
            ExecutorService executor,
            Deadline deadline,
            DeadlineScheduler deadlines,
            Callable<T> task,
            Runnable cancellationAction,
            Consumer<TrackedTask<T>> beforeStart
    ) {
        return new TrackedTask<>(executor, deadline, deadlines, task, cancellationAction,
                Objects.requireNonNull(beforeStart, "beforeStart"));
    }

    private void complete(T value) {
        boolean expired = false;
        synchronized (completionLock) {
            if (terminal.get()) {
                return;
            }
            if (deadline.isExpired()) {
                terminal.set(true);
                logicalCompletedNanos = System.nanoTime();
                expired = true;
            } else {
                terminal.set(true);
                logicalCompletedNanos = System.nanoTime();
                deadlineRegistration.cancel();
                outcome.complete(value);
            }
        }
        if (expired) {
            cancelWorkerAndChildren();
            outcome.completeExceptionally(new DeadlineExceededException("Task deadline expired"));
        }
    }

    private void fail(Throwable failure) {
        boolean expired = false;
        synchronized (completionLock) {
            if (terminal.get()) {
                return;
            }
            if (deadline.isExpired()) {
                terminal.set(true);
                logicalCompletedNanos = System.nanoTime();
                expired = true;
            } else {
                terminal.set(true);
                logicalCompletedNanos = System.nanoTime();
                deadlineRegistration.cancel();
                outcome.completeExceptionally(failure);
            }
        }
        if (expired) {
            cancelWorkerAndChildren();
            outcome.completeExceptionally(new DeadlineExceededException("Task deadline expired"));
        }
    }

    private void expire() {
        synchronized (completionLock) {
            if (terminal.get()) {
                return;
            }
            terminal.set(true);
            logicalCompletedNanos = System.nanoTime();
        }
        cancelWorkerAndChildren();
        outcome.completeExceptionally(new DeadlineExceededException("Task deadline expired"));
    }

    /**
     * Cancels the task. If its deadline has already passed (for example when an
     * enclosing nested stage with the same deadline expires first), the outcome is
     * a deadline expiry rather than a cancellation.
     */
    void cancel() {
        boolean expired;
        synchronized (completionLock) {
            if (terminal.get()) {
                return;
            }
            terminal.set(true);
            logicalCompletedNanos = System.nanoTime();
            deadlineRegistration.cancel();
            expired = deadline.isExpired();
        }
        cancelWorkerAndChildren();
        outcome.completeExceptionally(expired
                ? new DeadlineExceededException("Task deadline expired")
                : new CancellationException("Task was cancelled"));
    }

    private void cancelWorkerAndChildren() {
        // Cancel owned work (child tasks or a nested run) before interrupting the
        // worker, so it is logically complete before this task's outcome is published.
        runCancellationAction();
        lifecycle.requestCancellation();
        cancelQueuedSubmission();
    }

    private void cancelQueuedSubmission() {
        Future<?> future = submitted;
        if (future != null && !lifecycle.hasStarted()) {
            future.cancel(false);
        }
    }

    private void runCancellationAction() {
        try {
            cancellationAction.run();
        } catch (Throwable ignored) {
            // Cancellation must remain best-effort and must not replace the outcome.
        }
    }

    T join() throws Exception {
        try {
            return outcome.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException interrupted) {
                throw new TaskExecutionException(interrupted);
            }
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

    /** Runs {@code callback} once the logical outcome is known (immediately if it already is). */
    void onDone(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        outcome.whenComplete((value, failure) -> callback.run());
    }

    /**
     * Returns the task's own failure if it has completed with one. Cancellation and
     * deadline expiry are lifecycle outcomes, not task failures, and are not reported.
     */
    Optional<Throwable> failureIfDone() {
        if (!outcome.isCompletedExceptionally()) {
            return Optional.empty();
        }
        Throwable failure = outcome.handle((value, error) -> error).getNow(null);
        if (failure instanceof CompletionException wrapped && wrapped.getCause() != null) {
            failure = wrapped.getCause();
        }
        if (failure == null
                || failure instanceof CancellationException
                || failure instanceof DeadlineExceededException) {
            return Optional.empty();
        }
        return Optional.of(failure);
    }

    WorkerTermination workerTermination() {
        return lifecycle.termination();
    }

    WorkerLifecycle lifecycle() {
        return lifecycle;
    }

    long elapsedNanos() {
        long started = workerStartedNanos;
        if (started == 0L) {
            return 0L;
        }
        long completed = logicalCompletedNanos;
        long end = completed == 0L ? System.nanoTime() : completed;
        return Math.max(0L, end - started);
    }

    static final class DeadlineExceededException extends TimeoutException {
        private static final long serialVersionUID = 1L;

        DeadlineExceededException(String message) {
            super(message);
        }
    }

    static final class TaskExecutionException extends Exception {
        private static final long serialVersionUID = 1L;

        TaskExecutionException(Throwable cause) {
            super(cause);
        }
    }
}
