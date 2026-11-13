package dev.baton.api;

public enum FailurePolicy {

    /**
     * Stop scheduling affected downstream work and request
     * cancellation of active work in the pipeline execution.
     */
    FAIL_FAST,

    /**
     * Continue independent work. Block stages whose required
     * context inputs are unavailable.
     */
    CONTINUE
}