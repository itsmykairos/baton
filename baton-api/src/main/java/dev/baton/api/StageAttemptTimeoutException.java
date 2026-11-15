package dev.baton.api;

/**
 * One attempt of a stage exceeded {@link RetryPolicy#withAttemptTimeout}. Retryable
 * by default; if it is the stage's final outcome, the stage is TIMED_OUT. A kind of
 * {@link StageTimeoutException}.
 */
public final class StageAttemptTimeoutException extends StageTimeoutException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which attempt timed out and its limit
     */
    public StageAttemptTimeoutException(String message) {
        super(message);
    }
}
