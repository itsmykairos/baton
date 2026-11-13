package dev.baton.api;

import java.util.concurrent.Callable;

public interface StageTasks {

    /**
     * Starts a child task owned by the current stage.
     *
     * Child tasks inherit the stage's deadline and cancellation scope.
     * They cannot outlive their owning stage: when the stage returns, unfinished
     * children are cancelled. If a child already failed and the stage never called
     * {@link TaskHandle#join()} on it, the stage fails with that child's exception.
     */
    <T> TaskHandle<T> fork(Callable<T> task);
}