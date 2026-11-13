package dev.baton.api;

public enum StageStatus {
    /** The stage returned a patch, and it was applied to the context. */
    SUCCEEDED,
    /**
     * The stage threw, a child task it never joined failed, or its patch conflicted
     * with another stage in the same parallel layer.
     */
    FAILED,
    /** The stage's own timeout expired; any late patch is discarded. */
    TIMED_OUT,
    /**
     * The stage did not run because a key from {@link Stage#requiredReads()} was
     * missing from the context. Does not trigger {@link FailurePolicy#FAIL_FAST}.
     */
    BLOCKED,
    /** Reserved for future use; not currently produced. */
    SKIPPED,
    /** The run was cancelled, or a FAIL_FAST sibling in the same layer failed. */
    CANCELLED
}
