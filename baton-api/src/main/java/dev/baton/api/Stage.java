package dev.baton.api;

import java.util.Set;

public interface Stage {

    ContextPatch execute(StageContext context) throws Exception;

    /**
     * Context keys that must be available before this stage executes.
     *
     * <p>Checked at run time, immediately before the stage would start, not when
     * the pipeline is built. A missing key makes the stage
     * {@link StageStatus#BLOCKED}.</p>
     */
    default Set<ContextKey<?>> requiredReads() {
        return Set.of();
    }
}