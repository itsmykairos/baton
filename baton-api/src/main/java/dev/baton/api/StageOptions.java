package dev.baton.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Per-stage execution options. Immutable; each {@code with} method returns a copy. */
public final class StageOptions {

    private static final StageOptions DEFAULTS =
            new StageOptions(null, FailurePolicy.FAIL_FAST, RetryPolicy.none(), null);

    private final Duration timeout;
    private final FailurePolicy failurePolicy;
    private final RetryPolicy retry;
    private final StageFallback fallback;

    private StageOptions(
            Duration timeout,
            FailurePolicy failurePolicy,
            RetryPolicy retry,
            StageFallback fallback
    ) {
        if (timeout != null &&
                (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }

        this.timeout = timeout;
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.fallback = fallback;
    }

    /** {@return no timeout, {@link FailurePolicy#FAIL_FAST}, no retries and no fallback} */
    public static StageOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Sets the stage timeout.
     *
     * @param timeout a positive limit; the stage, every retry attempt and backoff
     *                wait, its child tasks and any nested pipeline it runs must
     *                finish within it
     * @return a copy with this timeout
     * @throws IllegalArgumentException if the timeout is zero or negative
     */
    public StageOptions withTimeout(Duration timeout) {
        return new StageOptions(Objects.requireNonNull(timeout, "timeout"), failurePolicy, retry, fallback);
    }

    /**
     * Sets the failure policy.
     *
     * @param failurePolicy what a failure or timeout of this stage means for the run
     * @return a copy with this policy
     */
    public StageOptions withFailurePolicy(FailurePolicy failurePolicy) {
        return new StageOptions(timeout, failurePolicy, retry, fallback);
    }

    /**
     * Sets how failed attempts are retried.
     *
     * @param retry the retry policy; {@link RetryPolicy#none()} for a single attempt
     * @return a copy with this policy
     */
    public StageOptions withRetry(RetryPolicy retry) {
        return new StageOptions(timeout, failurePolicy, Objects.requireNonNull(retry, "retry"), fallback);
    }

    /**
     * Sets a fallback used when the stage fails or times out after any retries.
     *
     * @param fallback supplies a replacement patch
     * @return a copy with this fallback
     * @see StageFallback
     */
    public StageOptions withFallback(StageFallback fallback) {
        return new StageOptions(timeout, failurePolicy, retry, Objects.requireNonNull(fallback, "fallback"));
    }

    /** {@return the timeout, if any} */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** {@return the failure policy} */
    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    /** {@return the retry policy} */
    public RetryPolicy retry() {
        return retry;
    }

    /** {@return the fallback, if any} */
    public Optional<StageFallback> fallback() {
        return Optional.ofNullable(fallback);
    }
}
