package dev.baton.api;

import dev.baton.api.propagation.ContextPropagator;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;

/**
 * Settings that tune Baton's behaviour. Immutable; build one with {@link #builder()}.
 *
 * <p>Every setting is optional. An unset setting is inherited from the layer below,
 * and finally from the built-in default (each accessor documents it). Layers, from
 * lowest to highest precedence:</p>
 * <ol>
 *   <li>built-in defaults;</li>
 *   <li>the application's config, passed to {@link Pipeline#builder(BatonConfig)};</li>
 *   <li>the pipeline's config, from {@link Pipeline.Builder#config(BatonConfig)};</li>
 *   <li>the run's config, from {@link RunOptions#withConfig(BatonConfig)};</li>
 *   <li>per-stage options in {@link StageOptions}, such as
 *       {@link StageOptions#withOverwritePolicy}.</li>
 * </ol>
 *
 * <p><b>Scope in nested pipelines.</b> <em>Run-scoped</em> settings (thread factory,
 * event settings, clock, listener error handler, context propagators) come from the
 * outermost run and
 * apply to nested pipelines too. Every other setting is <em>pipeline-scoped</em>: a
 * nested pipeline uses its own config, and a run's config overlays only the
 * outermost pipeline.</p>
 *
 * <pre>{@code
 * static final BatonConfig APP = BatonConfig.builder()
 *         .contextFlattenDepth(16)
 *         .layerConflictPolicy(LayerConflictPolicy.LAST_WINS)
 *         .maxParallelStages(32)
 *         .build();
 *
 * Pipeline checkout = Pipeline.builder(APP).name("checkout") ... .build();
 * }</pre>
 */
public final class BatonConfig {

    /** {@link #contextFlattenDepth()} value meaning "never flatten". */
    public static final int NEVER_FLATTEN = 0;
    /** {@link #maxParallelStages()} and {@link #maxChildTasks()} value meaning "no limit". */
    public static final int UNLIMITED = 0;
    /** The default event queue capacity. */
    public static final int DEFAULT_EVENT_BUFFER_CAPACITY = 8_192;
    /** The default event batch interval. */
    public static final Duration DEFAULT_EVENT_BATCH_INTERVAL = Duration.ofMillis(10);
    /** The default pipeline name. */
    public static final String DEFAULT_PIPELINE_NAME = "pipeline";

    private static final BatonConfig DEFAULTS = new BatonConfig(new Builder());

    private final Integer contextFlattenDepth;
    private final LayerConflictPolicy layerConflictPolicy;
    private final OverwritePolicy overwritePolicy;
    private final TypeClashPolicy typeClashPolicy;
    private final Boolean equalValuesConflict;
    private final ThreadFactory threadFactory;
    private final Integer maxParallelStages;
    private final Integer maxChildTasks;
    private final StageOptions defaultStageOptions;
    private final UnjoinedChildFailurePolicy unjoinedChildFailure;
    private final Duration runTimeout;
    private final Integer eventBufferCapacity;
    private final Duration eventBatchInterval;
    private final EventOverflowPolicy eventOverflowPolicy;
    private final ListenerErrorHandler listenerErrorHandler;
    private final Boolean notifyNestedListeners;
    private final Clock clock;
    private final String defaultPipelineName;
    private final List<ContextPropagator<?>> contextPropagators;

    private BatonConfig(Builder builder) {
        contextFlattenDepth = builder.contextFlattenDepth;
        layerConflictPolicy = builder.layerConflictPolicy;
        overwritePolicy = builder.overwritePolicy;
        typeClashPolicy = builder.typeClashPolicy;
        equalValuesConflict = builder.equalValuesConflict;
        threadFactory = builder.threadFactory;
        maxParallelStages = builder.maxParallelStages;
        maxChildTasks = builder.maxChildTasks;
        defaultStageOptions = builder.defaultStageOptions;
        unjoinedChildFailure = builder.unjoinedChildFailure;
        runTimeout = builder.runTimeout;
        eventBufferCapacity = builder.eventBufferCapacity;
        eventBatchInterval = builder.eventBatchInterval;
        eventOverflowPolicy = builder.eventOverflowPolicy;
        listenerErrorHandler = builder.listenerErrorHandler;
        notifyNestedListeners = builder.notifyNestedListeners;
        clock = builder.clock;
        defaultPipelineName = builder.defaultPipelineName;
        contextPropagators = List.copyOf(builder.contextPropagators);
    }

