package dev.baton.api;

import java.util.concurrent.TimeoutException;

/**
 * A stage ran out of time: its own {@link StageOptions#withTimeout timeout}, or the
 * deadline of the run or nested pipeline stage it belongs to, expired. Reported in
 * {@link StageResult#failure()} of a {@link StageStatus#TIMED_OUT} stage, passed to
 * its fallback, and thrown by {@link TaskHandle#join()} for a child task cut off by
 * its stage's deadline.
 *
 * <p>{@link StageAttemptTimeoutException}, for one attempt exceeding its per-attempt
 * limit, is a subtype, so catching this type covers both.</p>
 */
public class StageTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which stage timed out, and which limit expired
     */
    public StageTimeoutException(String message) {
        super(message);
    }
}
