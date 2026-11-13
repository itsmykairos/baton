
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
     * Baton Core recognizes NestedPipelineStage and executes
     * the child within the parent's execution scope.
     */
    default Stage asStage() {
        return new NestedPipelineStage(this);
    }

//    /**
//     * Adapts this pipeline as a regular stage.
//     *
//     * The child receives the parent's current context snapshot.
//     * Its changed and newly added values are returned as a patch.
//     */
//    default Stage asStage() {
//        return stageContext -> {
//
//            ContextView before = stageContext.context();
//
//            PipelineResult childResult = run(before);
//
//            if (!childResult.isSuccessful()) {
//                throw new PipelineExecutionException(childResult);
//            }
//
//            return ContextPatch.diff(
//                    before,
//                    childResult.context()
//            );
//        };
//    }

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
