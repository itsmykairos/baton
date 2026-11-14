package dev.baton.core.definition;

import dev.baton.api.BatonConfig;
import dev.baton.api.event.PipelineListener;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PipelineDefinition(
        String name, List<LayerDefinition> layers, List<PipelineListener> listeners, BatonConfig config) {
    public static final String DEFAULT_NAME = BatonConfig.DEFAULT_PIPELINE_NAME;

    public PipelineDefinition {
        StageNode.requireId(name, "pipeline name");
        layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
        listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        Objects.requireNonNull(config, "config");

        validate(layers);
    }

    public PipelineDefinition(List<LayerDefinition> layers) {
        this(DEFAULT_NAME, layers, List.of(), BatonConfig.defaults());
    }

    /** The pipeline's own run timeout, if configured. */
    public Optional<Duration> timeout() {
        return config.runTimeout();
    }

    private static void validate(List<LayerDefinition> layers) {
        Set<String> layerIds = new HashSet<>();
        Set<String> stageIds = new HashSet<>();

        for (LayerDefinition layer : layers) {
            if (!layerIds.add(layer.id())) {
                throw new IllegalArgumentException("Duplicate layer id: " + layer.id());
            }

            for (StageNode stage : layer.stages()) {
                if (!stageIds.add(stage.id())) {
                    throw new IllegalArgumentException("Duplicate stage id: " + stage.id());
                }
            }
        }
    }
}
