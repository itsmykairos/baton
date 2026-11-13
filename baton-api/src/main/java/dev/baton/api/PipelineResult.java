package dev.baton.api;

import java.util.List;
import java.util.Optional;

public interface PipelineResult {

    PipelineStatus status();

    /**
     * Final immutable context.
     */
    ContextView context();

    /**
     * Results in pipeline-definition order,
     * not worker completion order.
     */
    List<StageResult> stages();

    /**
     * True only if the pipeline completed successfully
     * without failed, blocked, timed-out or cancelled stages.
     */
    boolean isSuccessful();

    /**
     * True if at least one stage failed or timed out.
     */
    boolean hasFailures();

    /**
     * Primary pipeline-level failure, if present.
     */
    Optional<Throwable> failure();
}