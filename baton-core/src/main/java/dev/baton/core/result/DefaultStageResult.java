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
        List<StageResult> children
) implements StageResult {

    public DefaultStageResult {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(workerTermination, "workerTermination");
        children = List.copyOf(children);
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