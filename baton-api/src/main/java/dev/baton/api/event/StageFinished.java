package dev.baton.api.event;

import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A stage reached its logical outcome. Emitted once for every stage the run
 * reached, including BLOCKED stages and stages cancelled before they started.
 *
 * @param runId the run id
 * @param pipelineName the outermost pipeline's name
 * @param path the stage's position, including nested pipeline stages
 * @param status the stage outcome
 * @param duration how long the stage ran; zero if it never started
 * @param failure the failure for FAILED, TIMED_OUT and CANCELLED stages
 * @param attempts how many attempts were started; 0 if the stage never started
 * @param workerTermination what is known about the worker's physical exit
 * @param timestamp when the outcome was reached
 */
public record StageFinished(
        UUID runId,
        String pipelineName,
        StagePath path,
        StageStatus status,
        Duration duration,
        Optional<Throwable> failure,
        int attempts,
        WorkerTermination workerTermination,
        Instant timestamp
) implements PipelineEvent {
}
