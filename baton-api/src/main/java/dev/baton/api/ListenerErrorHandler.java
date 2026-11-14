package dev.baton.api;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;

/**
 * Handles an exception thrown by a {@link PipelineListener}. Called on the run's
 * event delivery thread; delivery continues afterwards.
 */
@FunctionalInterface
public interface ListenerErrorHandler {

    /**
     * Handles one listener failure.
     *
     * @param listener the listener that threw
     * @param event the event it was handling
     * @param failure what it threw
     */
    void onListenerError(PipelineListener listener, PipelineEvent event, Throwable failure);

    /** {@return the default handler: logs a WARNING to the {@code dev.baton.event} System.Logger} */
    static ListenerErrorHandler logging() {
        return (listener, event, failure) -> System.getLogger("dev.baton.event").log(
                System.Logger.Level.WARNING,
                "Pipeline listener " + listener + " failed on " + event.getClass().getSimpleName(),
                failure);
    }
}
