package dev.baton.api;

public interface StageContext {

    /**
     * Immutable snapshot of context available to this stage.
     */
    ContextView context();

    /**
     * Stage-owned child task scope.
     */
    StageTasks tasks();
}