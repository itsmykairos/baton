package dev.baton.api;

public interface TaskHandle<T> {

    /**
     * Waits for task completion and returns its result.
     */
    T join() throws Exception;

    boolean isDone();
}