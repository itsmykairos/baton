package dev.baton.api;

import java.util.List;
import java.util.Optional;

/** The terminal outcome of a pipeline run. */
public interface PipelineResult {

    /** {@return the run's terminal status} */
    PipelineStatus status();

    /**
     * Final immutable context.
     *
     * @return the input with every applied stage patch
     */
    ContextView context();

    /**
     * Results in pipeline-definition order,
     * not worker completion order.
     *
     * @return the results of the stages that were reached
     */
    List<StageResult> stages();

    /**
     * True only if the pipeline completed successfully: every stage SUCCEEDED, was
     * RECOVERED by its fallback, or was deliberately SKIPPED, with no failed,
     * blocked, timed-out or cancelled stages.
     *
     * <p>A run can end {@link PipelineStatus#COMPLETED} and still be unsuccessful:
     * BLOCKED stages and stages that failed under {@link FailurePolicy#CONTINUE}
     * do not stop the run.</p>
     *
     * <p>Nested stages are checked recursively through {@link StageResult#children()},
     * except inside a RECOVERED stage: its fallback replaced the nested outcome.</p>
     *
     * @return whether the run fully succeeded
     */
    boolean isSuccessful();

    /**
     * True if at least one stage failed or timed out, including stages of
     * nested pipelines reported through {@link StageResult#children()} (but not
     * inside a RECOVERED stage, whose fallback replaced the nested outcome).
     *
     * @return whether any stage failed or timed out
     */
    boolean hasFailures();

    /**
     * Context write conflicts found in this run, including nested pipelines, in the
     * order they were resolved. Each records how the configured policy resolved it.
     *
     * @return the conflicts; empty if there were none
     * @see LayerConflictPolicy
     * @see OverwritePolicy
     * @see TypeClashPolicy
     */
    default List<KeyConflict> conflicts() {
        return List.of();
    }

    /**
     * Primary pipeline-level failure, if present.
     *
     * @return the failure that ended the run, for FAILED, TIMED_OUT and CANCELLED
     */
    Optional<Throwable> failure();
}