package dev.baton.core.execution;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.NestedPipelineStage;
import dev.baton.api.PipelineExecutionException;
import dev.baton.api.PipelineStatus;
import dev.baton.api.Stage;
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

    /** Child engines of nested stages, so their results can be reported as children. */
    private final Map<TrackedTask<?>, ExecutionEngine> childEngines = new ConcurrentHashMap<>();

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

        // Submit all runnable stages before observing any result.
        for (int i = 0; i < size; i++) {
            StageNode stage = stages.get(i);
            if (control.isCancelled()) {
                record(layerResults, i, cancelledBeforeStart(stage));
            } else if (!hasRequiredInputs(stage, context)) {
                record(layerResults, i, blocked(stage));
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
        StageResult trigger = null;
        try {
            while (pending > 0) {
                int i = finished.take();
                pending--;
                StageResult result = awaitStage(stages.get(i), tasks[i]);
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

        TrackedTask<StageExecution> task;
        if (node.stage() instanceof NestedPipelineStage nested
                && nested.pipeline() instanceof DefaultPipeline child) {
            ExecutionControl childControl = new ExecutionControl();
            // The child's own listeners are not used: its events go to the outermost run.
            ExecutionEngine childEngine = new ExecutionEngine(
                    child.definition(), executor, deadlines, childControl, deadline,
                    events, pathOf(node.id()));
            task = TrackedTask.submit(
                    executor,
                    deadline,
                    deadlines,
                    () -> executeNestedBody(childEngine, context),
                    childControl::cancel,
                    registered -> {
                        childEngines.put(registered, childEngine);
                        register.accept(registered);
                    }
            );
        } else {
            DefaultStageTasks childTasks = new DefaultStageTasks(executor, deadline, deadlines);
            task = TrackedTask.submit(
                    executor,
                    deadline,
                    deadlines,
                    () -> executeStageBody(node, context, childTasks),
                    childTasks::close,
                    register
            );
        }
        control.track(task);
        return task;
    }

    private StageExecution executeStageBody(
            StageNode node,
            ContextView context,
            DefaultStageTasks childTasks
    ) throws Exception {
        long started = System.nanoTime();
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
        return new StageExecution(patch, elapsed(started));
    }

    /**
     * Runs a nested pipeline in this run's scope. A COMPLETED child succeeds and
     * hands back everything it wrote, even if some of its own stages were blocked
     * or failed under CONTINUE; those show up in the nested stage's children.
     */
    private StageExecution executeNestedBody(ExecutionEngine childEngine, ContextView context)
            throws Exception {
        long started = System.nanoTime();
        control.throwIfCancelled();
        DefaultPipelineResult childResult = childEngine.execute(context);
        return switch (childResult.status()) {
            case COMPLETED -> new StageExecution(childEngine.writtenPatch(), elapsed(started));
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
                    e, null, task.workerTermination(), childrenOf(task)
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
                    execution.patch(), task.workerTermination(), childrenOf(task)
            );
        } catch (TrackedTask.DeadlineExceededException e) {
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
                failure, null, task.workerTermination(), childrenOf(task)
        );
    }

    private List<StageResult> childrenOf(TrackedTask<?> task) {
        ExecutionEngine child = childEngines.get(task);
        return child == null ? List.of() : child.snapshot();
    }

    private boolean hasRequiredInputs(StageNode node, ContextView context) {
        return context.keys().containsAll(node.requiredReads());
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
            if (result.status() != StageStatus.SUCCEEDED) {
                continue;
            }
            result.output().ifPresent(patch -> patch.writtenKeys().forEach(key ->
                    writersByName.computeIfAbsent(key.name(), ignored -> new ArrayList<>())
                            .add(result.stageId())));
        }
        Map<String, ContextKey<?>> baseByName = new LinkedHashMap<>();
        base.keys().forEach(key -> baseByName.put(key.name(), key));

        List<StageResult> adjusted = new ArrayList<>(layerResults.size());
        List<ContextPatch> applied = new ArrayList<>();
        ContextMergeException firstConflict = null;
        for (StageResult result : layerResults) {
            List<String> problems = result.status() == StageStatus.SUCCEEDED
                    ? result.output().map(patch -> conflictsOf(patch, writersByName, baseByName))
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
                        result.output().orElse(null), result.workerTermination(), result.children()
                ));
            } else {
                adjusted.add(result);
                if (result.status() == StageStatus.SUCCEEDED) {
                    result.output().ifPresent(applied::add);
                }
            }
        }
        return new MergeOutcome(adjusted, ContextPatch.merge(applied), firstConflict);
    }

    private static List<String> conflictsOf(
            ContextPatch patch,
            Map<String, List<String>> writersByName,
            Map<String, ContextKey<?>> baseByName
    ) {
        List<String> problems = new ArrayList<>();
        for (ContextKey<?> key : patch.writtenKeys()) {
            List<String> writers = writersByName.get(key.name());
            if (writers.size() > 1) {
                problems.add(key.name() + " also written in the same layer by " + writers);
            }
            ContextKey<?> existing = baseByName.get(key.name());
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
