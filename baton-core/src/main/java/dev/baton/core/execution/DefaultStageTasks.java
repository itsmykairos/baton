package dev.baton.core.execution;

import dev.baton.api.StageTasks;
import dev.baton.api.TaskHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class DefaultStageTasks implements StageTasks, AutoCloseable {

    private final ExecutorService executor;
    private final List<Future<?>> tasks = new ArrayList<>();
    private boolean closed;

    DefaultStageTasks(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public synchronized <T> TaskHandle<T> fork(Callable<T> task) {
        if (closed) {
            throw new IllegalStateException("Stage task scope is closed");
        }

        Future<T> future = executor.submit(
                Objects.requireNonNull(task, "task")
        );

        tasks.add(future);
        return new DefaultTaskHandle<>(future);
    }

    /**
     * Child tasks belong to the stage. On stage completion, unfinished
     * children are interrupted and the scope waits for their completion.
     */
    @Override
    public void close() {
        List<Future<?>> snapshot;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
            snapshot = List.copyOf(tasks);
        }

        snapshot.stream()
                .filter(task -> !task.isDone())
                .forEach(task -> task.cancel(true));

        boolean interrupted = false;

        for (Future<?> task : snapshot) {
            for (;;) {
                try {
                    task.get();
                    break;
                } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    task.cancel(true);
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record DefaultTaskHandle<T>(Future<T> future)
            implements TaskHandle<T> {

        @Override
        public T join() throws Exception {
            try {
                return future.get();
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

        @Override
        public boolean isDone() {
            return future.isDone();
        }
    }
}