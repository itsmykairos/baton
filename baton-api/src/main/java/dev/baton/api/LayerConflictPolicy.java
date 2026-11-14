package dev.baton.api;

/** What happens when two or more stages of one parallel layer write the same key name. */
public enum LayerConflictPolicy {
    /** Every writer of the key is FAILED with a {@link ContextMergeException}. The default. */
    FAIL,
    /** The first writer in definition order wins; the others' writes of that key are dropped. */
    FIRST_WINS,
    /** The last writer in definition order wins; the others' writes of that key are dropped. */
    LAST_WINS,
    /** Nobody's write of that key is applied; the stages still succeed. */
    SKIP_KEY
}
