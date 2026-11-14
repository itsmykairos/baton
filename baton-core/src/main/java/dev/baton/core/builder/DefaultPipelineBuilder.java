package dev.baton.core.builder;

import dev.baton.api.Pipeline;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;
import dev.baton.api.event.PipelineListener;
import dev.baton.core.definition.LayerDefinition;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.definition.StageNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class DefaultPipelineBuilder implements Pipeline.Builder {

    private final List<LayerDefinition> layers = new ArrayList<>();
    private final List<PipelineListener> listeners = new ArrayList<>();
    private String name = PipelineDefinition.DEFAULT_NAME;
    private Duration timeout;

    private final Set<String> layerIds = new HashSet<>();
    private final Set<String> stageIds = new HashSet<>();

    private boolean built;

    @Override
    public DefaultPipelineBuilder name(String name) {
        ensureOpen();
        StageNode.requireId(name, "pipeline name");
        this.name = name;
        return this;
    }

    @Override
    public DefaultPipelineBuilder timeout(Duration timeout) {
        ensureOpen();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
        return this;
    }

    @Override
    public DefaultPipelineBuilder listener(PipelineListener listener) {
        ensureOpen();
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    @Override
    public DefaultPipelineBuilder stage(
            String id,
            Stage stage
    ) {
        return stage(id, stage, StageOptions.defaults());
    }

    @Override
    public DefaultPipelineBuilder stage(
            String id,
            Stage stage,
            StageOptions options
    ) {
        ensureOpen();

        StageNode node = new StageNode(id, stage, options);

        validateStageId(node.id());

        // A sequential layer uses its stage ID as its layer ID.
        validateLayerId(node.id());

        LayerDefinition layer = new LayerDefinition(
                node.id(),
                LayerDefinition.Type.SEQUENTIAL,
                List.of(node)
        );

        stageIds.add(node.id());
        layerIds.add(layer.id());
        layers.add(layer);

        return this;
    }

    @Override
    public DefaultPipelineBuilder parallel(
            String id,
            Consumer<Pipeline.ParallelBuilder> definition
    ) {
        ensureOpen();

        StageNode.requireId(id, "layer id");
        Objects.requireNonNull(definition, "definition");

        validateLayerId(id);

        DefaultParallelBuilder parallelBuilder =
                new DefaultParallelBuilder();

        // If the callback fails, nothing is added to this pipeline.
        definition.accept(parallelBuilder);

        List<StageNode> parallelStages =
                parallelBuilder.finish();

        validateParallelStageIds(parallelStages);

        LayerDefinition layer = new LayerDefinition(
                id,
                LayerDefinition.Type.PARALLEL,
                parallelStages
        );

        layerIds.add(id);

        for (StageNode stage : parallelStages) {
            stageIds.add(stage.id());
        }

        layers.add(layer);

        return this;
    }

    @Override
    public Pipeline build() {
        ensureOpen();

        if (layers.isEmpty()) {
            throw new IllegalStateException("A pipeline must contain at least one stage");
        }
        built = true;

        PipelineDefinition definition =
                new PipelineDefinition(name, layers, listeners, Optional.ofNullable(timeout));

        return new dev.baton.core.DefaultPipeline(definition);
    }

    private void validateParallelStageIds(List<StageNode> stages) {
        Set<String> localIds = new HashSet<>();

        for (StageNode stage : stages) {
            if (stageIds.contains(stage.id())
                    || !localIds.add(stage.id())) {
                throw new IllegalArgumentException(
                        "Duplicate stage id: " + stage.id()
                );
            }
        }
    }

    private void validateStageId(String id) {
        if (stageIds.contains(id)) {
            throw new IllegalArgumentException(
                    "Duplicate stage id: " + id
            );
        }
    }

    private void validateLayerId(String id) {
        if (layerIds.contains(id)) {
            throw new IllegalArgumentException(
                    "Duplicate layer id: " + id
            );
        }
    }

    private void ensureOpen() {
        if (built) {
            throw new IllegalStateException(
                    "Pipeline builder has already been used"
            );
        }
    }
}