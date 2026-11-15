package dev.baton.api;

/** Terminal outcome of a pipeline run. */
public enum PipelineStatus {
    /**
     * Every layer ran. Stages may still have been BLOCKED or failed under
     * {@link FailurePolicy#CONTINUE}; see {@link PipelineResult#isSuccessful()}.
     */
    COMPLETED,
    /** A {@link FailurePolicy#FAIL_FAST} stage failed, or a layer had conflicting writes. */
    FAILED,
    /** A {@link FailurePolicy#FAIL_FAST} stage timed out, or the run's own timeout expired. */
    TIMED_OUT,
    /** The run was cancelled with {@link PipelineRun#cancel()}. */
    CANCELLED
}
