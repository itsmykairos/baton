package dev.baton.api;

/**
 * What happens when a stage writes a key whose name the context already holds with
 * a different type.
 */
public enum TypeClashPolicy {
    /** The stage is FAILED with a {@link ContextMergeException}. The default. */
    FAIL,
    /** The old key and its value are removed and the new key is written. */
    REPLACE
}
