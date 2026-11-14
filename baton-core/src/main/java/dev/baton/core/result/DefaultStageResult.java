package dev.baton.core.result;

import dev.baton.api.ContextPatch;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.WorkerTermination;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DefaultStageResult(
        String stageId,
        StageStatus status,
        Duration duration,
        Throwable error,
        ContextPatch patch,
        WorkerTermination workerTermination,
        List<StageResult> children,
        int attempts)
        implements StageResult {

    public DefaultStageResult {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(workerTermination, "workerTermination");
        children = List.copyOf(children);
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /** A result with one attempt, or none if the stage never started. */
    public DefaultStageResult(
            String stageId,
            StageStatus status,
            Duration duration,
            Throwable error,
            ContextPatch patch,
            WorkerTermination workerTermination,
            List<StageResult> children) {
        this(
                stageId,
                status,
                duration,
                error,
                patch,
                workerTermination,
                children,
                workerTermination == WorkerTermination.NOT_STARTED ? 0 : 1);
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(error);
    }

    @Override
    public Optional<ContextPatch> output() {
        return Optional.ofNullable(patch);
    }
}