    /** {@return a config that sets nothing, so every setting has its built-in default} */
    public static BatonConfig defaults() {
        return DEFAULTS;
    }

    /** {@return a builder that sets nothing} */
    public static Builder builder() {
        return new Builder();
    }

    /** {@return a builder starting from this config's settings} */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Overlays another config: each setting {@code overrides} sets replaces this one's.
     *
     * @param overrides the higher-precedence config
     * @return the combined config
     */
    public BatonConfig merge(BatonConfig overrides) {
        Objects.requireNonNull(overrides, "overrides");
        if (overrides == DEFAULTS) {
            return this;
        }
        Builder merged = new Builder(this);
        merged.contextFlattenDepth = pick(overrides.contextFlattenDepth, contextFlattenDepth);
        merged.layerConflictPolicy = pick(overrides.layerConflictPolicy, layerConflictPolicy);
        merged.overwritePolicy = pick(overrides.overwritePolicy, overwritePolicy);
        merged.typeClashPolicy = pick(overrides.typeClashPolicy, typeClashPolicy);
        merged.equalValuesConflict = pick(overrides.equalValuesConflict, equalValuesConflict);
        merged.threadFactory = pick(overrides.threadFactory, threadFactory);
        merged.maxParallelStages = pick(overrides.maxParallelStages, maxParallelStages);
        merged.maxChildTasks = pick(overrides.maxChildTasks, maxChildTasks);
        merged.defaultStageOptions = pick(overrides.defaultStageOptions, defaultStageOptions);
        merged.unjoinedChildFailure = pick(overrides.unjoinedChildFailure, unjoinedChildFailure);
        merged.runTimeout = pick(overrides.runTimeout, runTimeout);
        merged.eventBufferCapacity = pick(overrides.eventBufferCapacity, eventBufferCapacity);
        merged.eventBatchInterval = pick(overrides.eventBatchInterval, eventBatchInterval);
        merged.eventOverflowPolicy = pick(overrides.eventOverflowPolicy, eventOverflowPolicy);
        merged.listenerErrorHandler = pick(overrides.listenerErrorHandler, listenerErrorHandler);
        merged.notifyNestedListeners = pick(overrides.notifyNestedListeners, notifyNestedListeners);
        merged.clock = pick(overrides.clock, clock);
        merged.defaultPipelineName = pick(overrides.defaultPipelineName, defaultPipelineName);
        // Propagators accumulate across layers rather than replace each other.
        merged.contextPropagators.addAll(overrides.contextPropagators);
        return new BatonConfig(merged);
    }

    private static <T> T pick(T override, T base) {
        return override != null ? override : base;
    }

    // ---- context and conflicts (pipeline-scoped) -------------------------------------

    /**
     * How many levels a context may stack before it is flattened into one.
     *
     * <p>Each layer's patch adds one level holding only its writes, so applying a patch
     * costs only its own writes. A read walks the levels from newest to oldest. With
     * depth N, once the chain would exceed N levels the next patch copies every value
     * into a single level: a full copy every N layers, and reads that check at most N
     * levels. With {@link #NEVER_FLATTEN} nothing is ever copied, but a read can check
     * one level per layer applied so far. See {@code docs/configuration.md} for
     * guidance.</p>
     *
     * @return the depth; default {@link #NEVER_FLATTEN}
     */
    public int contextFlattenDepth() {
        return contextFlattenDepth != null ? contextFlattenDepth : NEVER_FLATTEN;
    }

