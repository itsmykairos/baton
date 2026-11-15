package dev.baton.api.event;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An attempt of a stage failed and another will start after {@code delay}.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param path the stage's position, including nested pipeline stages
 * @param failedAttempt the attempt that failed, starting at 1
 * @param failure why it failed
 * @param delay the backoff before the next attempt
 * @param timestamp when the attempt failed
 */
public record StageRetrying(
        UUID runId,
        String pipelineName,
        StagePath path,
        int failedAttempt,
        Throwable failure,
        Duration delay,
        Instant timestamp)
        implements PipelineEvent {}
