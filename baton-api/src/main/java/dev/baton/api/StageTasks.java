package dev.baton.api;

import java.util.concurrent.Callable;

public interface StageTasks {

    /**
     * Starts a child task owned by the current stage.
     *
     * Child tasks inherit the stage's deadline and cancellation scope.
     * They cannot outlive their owning stage.
     */
    <T> TaskHandle<T> fork(Callable<T> task);
}