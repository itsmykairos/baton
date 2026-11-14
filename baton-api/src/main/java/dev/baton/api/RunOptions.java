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
    public static final int DEFAULT_EVENT_BUFFER_CAPACITY = BatonConfig.DEFAULT_EVENT_BUFFER_CAPACITY;

    private static final RunOptions DEFAULTS = new RunOptions(List.of(), BatonConfig.defaults());

    private final List<PipelineListener> listeners;
    private final BatonConfig config;

    private RunOptions(List<PipelineListener> listeners, BatonConfig config) {
        this.listeners = List.copyOf(listeners);
        this.config = config;
    }

    /** {@return options with no extra listeners and no config overrides} */
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
        return new RunOptions(copy, config);
    }

    /**
     * Overlays settings for this run. They take precedence over the pipeline's config;
     * pipeline-scoped settings apply to the outermost pipeline only.
     *
     * @param overrides the settings to overlay
     * @return a copy with the settings overlaid on any set before
     * @see BatonConfig
     */
    public RunOptions withConfig(BatonConfig overrides) {
        return new RunOptions(listeners, config.merge(Objects.requireNonNull(overrides, "overrides")));
    }

    /**
     * Sets how many undelivered events this run may queue; shortcut for
     * {@link BatonConfig.Builder#eventBufferCapacity}.
     *
     * @param capacity a positive number of events
     * @return a copy with this capacity
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public RunOptions withEventBufferCapacity(int capacity) {
        return withConfig(BatonConfig.builder().eventBufferCapacity(capacity).build());
    }

    /**
     * Limits this run, overriding the pipeline's {@link Pipeline.Builder#timeout};
     * shortcut for {@link BatonConfig.Builder#runTimeout}. When it expires, active
     * stages time out, no further layer starts, and the run ends
     * {@link PipelineStatus#TIMED_OUT}.
     *
     * @param timeout a positive limit for the whole run
     * @return a copy with this timeout
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public RunOptions withTimeout(Duration timeout) {
        return withConfig(BatonConfig.builder().runTimeout(timeout).build());
    }

    /** {@return the run timeout, if set here; otherwise the pipeline's own applies} */
    public Optional<Duration> timeout() {
        return config.runTimeout();
    }

    /** {@return the run-only listeners} */
    public List<PipelineListener> listeners() {
        return listeners;
    }

    /** {@return the event queue capacity set here, or the default} */
    public int eventBufferCapacity() {
        return config.eventBufferCapacity();
    }

    /** {@return the settings overlaid for this run} */
    public BatonConfig config() {
        return config;
    }
}
