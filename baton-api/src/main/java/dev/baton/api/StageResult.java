package dev.baton.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface StageResult {

    String stageId();

    StageStatus status();

    Duration duration();

    Optional<Throwable> failure();

    Optional<ContextPatch> output();

    WorkerTermination workerTermination();

    /**
     * Nested stage results.
     * Empty for stages without nested pipelines.
     */
    default List<StageResult> children() {
        return List.of();
    }
}