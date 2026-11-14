package dev.baton.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A run started. Always the first event of a run.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param timestamp when the run started
 */
public record RunStarted(UUID runId, String pipelineName, Instant timestamp) implements PipelineEvent {}
