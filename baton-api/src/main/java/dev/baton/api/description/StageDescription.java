package dev.baton.api.description;

import dev.baton.api.ContextKey;
import dev.baton.api.StageOptions;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One stage of a pipeline.
 *
 * @param id the stage id
 * @param stageType the stage implementation's class name, for documentation
 * @param options the stage's resolved options (including any pipeline default)
 * @param requiredReads the keys the stage needs before it can start
 * @param nested the pipeline this stage runs, if it is a nested pipeline stage
 *               whose pipeline can describe itself
 */
public record StageDescription(
        String id,
        String stageType,
        StageOptions options,
        Set<ContextKey<?>> requiredReads,
        Optional<PipelineDescription> nested) {

    /**
     * Validates and copies the record's fields.
     *
     * @param id the stage id
     * @param stageType the stage implementation's class name
     * @param options the stage's resolved options
     * @param requiredReads the keys the stage needs before it can start
     * @param nested the nested pipeline's description, if any
     */
    public StageDescription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stageType, "stageType");
        Objects.requireNonNull(options, "options");
        requiredReads = Set.copyOf(requiredReads);
        Objects.requireNonNull(nested, "nested");
    }
}
