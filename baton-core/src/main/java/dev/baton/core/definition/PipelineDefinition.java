package dev.baton.core.definition;

import dev.baton.api.event.PipelineListener;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PipelineDefinition(
        String name,
        List<LayerDefinition> layers,
        List<PipelineListener> listeners
) {
    public static final String DEFAULT_NAME = "pipeline";

    public PipelineDefinition {
        StageNode.requireId(name, "pipeline name");
        layers = List.copyOf(
                Objects.requireNonNull(layers, "layers")
        );
        listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));

        validate(layers);
    }

    public PipelineDefinition(List<LayerDefinition> layers) {
        this(DEFAULT_NAME, layers, List.of());
    }

    private static void validate(List<LayerDefinition> layers) {
        Set<String> layerIds = new HashSet<>();
        Set<String> stageIds = new HashSet<>();

        for (LayerDefinition layer : layers) {
            if (!layerIds.add(layer.id())) {
                throw new IllegalArgumentException(
                        "Duplicate layer id: " + layer.id()
                );
            }

            for (StageNode stage : layer.stages()) {
                if (!stageIds.add(stage.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate stage id: " + stage.id()
                    );
                }
            }
        }
    }
}