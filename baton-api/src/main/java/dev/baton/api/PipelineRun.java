package dev.baton.api;

public interface PipelineRun {

    /**
     * Waits for the pipeline's terminal outcome.
     *
     * Interrupting this wait does not automatically cancel
     * the pipeline execution.
     */
    PipelineResult await() throws InterruptedException;

    /**
     * Requests cancellation.
     *
     * Returns true when the cancellation request was accepted.
     * This does not guarantee worker termination.
     */
    boolean cancel();

    boolean isDone();

    PipelineRunStatus status();
}