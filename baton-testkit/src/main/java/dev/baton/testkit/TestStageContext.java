package dev.baton.testkit;

import dev.baton.api.ContextView;
import dev.baton.api.StageContext;
import dev.baton.api.StageTasks;
import dev.baton.api.TaskHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A {@link StageContext} for calling a stage directly in a unit test.
 *
 * <p>Forked child tasks run on virtual threads. {@link #close()} interrupts any
 * child still running, like the runtime does when a stage returns.</p>
 */
public final class TestStageContext implements StageContext, AutoCloseable {

    private final ContextView context;
    private final Tasks tasks = new Tasks();

    private TestStageContext(ContextView context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Creates a test context over {@code context}.
     *
     * @param context the snapshot the stage will read
     * @return a new test context
     */
    public static TestStageContext of(ContextView context) {
        return new TestStageContext(context);
    }

    /** {@return a new test context with no values} */
    public static TestStageContext empty() {
        return new TestStageContext(ContextView.empty());
    }

    @Override
    public ContextView context() {
        return context;
    }

    @Override
    public StageTasks tasks() {
        return tasks;
    }

    /** {@return how many child tasks the stage forked} */
    public int forkedTaskCount() {
        return tasks.count();
    }

    /** Interrupts child tasks that are still running; forking is no longer allowed. */
    @Override
    public void close() {
        tasks.close();
    }

    private static final class Tasks implements StageTasks {

        private final List<Thread> threads = new ArrayList<>();
        private boolean closed;

        @Override
        public synchronized <T> TaskHandle<T> fork(Callable<T> task) {
            Objects.requireNonNull(task, "task");
            if (closed) {
                throw new IllegalStateException("Stage task scope is closed");
            }
            CompletableFuture<T> outcome = new CompletableFuture<>();
            threads.add(Thread.ofVirtual().name("baton-test-child").start(() -> {
                try {
                    outcome.complete(task.call());
                } catch (Throwable failure) {
                    outcome.completeExceptionally(failure);
                }
            }));
            return new Handle<>(outcome);
        }

        synchronized int count() {
            return threads.size();
        }

        void close() {
            List<Thread> snapshot;
            synchronized (this) {
                closed = true;
                snapshot = List.copyOf(threads);
            }
            snapshot.forEach(Thread::interrupt);
        }
    }

    private record Handle<T>(CompletableFuture<T> outcome) implements TaskHandle<T> {
        @Override
        public T join() throws Exception {
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
                throw new CancellationException(String.valueOf(cause));
            }
        }

        @Override
        public boolean isDone() {
            return outcome.isDone();
        }
    }
}
