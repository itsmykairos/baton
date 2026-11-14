package dev.baton.api;

import dev.baton.api.event.PipelineListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Options for one run, passed to {@link Pipeline#run(ContextView, RunOptions)} or
 * {@link Pipeline#start(ContextView, RunOptions)}. Immutable; each {@code with}
 * method returns a copy.
 */
public final class RunOptions {

    /** The default size of a run's event queue. */
    public static final int DEFAULT_EVENT_BUFFER_CAPACITY = 8_192;

    private static final RunOptions DEFAULTS = new RunOptions(List.of(), DEFAULT_EVENT_BUFFER_CAPACITY, null);

    private final List<PipelineListener> listeners;
    private final int eventBufferCapacity;
    private final Duration timeout;

    private RunOptions(List<PipelineListener> listeners, int eventBufferCapacity, Duration timeout) {
        this.listeners = List.copyOf(listeners);
        this.eventBufferCapacity = eventBufferCapacity;
        this.timeout = timeout;
    }

    /** {@return options with no extra listeners, the default event buffer and the pipeline's own timeout} */
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
        return new RunOptions(copy, eventBufferCapacity, timeout);
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
        return new RunOptions(listeners, capacity, timeout);
    }

    /**
     * Limits this run, overriding the pipeline's {@link Pipeline.Builder#timeout}.
     * When it expires, active stages time out, no further layer starts, and the
     * run ends {@link PipelineStatus#TIMED_OUT}.
     *
     * @param timeout a positive limit for the whole run
     * @return a copy with this timeout
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public RunOptions withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new RunOptions(listeners, eventBufferCapacity, timeout);
    }

    /** {@return the run timeout, if set; otherwise the pipeline's own applies} */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
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
