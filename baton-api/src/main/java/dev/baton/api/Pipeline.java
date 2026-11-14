package dev.baton.api;

import dev.baton.api.event.PipelineListener;
import java.util.function.Consumer;

/**
 * An immutable, reusable pipeline of stages arranged in layers. Each layer is one
 * sequential stage or a group of parallel stages; layers run in order.
 */
public interface Pipeline {

    /**
     * Executes synchronously with default {@link RunOptions}.
     *
     * @param input the initial context
     * @return the terminal result
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    default PipelineResult run(ContextView input) throws InterruptedException {
        return run(input, RunOptions.defaults());
    }

    /**
     * Executes synchronously.
     *
     * @param input the initial context
     * @param options per-run options, such as extra listeners
     * @return the terminal result
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    PipelineResult run(ContextView input, RunOptions options) throws InterruptedException;

    /**
     * Starts asynchronous execution on virtual threads with default {@link RunOptions}.
     *
     * @param input the initial context
     * @return a handle for awaiting or cancelling the run
     */
    default PipelineRun start(ContextView input) {
        return start(input, RunOptions.defaults());
    }

    /**
     * Starts asynchronous execution on virtual threads.
     *
     * @param input the initial context
     * @param options per-run options, such as extra listeners
     * @return a handle for awaiting or cancelling the run
     */
    PipelineRun start(ContextView input, RunOptions options);

    /**
     * Adapts this pipeline as a regular stage.
     *
     * <p>Baton Core runs a core pipeline adapted this way inside the parent run,
     * inheriting its executor, deadline and cancellation; see
     * {@link NestedPipelineStage} for the outcome rules.</p>
     *
     * @return a stage that runs this pipeline
     */
    default Stage asStage() {
        return new NestedPipelineStage(this);
    }

    /**
     * Describes this pipeline's structure: its layers and stages with their resolved
     * options, required inputs and nested pipelines. Render it as a diagram with the
     * optional {@code baton-visualize} module.
     *
     * @return the description
     * @throws UnsupportedOperationException if this implementation cannot describe
     *         itself; the built-in runtime always can
     */
    default dev.baton.api.description.PipelineDescription describe() {
        throw new UnsupportedOperationException(getClass().getName() + " cannot describe its structure");
    }

    /**
     * Creates a builder from the runtime found by {@link PipelineProvider#load()}.
     *
     * @return a new builder
     * @throws IllegalStateException if no runtime is available
     */
    static Builder builder() {
        return PipelineProvider.load().builder();
    }

    /**
     * Creates a builder whose pipeline starts from the application's settings.
     * Keep one app-wide config and pass it to every builder.
     *
     * @param appConfig the application's settings; the builder's own
     *                  {@link Builder#config} overlays them
     * @return a new builder
     * @throws IllegalStateException if no runtime is available
     * @see BatonConfig
     */
    static Builder builder(BatonConfig appConfig) {
        return builder().config(java.util.Objects.requireNonNull(appConfig, "appConfig"));
    }

    /** Builds a {@link Pipeline}. Stage and layer ids must be unique and non-blank. */
    interface Builder {

        /**
         * Names the pipeline. The name is carried by every event and is meant for
         * metrics and traces; it need not be unique. Defaults to {@code "pipeline"}.
         *
         * @param name a non-blank name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Overlays settings for this pipeline on those set so far (for example the
         * app config from {@link Pipeline#builder(BatonConfig)}). May be called more
         * than once; later calls take precedence. Settings are resolved at
         * {@link #build()}, so the order relative to stages does not matter.
         *
         * @param overrides the settings to overlay
         * @return this builder
         * @see BatonConfig
         */
        Builder config(BatonConfig overrides);

        /**
         * Adds a listener that receives the events of every run of this pipeline.
         * When this pipeline runs nested inside another, its events go to the
         * outermost run's listeners instead.
         *
         * @param listener the listener
         * @return this builder
         * @see PipelineListener
         */
        Builder listener(PipelineListener listener);

        /**
         * Limits every run of this pipeline; {@link RunOptions#withTimeout} overrides
         * it for one run. Shortcut for {@link BatonConfig.Builder#runTimeout}. When it expires, active stages time out, no further layer
         * starts, and the run ends {@link PipelineStatus#TIMED_OUT}. When this
         * pipeline runs nested, its timeout is also capped by the nested stage's
         * deadline.
         *
         * @param timeout a positive limit for a whole run
         * @return this builder
         */
        Builder timeout(java.time.Duration timeout);

        /**
         * Adds a sequential stage using the pipeline's
         * {@link BatonConfig#defaultStageOptions() default stage options}.
         *
         * @param id unique stage id
         * @param stage the stage
         * @return this builder
         */
        Builder stage(String id, Stage stage);

        /**
         * Adds a sequential stage with explicit options.
         *
         * @param id unique stage id
         * @param stage the stage
         * @param options timeout and failure policy
         * @return this builder
         */
        Builder stage(String id, Stage stage, StageOptions options);

        /**
         * Defines a parallel execution layer. Its stages all read the same context
         * snapshot; their patches are merged when every stage has finished.
         *
         * @param id unique layer id
         * @param definition adds at least one stage to the layer
         * @return this builder
         */
        Builder parallel(String id, Consumer<ParallelBuilder> definition);

        /**
         * Builds the immutable pipeline definition. The builder cannot be used again.
         *
         * @return the pipeline
         * @throws IllegalStateException if no stage was added, or the builder was already used
         */
        Pipeline build();
    }

    /**
     * Builder for stages belonging to one parallel layer.
     */
    interface ParallelBuilder {

        /**
         * Adds a stage to this parallel layer using the pipeline's
         * {@link BatonConfig#defaultStageOptions() default stage options}.
         *
         * @param id unique stage id
         * @param stage the stage
         * @return this builder
         */
        ParallelBuilder stage(String id, Stage stage);

        /**
         * Adds a stage to this parallel layer with explicit options.
         *
         * @param id unique stage id
         * @param stage the stage
         * @param options timeout and failure policy
         * @return this builder
         */
        ParallelBuilder stage(String id, Stage stage, StageOptions options);
    }
}
