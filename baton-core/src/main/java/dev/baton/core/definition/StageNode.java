package dev.baton.core.definition;

import dev.baton.api.Stage;
import dev.baton.api.StageOptions;

import java.util.Objects;

public record StageNode(
        String id,
        Stage stage,
        StageOptions options
) {
    public StageNode {
        requireId(id, "stage id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(options, "options");
    }

    public static void requireId(String id, String name) {
        Objects.requireNonNull(id, name);

        if (id.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must not be blank"
            );
        }
    }
}