    /** {@return the same-layer conflict policy; default {@link LayerConflictPolicy#FAIL}} */
    public LayerConflictPolicy layerConflictPolicy() {
        return layerConflictPolicy != null ? layerConflictPolicy : LayerConflictPolicy.FAIL;
    }

    /** {@return the policy for writes to existing keys; default {@link OverwritePolicy#OVERRIDE}} */
    public OverwritePolicy overwritePolicy() {
        return overwritePolicy != null ? overwritePolicy : OverwritePolicy.OVERRIDE;
    }

    /** {@return the policy for a name written with another type; default {@link TypeClashPolicy#FAIL}} */
    public TypeClashPolicy typeClashPolicy() {
        return typeClashPolicy != null ? typeClashPolicy : TypeClashPolicy.FAIL;
    }

    /**
     * {@return whether stages of one layer writing equal values for a key conflict;
     * default true. When false, equal writes are merged into one write}
     */
    public boolean equalValuesConflict() {
        return equalValuesConflict == null || equalValuesConflict;
    }

    // ---- execution ---------------------------------------------------------------------

    /**
     * {@return the factory for stage and child-task threads (run-scoped); default
     * virtual threads. Each run creates its own thread-per-task executor from it}
     */
    public ThreadFactory threadFactory() {
        return threadFactory != null ? threadFactory : Thread.ofVirtual().name("baton-stage").factory();
    }

    /**
     * {@return how many stages of one parallel layer may run at once; default
     * {@link #UNLIMITED}. Waiting stages start as running ones finish, and a stage's
     * timeout starts when it starts}
     */
    public int maxParallelStages() {
        return maxParallelStages != null ? maxParallelStages : UNLIMITED;
    }

    /**
     * {@return how many child tasks of one stage may run at once; default
     * {@link #UNLIMITED}. Forking never blocks: a forked task waits for a free slot
     * before running, within the stage's deadline}
     */
    public int maxChildTasks() {
        return maxChildTasks != null ? maxChildTasks : UNLIMITED;
    }

    /**
     * {@return the options for stages added without explicit {@link StageOptions};
     * default {@link StageOptions#defaults()}}
     */
    public StageOptions defaultStageOptions() {
        return defaultStageOptions != null ? defaultStageOptions : StageOptions.defaults();
    }

    /** {@return what an unjoined, failed child task does; default {@link UnjoinedChildFailurePolicy#FAIL_STAGE}} */
    public UnjoinedChildFailurePolicy unjoinedChildFailure() {
        return unjoinedChildFailure != null ? unjoinedChildFailure : UnjoinedChildFailurePolicy.FAIL_STAGE;
    }

    /** {@return the timeout for a whole run, if any; default none} */
    public Optional<Duration> runTimeout() {
        return Optional.ofNullable(runTimeout);
    }

    // ---- events (run-scoped) ---------------------------------------------------------

    /** {@return how many undelivered events a run may queue; default {@value #DEFAULT_EVENT_BUFFER_CAPACITY}} */
    public int eventBufferCapacity() {
        return eventBufferCapacity != null ? eventBufferCapacity : DEFAULT_EVENT_BUFFER_CAPACITY;
    }

    /** {@return the longest delay before queued events are delivered during a run; default 10 ms} */
    public Duration eventBatchInterval() {
        return eventBatchInterval != null ? eventBatchInterval : DEFAULT_EVENT_BATCH_INTERVAL;
    }

    /** {@return which event is dropped when the queue is full; default {@link EventOverflowPolicy#DROP_NEWEST}} */
    public EventOverflowPolicy eventOverflowPolicy() {
        return eventOverflowPolicy != null ? eventOverflowPolicy : EventOverflowPolicy.DROP_NEWEST;
    }

    /** {@return the handler for listener exceptions; default {@link ListenerErrorHandler#logging()}} */
    public ListenerErrorHandler listenerErrorHandler() {
        return listenerErrorHandler != null ? listenerErrorHandler : ListenerErrorHandler.logging();
    }

