
package dev.baton.api;

import java.util.Objects;

/**
 * Adapter representing a pipeline used as a stage.
 *
 * Baton Core recognizes this adapter and executes the nested
 * pipeline within the parent execution scope.
 *
 * The execute method provides standalone behavior for consumers
 * that invoke the stage directly.
 */
public final class NestedPipelineStage implements Stage {

    private final Pipeline pipeline;

    NestedPipelineStage(Pipeline pipeline) {
        this.pipeline = Objects.requireNonNull(
                pipeline,
                "pipeline"
        );
    }

    /**
     * Returns the pipeline represented by this stage.
     */
    public Pipeline pipeline() {
        return pipeline;
    }

    @Override
    public ContextPatch execute(StageContext stageContext)
            throws Exception {

        ContextView before = stageContext.context();

        PipelineResult childResult = pipeline.run(before);

        if (!childResult.isSuccessful()) {
            throw new PipelineExecutionException(childResult);
        }

        return ContextPatch.diff(
                before,
                childResult.context()
        );
    }
}
