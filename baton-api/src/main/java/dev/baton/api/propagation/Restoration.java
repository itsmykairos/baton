package dev.baton.api.propagation;

/**
 * Undoes what {@link ContextPropagator#restore} did, on the same thread, when the
 * work is over.
 */
@FunctionalInterface
public interface Restoration extends AutoCloseable {

    /** A restoration that does nothing. */
    Restoration NONE = () -> { };

    /**
     * Called before {@link #close()} when the work failed, timed out or was
     * cancelled, for example to mark a span as an error. Does nothing by default.
     *
     * @param failure what ended the work
     */
    default void failed(Throwable failure) {
    }

    /** Undoes the restoration. Must not throw. */
    @Override
    void close();
}
