package dev.baton.api;

/**
 * What a stage's failure or timeout means for the rest of the pipeline run.
 *
 * <p>Only {@link StageStatus#FAILED} and {@link StageStatus#TIMED_OUT} trigger a
 * policy. A {@link StageStatus#BLOCKED} stage (missing required inputs) never stops
 * the run, under either policy; the run can then end {@link PipelineStatus#COMPLETED}
 * while {@link PipelineResult#isSuccessful()} is false.</p>
 */
public enum FailurePolicy {

    /**
     * Stop the run. Unfinished stages in the same parallel layer are cancelled
     * immediately (and reported {@link StageStatus#CANCELLED}), later layers are not
     * started, and the run ends {@link PipelineStatus#FAILED} or
     * {@link PipelineStatus#TIMED_OUT} with this stage's failure.
     */
    FAIL_FAST,

    /**
     * Continue independent work. Later stages whose required context inputs are
     * unavailable are reported {@link StageStatus#BLOCKED}.
     */
    CONTINUE
}
