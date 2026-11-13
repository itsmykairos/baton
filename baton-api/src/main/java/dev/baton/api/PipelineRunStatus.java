package dev.baton.api;

/** Lifecycle status of a {@link PipelineRun}. */
public enum PipelineRunStatus {
    /** Created but not yet started. */
    CREATED,
    /** Executing stages. */
    RUNNING,
    /** Finished; see {@link PipelineStatus#COMPLETED}. */
    COMPLETED,
    /** Finished; see {@link PipelineStatus#FAILED}. */
    FAILED,
    /** Finished; see {@link PipelineStatus#TIMED_OUT}. */
    TIMED_OUT,
    /** Finished; see {@link PipelineStatus#CANCELLED}. */
    CANCELLED
}
