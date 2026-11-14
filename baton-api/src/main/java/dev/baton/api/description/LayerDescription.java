package dev.baton.api.description;

import java.util.List;
import java.util.Objects;

/**
 * One layer of a pipeline: a single sequential stage, or a group of stages that run
 * in parallel.
 *
 * @param id the layer id (for a sequential layer, its stage's id)
 * @param kind whether the layer is sequential or parallel
 * @param stages the layer's stages, in definition order
 */
public record LayerDescription(String id, Kind kind, List<StageDescription> stages) {

    /**
     * Validates and copies the record's fields.
     *
     * @param id the layer id
     * @param kind whether the layer is sequential or parallel
     * @param stages the layer's stages, in definition order
     */
    public LayerDescription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        stages = List.copyOf(stages);
    }

    /** How a layer runs its stages. */
    public enum Kind {
        /** One stage, run on its own. */
        SEQUENTIAL,
        /** Several stages, run at the same time on the same context snapshot. */
        PARALLEL
    }
}
