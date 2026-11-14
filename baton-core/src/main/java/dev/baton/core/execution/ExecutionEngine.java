package dev.baton.core.execution;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.NestedPipelineStage;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageAttemptTimeoutException;
import dev.baton.api.StageFallback;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;
import dev.baton.core.DefaultPipeline;
import dev.baton.api.event.StagePath;
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
import java.util.ArrayList;
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
    private final RunEvents events;
    /** Path of the nested stage running this engine; null for the outermost run. */
    private final StagePath parentPath;

    private final Object state = new Object();
    private ContextView context;
    private final List<StageResult> results = new ArrayList<>();
    private final Set<ContextKey<?>> writtenKeys = new LinkedHashSet<>();
    private ActiveLayer activeLayer;

    /** Per-stage attempt state, so results can report attempts and nested children. */
    private final Map<TrackedTask<?>, StageRun> stageRuns = new ConcurrentHashMap<>();

    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control
    ) {
        this(definition, executor, deadlines, control, RunEvents.NONE);
    }

    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control,
            RunEvents events
    ) {
        this(definition, executor, deadlines, control, Deadline.unlimited(), events, null);
    }

    /** The outermost engine of a run limited by {@code runDeadline}. */
    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control,
            RunEvents events,
            Deadline runDeadline
    ) {
        this(definition, executor, deadlines, control, runDeadline, events, null);
    }

    private ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control,
            Deadline inherited,
            RunEvents events,
            StagePath parentPath
    ) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.control = Objects.requireNonNull(control, "control");
        this.inherited = Objects.requireNonNull(inherited, "inherited");
        this.events = Objects.requireNonNull(events, "events");
        this.parentPath = parentPath;
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
                MergeOutcome merge = mergeSuccessfulOutputs(layerOutcome.results(), currentContext());
                synchronized (state) {
                    results.addAll(merge.results());
                    context = merge.patch().applyTo(context);
                    writtenKeys.addAll(merge.patch().writtenKeys());
                    activeLayer = null;
                }
                // Emitted after the merge so a conflict's FAILED status is what listeners see.
                if (events.enabled()) {
                    List<StageResult> finalResults = merge.results();
                    for (int i = 0; i < finalResults.size(); i++) {
                        StageResult result = finalResults.get(i);
                        events.stageFinished(pathOf(result.stageId()), result, layerOutcome.startedAt()[i]);
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
            return new DefaultPipelineResult(status, context, snapshot(), failure);
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
                putUntyped(builder, key, context);
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
        int pending = 0;

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

        // Submit all runnable stages before observing any result.
        for (int i = 0; i < size; i++) {
            StageNode stage = stages.get(i);
            if (layerResults[i] != null) {
                continue;
            }
            if (control.isCancelled()) {
                record(layerResults, i, cancelledBeforeStart(stage));
            } else {
                try {
                    int index = i;
                    if (events.enabled()) {
                        startedAt[i] = events.stageStarted(pathOf(stage.id()));
                    }
                    TrackedTask<StageExecution> task = submitStage(stage, context, registered -> {
                        synchronized (state) {
                            tasks[index] = registered;
                        }
                    });
                    task.onDone(() -> finished.add(index));
                    pending++;
                } catch (RejectedExecutionException rejected) {
                    if (!control.isCancelled()) {
                        throw rejected;
                    }
                    record(layerResults, i, cancelledBeforeStart(stage));
                }
            }
        }

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
            }
        } catch (InterruptedException interrupted) {
            // Every registered task is logically complete once cancel() returns, so
            // waiting for their outcomes is immediate; do it without the interrupt flag
            // so real outcomes (such as TIMED_OUT) are not replaced by CANCELLED.
            control.cancel();
            for (int i = 0; i < size; i++) {
                if (layerResults[i] == null) {
                    record(layerResults, i, awaitCancelledStage(stages.get(i), tasks[i]));
                }
            }
            Thread.currentThread().interrupt();
        }
        return new LayerOutcome(List.of(layerResults), trigger, startedAt);
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

        StageRun run = new StageRun();
        TrackedTask<StageExecution> task = TrackedTask.submit(
                executor,
                deadline,
                deadlines,
                () -> runAttempts(node, context, deadline, run),
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
                events.stageRetrying(pathOf(node.id()), attempt, failure, delay);
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
                () -> attemptBody(node, context, attemptDeadline, run, attemptCancel),
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
            Deadline childDeadline = child.definition().timeout()
                    .map(Deadline::after)
                    .map(deadline::min)
                    .orElse(deadline);
            // The child's own listeners are not used: its events go to the outermost run.
            ExecutionEngine childEngine = new ExecutionEngine(
                    child.definition(), executor, deadlines, childControl, childDeadline,
                    events, pathOf(node.id()));
            run.childEngine = childEngine;
            cancel.set(childControl::cancel);
            if (run.isDone()) {
                childControl.cancel();
                throw new CancellationException("Stage was cancelled");
            }
            return executeNestedBody(childEngine, context);
        }
        DefaultStageTasks childTasks = new DefaultStageTasks(executor, deadline, deadlines);
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
    private ContextPatch executeNestedBody(ExecutionEngine childEngine, ContextView context)
            throws Exception {
        control.throwIfCancelled();
        DefaultPipelineResult childResult = childEngine.execute(context);
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
        try {
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
        try {
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
     * Merges the outputs of a layer's successful stages. A stage fails with a
     * {@link ContextMergeException}, and its output is kept for diagnosis but not
     * applied, when it wrote a key name that another stage in the same layer also
     * wrote, or a key name the context already holds with a different type.
     * Outputs of the other successful stages are applied.
     */
    private MergeOutcome mergeSuccessfulOutputs(List<StageResult> layerResults, ContextView base) {
        Map<String, List<String>> writersByName = new LinkedHashMap<>();
        for (StageResult result : layerResults) {
            if (!isApplied(result.status())) {
                continue;
            }
            result.output().ifPresent(patch -> patch.writtenKeys().forEach(key ->
                    writersByName.computeIfAbsent(key.name(), ignored -> new ArrayList<>())
                            .add(result.stageId())));
        }

        List<StageResult> adjusted = new ArrayList<>(layerResults.size());
        List<ContextPatch> applied = new ArrayList<>();
        ContextMergeException firstConflict = null;
        for (StageResult result : layerResults) {
            List<String> problems = isApplied(result.status())
                    ? result.output().map(patch -> conflictsOf(patch, writersByName, base))
                            .orElse(List.of())
                    : List.of();
            if (!problems.isEmpty()) {
                ContextMergeException conflict = new ContextMergeException(
                        "Stage '" + result.stageId() + "' has conflicting context writes: "
                                + String.join("; ", problems));
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
                if (isApplied(result.status())) {
                    result.output().ifPresent(applied::add);
                }
            }
        }
        return new MergeOutcome(adjusted, ContextPatch.merge(applied), firstConflict);
    }

    private static List<String> conflictsOf(
            ContextPatch patch,
            Map<String, List<String>> writersByName,
            ContextView base
    ) {
        List<String> problems = new ArrayList<>();
        for (ContextKey<?> key : patch.writtenKeys()) {
            List<String> writers = writersByName.get(key.name());
            if (writers.size() > 1) {
                problems.add(key.name() + " also written in the same layer by " + writers);
            }
            ContextKey<?> existing = base.keyNamed(key.name()).orElse(null);
            if (existing != null && !existing.type().equals(key.type())) {
                problems.add(key.name() + " already holds " + existing.type().getName()
                        + ", not " + key.type().getName());
            }
        }
        return problems;
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
        final AtomicInteger attempts = new AtomicInteger();
        final CancelSlot cancel = new CancelSlot();
        volatile ExecutionEngine childEngine;
        volatile TrackedTask<?> self;

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
