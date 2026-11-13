package dev.baton.core.execution;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.PipelineStatus;
import dev.baton.api.Stage;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;
import dev.baton.core.deadline.Deadline;
import dev.baton.core.deadline.DeadlineScheduler;
import dev.baton.core.definition.LayerDefinition;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.definition.StageNode;
import dev.baton.core.result.DefaultPipelineResult;
import dev.baton.core.result.DefaultStageResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * Runs one pipeline execution. Progress (the current context and the stage
 * results so far) is kept in fields so a caller can report partial results
 * if execution ends unexpectedly.
 */
final class ExecutionEngine {

    private final PipelineDefinition definition;
    private final ExecutorService executor;
    private final DeadlineScheduler deadlines;
    private final ExecutionControl control;

    private ContextView context;
    private final List<StageResult> results = new ArrayList<>();

    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            DeadlineScheduler deadlines,
            ExecutionControl control
    ) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.control = Objects.requireNonNull(control, "control");
    }

    DefaultPipelineResult execute(ContextView input) {
        context = Objects.requireNonNull(input, "input");

        try {
            for (LayerDefinition layer : definition.layers()) {
                control.throwIfCancelled();

                LayerOutcome layerOutcome = executeLayer(layer, context);
                MergeOutcome merge = mergeSuccessfulOutputs(layerOutcome.results());
                results.addAll(merge.results());
                context = merge.patch().applyTo(context);

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
        ContextView current = context;
        if (current == null) {
            throw new IllegalStateException("Execution has not started");
        }
        return new DefaultPipelineResult(status, current, results, failure);
    }

    private LayerOutcome executeLayer(LayerDefinition layer, ContextView context) {
        if (layer.type() == LayerDefinition.Type.SEQUENTIAL) {
            StageNode stage = layer.stages().getFirst();
            StageResult result = hasRequiredInputs(stage, context)
                    ? executeStage(stage, context)
                    : blocked(stage);
            return new LayerOutcome(List.of(result), stopsPipeline(stage, result) ? result : null);
        }
        return executeParallel(layer.stages(), context);
    }

    private LayerOutcome executeParallel(List<StageNode> stages, ContextView context) {
        int size = stages.size();
        @SuppressWarnings("unchecked")
        TrackedTask<StageExecution>[] tasks = new TrackedTask[size];
        StageResult[] layerResults = new StageResult[size];
        LinkedBlockingQueue<Integer> finished = new LinkedBlockingQueue<>();
        int pending = 0;

        // Submit all runnable siblings before observing any result.
        for (int i = 0; i < size; i++) {
            StageNode stage = stages.get(i);
            if (control.isCancelled()) {
                layerResults[i] = cancelledBeforeStart(stage);
            } else if (!hasRequiredInputs(stage, context)) {
                layerResults[i] = blocked(stage);
            } else {
                try {
                    TrackedTask<StageExecution> task = submitStage(stage, context);
                    tasks[i] = task;
                    int index = i;
                    task.onDone(() -> finished.add(index));
                    pending++;
                } catch (RejectedExecutionException rejected) {
                    if (!control.isCancelled()) {
                        throw rejected;
                    }
                    layerResults[i] = cancelledBeforeStart(stage);
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
                layerResults[i] = result;
                if (trigger == null && stopsPipeline(stages.get(i), result)) {
                    trigger = result;
                    cancelUnfinished(tasks, layerResults);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            control.cancel();
            for (int i = 0; i < size; i++) {
                if (layerResults[i] == null) {
                    layerResults[i] = awaitStage(stages.get(i), tasks[i]);
                }
            }
        }
        return new LayerOutcome(List.of(layerResults), trigger);
    }

    private static void cancelUnfinished(TrackedTask<?>[] tasks, StageResult[] layerResults) {
        for (int i = 0; i < tasks.length; i++) {
            if (tasks[i] != null && layerResults[i] == null) {
                tasks[i].cancel();
            }
        }
    }

    private StageResult executeStage(StageNode node, ContextView context) {
        if (control.isCancelled()) {
            return cancelledBeforeStart(node);
        }
        try {
            TrackedTask<StageExecution> task = submitStage(node, context);
            return awaitStage(node, task);
        } catch (RejectedExecutionException rejected) {
            if (control.isCancelled()) {
                return cancelledBeforeStart(node);
            }
            throw rejected;
        }
    }

    private TrackedTask<StageExecution> submitStage(StageNode node, ContextView context) {
        Deadline deadline = node.options().timeout()
                .map(Deadline::after)
                .orElseGet(Deadline::unlimited);

        DefaultStageTasks childTasks = new DefaultStageTasks(executor, deadline, deadlines);
        TrackedTask<StageExecution> task = TrackedTask.submit(
                executor,
                deadline,
                deadlines,
                () -> executeStageBody(node, context, childTasks),
                childTasks::close
        );
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

    private StageResult awaitStage(StageNode node, TrackedTask<StageExecution> task) {
        try {
            StageExecution execution = task.join();
            return new DefaultStageResult(
                    node.id(), StageStatus.SUCCEEDED, execution.duration(), null,
                    execution.patch(), task.workerTermination(), List.of()
            );
        } catch (TrackedTask.DeadlineExceededException e) {
            return new DefaultStageResult(
                    node.id(), StageStatus.TIMED_OUT, Duration.ofNanos(task.elapsedNanos()),
                    e, null, task.workerTermination(), List.of()
            );
        } catch (CancellationException e) {
            return new DefaultStageResult(
                    node.id(), StageStatus.CANCELLED, Duration.ofNanos(task.elapsedNanos()),
                    e, null, task.workerTermination(), List.of()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            control.cancel();
            return new DefaultStageResult(
                    node.id(), StageStatus.CANCELLED, Duration.ofNanos(task.elapsedNanos()),
                    e, null, task.workerTermination(), List.of()
            );
        } catch (TrackedTask.TaskExecutionException e) {
            StageStatus status = e.getCause() instanceof InterruptedException
                    ? StageStatus.CANCELLED : StageStatus.FAILED;
            return new DefaultStageResult(
                    node.id(), status, Duration.ofNanos(task.elapsedNanos()),
                    e.getCause(), null, task.workerTermination(), List.of()
            );
        } catch (Throwable e) {
            return new DefaultStageResult(
                    node.id(), StageStatus.FAILED, Duration.ofNanos(task.elapsedNanos()),
                    e, null, task.workerTermination(), List.of()
            );
        } finally {
            control.untrack(task);
        }
    }

    private boolean hasRequiredInputs(StageNode node, ContextView context) {
        return context.keys().containsAll(node.stage().requiredReads());
    }

    /**
     * Merges the outputs of a layer's successful stages. Stages that wrote a key
     * another stage in the same layer also wrote are marked FAILED with a
     * {@link ContextMergeException}; their output is kept for diagnosis but not
     * applied. Outputs of non-conflicting stages are applied.
     */
    private MergeOutcome mergeSuccessfulOutputs(List<StageResult> layerResults) {
        Map<ContextKey<?>, List<String>> writers = new LinkedHashMap<>();
        for (StageResult result : layerResults) {
            if (result.status() != StageStatus.SUCCEEDED) {
                continue;
            }
            result.output().ifPresent(patch -> patch.writtenKeys().forEach(key ->
                    writers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result.stageId())));
        }

        Map<String, Set<ContextKey<?>>> conflictsByStage = new LinkedHashMap<>();
        writers.forEach((key, stageIds) -> {
            if (stageIds.size() > 1) {
                stageIds.forEach(id ->
                        conflictsByStage.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(key));
            }
        });

        List<StageResult> adjusted = new ArrayList<>(layerResults.size());
        List<ContextPatch> applied = new ArrayList<>();
        ContextMergeException firstConflict = null;
        for (StageResult result : layerResults) {
            Set<ContextKey<?>> conflictingKeys = conflictsByStage.get(result.stageId());
            if (conflictingKeys != null) {
                ContextMergeException conflict = new ContextMergeException(
                        conflictMessage(result.stageId(), conflictingKeys, writers));
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

    private static String conflictMessage(
            String stageId,
            Set<ContextKey<?>> keys,
            Map<ContextKey<?>, List<String>> writers
    ) {
        return "Stage '" + stageId + "' wrote context keys also written in the same layer: "
                + keys.stream()
                .map(key -> key.name() + " (written by " + writers.get(key) + ")")
                .collect(Collectors.joining(", "));
    }

    private static boolean stopsPipeline(StageNode node, StageResult result) {
        boolean unsuccessful = result.status() == StageStatus.FAILED
                || result.status() == StageStatus.TIMED_OUT;
        return unsuccessful && node.options().failurePolicy() == FailurePolicy.FAIL_FAST;
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
    private record LayerOutcome(List<StageResult> results, StageResult trigger) { }

    private record MergeOutcome(
            List<StageResult> results,
            ContextPatch patch,
            ContextMergeException conflict
    ) { }
}