    /**
     * {@return whether a nested pipeline's own builder listeners also receive its
     * events, with paths relative to it and its own run id; default false}
     */
    public boolean notifyNestedListeners() {
        return notifyNestedListeners != null && notifyNestedListeners;
    }

    /** {@return the clock for event timestamps; default the system UTC clock. Durations always use {@code nanoTime}} */
    public Clock clock() {
        return clock != null ? clock : Clock.systemUTC();
    }

    /**
     * {@return the context propagators (run-scoped), in registration order; default
     * none. Unlike other settings they accumulate across layers: app, pipeline and
     * run propagators all apply}
     */
    public List<ContextPropagator<?>> contextPropagators() {
        return contextPropagators;
    }

    /** {@return the name of pipelines built without {@link Pipeline.Builder#name}; default "pipeline"} */
    public String defaultPipelineName() {
        return defaultPipelineName != null ? defaultPipelineName : DEFAULT_PIPELINE_NAME;
    }

    /** Builds a {@link BatonConfig}. Each method sets one setting; unset settings are inherited. */
    public static final class Builder {

        private Integer contextFlattenDepth;
        private LayerConflictPolicy layerConflictPolicy;
        private OverwritePolicy overwritePolicy;
        private TypeClashPolicy typeClashPolicy;
        private Boolean equalValuesConflict;
        private ThreadFactory threadFactory;
        private Integer maxParallelStages;
        private Integer maxChildTasks;
        private StageOptions defaultStageOptions;
        private UnjoinedChildFailurePolicy unjoinedChildFailure;
        private Duration runTimeout;
        private Integer eventBufferCapacity;
        private Duration eventBatchInterval;
        private EventOverflowPolicy eventOverflowPolicy;
        private ListenerErrorHandler listenerErrorHandler;
        private Boolean notifyNestedListeners;
        private Clock clock;
        private String defaultPipelineName;
        private final List<ContextPropagator<?>> contextPropagators = new ArrayList<>();

        private Builder() {
        }

        private Builder(BatonConfig config) {
            contextFlattenDepth = config.contextFlattenDepth;
            layerConflictPolicy = config.layerConflictPolicy;
            overwritePolicy = config.overwritePolicy;
            typeClashPolicy = config.typeClashPolicy;
            equalValuesConflict = config.equalValuesConflict;
            threadFactory = config.threadFactory;
            maxParallelStages = config.maxParallelStages;
            maxChildTasks = config.maxChildTasks;
            defaultStageOptions = config.defaultStageOptions;
            unjoinedChildFailure = config.unjoinedChildFailure;
            runTimeout = config.runTimeout;
            eventBufferCapacity = config.eventBufferCapacity;
            eventBatchInterval = config.eventBatchInterval;
            eventOverflowPolicy = config.eventOverflowPolicy;
            listenerErrorHandler = config.listenerErrorHandler;
            notifyNestedListeners = config.notifyNestedListeners;
            clock = config.clock;
            defaultPipelineName = config.defaultPipelineName;
            contextPropagators.addAll(config.contextPropagators);
        }

        /**
         * Sets the context flatten depth.
         *
         * @param depth {@link #NEVER_FLATTEN} or a positive number of levels
         * @return this builder
         * @throws IllegalArgumentException if the depth is negative
         * @see BatonConfig#contextFlattenDepth()
         */
        public Builder contextFlattenDepth(int depth) {
            if (depth < 0) {
                throw new IllegalArgumentException("depth must be 0 (never flatten) or positive");
            }
            this.contextFlattenDepth = depth;
            return this;
        }

