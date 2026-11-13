package dev.baton.core.result;

import dev.baton.api.ContextView;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DefaultPipelineResult(
        PipelineStatus status,
        ContextView context,
        List<StageResult> stages,
        Throwable error
) implements PipelineResult {

    public DefaultPipelineResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(context, "context");
        stages = List.copyOf(stages);
    }

    @Override
    public boolean isSuccessful() {
        return status == PipelineStatus.COMPLETED
                && stages.stream().allMatch(stage ->
                        stage.status() == StageStatus.SUCCEEDED);
    }

    @Override
    public boolean hasFailures() {
        return stages.stream().anyMatch(stage ->
                stage.status() == StageStatus.FAILED
                        || stage.status() == StageStatus.TIMED_OUT);
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(error);
    }
}