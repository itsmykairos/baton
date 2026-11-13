package dev.baton.api.event;

/**
 * Receives the events of pipeline runs.
 *
 * <p>Delivery is asynchronous: each run with listeners has a bounded event queue
 * drained by one virtual thread, so stages never wait for a listener. For one run,
 * events arrive one at a time in the order they were emitted; the last one is
 * always {@link RunFinished}, which can arrive after {@code Pipeline.run} has
 * returned. A listener shared by several pipelines or runs is called concurrently
 * for different runs and must be thread-safe.</p>
 *
 * <p>If a listener throws, the exception is logged through {@link System.Logger}
 * (logger {@code dev.baton.event}) and delivery continues. If a listener is so slow
 * that the queue fills, further events of that run are dropped and counted in
 * {@link RunFinished#droppedEvents()}; the pipeline itself is never blocked.</p>
 */
@FunctionalInterface
public interface PipelineListener {

    /**
     * Handles one event.
     *
     * @param event the event; switch over the {@link PipelineEvent} subtypes
     */
    void onEvent(PipelineEvent event);
}