        /**
         * Sets the same-layer conflict policy.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder layerConflictPolicy(LayerConflictPolicy policy) {
            this.layerConflictPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the policy for writes to keys already in the context.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder overwritePolicy(OverwritePolicy policy) {
            this.overwritePolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the policy for a name written with another type.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder typeClashPolicy(TypeClashPolicy policy) {
            this.typeClashPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets whether equal same-layer writes conflict.
         *
         * @param conflict false to merge equal writes into one
         * @return this builder
         */
        public Builder equalValuesConflict(boolean conflict) {
            this.equalValuesConflict = conflict;
            return this;
        }

        /**
         * Sets the thread factory for stage and child-task threads.
         *
         * @param factory the factory; for example {@code Thread.ofVirtual().name("checkout-", 0).factory()}
         * @return this builder
         */
        public Builder threadFactory(ThreadFactory factory) {
            this.threadFactory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /**
         * Limits how many stages of one parallel layer run at once.
         *
         * @param limit {@link #UNLIMITED} or a positive number
         * @return this builder
         * @throws IllegalArgumentException if the limit is negative
         */
        public Builder maxParallelStages(int limit) {
            this.maxParallelStages = requireLimit(limit);
            return this;
        }

        /**
         * Limits how many child tasks of one stage run at once.
         *
         * @param limit {@link #UNLIMITED} or a positive number
         * @return this builder
         * @throws IllegalArgumentException if the limit is negative
         */
        public Builder maxChildTasks(int limit) {
            this.maxChildTasks = requireLimit(limit);
            return this;
        }

        /**
         * Sets the options for stages added without explicit options.
         *
         * @param options the default options
         * @return this builder
         */
        public Builder defaultStageOptions(StageOptions options) {
            this.defaultStageOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Sets what an unjoined, failed child task does.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder unjoinedChildFailure(UnjoinedChildFailurePolicy policy) {
            this.unjoinedChildFailure = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the timeout for a whole run.
         *
         * @param timeout a positive limit
         * @return this builder
         * @throws IllegalArgumentException if the timeout is not positive
         */
        public Builder runTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.runTimeout = timeout;
            return this;
        }

        /**
         * Sets the event queue capacity.
         *
         * @param capacity a positive number of events
         * @return this builder
         * @throws IllegalArgumentException if the capacity is not positive
         */
        public Builder eventBufferCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.eventBufferCapacity = capacity;
            return this;
        }

        /**
         * Sets the longest delay before queued events are delivered during a run.
         *
         * @param interval a positive duration
         * @return this builder
         * @throws IllegalArgumentException if the interval is not positive
         */
        public Builder eventBatchInterval(Duration interval) {
            Objects.requireNonNull(interval, "interval");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
            this.eventBatchInterval = interval;
            return this;
        }

        /**
         * Sets which event is dropped when the queue is full.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder eventOverflowPolicy(EventOverflowPolicy policy) {
            this.eventOverflowPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the handler for listener exceptions.
         *
         * @param handler the handler
         * @return this builder
         */
        public Builder listenerErrorHandler(ListenerErrorHandler handler) {
            this.listenerErrorHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Sets whether nested pipelines' own listeners also receive their events.
         *
         * @param notify true to notify them
         * @return this builder
         */
        public Builder notifyNestedListeners(boolean notify) {
            this.notifyNestedListeners = notify;
            return this;
        }

        /**
         * Sets the clock for event timestamps.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the name of pipelines built without a name.
         *
         * @param name a non-blank name
         * @return this builder
         * @throws IllegalArgumentException if the name is blank
         */
        public Builder defaultPipelineName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            this.defaultPipelineName = name;
            return this;
        }

        /**
         * Adds a context propagator. Propagators restore in registration order and
         * undo in reverse order.
         *
         * @param propagator the propagator
         * @return this builder
         * @see ContextPropagator
         */
        public Builder contextPropagator(ContextPropagator<?> propagator) {
            contextPropagators.add(Objects.requireNonNull(propagator, "propagator"));
            return this;
        }

        /** {@return the immutable config} */
        public BatonConfig build() {
            return new BatonConfig(this);
        }

        private static int requireLimit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be 0 (unlimited) or positive");
            }
            return limit;
        }
    }
}
