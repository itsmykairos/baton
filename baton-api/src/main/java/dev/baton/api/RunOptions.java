package dev.baton.api;

import dev.baton.api.event.PipelineListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Options for one run, passed to {@link Pipeline#run(ContextView, RunOptions)} or
 * {@link Pipeline#start(ContextView, RunOptions)}. Immutable; each {@code with}
 * method returns a copy.
 */
public final class RunOptions {

    /** The default size of a run's event queue. */
    public static final int DEFAULT_EVENT_BUFFER_CAPACITY = 8_192;

    private static final RunOptions DEFAULTS = new RunOptions(List.of(), DEFAULT_EVENT_BUFFER_CAPACITY);

    private final List<PipelineListener> listeners;
    private final int eventBufferCapacity;

    private RunOptions(List<PipelineListener> listeners, int eventBufferCapacity) {
        this.listeners = List.copyOf(listeners);
        this.eventBufferCapacity = eventBufferCapacity;
    }

    /** {@return options with no extra listeners and the default event buffer} */
    public static RunOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Adds a listener for this run only, in addition to the pipeline's own listeners.
     *
     * @param listener the listener
     * @return a copy with the listener added
     */
    public RunOptions withListener(PipelineListener listener) {
        List<PipelineListener> copy = new ArrayList<>(listeners);
        copy.add(Objects.requireNonNull(listener, "listener"));
        return new RunOptions(copy, eventBufferCapacity);
    }

    /**
     * Sets how many undelivered events a run may queue before further events are
     * dropped (and counted in {@link dev.baton.api.event.RunFinished#droppedEvents()}).
     *
     * @param capacity a positive number of events
     * @return a copy with this capacity
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public RunOptions withEventBufferCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return new RunOptions(listeners, capacity);
    }

    /** {@return the run-only listeners} */
    public List<PipelineListener> listeners() {
        return listeners;
    }

    /** {@return the event queue capacity} */
    public int eventBufferCapacity() {
        return eventBufferCapacity;
    }
}
