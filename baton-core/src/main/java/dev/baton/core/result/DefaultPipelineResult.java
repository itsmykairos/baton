package dev.baton.core.result;

import dev.baton.api.ContextView;
import dev.baton.api.KeyConflict;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DefaultPipelineResult(
        PipelineStatus status,
        ContextView context,
        List<StageResult> stages,
        Throwable error,
        List<KeyConflict> conflicts)
        implements PipelineResult {

    public DefaultPipelineResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(context, "context");
        stages = List.copyOf(stages);
        conflicts = List.copyOf(conflicts);
    }

    /** A result without recorded conflicts. */
    public DefaultPipelineResult(
            PipelineStatus status, ContextView context, List<StageResult> stages, Throwable error) {
        this(status, context, stages, error, List.of());
    }

    @Override
    public boolean isSuccessful() {
        return status == PipelineStatus.COMPLETED && allSucceeded(stages);
    }

    @Override
    public boolean hasFailures() {
        return anyFailed(stages);
    }

    /** Checks nested children too: a successful nested stage may contain failed stages. */
    /**
     * A RECOVERED stage's fallback replaced its outcome, so failures inside it (a
     * nested pipeline that failed) do not count.
     */
    private static boolean allSucceeded(List<StageResult> stages) {
        return stages.stream().allMatch(stage -> switch (stage.status()) {
            case RECOVERED -> true;
            case SUCCEEDED, SKIPPED -> allSucceeded(stage.children());
            default -> false;
        });
    }

    private static boolean anyFailed(List<StageResult> stages) {
        return stages.stream().anyMatch(stage -> switch (stage.status()) {
            case RECOVERED -> false;
            case FAILED, TIMED_OUT -> true;
            default -> anyFailed(stage.children());
        });
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(error);
    }
}
