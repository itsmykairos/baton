package dev.baton.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Per-stage execution options. Immutable; each {@code with} method returns a copy. */
public final class StageOptions {

    private static final StageOptions DEFAULTS =
            new StageOptions(null, FailurePolicy.FAIL_FAST);

    private final Duration timeout;
    private final FailurePolicy failurePolicy;

    private StageOptions(
            Duration timeout,
            FailurePolicy failurePolicy
    ) {
        if (timeout != null &&
                (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }

        this.timeout = timeout;
        this.failurePolicy = Objects.requireNonNull(
                failurePolicy,
                "failurePolicy"
        );
    }

    /** {@return no timeout and {@link FailurePolicy#FAIL_FAST}} */
    public static StageOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Sets the stage timeout.
     *
     * @param timeout a positive limit; the stage, its child tasks and any nested
     *                pipeline it runs must finish within it
     * @return a copy with this timeout
     * @throws IllegalArgumentException if the timeout is zero or negative
     */
    public StageOptions withTimeout(Duration timeout) {
        return new StageOptions(
                Objects.requireNonNull(timeout, "timeout"),
                failurePolicy
        );
    }

    /**
     * Sets the failure policy.
     *
     * @param failurePolicy what a failure or timeout of this stage means for the run
     * @return a copy with this policy
     */
    public StageOptions withFailurePolicy(
            FailurePolicy failurePolicy
    ) {
        return new StageOptions(
                timeout,
                failurePolicy
        );
    }

    /** {@return the timeout, if any} */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** {@return the failure policy} */
    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }
}