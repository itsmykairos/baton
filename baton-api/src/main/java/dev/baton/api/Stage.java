package dev.baton.api;

import java.util.Set;

public interface Stage {

    ContextPatch execute(StageContext context) throws Exception;

    /**
     * Context keys that must be available before this stage executes.
     */
    default Set<ContextKey<?>> requiredReads() {
        return Set.of();
    }
}