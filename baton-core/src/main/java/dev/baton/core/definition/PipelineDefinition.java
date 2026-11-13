package dev.baton.core.definition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PipelineDefinition(
        List<LayerDefinition> layers
) {
    public PipelineDefinition {
        layers = List.copyOf(
                Objects.requireNonNull(layers, "layers")
        );

        validate(layers);
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