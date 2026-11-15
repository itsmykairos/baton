package dev.baton.api;

import java.util.Set;

/** A unit of work in a pipeline. Implementations should be stateless and thread-safe. */
@FunctionalInterface
public interface Stage {

    /**
     * Runs the stage.
     *
     * @param context the context snapshot and child-task scope for this run
     * @return the writes to apply; never null ({@link ContextPatch#empty()} for none)
     * @throws Exception any failure; the stage is reported FAILED
     */
    ContextPatch execute(StageContext context) throws Exception;

    /**
     * Context keys that must be available before this stage executes.
     *
     * <p>Read once when the pipeline is built; the result must be non-null and contain
     * no null keys. Whether the keys are present is checked at run time, immediately
     * before the stage would start, not at build time. A missing key makes the stage
     * {@link StageStatus#BLOCKED}, or {@link StageStatus#SKIPPED} with
     * {@link StageOptions#skipIfInputsMissing()}.</p>
     */
    /** {@return the required keys; empty by default} */
    default Set<ContextKey<?>> requiredReads() {
        return Set.of();
    }
}
