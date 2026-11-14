package dev.baton.api.event;

import dev.baton.api.KeyConflict;

import java.time.Instant;
import java.util.UUID;

/**
 * A context write conflict was found while merging a layer, and resolved by the
 * configured policy.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param conflict the conflict and its resolution; stage paths are from the outermost pipeline
 * @param timestamp when it was resolved
 */
public record ContextConflict(UUID runId, String pipelineName, KeyConflict conflict, Instant timestamp)
        implements PipelineEvent {
}
