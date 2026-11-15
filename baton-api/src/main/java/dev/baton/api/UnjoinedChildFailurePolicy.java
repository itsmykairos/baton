package dev.baton.api;

/**
 * What happens when a stage returns while a child task it forked but never joined
 * has already failed.
 */
public enum UnjoinedChildFailurePolicy {
    /** The stage fails with the child's exception. The default. */
    FAIL_STAGE,
    /** The failure is logged (logger {@code dev.baton}) and the stage succeeds. */
    IGNORE
}
