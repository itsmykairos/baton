package dev.baton.api.event;

import dev.baton.api.PipelineStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A run reached its terminal outcome. Always the last event of a run, and never
 * dropped.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param status the run outcome
 * @param duration how long the run took
 * @param failure the failure that ended the run, if any
 * @param droppedEvents how many events of this run were dropped because the
 *                      listeners fell behind
 * @param timestamp when the run finished
 */
public record RunFinished(
        UUID runId,
        String pipelineName,
        PipelineStatus status,
        Duration duration,
        Optional<Throwable> failure,
        long droppedEvents,
        Instant timestamp
) implements PipelineEvent {
}
