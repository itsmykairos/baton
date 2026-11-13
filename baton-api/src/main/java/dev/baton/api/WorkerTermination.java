package dev.baton.api;

/**
 * Describes what Baton knows about actual worker termination.
 */
public enum WorkerTermination {

    /**
     * The stage was never started.
     */
    NOT_STARTED,

    /**
     * The worker has actually terminated.
     */
    TERMINATED,

    /**
     * Cancellation or interruption was requested,
     * but worker termination has not been confirmed.
     */
    UNCONFIRMED
}