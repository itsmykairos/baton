package dev.baton.core.event;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunFinished;
import java.util.function.LongFunction;

/**
 * Where the engine sends events. {@link #NONE} is used when a run has no
 * listeners: callers check {@link #enabled()} first, so such a run builds no
 * events at all.
 */
public interface EventSink {

    /** A sink for runs without listeners. */
    EventSink NONE = new EventSink() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void emit(PipelineEvent event) {}

        @Override
        public void finish(LongFunction<RunFinished> lastEvent) {}
    };

    /** {@return whether events are wanted; if false, do not build them} */
    boolean enabled();

    /** Queues an event without ever blocking; drops it if the queue is full. */
    void emit(PipelineEvent event);

    /**
     * Ends the run's event stream. The final event is built from the number of
     * dropped events and is delivered after every queued event. Events emitted
     * afterwards are dropped.
     */
    void finish(LongFunction<RunFinished> lastEvent);
}
