package dev.baton.api;

/**
 * What happens when a stage writes a key that is already in the context, from the
 * input or an earlier layer.
 */
public enum OverwritePolicy {
    /** The new value replaces the old one. The default; not recorded as a conflict. */
    OVERRIDE,
    /** The existing value is kept and the write is dropped. */
    KEEP_EXISTING,
    /** The stage is FAILED with a {@link ContextMergeException}. */
    FAIL
}
