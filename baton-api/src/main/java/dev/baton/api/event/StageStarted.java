package dev.baton.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A stage was submitted for execution. Stages that never start (BLOCKED, or
 * cancelled before submission) have only a {@link StageFinished} event.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param path the stage's position, including nested pipeline stages
 * @param timestamp when the stage started
 */
public record StageStarted(UUID runId, String pipelineName, StagePath path, Instant timestamp)
        implements PipelineEvent {}
