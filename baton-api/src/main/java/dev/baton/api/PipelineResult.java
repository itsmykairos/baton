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
     *
     * <p>A run can end {@link PipelineStatus#COMPLETED} and still be unsuccessful:
     * BLOCKED stages and stages that failed under {@link FailurePolicy#CONTINUE}
     * do not stop the run.</p>
     *
     * <p>Nested stages are checked recursively through {@link StageResult#children()}.</p>
     */
    boolean isSuccessful();

    /**
     * True if at least one stage failed or timed out, including stages of
     * nested pipelines reported through {@link StageResult#children()}.
     */
    boolean hasFailures();

    /**
     * Primary pipeline-level failure, if present.
     */
    Optional<Throwable> failure();
}