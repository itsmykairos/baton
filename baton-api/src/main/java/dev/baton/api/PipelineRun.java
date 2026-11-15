package dev.baton.api;

/**
 * A pipeline execution started with {@link Pipeline#start(ContextView)}.
 */
public interface PipelineRun {

    /**
     * Waits for the pipeline's terminal outcome.
     *
     * <p>Interrupting this wait does not cancel the pipeline execution.</p>
     *
     * @return the pipeline result
     * @throws InterruptedException if the waiting thread is interrupted
     */
    PipelineResult await() throws InterruptedException;

    /**
     * Requests cancellation. Active stages are cancelled immediately and the run
     * completes {@link PipelineStatus#CANCELLED}; this does not wait for, or
     * guarantee, worker termination.
     *
     * @return true if this call cancelled the run; false if it had already
     *         completed or been cancelled
     */
    boolean cancel();

    /** {@return true once the run has a terminal outcome} */
    boolean isDone();

    /** {@return the current lifecycle status} */
    PipelineRunStatus status();
}
