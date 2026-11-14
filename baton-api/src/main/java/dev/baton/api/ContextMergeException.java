package dev.baton.api;

/**
 * Context writes that cannot be combined: two stages of one parallel layer wrote
 * the same key name, or a key name was written with a type different from the
 * one the context already holds.
 */
public final class ContextMergeException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message describes the conflicting keys and stages
     */
    public ContextMergeException(String message) {
        super(message);
    }
}
