package dev.baton.api;

/** Outcome of one stage in a pipeline run. */
public enum StageStatus {
    /** The stage returned a patch, and it was applied to the context. */
    SUCCEEDED,
    /**
     * The stage threw, a child task it never joined failed, or its patch conflicted
     * with another stage in the same parallel layer.
     */
    FAILED,
    /**
     * The stage failed or timed out, and its {@link StageFallback} supplied a patch
     * that was applied instead. Counts as success; the original failure is kept in
     * {@link StageResult#failure()}.
     */
    RECOVERED,
    /**
     * The stage's own timeout expired, or its last attempt exceeded the
     * per-attempt timeout; any late patch is discarded.
     */
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
