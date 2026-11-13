package dev.baton.core.definition;

import dev.baton.api.ContextKey;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;

import java.util.Objects;
import java.util.Set;

/**
 * One stage in a pipeline definition. {@link Stage#requiredReads()} is captured
 * and validated once, when the pipeline is built.
 */
public record StageNode(
        String id,
        Stage stage,
        StageOptions options,
        Set<ContextKey<?>> requiredReads
) {
    public StageNode {
        requireId(id, "stage id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(requiredReads, "requiredReads of stage " + id);
        try {
            requiredReads = Set.copyOf(requiredReads);
        } catch (NullPointerException nullKey) {
            throw new NullPointerException("requiredReads of stage " + id + " contains null");
        }
    }

    public StageNode(String id, Stage stage, StageOptions options) {
        this(id, stage, options, requiredReadsOf(id, stage));
    }

    private static Set<ContextKey<?>> requiredReadsOf(String id, Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return Objects.requireNonNull(stage.requiredReads(), "requiredReads of stage " + id);
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
