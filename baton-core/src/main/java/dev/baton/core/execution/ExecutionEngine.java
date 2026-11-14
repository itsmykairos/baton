package dev.baton.core.execution;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.KeyConflict;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.OverwritePolicy;
import dev.baton.api.NestedPipelineStage;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageAttemptTimeoutException;
import dev.baton.api.StageFallback;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.TypeClashPolicy;
import dev.baton.api.WorkerTermination;
import dev.baton.core.DefaultPipeline;
import dev.baton.api.event.StagePath;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import dev.baton.core.definition.LayerDefinition;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.definition.StageNode;
import dev.baton.core.event.RunEvents;
import dev.baton.core.result.DefaultPipelineResult;
import dev.baton.core.result.DefaultStageResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs one pipeline execution, top-level or nested.
 *
 * <p>Progress (the current context, finished stage results, the layer in flight
 * and the keys written so far) is guarded by {@link #state}, so other threads can
 * take a {@link #snapshot()} or a {@link #partialResult partial result} while the
 * engine runs.</p>
 *
 * <p>A stage that wraps a core {@link DefaultPipeline} via
 * {@link NestedPipelineStage} is run by a child engine on the same executor and
 * deadline scheduler, with its own {@link ExecutionControl}. The child inherits
 * the nested stage's deadline, and cancelling the nested stage cancels the child
 * control, which cascades to every descendant.</p>
 */
final class ExecutionEngine {

    private final PipelineDefinition definition;
    private final ExecutorService executor;
    private final DeadlineScheduler deadlines;
    private final ExecutionControl control;
    private final Deadline inherited;
    /** Pipeline-scoped settings: this pipeline's own config (plus the run's, if outermost). */
    private final BatonConfig config;
    /** Run-scoped settings of the outermost run. */
    private final BatonConfig runConfig;
    /** Where events go: the outermost run's listeners, and a nested pipeline's own if enabled. */
    private final List<Observer> observers;
    private final boolean eventsEnabled;
    /** Path of the nested stage running this engine; null for the outermost run. */
    private final StagePath parentPath;
    /** Receives conflicts recorded here, so parents can report their children's too. */
    private final Consumer<KeyConflict> parentConflicts;
    /** Carries the run's thread-bound context into every stage, child task and attempt. */
    private final Propagation propagation;

    private final Object state = new Object();
    private ContextView context;
    private final List<StageResult> results = new ArrayList<>();
    private final Set<ContextKey<?>> writtenKeys = new LinkedHashSet<>();
    private final List<KeyConflict> conflicts = new ArrayList<>();
    private ActiveLayer activeLayer;

    /** Per-stage attempt state, so results can report attempts and nested children. */
    private final Map<TrackedTask<?>, StageRun> stageRuns = new ConcurrentHashMap<>();

    /** The outermost engine of a run limited by {@code runDeadline}, with the run's settings. */
    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control,
            RunEvents events,
            Deadline runDeadline,
            BatonConfig config,
            Propagation propagation
    ) {
        this(definition, executor, deadlines, control, runDeadline, config, config,
                List.of(new Observer(events, null)), null, null, propagation);
    }

    private ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control,
            Deadline inherited,
            BatonConfig config,
            BatonConfig runConfig,
            List<Observer> observers,
            StagePath parentPath,
            Consumer<KeyConflict> parentConflicts,
            Propagation propagation
    ) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.control = Objects.requireNonNull(control, "control");
        this.inherited = Objects.requireNonNull(inherited, "inherited");
        this.config = Objects.requireNonNull(config, "config");
        this.runConfig = Objects.requireNonNull(runConfig, "runConfig");
        this.observers = List.copyOf(observers);
        this.eventsEnabled = this.observers.stream().anyMatch(observer -> observer.events().enabled());
        this.parentPath = parentPath;
        this.parentConflicts = parentConflicts;
        this.propagation = Objects.requireNonNull(propagation, "propagation");
    }

    DefaultPipelineResult execute(ContextView input) {
        Objects.requireNonNull(input, "input");
        synchronized (state) {
            context = input;
        }

        try {
            for (LayerDefinition layer : definition.layers()) {
                control.throwIfCancelled();
                if (inherited.isExpired()) {
                    return partialResult(PipelineStatus.TIMED_OUT, deadlineExpired());
                }

                LayerOutcome layerOutcome = executeLayer(layer, currentContext());
                MergeOutcome merge = resolveLayer(layer.stages(), layerOutcome.results(), currentContext());
                synchronized (state) {
                    results.addAll(merge.results());
                    context = merge.patch().applyTo(context, config);
                    writtenKeys.addAll(merge.patch().writtenKeys());
                    activeLayer = null;
                }
                // Emitted after the merge so a conflict's FAILED status is what listeners see.
                if (eventsEnabled) {
                    List<StageResult> finalResults = merge.results();
                    for (int i = 0; i < finalResults.size(); i++) {
                        StageResult result = finalResults.get(i);
                        emitStageFinished(result.stageId(), result, layerOutcome.startedAt()[i]);
                    }
                }

                if (control.isCancelled()) {
                    return partialResult(
                            PipelineStatus.CANCELLED,
                            new CancellationException("Pipeline execution was cancelled")
                    );
                }

                StageResult trigger = layerOutcome.trigger();
                if (trigger != null) {
                    PipelineStatus status = trigger.status() == StageStatus.TIMED_OUT
                            ? PipelineStatus.TIMED_OUT : PipelineStatus.FAILED;
                    return partialResult(status, trigger.failure().orElse(null));
                }
                if (merge.conflict() != null) {
                    return partialResult(PipelineStatus.FAILED, merge.conflict());
                }
                // Stages that CONTINUE past a timeout still end the run if the run's
                // (or nested stage's) own deadline is what expired.
                if (inherited.isExpired() && merge.results().stream()
                        .anyMatch(result -> result.status() == StageStatus.TIMED_OUT)) {
                    return partialResult(PipelineStatus.TIMED_OUT, deadlineExpired());
                }
            }

            return partialResult(PipelineStatus.COMPLETED, null);
        } catch (CancellationException e) {
            return partialResult(PipelineStatus.CANCELLED, e);
        }
    }

    /** Result built from whatever progress has been made so far. */
    DefaultPipelineResult partialResult(PipelineStatus status, Throwable failure) {
        synchronized (state) {
            if (context == null) {
                throw new IllegalStateException("Execution has not started");
            }
            return new DefaultPipelineResult(status, context, snapshot(), failure, List.copyOf(conflicts));
        }
    }

    /**
     * Stage results so far, in definition order: finished layers plus every stage
     * of the layer in flight whose outcome is already known. Never blocks on a
     * running stage. Stages of layers not yet reached are omitted.
     */
    List<StageResult> snapshot() {
        synchronized (state) {
            List<StageResult> snapshot = new ArrayList<>(results);
            ActiveLayer layer = activeLayer;
            if (layer != null) {
                for (int i = 0; i < layer.stages().size(); i++) {
                    StageResult known = layer.results()[i];
                    TrackedTask<StageExecution> task = layer.tasks()[i];
                    if (known != null) {
                        snapshot.add(known);
                    } else if (task != null && task.isDone()) {
                        snapshot.add(outcomeOfDone(layer.stages().get(i), task));
                    }
                }
            }
            return snapshot;
        }
    }

    /**
     * The patch a nested run hands to its parent: every key written by an applied
     * patch, with its final value. Unlike a before/after diff, a rewrite with an
     * equal value still counts as a write.
     */
    ContextPatch writtenPatch() {
        synchronized (state) {
            ContextPatch.Builder builder = ContextPatch.builder();
            for (ContextKey<?> key : writtenKeys) {
                // A key replaced by one of another type (TypeClashPolicy.REPLACE) is gone.
                if (context.contains(key)) {
                    putUntyped(builder, key, context);
                }
            }
            return builder.build();
        }
    }

    private ContextView currentContext() {
        synchronized (state) {
            return context;
        }
    }

    private LayerOutcome executeLayer(LayerDefinition layer, ContextView context) {
        List<StageNode> stages = layer.stages();
        int size = stages.size();
        @SuppressWarnings("unchecked")
        TrackedTask<StageExecution>[] tasks = new TrackedTask[size];
        StageResult[] layerResults = new StageResult[size];
        Instant[] startedAt = new Instant[size];
        synchronized (state) {
            activeLayer = new ActiveLayer(stages, tasks, layerResults);
        }
        LinkedBlockingQueue<Integer> finished = new LinkedBlockingQueue<>();

        // Settle stages that will not run (blocked, skipped, failed condition) before
        // submitting any; a FAIL_FAST condition failure then starts nothing.
        StageResult trigger = null;
        for (int i = 0; i < size; i++) {
            StageResult settled = settleBeforeStart(stages.get(i), context);
            if (settled != null) {
                record(layerResults, i, settled);
                if (trigger == null && stopsPipeline(stages.get(i), settled)) {
                    trigger = settled;
                }
            }
        }
        if (trigger != null) {
            for (int i = 0; i < size; i++) {
                if (layerResults[i] == null) {
                    record(layerResults, i, cancelledBeforeStart(stages.get(i)));
                }
            }
            return new LayerOutcome(List.of(layerResults), trigger, startedAt);
        }

        // Start runnable stages, at most maxParallelStages at a time; the rest wait.
        Deque<Integer> waiting = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            if (layerResults[i] == null) {
                waiting.add(i);
            }
        }
        int pending = startStages(waiting, 0, stages, context, tasks, layerResults, startedAt, finished);

        // Observe stages in completion order so a FAIL_FAST failure can cancel
        // siblings immediately. Results are stored at their definition index.
        // A timeout/cancel completes logically even when the worker is still running.
        try {
            while (pending > 0) {
                int i = finished.take();
                pending--;
                StageResult result = withFallback(stages.get(i), context,
                        awaitStage(stages.get(i), tasks[i]));
                record(layerResults, i, result);
                if (trigger == null && stopsPipeline(stages.get(i), result)) {
                    trigger = result;
                    cancelUnfinished(tasks, layerResults);
                }
                if (trigger == null) {
                    pending += startStages(waiting, pending, stages, context, tasks, layerResults,
                            startedAt, finished);
                }
            }
        } catch (InterruptedException interrupted) {
            // Every registered task is logically complete once cancel() returns, so
            // waiting for their outcomes is immediate; do it without the interrupt flag
            // so real outcomes (such as TIMED_OUT) are not replaced by CANCELLED.
            control.cancel();
            for (int i = 0; i < size; i++) {
                if (layerResults[i] == null && tasks[i] != null) {
                    record(layerResults, i, awaitCancelledStage(stages.get(i), tasks[i]));
                }
            }
            Thread.currentThread().interrupt();
        }
        // Stages still waiting for a slot never start once the layer is stopping.
        for (Integer i : waiting) {
            record(layerResults, i, cancelledBeforeStart(stages.get(i)));
        }
        return new LayerOutcome(List.of(layerResults), trigger, startedAt);
    }

    /**
     * Starts waiting stages until {@code running + started} reaches the layer's
     * {@link BatonConfig#maxParallelStages()} limit.
     *
     * @return how many stages were started
     */
    private int startStages(
            Deque<Integer> waiting,
            int running,
            List<StageNode> stages,
            ContextView context,
            TrackedTask<StageExecution>[] tasks,
            StageResult[] layerResults,
            Instant[] startedAt,
            LinkedBlockingQueue<Integer> finished
    ) {
        int limit = config.maxParallelStages();
        int started = 0;
        while (!waiting.isEmpty() && (limit == BatonConfig.UNLIMITED || running + started < limit)) {
            int index = waiting.poll();
            StageNode stage = stages.get(index);
            if (control.isCancelled()) {
                record(layerResults, index, cancelledBeforeStart(stage));
                continue;
            }
            try {
                if (eventsEnabled) {
                    startedAt[index] = emitStageStarted(stage.id());
                }
                TrackedTask<StageExecution> task = submitStage(stage, context, registered -> {
                    synchronized (state) {
                        tasks[index] = registered;
                    }
                });
                task.onDone(() -> finished.add(index));
                started++;
            } catch (RejectedExecutionException rejected) {
                if (!control.isCancelled()) {
                    throw rejected;
                }
                record(layerResults, index, cancelledBeforeStart(stage));
            }
        }
        return started;
    }

    private void record(StageResult[] layerResults, int index, StageResult result) {
        synchronized (state) {
            layerResults[index] = result;
        }
    }

    private void cancelUnfinished(TrackedTask<?>[] tasks, StageResult[] layerResults) {
        List<TrackedTask<?>> unfinished = new ArrayList<>();
        synchronized (state) {
            for (int i = 0; i < tasks.length; i++) {
                if (tasks[i] != null && layerResults[i] == null) {
                    unfinished.add(tasks[i]);
                }
            }
        }
        // Never cancel (which may cascade into child engines) under the state lock.
        unfinished.forEach(TrackedTask::cancel);
    }

    private TrackedTask<StageExecution> submitStage(
            StageNode node,
            ContextView context,
            Consumer<TrackedTask<StageExecution>> register
    ) {
        Deadline deadline = node.options().timeout()
                .map(Deadline::after)
                .map(inherited::min)
                .orElse(inherited);

        StageRun run = new StageRun(pathOf(node.id()));
        TrackedTask<StageExecution> task = TrackedTask.submit(
                executor,
                deadline,
                deadlines,
                // Captured here, on the coordinating thread; restored on the stage's thread.
                propagation.wrap(() -> runAttempts(node, context, deadline, run),
                        propagation.scope(ExecutionScope.Kind.STAGE, run.path)),
                run.cancel::run,
                registered -> {
                    run.self = registered;
                    stageRuns.put(registered, run);
                    register.accept(registered);
                }
        );
        control.track(task);
        return task;
    }

    /**
     * Runs the stage's attempts on its worker. The stage deadline covers every
     * attempt and backoff wait; a retry is skipped if its backoff cannot finish in
     * time. Cancellation or expiry interrupts this thread and cancels the current
     * attempt through {@link StageRun#cancel}.
     */
    private StageExecution runAttempts(
            StageNode node,
            ContextView context,
            Deadline stageDeadline,
            StageRun run
    ) throws Exception {
        long started = System.nanoTime();
        RetryPolicy retry = node.options().retry();
        for (int attempt = 1; ; attempt++) {
            control.throwIfCancelled();
            run.attempts.set(attempt);
            try {
                ContextPatch patch = runAttempt(node, context, stageDeadline, retry, attempt, run);
                return new StageExecution(patch, elapsed(started));
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException
                        || run.isDone()
                        || control.isCancelled()
                        || !retry.shouldRetry(attempt, failure)) {
                    throw failure;
                }
                Duration delay = retry.delayAfter(attempt);
                if (delay.toNanos() >= stageDeadline.remainingNanos()) {
                    throw failure;
                }
                emitRetrying(node.id(), attempt, failure, delay);
                if (!delay.isZero()) {
                    Thread.sleep(delay);
                }
            }
        }
    }

    /** One attempt: inline, or as its own task when a per-attempt timeout applies. */
    private ContextPatch runAttempt(
            StageNode node,
            ContextView context,
            Deadline stageDeadline,
            RetryPolicy retry,
            int attempt,
            StageRun run
    ) throws Exception {
        if (retry.attemptTimeout().isEmpty()) {
            return attemptBody(node, context, stageDeadline, run, run.cancel);
        }
        Duration limit = retry.attemptTimeout().get();
        Deadline attemptDeadline = stageDeadline.min(Deadline.after(limit));
        CancelSlot attemptCancel = new CancelSlot();
        TrackedTask<ContextPatch> attemptTask = TrackedTask.submit(
                executor,
                attemptDeadline,
                deadlines,
                propagation.wrap(() -> attemptBody(node, context, attemptDeadline, run, attemptCancel),
                        propagation.scope(ExecutionScope.Kind.ATTEMPT, run.path)),
                attemptCancel::run
        );
        run.cancel.set(attemptTask::cancel);
        if (run.isDone()) {
            attemptTask.cancel();
        }
        try {
            return attemptTask.join();
        } catch (TrackedTask.DeadlineExceededException expired) {
            if (stageDeadline.isExpired()) {
                throw expired;
            }
            throw new StageAttemptTimeoutException(
                    "Attempt " + attempt + " of stage '" + node.id() + "' exceeded " + limit);
        } catch (TrackedTask.TaskExecutionException wrapped) {
            if (wrapped.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw wrapped;
        }
    }

    /** Runs the stage body once, with a fresh child-task scope or child engine. */
    private ContextPatch attemptBody(
            StageNode node,
            ContextView context,
            Deadline deadline,
            StageRun run,
            CancelSlot cancel
    ) throws Exception {
        if (node.stage() instanceof NestedPipelineStage nested
                && nested.pipeline() instanceof DefaultPipeline child) {
            ExecutionControl childControl = new ExecutionControl();
            PipelineDefinition childDefinition = child.definition();
            // Pipeline-scoped settings come from the child's own config.
            BatonConfig childConfig = childDefinition.config();
            Deadline childDeadline = childConfig.runTimeout()
                    .map(Deadline::after)
                    .map(deadline::min)
                    .orElse(deadline);
            // The child's events go to every observer of this engine, with longer paths;
            // its own builder listeners are used only if notifyNestedListeners is on.
            List<Observer> childObservers = new ArrayList<>();
            for (Observer observer : observers) {
                childObservers.add(new Observer(observer.events(), observer.pathOf(node.id())));
            }
            RunEvents ownEvents = RunEvents.NONE;
            if (runConfig.notifyNestedListeners() && !childDefinition.listeners().isEmpty()) {
                ownEvents = RunEvents.create(childDefinition.name(), childDefinition.listeners(), runConfig);
                childObservers.add(new Observer(ownEvents, null));
            }
            ExecutionEngine childEngine = new ExecutionEngine(
                    childDefinition, executor, deadlines, childControl, childDeadline,
                    childConfig, runConfig, childObservers, pathOf(node.id()), this::recordChildConflict,
                    propagation);
            run.childEngine = childEngine;
            cancel.set(childControl::cancel);
            if (run.isDone()) {
                childControl.cancel();
                throw new CancellationException("Stage was cancelled");
            }
            return executeNestedBody(childEngine, context, ownEvents);
        }
        TaskWrapper inStageContext = new TaskWrapper() {
            @Override
            public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
                // Called by fork() on the stage's thread, so the stage's context is captured.
                return propagation.wrap(task, propagation.scope(ExecutionScope.Kind.CHILD_TASK, run.path));
            }
        };
        DefaultStageTasks childTasks = new DefaultStageTasks(executor, deadline, deadlines,
                config.maxChildTasks(), config.unjoinedChildFailure(), inStageContext);
        cancel.set(childTasks::close);
        if (run.isDone()) {
            childTasks.close();
            throw new CancellationException("Stage was cancelled");
        }
        return executeStageBody(node, context, childTasks);
    }

    private ContextPatch executeStageBody(
            StageNode node,
            ContextView context,
            DefaultStageTasks childTasks
    ) throws Exception {
        ContextPatch patch;
        try {
            control.throwIfCancelled();
            Stage stage = node.stage();
            patch = Objects.requireNonNull(
                    stage.execute(new DefaultStageContext(context, childTasks)),
                    "Stage returned a null ContextPatch: " + node.id()
            );
        } catch (Throwable failure) {
            // The stage's own failure wins; children are only cancelled.
            childTasks.close();
            throw failure;
        }
        // Normal return: an unobserved child failure fails the stage.
        childTasks.closeAndVerify();
        return patch;
    }

    /**
     * Runs a nested pipeline in this run's scope. A COMPLETED child succeeds and
     * hands back everything it wrote, even if some of its own stages were blocked
     * or failed under CONTINUE; those show up in the nested stage's children.
     */
    private ContextPatch executeNestedBody(ExecutionEngine childEngine, ContextView context, RunEvents ownEvents)
            throws Exception {
        control.throwIfCancelled();
        ownEvents.runStarted();
        DefaultPipelineResult childResult;
        try {
            childResult = childEngine.execute(context);
        } catch (RuntimeException | Error failure) {
            ownEvents.runFinished(PipelineStatus.FAILED, java.util.Optional.of(failure));
            throw failure;
        }
        ownEvents.runFinished(childResult);
        return switch (childResult.status()) {
            case COMPLETED -> childEngine.writtenPatch();
            case CANCELLED -> throw new CancellationException("Nested pipeline was cancelled");
            case FAILED, TIMED_OUT -> throw new PipelineExecutionException(childResult);
        };
    }

    /** Waits for a stage's logical outcome, then stops tracking it. */
    private StageResult awaitStage(StageNode node, TrackedTask<StageExecution> task) {
        try {
            return outcomeOf(node, task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            control.cancel();
            return new DefaultStageResult(
                    node.id(), StageStatus.CANCELLED, Duration.ofNanos(task.elapsedNanos()),
                    e, null, task.workerTermination(), childrenOf(task), attemptsOf(task)
            );
        } finally {
            control.untrack(task);
        }
    }

    /** Waits for an already-cancelled task's outcome, ignoring interrupts meanwhile. */
    private StageResult awaitCancelledStage(StageNode node, TrackedTask<StageExecution> task) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return outcomeOf(node, task);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            control.untrack(task);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Maps a finished task's outcome without side effects; never blocks. */
    private StageResult outcomeOfDone(StageNode node, TrackedTask<StageExecution> task) {
        try {
            return outcomeOf(node, task);
        } catch (InterruptedException impossible) {
            // join() on a completed outcome does not wait, so it cannot be interrupted.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(impossible);
        }
    }

    private StageResult outcomeOf(StageNode node, TrackedTask<StageExecution> task)
            throws InterruptedException {
        try {
            StageExecution execution = task.join();
            return new DefaultStageResult(
                    node.id(), StageStatus.SUCCEEDED, execution.duration(), null,
                    execution.patch(), task.workerTermination(), childrenOf(task), attemptsOf(task)
            );
        } catch (TrackedTask.DeadlineExceededException | StageAttemptTimeoutException e) {
            return unsuccessful(node, task, StageStatus.TIMED_OUT, e);
        } catch (CancellationException e) {
            return unsuccessful(node, task, StageStatus.CANCELLED, e);
        } catch (InterruptedException e) {
            throw e;
        } catch (TrackedTask.TaskExecutionException e) {
            StageStatus status = e.getCause() instanceof InterruptedException
                    ? StageStatus.CANCELLED : StageStatus.FAILED;
            return unsuccessful(node, task, status, e.getCause());
        } catch (Throwable e) {
            return unsuccessful(node, task, StageStatus.FAILED, e);
        }
    }

    private StageResult unsuccessful(
            StageNode node,
            TrackedTask<StageExecution> task,
            StageStatus status,
            Throwable failure
    ) {
        return new DefaultStageResult(
                node.id(), status, Duration.ofNanos(task.elapsedNanos()),
                failure, null, task.workerTermination(), childrenOf(task), attemptsOf(task)
        );
    }

    private List<StageResult> childrenOf(TrackedTask<?> task) {
        StageRun run = stageRuns.get(task);
        ExecutionEngine child = run == null ? null : run.childEngine;
        return child == null ? List.of() : child.snapshot();
    }

    private int attemptsOf(TrackedTask<?> task) {
        StageRun run = stageRuns.get(task);
        return run == null ? 0 : run.attempts.get();
    }

    /**
     * Replaces a FAILED or TIMED_OUT result with a RECOVERED one when the stage has
     * a fallback. Not used when the run is cancelled or the enclosing deadline has
     * expired: the run is ending anyway.
     */
    private StageResult withFallback(StageNode node, ContextView context, StageResult result) {
        boolean failed = result.status() == StageStatus.FAILED || result.status() == StageStatus.TIMED_OUT;
        StageFallback fallback = node.options().fallback().orElse(null);
        if (!failed || fallback == null || control.isCancelled() || inherited.isExpired()) {
            return result;
        }
        Throwable failure = result.failure().orElse(null);
        try (Restoration ignored = propagation.capture()
                .restore(propagation.scope(ExecutionScope.Kind.FALLBACK, pathOf(node.id())))) {
            ContextPatch patch = Objects.requireNonNull(fallback.recover(context, failure),
                    "Fallback returned a null ContextPatch: " + node.id());
            return new DefaultStageResult(result.stageId(), StageStatus.RECOVERED, result.duration(),
                    failure, patch, result.workerTermination(), result.children(), result.attempts());
        } catch (Throwable fallbackFailure) {
            if (failure != null && failure != fallbackFailure) {
                fallbackFailure.addSuppressed(failure);
            }
            return new DefaultStageResult(result.stageId(), StageStatus.FAILED, result.duration(),
                    fallbackFailure, null, result.workerTermination(), result.children(), result.attempts());
        }
    }

    private static TimeoutException deadlineExpired() {
        return new TimeoutException("Pipeline deadline expired");
    }

    /**
     * Decides, without starting the stage, whether it will run. Returns its result
     * if it will not: BLOCKED or SKIPPED for missing inputs, SKIPPED for a false
     * condition, or FAILED (possibly RECOVERED by the fallback) if the condition
     * threw. Returns null if the stage should be submitted.
     */
    private StageResult settleBeforeStart(StageNode node, ContextView context) {
        if (!hasRequiredInputs(node, context)) {
            return node.options().skipsIfInputsMissing() ? skipped(node) : blocked(node);
        }
        var condition = node.options().condition().orElse(null);
        if (condition == null) {
            return null;
        }
        try (Restoration ignored = propagation.capture()
                .restore(propagation.scope(ExecutionScope.Kind.CONDITION, pathOf(node.id())))) {
            return condition.test(context) ? null : skipped(node);
        } catch (Throwable failure) {
            StageResult failed = new DefaultStageResult(node.id(), StageStatus.FAILED, Duration.ZERO,
                    failure, null, WorkerTermination.NOT_STARTED, List.of(), 0);
            return withFallback(node, context, failed);
        }
    }

    private boolean hasRequiredInputs(StageNode node, ContextView context) {
        for (ContextKey<?> key : node.requiredReads()) {
            if (!context.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a layer's context writes according to the pipeline's conflict
     * settings and returns the patch to apply. Every conflict found is recorded as a
     * {@link KeyConflict}. In order:
     * <ol>
     *   <li>Same-layer conflicts ({@link LayerConflictPolicy}): two or more applied
     *       stages wrote a key name. Equal values merge silently when
     *       {@link BatonConfig#equalValuesConflict()} is false.</li>
     *   <li>Type clashes ({@link TypeClashPolicy}): a write uses a name the context
     *       holds with another type.</li>
     *   <li>Overwrites ({@link OverwritePolicy}, per stage or pipeline): a write to a
     *       key already in the context.</li>
     * </ol>
     * A stage with a FAIL resolution is FAILED with a {@link ContextMergeException}
     * (its output kept for diagnosis, nothing of it applied). Other stages keep their
     * status; their surviving writes are applied.
     */
    private MergeOutcome resolveLayer(List<StageNode> stages, List<StageResult> layerResults, ContextView base) {
        int size = layerResults.size();
        // Allocation-free on the common path: patches are only copied for stages that
        // lose a key, and problem lists only exist for stages that fail.
        ContextPatch[] patches = new ContextPatch[size];
        Map<String, List<Integer>> writersByName = null;
        int appliedCount = 0;
        for (int i = 0; i < size; i++) {
            StageResult result = layerResults.get(i);
            if (isApplied(result.status()) && result.output().isPresent() && !result.output().get().isEmpty()) {
                patches[i] = result.output().get();
                appliedCount++;
            }
        }
        if (appliedCount > 1) {
            writersByName = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                if (patches[i] != null) {
                    for (ContextKey<?> key : patches[i].writtenKeys()) {
                        writersByName.computeIfAbsent(key.name(), ignored -> new ArrayList<>(2)).add(i);
                    }
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<String>[] problems = new List[size];
        @SuppressWarnings("unchecked")
        Set<String>[] dropped = new Set[size];

        // 1. Same-layer conflicts.
        if (writersByName != null) {
            for (Map.Entry<String, List<Integer>> entry : writersByName.entrySet()) {
                List<Integer> writers = entry.getValue();
                if (writers.size() < 2) {
                    continue;
                }
                String name = entry.getKey();
                if (!config.equalValuesConflict() && allEqual(name, writers, patches)) {
                    writers.subList(1, writers.size()).forEach(i -> drop(dropped, i, name));
                    continue;
                }
                List<StagePath> paths = writers.stream()
                        .map(i -> pathOf(layerResults.get(i).stageId())).toList();
                List<String> ids = writers.stream().map(i -> layerResults.get(i).stageId()).toList();
                switch (config.layerConflictPolicy()) {
                    case FAIL -> {
                        writers.forEach(i -> problem(problems, i, name + " also written in the same layer by " + ids));
                        recordConflict(new KeyConflict(KeyConflict.Kind.SAME_LAYER, name, paths,
                                KeyConflict.Resolution.FAILED));
                    }
                    case FIRST_WINS -> {
                        writers.subList(1, writers.size()).forEach(i -> drop(dropped, i, name));
                        recordConflict(new KeyConflict(KeyConflict.Kind.SAME_LAYER, name, paths,
                                KeyConflict.Resolution.FIRST_WINS));
                    }
                    case LAST_WINS -> {
                        writers.subList(0, writers.size() - 1).forEach(i -> drop(dropped, i, name));
                        recordConflict(new KeyConflict(KeyConflict.Kind.SAME_LAYER, name, paths,
                                KeyConflict.Resolution.LAST_WINS));
                    }
                    case SKIP_KEY -> {
                        writers.forEach(i -> drop(dropped, i, name));
                        recordConflict(new KeyConflict(KeyConflict.Kind.SAME_LAYER, name, paths,
                                KeyConflict.Resolution.KEY_SKIPPED));
                    }
                }
            }
        }

        // 2 and 3. Type clashes and overwrites against the context before this layer.
        for (int i = 0; i < size; i++) {
            if (patches[i] == null) {
                continue;
            }
            OverwritePolicy overwrite = stages.get(i).options().overwritePolicy()
                    .orElse(config.overwritePolicy());
            for (ContextKey<?> key : patches[i].writtenKeys()) {
                if (dropped[i] != null && dropped[i].contains(key.name())) {
                    continue;
                }
                ContextKey<?> existing = base.keyNamed(key.name()).orElse(null);
                if (existing == null) {
                    continue;
                }
                StagePath path = pathOf(layerResults.get(i).stageId());
                if (!existing.type().equals(key.type())) {
                    if (config.typeClashPolicy() == TypeClashPolicy.FAIL) {
                        problem(problems, i, key.name() + " already holds " + existing.type().getName()
                                + ", not " + key.type().getName());
                        recordConflict(new KeyConflict(KeyConflict.Kind.TYPE_CLASH, key.name(), List.of(path),
                                KeyConflict.Resolution.FAILED));
                    } else {
                        recordConflict(new KeyConflict(KeyConflict.Kind.TYPE_CLASH, key.name(), List.of(path),
                                KeyConflict.Resolution.REPLACED));
                    }
                    continue;
                }
                switch (overwrite) {
                    case OVERRIDE -> { }
                    case KEEP_EXISTING -> {
                        drop(dropped, i, key.name());
                        recordConflict(new KeyConflict(KeyConflict.Kind.OVERWRITE, key.name(), List.of(path),
                                KeyConflict.Resolution.KEPT_EXISTING));
                    }
                    case FAIL -> {
                        problem(problems, i, key.name() + " is already in the context");
                        recordConflict(new KeyConflict(KeyConflict.Kind.OVERWRITE, key.name(), List.of(path),
                                KeyConflict.Resolution.FAILED));
                    }
                }
            }
        }

        List<StageResult> adjusted = new ArrayList<>(size);
        List<ContextPatch> applied = new ArrayList<>(appliedCount);
        ContextMergeException firstConflict = null;
        for (int i = 0; i < size; i++) {
            StageResult result = layerResults.get(i);
            if (problems[i] != null) {
                ContextMergeException conflict = new ContextMergeException(
                        "Stage '" + result.stageId() + "' has conflicting context writes: "
                                + String.join("; ", problems[i]));
                if (firstConflict == null) {
                    firstConflict = conflict;
                }
                adjusted.add(new DefaultStageResult(
                        result.stageId(), StageStatus.FAILED, result.duration(), conflict,
                        result.output().orElse(null), result.workerTermination(), result.children(),
                        result.attempts()
                ));
            } else {
                adjusted.add(result);
                if (patches[i] != null) {
                    applied.add(dropped[i] == null ? patches[i] : without(patches[i], dropped[i]));
                }
            }
        }
        ContextPatch merged = applied.isEmpty() ? ContextPatch.empty()
                : applied.size() == 1 ? applied.getFirst() : ContextPatch.merge(applied);
        return new MergeOutcome(adjusted, merged, firstConflict);
    }

    @SuppressWarnings("unchecked")
    private static boolean allEqual(String name, List<Integer> writers, ContextPatch[] patches) {
        ContextKey<?> firstKey = null;
        Object firstValue = null;
        for (int i : writers) {
            for (ContextKey<?> key : patches[i].writtenKeys()) {
                if (!key.name().equals(name)) {
                    continue;
                }
                Object value = patches[i].find((ContextKey<Object>) key).orElse(null);
                if (firstKey == null) {
                    firstKey = key;
                    firstValue = value;
                } else if (!firstKey.equals(key) || !Objects.equals(firstValue, value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void drop(Set<String>[] dropped, int stage, String name) {
        if (dropped[stage] == null) {
            dropped[stage] = new java.util.HashSet<>();
        }
        dropped[stage].add(name);
    }

    private static void problem(List<String>[] problems, int stage, String problem) {
        if (problems[stage] == null) {
            problems[stage] = new ArrayList<>();
        }
        problems[stage].add(problem);
    }

    /** The patch without the writes of the dropped key names. */
    @SuppressWarnings("unchecked")
    private static ContextPatch without(ContextPatch patch, Set<String> droppedNames) {
        ContextPatch.Builder builder = ContextPatch.builder();
        for (ContextKey<?> key : patch.writtenKeys()) {
            if (!droppedNames.contains(key.name())) {
                ContextKey<Object> untyped = (ContextKey<Object>) key;
                builder.put(untyped, patch.find(untyped).orElseThrow());
            }
        }
        return builder.build();
    }

    /** Records a conflict found here, reports it to the parent engine, and emits it. */
    private void recordConflict(KeyConflict conflict) {
        recordChildConflict(conflict);
        if (eventsEnabled) {
            for (Observer observer : observers) {
                observer.events().conflict(conflict);
            }
        }
    }

    /** Records a conflict found here or in a nested pipeline (already emitted there). */
    private void recordChildConflict(KeyConflict conflict) {
        synchronized (state) {
            conflicts.add(conflict);
        }
        if (parentConflicts != null) {
            parentConflicts.accept(conflict);
        }
    }

    private Instant emitStageStarted(String stageId) {
        Instant first = null;
        for (Observer observer : observers) {
            Instant at = observer.events().stageStarted(observer.pathOf(stageId));
            if (first == null) {
                first = at;
            }
        }
        return first;
    }

    private void emitStageFinished(String stageId, StageResult result, Instant startedAt) {
        for (Observer observer : observers) {
            observer.events().stageFinished(observer.pathOf(stageId), result, startedAt);
        }
    }

    private void emitRetrying(String stageId, int attempt, Throwable failure, Duration delay) {
        if (!eventsEnabled) {
            return;
        }
        for (Observer observer : observers) {
            observer.events().stageRetrying(observer.pathOf(stageId), attempt, failure, delay);
        }
    }

    private StagePath pathOf(String stageId) {
        return parentPath == null ? StagePath.of(stageId) : parentPath.child(stageId);
    }

    /** SUCCEEDED and RECOVERED stages have their patch applied. */
    private static boolean isApplied(StageStatus status) {
        return status == StageStatus.SUCCEEDED || status == StageStatus.RECOVERED;
    }

    private static boolean stopsPipeline(StageNode node, StageResult result) {
        boolean unsuccessful = result.status() == StageStatus.FAILED
                || result.status() == StageStatus.TIMED_OUT;
        return unsuccessful && node.options().failurePolicy() == FailurePolicy.FAIL_FAST;
    }

    @SuppressWarnings("unchecked")
    private static void putUntyped(ContextPatch.Builder builder, ContextKey<?> key, ContextView view) {
        ContextKey<Object> untyped = (ContextKey<Object>) key;
        builder.put(untyped, view.get(untyped));
    }

    private StageResult skipped(StageNode node) {
        return new DefaultStageResult(node.id(), StageStatus.SKIPPED, Duration.ZERO,
                null, null, WorkerTermination.NOT_STARTED, List.of(), 0);
    }

    private StageResult blocked(StageNode node) {
        return new DefaultStageResult(node.id(), StageStatus.BLOCKED, Duration.ZERO,
                null, null, WorkerTermination.NOT_STARTED, List.of());
    }

    private StageResult cancelledBeforeStart(StageNode node) {
        return new DefaultStageResult(node.id(), StageStatus.CANCELLED, Duration.ZERO,
                new CancellationException("Pipeline execution was cancelled"),
                null, WorkerTermination.NOT_STARTED, List.of());
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private record StageExecution(ContextPatch patch, Duration duration) { }

    /** An event destination, and the path prefix its stage paths get (null for none). */
    private record Observer(RunEvents events, StagePath prefix) {
        StagePath pathOf(String stageId) {
            return prefix == null ? StagePath.of(stageId) : prefix.child(stageId);
        }
    }

    /** A replaceable cancellation action: whatever the current attempt must cancel. */
    private static final class CancelSlot {
        private volatile Runnable action = () -> { };

        void set(Runnable action) {
            this.action = action;
        }

        void run() {
            action.run();
        }
    }

    /** State shared by a stage's coordinator and its worker across attempts. */
    private static final class StageRun {
        final StagePath path;
        final AtomicInteger attempts = new AtomicInteger();
        final CancelSlot cancel = new CancelSlot();
        volatile ExecutionEngine childEngine;
        volatile TrackedTask<?> self;

        StageRun(StagePath path) {
            this.path = path;
        }

        boolean isDone() {
            TrackedTask<?> task = self;
            return task != null && task.isDone();
        }
    }

    /** A layer's results in definition order, and the FAIL_FAST result that stops the run, if any. */
    private record LayerOutcome(List<StageResult> results, StageResult trigger, Instant[] startedAt) { }

    /** The layer in flight; its arrays are only read or written under {@link #state}. */
    private record ActiveLayer(
            List<StageNode> stages,
            TrackedTask<StageExecution>[] tasks,
            StageResult[] results
    ) { }

    private record MergeOutcome(
            List<StageResult> results,
            ContextPatch patch,
            ContextMergeException conflict
    ) { }
}
