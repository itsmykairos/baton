package dev.baton.core.execution;

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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;

final class ExecutionEngine {

    private final PipelineDefinition definition;
    private final ExecutorService executor;
    private final DeadlineScheduler deadlines;
    private final ExecutionControl control;

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
        ContextView context = Objects.requireNonNull(input, "input");
        List<StageResult> results = new ArrayList<>();

        try {
            for (LayerDefinition layer : definition.layers()) {
                control.throwIfCancelled();

                List<StageResult> layerResults = executeLayer(layer, context);
                results.addAll(layerResults);

                ContextPatch merged = mergeSuccessfulOutputs(layerResults);
                context = merged.applyTo(context);

                if (control.isCancelled()) {
                    return new DefaultPipelineResult(
                            PipelineStatus.CANCELLED, context, results,
                            new CancellationException("Pipeline execution was cancelled")
                    );
                }

                if (shouldStop(layer, layerResults)) {
                    boolean timedOut = layerResults.stream()
                            .anyMatch(result -> result.status() == StageStatus.TIMED_OUT
                                    && failurePolicyFor(layer, result.stageId()) == FailurePolicy.FAIL_FAST);
                    return new DefaultPipelineResult(
                            timedOut ? PipelineStatus.TIMED_OUT : PipelineStatus.FAILED,
                            context,
                            results,
                            firstFailure(layerResults)
                    );
                }
            }

            return new DefaultPipelineResult(PipelineStatus.COMPLETED, context, results, null);
        } catch (CancellationException e) {
            return new DefaultPipelineResult(PipelineStatus.CANCELLED, context, results, e);
        } catch (ContextMergeException e) {
            return new DefaultPipelineResult(PipelineStatus.FAILED, context, results, e);
        }
    }

    private List<StageResult> executeLayer(LayerDefinition layer, ContextView context) {
        if (layer.type() == LayerDefinition.Type.SEQUENTIAL) {
            StageNode stage = layer.stages().getFirst();
            if (!hasRequiredInputs(stage, context)) {
                return List.of(blocked(stage));
            }
            return List.of(executeStage(stage, context));
        }
        return executeParallel(layer.stages(), context);
    }

    private List<StageResult> executeParallel(List<StageNode> stages, ContextView context) {
        List<TrackedTask<StageExecution>> tasks = new ArrayList<>(stages.size());
        List<StageResult> results = new ArrayList<>(stages.size());

        // Submit all runnable siblings before observing any result.
        for (StageNode stage : stages) {
            if (control.isCancelled()) {
                tasks.add(null);
                results.add(cancelledBeforeStart(stage));
            } else if (!hasRequiredInputs(stage, context)) {
                tasks.add(null);
                results.add(blocked(stage));
            } else {
                try {
                    TrackedTask<StageExecution> task = submitStage(stage, context);
                    tasks.add(task);
                    results.add(null);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    if (!control.isCancelled()) {
                        throw rejected;
                    }
                    tasks.add(null);
                    results.add(cancelledBeforeStart(stage));
                }
            }
        }

        // Join in definition order. A timeout/cancel completes logically even
        // when the worker is still physically running.
        for (int i = 0; i < stages.size(); i++) {
            TrackedTask<StageExecution> task = tasks.get(i);
            if (task != null) {
                results.set(i, awaitStage(stages.get(i), task));
            }
        }
        return List.copyOf(results);
    }

    private StageResult executeStage(StageNode node, ContextView context) {
        if (control.isCancelled()) {
            return cancelledBeforeStart(node);
        }
        try {
            TrackedTask<StageExecution> task = submitStage(node, context);
            return awaitStage(node, task);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
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
        try (childTasks) {
            control.throwIfCancelled();
            Stage stage = node.stage();
            ContextPatch patch = Objects.requireNonNull(
                    stage.execute(new DefaultStageContext(context, childTasks)),
                    "Stage returned a null ContextPatch: " + node.id()
            );
            return new StageExecution(patch, elapsed(started));
        }
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

    private ContextPatch mergeSuccessfulOutputs(List<StageResult> results) {
        List<ContextPatch> patches = results.stream()
                .filter(result -> result.status() == StageStatus.SUCCEEDED)
                .flatMap(result -> result.output().stream())
                .toList();
        return ContextPatch.merge(patches);
    }

    private boolean shouldStop(LayerDefinition layer, List<StageResult> results) {
        for (int i = 0; i < results.size(); i++) {
            StageStatus status = results.get(i).status();
            boolean unsuccessful = status == StageStatus.FAILED
                    || status == StageStatus.TIMED_OUT;
            if (unsuccessful && layer.stages().get(i).options().failurePolicy() == FailurePolicy.FAIL_FAST) {
                return true;
            }
        }
        return false;
    }

    private FailurePolicy failurePolicyFor(LayerDefinition layer, String stageId) {
        return layer.stages().stream()
                .filter(stage -> stage.id().equals(stageId))
                .findFirst()
                .map(stage -> stage.options().failurePolicy())
                .orElse(FailurePolicy.FAIL_FAST);
    }

    private Throwable firstFailure(List<StageResult> results) {
        return results.stream().flatMap(result -> result.failure().stream()).findFirst().orElse(null);
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
}
