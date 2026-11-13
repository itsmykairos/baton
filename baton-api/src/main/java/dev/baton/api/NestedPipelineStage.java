package dev.baton.api;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Adapter representing a pipeline used as a stage.
 *
 * <p>Baton Core recognises this adapter when it wraps a core pipeline and runs
 * the child inside the parent run: on the parent's executor, with the nested
 * stage's deadline (its own timeout, capped by any enclosing deadline), and with
 * cancellation reaching every child stage. The child's stage results are reported
 * through {@link StageResult#children()}.</p>
 *
 * <p>Outcome rules, in core and in {@link #execute(StageContext)}:</p>
 * <ul>
 *   <li>Child {@link PipelineStatus#COMPLETED}: the stage succeeds, even if some
 *       child stages were BLOCKED or failed under {@link FailurePolicy#CONTINUE}.
 *       Its patch contains every key the child wrote, with its final value, even
 *       when that value equals the input value.</li>
 *   <li>Child FAILED or TIMED_OUT: the stage fails with a
 *       {@link PipelineExecutionException}.</li>
 *   <li>Child CANCELLED: the stage is cancelled.</li>
 * </ul>
 *
 * <p>{@link #execute(StageContext)} is the standalone behaviour, used for other
 * {@link Pipeline} implementations or when the stage is invoked directly. It runs
 * the child with {@link Pipeline#run(ContextView)}.</p>
 */
public final class NestedPipelineStage implements Stage {

    private final Pipeline pipeline;

    NestedPipelineStage(Pipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Returns the pipeline represented by this stage.
     */
    public Pipeline pipeline() {
        return pipeline;
    }

    @Override
    public ContextPatch execute(StageContext stageContext) throws Exception {
        PipelineResult childResult = pipeline.run(stageContext.context());

        return switch (childResult.status()) {
            case COMPLETED -> writtenBy(childResult);
            case CANCELLED -> throw new CancellationException("Nested pipeline was cancelled");
            case FAILED, TIMED_OUT -> throw new PipelineExecutionException(childResult);
        };
    }

    /** Keys written by the child's applied (SUCCEEDED) stage outputs, with final values. */
    private static ContextPatch writtenBy(PipelineResult result) {
        ContextPatch.Builder builder = ContextPatch.builder();
        java.util.Set<ContextKey<?>> keys = new java.util.LinkedHashSet<>();
        for (StageResult stage : result.stages()) {
            if (stage.status() == StageStatus.SUCCEEDED) {
                stage.output().ifPresent(patch -> keys.addAll(patch.writtenKeys()));
            }
        }
        for (ContextKey<?> key : keys) {
            putFinal(builder, key, result.context());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void putFinal(ContextPatch.Builder builder, ContextKey<?> key, ContextView context) {
        ContextKey<Object> untyped = (ContextKey<Object>) key;
        context.find(untyped).ifPresent(value -> builder.put(untyped, value));
    }
}
