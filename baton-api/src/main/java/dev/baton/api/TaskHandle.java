package dev.baton.api;

/**
 * A child task forked with {@link StageTasks#fork}.
 *
 * @param <T> the task's result type
 */
public interface TaskHandle<T> {

    /**
     * Waits for the task and returns its result. Joining also marks the task's
     * failure as observed, so it does not fail the stage when it returns.
     *
     * @return the task's result
     * @throws Exception the task's own exception, or an exception describing its
     *         cancellation or deadline expiry
     */
    T join() throws Exception;

    /** {@return true once the task has an outcome} */
    boolean isDone();
}
