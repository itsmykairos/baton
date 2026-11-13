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
import dev.baton.core.definition.LayerDefinition;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.definition.StageNode;
import dev.baton.core.result.DefaultPipelineResult;
import dev.baton.core.result.DefaultStageResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class ExecutionEngine {

    private final PipelineDefinition definition;
    private final ExecutorService executor;
    private final ExecutionControl control;

    ExecutionEngine(
            PipelineDefinition definition,
            ExecutorService executor,
            ExecutionControl control
    ) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.control = Objects.requireNonNull(control, "control");
    }

    DefaultPipelineResult execute(ContextView input)
            throws InterruptedException {

        ContextView context = Objects.requireNonNull(input, "input");
        List<StageResult> results = new ArrayList<>();

        try {
            for (LayerDefinition layer : definition.layers()) {
                control.throwIfCancelled();

                List<StageResult> layerResults =
                        executeLayer(layer, context);

                results.addAll(layerResults);

                ContextPatch merged = mergeSuccessfulOutputs(layerResults);
                context = merged.applyTo(context);

                if (shouldStop(layer, layerResults)) {
                    return new DefaultPipelineResult(
                            PipelineStatus.FAILED,
                            context,
                            results,
                            firstFailure(layerResults)
                    );
                }
            }

            return new DefaultPipelineResult(
                    PipelineStatus.COMPLETED,
                    context,
                    results,
                    null
            );

        } catch (CancellationException e) {
            return new DefaultPipelineResult(
                    PipelineStatus.CANCELLED,
                    context,
                    results,
                    e
            );
        } catch (ContextMergeException e) {
            return new DefaultPipelineResult(
                    PipelineStatus.FAILED,
                    context,
                    results,
                    e
            );
        }
    }

    private List<StageResult> executeLayer(
            LayerDefinition layer,
            ContextView context
    ) throws InterruptedException {

        if (layer.type() == LayerDefinition.Type.SEQUENTIAL) {
            StageNode stage = layer.stages().getFirst();

            if (!hasRequiredInputs(stage, context)) {
                return List.of(blocked(stage));
            }

            return List.of(executeStage(stage, context));
        }

        return executeParallel(layer.stages(), context);
    }

    private List<StageResult> executeParallel(
            List<StageNode> stages,
            ContextView context
    ) throws InterruptedException {

        List<Future<StageResult>> futures = new ArrayList<>();
        List<StageResult> results = new ArrayList<>();

        // Every stage receives the same immutable context snapshot.
        for (StageNode stage : stages) {
            control.throwIfCancelled();

            if (!hasRequiredInputs(stage, context)) {
                futures.add(null);
                results.add(blocked(stage));
            } else {
                futures.add(executor.submit(() -> executeStage(stage, context)));
                results.add(null);
            }
        }

        // Collect in definition order, not completion order.
        for (int i = 0; i < stages.size(); i++) {
            Future<StageResult> future = futures.get(i);

            if (future == null) {
                continue;
            }

            try {
                results.set(i, future.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                results.set(i, failedWithoutWorker(
                        stages.get(i),
                        cause
                ));
            } catch (CancellationException e) {
                results.set(i, cancelled(stages.get(i), e));
            }
        }

        return List.copyOf(results);
    }

    private StageResult executeStage(
            StageNode node,
            ContextView context
    ) {
        long started = System.nanoTime();

        try (DefaultStageTasks tasks = new DefaultStageTasks(executor)) {
            control.throwIfCancelled();

            Stage stage = node.stage();
            var stageContext = new DefaultStageContext(context, tasks);

            ContextPatch patch = Objects.requireNonNull(
                    stage.execute(stageContext),
                    "Stage returned a null ContextPatch: " + node.id()
            );

            return new DefaultStageResult(
                    node.id(),
                    StageStatus.SUCCEEDED,
                    elapsed(started),
                    null,
                    patch,
                    WorkerTermination.TERMINATED,
                    List.of()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return new DefaultStageResult(
                    node.id(),
                    StageStatus.CANCELLED,
                    elapsed(started),
                    e,
                    null,
                    WorkerTermination.TERMINATED,
                    List.of()
            );

        } catch (Throwable e) {
            return new DefaultStageResult(
                    node.id(),
                    StageStatus.FAILED,
                    elapsed(started),
                    e,
                    null,
                    WorkerTermination.TERMINATED,
                    List.of()
            );
        }
    }

    private boolean hasRequiredInputs(
            StageNode node,
            ContextView context
    ) {
        return context.keys().containsAll(node.stage().requiredReads());
    }

    private ContextPatch mergeSuccessfulOutputs(
            List<StageResult> results
    ) {
        List<ContextPatch> patches = results.stream()
                .filter(result -> result.status() == StageStatus.SUCCEEDED)
                .flatMap(result -> result.output().stream())
                .toList();

        return ContextPatch.merge(patches);
    }

    private boolean shouldStop(
            LayerDefinition layer,
            List<StageResult> results
    ) {
        for (int i = 0; i < results.size(); i++) {
            StageResult result = results.get(i);

            boolean unsuccessful =
                    result.status() == StageStatus.FAILED
                            || result.status() == StageStatus.TIMED_OUT
                            || result.status() == StageStatus.CANCELLED;

            if (unsuccessful
                    && layer.stages().get(i).options().failurePolicy()
                    == FailurePolicy.FAIL_FAST) {
                return true;
            }
        }

        return false;
    }

    private Throwable firstFailure(List<StageResult> results) {
        return results.stream()
                .flatMap(result -> result.failure().stream())
                .findFirst()
                .orElse(null);
    }

    private StageResult blocked(StageNode node) {
        return new DefaultStageResult(
                node.id(),
                StageStatus.BLOCKED,
                Duration.ZERO,
                null,
                null,
                WorkerTermination.NOT_STARTED,
                List.of()
        );
    }

    private StageResult cancelled(StageNode node, Throwable error) {
        return new DefaultStageResult(
                node.id(),
                StageStatus.CANCELLED,
                Duration.ZERO,
                error,
                null,
                WorkerTermination.UNCONFIRMED,
                List.of()
        );
    }

    private StageResult failedWithoutWorker(
            StageNode node,
            Throwable error
    ) {
        return new DefaultStageResult(
                node.id(),
                StageStatus.FAILED,
                Duration.ZERO,
                error,
                null,
                WorkerTermination.TERMINATED,
                List.of()
        );
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}