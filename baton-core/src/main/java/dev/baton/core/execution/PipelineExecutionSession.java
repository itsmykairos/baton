package dev.baton.core.execution;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextView;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.PipelineRunStatus;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RunOptions;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.event.RunEvents;
import dev.baton.core.result.DefaultPipelineResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class PipelineExecutionSession implements PipelineRun {

    private final PipelineDefinition definition;
    private final ContextView input;
    private final ExecutorService executor;
    private final DeadlineScheduler deadlines = DeadlineScheduler.shared();
    private final ExecutionControl control = new ExecutionControl();
    private final CompletableFuture<PipelineResult> completion = new CompletableFuture<>();
    private final AtomicReference<PipelineRunStatus> status =
            new AtomicReference<>(PipelineRunStatus.CREATED);
    private final Object completionLock = new Object();
    private final ExecutionEngine engine;
    private final RunEvents events;
    private final Propagation propagation;

    public PipelineExecutionSession(PipelineDefinition definition, ContextView input) {
        this(definition, input, RunOptions.defaults());
    }

    public PipelineExecutionSession(PipelineDefinition definition, ContextView input, RunOptions options) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.input = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        // The run's settings overlay the outermost pipeline's (app + pipeline) settings.
        BatonConfig config = definition.config().merge(options.config());
        this.executor = Executors.newThreadPerTaskExecutor(config.threadFactory());
        List<PipelineListener> listeners = new ArrayList<>(definition.listeners());
        listeners.addAll(options.listeners());
        UUID runId = RunEvents.newRunId();
        this.events = RunEvents.create(definition.name(), listeners, config, runId);
        this.propagation = new Propagation(runId, definition.name(), config.contextPropagators());
        Deadline runDeadline = config.runTimeout()
                .map(Deadline::after)
                .orElseGet(Deadline::unlimited);
        this.engine = new ExecutionEngine(definition, executor, deadlines, control, events, runDeadline, config,
                propagation);
    }

    public PipelineResult executeSync() throws InterruptedException {
        if (!status.compareAndSet(PipelineRunStatus.CREATED, PipelineRunStatus.RUNNING)) {
            throw new IllegalStateException("Execution session already started");
        }
        events.runStarted();
        Propagation.Snapshot caller = propagation.capture();
        try {
            PipelineResult result = executeEngine(caller);
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
        // Captured on the caller's thread, restored on the run's coordinating thread.
        Propagation.Snapshot caller = propagation.capture();
        Thread.ofVirtual().name("baton-pipeline").start(() -> {
            try {
                PipelineResult result = executeEngine(caller);
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

    /** Runs the engine with the caller's context restored for the run. */
    private PipelineResult executeEngine(Propagation.Snapshot caller) {
        try (Restoration restoration = caller.restore(propagation.scope(ExecutionScope.Kind.RUN, null))) {
            PipelineResult result;
            try {
                result = engine.execute(input);
            } catch (RuntimeException | Error failure) {
                restoration.failed(failure);
                throw failure;
            }
            if (result.status() != PipelineStatus.COMPLETED) {
                restoration.failed(result.failure().orElseGet(
                        () -> new IllegalStateException("Pipeline ended " + result.status())));
            }
            return result;
        }
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
                        ),
                        result.conflicts()
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
