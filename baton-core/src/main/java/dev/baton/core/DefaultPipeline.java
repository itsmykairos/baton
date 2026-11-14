package dev.baton.core;

import dev.baton.api.ContextView;
import dev.baton.api.NestedPipelineStage;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.RunOptions;
import dev.baton.api.description.LayerDescription;
import dev.baton.api.description.PipelineDescription;
import dev.baton.api.description.StageDescription;
import dev.baton.core.definition.LayerDefinition;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.definition.StageNode;
import dev.baton.core.execution.PipelineExecutionSession;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultPipeline implements Pipeline {

    private final PipelineDefinition definition;

    public DefaultPipeline(PipelineDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    /** The immutable definition; used by the engine to run this pipeline nested in another. */
    public PipelineDefinition definition() {
        return definition;
    }

    @Override
    public PipelineDescription describe() {
        List<LayerDescription> layers = definition.layers().stream()
                .map(layer -> new LayerDescription(
                        layer.id(),
                        layer.type() == LayerDefinition.Type.PARALLEL
                                ? LayerDescription.Kind.PARALLEL
                                : LayerDescription.Kind.SEQUENTIAL,
                        layer.stages().stream().map(DefaultPipeline::describe).toList()))
                .toList();
        return new PipelineDescription(definition.name(), layers, definition.config());
    }

    private static StageDescription describe(StageNode node) {
        Optional<PipelineDescription> nested = Optional.empty();
        if (node.stage() instanceof NestedPipelineStage stage) {
            try {
                nested = Optional.of(stage.pipeline().describe());
            } catch (UnsupportedOperationException opaque) {
                // A pipeline implementation that cannot describe itself.
            }
        }
        return new StageDescription(node.id(), stageType(node.stage()), node.options(), node.requiredReads(), nested);
    }

    private static String stageType(Object stage) {
        Class<?> type = stage.getClass();
        return type.isSynthetic() || type.getName().contains("$$Lambda") ? "lambda" : type.getName();
    }

    @Override
    public PipelineResult run(ContextView input, RunOptions options) throws InterruptedException {

        return new PipelineExecutionSession(
                        definition, Objects.requireNonNull(input, "input"), Objects.requireNonNull(options, "options"))
                .executeSync();
    }

    @Override
    public PipelineRun start(ContextView input, RunOptions options) {
        PipelineExecutionSession session = new PipelineExecutionSession(
                definition, Objects.requireNonNull(input, "input"), Objects.requireNonNull(options, "options"));

        session.startAsync();
        return session;
    }
}
