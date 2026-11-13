package dev.baton.core.definition;

import java.util.List;
import java.util.Objects;

public record LayerDefinition(
        String id,
        Type type,
        List<StageNode> stages
) {
    public LayerDefinition {
        StageNode.requireId(id, "layer id");
        Objects.requireNonNull(type, "type");
        stages = List.copyOf(
                Objects.requireNonNull(stages, "stages")
        );

        if (stages.isEmpty()) {
            throw new IllegalArgumentException(
                    "A layer must contain at least one stage"
            );
        }

        if (type == Type.SEQUENTIAL && stages.size() != 1) {
            throw new IllegalArgumentException(
                    "A sequential layer must contain exactly one stage"
            );
        }
    }

    public enum Type {
        SEQUENTIAL,
        PARALLEL
    }
}