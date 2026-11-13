
package dev.baton.api;

import java.util.function.Consumer;

public interface Pipeline {

    /**
     * Executes synchronously.
     */
    PipelineResult run(ContextView input)
            throws InterruptedException;

    /**
     * Starts asynchronous execution.
     */
    PipelineRun start(ContextView input);

    /**
     * Adapts this pipeline as a regular stage.
     *
     * <p>Baton Core runs a core pipeline adapted this way inside the parent run,
     * inheriting its executor, deadline and cancellation; see
     * {@link NestedPipelineStage} for the outcome rules.</p>
     */
    default Stage asStage() {
        return new NestedPipelineStage(this);
    }

    static Builder builder() {
        return PipelineProvider.load().builder();
    }

    interface Builder {

        /**
         * Adds a sequential stage using default options.
         */
        Builder stage(String id, Stage stage);

        /**
         * Adds a sequential stage with explicit options.
         */
        Builder stage(
                String id,
                Stage stage,
                StageOptions options
        );

        /**
         * Defines a parallel execution layer.
         */
        Builder parallel(
                String id,
                Consumer<ParallelBuilder> definition
        );

        /**
         * Builds the immutable pipeline definition.
         */
        Pipeline build();
    }

    /**
     * Builder for stages belonging to one parallel layer.
     */
    interface ParallelBuilder {

        /**
         * Adds a stage to this parallel layer using default options.
         */
        ParallelBuilder stage(String id, Stage stage);

        /**
         * Adds a stage to this parallel layer with explicit options.
         */
        ParallelBuilder stage(
                String id,
                Stage stage,
                StageOptions options
        );
    }
}
