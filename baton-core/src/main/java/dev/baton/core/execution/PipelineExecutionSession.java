package dev.baton.core.execution;

import dev.baton.api.ContextView;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.PipelineRunStatus;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RunOptions;
import dev.baton.api.event.PipelineListener;
import dev.baton.core.deadline.DeadlineScheduler;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.event.RunEvents;
import dev.baton.core.result.DefaultPipelineResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class PipelineExecutionSession implements PipelineRun {

    private final PipelineDefinition definition;
    private final ContextView input;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final DeadlineScheduler deadlines = DeadlineScheduler.shared();
    private final ExecutionControl control = new ExecutionControl();
    private final CompletableFuture<PipelineResult> completion = new CompletableFuture<>();
    private final AtomicReference<PipelineRunStatus> status =
            new AtomicReference<>(PipelineRunStatus.CREATED);
    private final Object completionLock = new Object();
    private final ExecutionEngine engine;
    private final RunEvents events;

    public PipelineExecutionSession(PipelineDefinition definition, ContextView input) {
        this(definition, input, RunOptions.defaults());
    }

    public PipelineExecutionSession(PipelineDefinition definition, ContextView input, RunOptions options) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.input = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        List<PipelineListener> listeners = new ArrayList<>(definition.listeners());
        listeners.addAll(options.listeners());
        this.events = RunEvents.create(definition.name(), listeners, options.eventBufferCapacity());
        this.engine = new ExecutionEngine(definition, executor, deadlines, control, events);
    }

    public PipelineResult executeSync() throws InterruptedException {
        if (!status.compareAndSet(PipelineRunStatus.CREATED, PipelineRunStatus.RUNNING)) {
            throw new IllegalStateException("Execution session already started");
        }
        events.runStarted();
        try {
            PipelineResult result = executeEngine();
            complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            control.abort();
            events.runFinished(PipelineStatus.FAILED, Optional.of(failure));
            throw failure;
        } finally {
            shutdownResources();
        }
    }

    public void startAsync() {
        if (!status.compareAndSet(PipelineRunStatus.CREATED, PipelineRunStatus.RUNNING)) {
            throw new IllegalStateException("Execution session already started");
        }
        events.runStarted();
        Thread.ofVirtual().name("baton-pipeline").start(() -> {
            try {
                PipelineResult result = executeEngine();
                complete(result);
            } catch (Throwable failure) {
                boolean cancelled = control.isCancelled();
                control.abort();
                PipelineStatus pipelineStatus = cancelled
                        ? PipelineStatus.CANCELLED : PipelineStatus.FAILED;
                complete(partialResult(pipelineStatus, failure));
            } finally {
                shutdownResources();
            }
        });
    }

    private PipelineResult executeEngine() {
        return engine.execute(input);
    }

    /** Keeps the stages and context reached before an unexpected engine failure. */
    private PipelineResult partialResult(PipelineStatus pipelineStatus, Throwable failure) {
        try {
            return engine.partialResult(pipelineStatus, failure);
        } catch (IllegalStateException notStarted) {
            return new DefaultPipelineResult(pipelineStatus, input, List.of(), failure);
        }
    }

    private void complete(PipelineResult result) {
        synchronized (completionLock) {
            if (completion.isDone()) {
                return;
            }
            if (control.isCancelled() && result.status() != PipelineStatus.CANCELLED) {
                result = new DefaultPipelineResult(
                        PipelineStatus.CANCELLED,
                        result.context(),
                        result.stages(),
                        new java.util.concurrent.CancellationException(
                                "Pipeline execution was cancelled"
                        )
                );
            }
            PipelineRunStatus terminalStatus = switch (result.status()) {
                case COMPLETED -> PipelineRunStatus.COMPLETED;
                case FAILED -> PipelineRunStatus.FAILED;
                case TIMED_OUT -> PipelineRunStatus.TIMED_OUT;
                case CANCELLED -> PipelineRunStatus.CANCELLED;
            };
            status.set(terminalStatus);
            events.runFinished(result);
            completion.complete(result);
        }
    }

    /** Releases the run's executor without waiting for workers to exit. */
    private void shutdownResources() {
        if (control.isCancelled()) {
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }
    }

    @Override
    public PipelineResult await() throws InterruptedException {
        try {
            return completion.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unexpected pipeline execution failure", e.getCause());
        }
    }

    @Override
    public boolean cancel() {
        synchronized (completionLock) {
            if (completion.isDone()) {
                return false;
            }
            boolean accepted = control.cancel();
            if (accepted) {
                executor.shutdownNow();
            }
            return accepted;
        }
    }

    @Override
    public boolean isDone() {
        return completion.isDone();
    }

    @Override
    public PipelineRunStatus status() {
        return status.get();
    }
}
