package dev.baton.api.description;

import dev.baton.api.BatonConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The structure of a built pipeline: its layers in order, with nested pipelines
 * described recursively.
 *
 * @param name the pipeline name
 * @param layers the layers, in execution order
 * @param config the pipeline's resolved settings (app and pipeline layers)
 */
public record PipelineDescription(String name, List<LayerDescription> layers, BatonConfig config) {

    /**
     * Validates and copies the record's fields.
     *
     * @param name the pipeline name
     * @param layers the layers, in execution order
     * @param config the pipeline's resolved settings
     */
    public PipelineDescription {
        Objects.requireNonNull(name, "name");
        layers = List.copyOf(layers);
        Objects.requireNonNull(config, "config");
    }

    /** {@return every stage of this pipeline (not of nested ones), in definition order} */
    public List<StageDescription> stages() {
        return layers.stream().flatMap(layer -> layer.stages().stream()).toList();
    }

    /**
     * Finds a stage by path; nested stages are separated by {@code /}.
     *
     * @param path stage ids from this pipeline down, such as {@code "payment/charge"}
     * @return the stage, or empty if no stage has that path
     */
    public Optional<StageDescription> stage(String path) {
        Objects.requireNonNull(path, "path");
        String[] ids = path.split("/", -1);
        PipelineDescription level = this;
        StageDescription found = null;
        for (String id : ids) {
            if (level == null) {
                return Optional.empty();
            }
            found = level.stages().stream()
                    .filter(stage -> stage.id().equals(id))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return Optional.empty();
            }
            level = found.nested().orElse(null);
        }
        return Optional.ofNullable(found);
    }
}
