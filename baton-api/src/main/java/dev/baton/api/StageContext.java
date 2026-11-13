package dev.baton.api;

/** What a running stage can see and use. */
public interface StageContext {

    /** {@return the immutable context snapshot available to this stage} */
    ContextView context();

    /** {@return the stage-owned scope for forking child tasks} */
    StageTasks tasks();
}
