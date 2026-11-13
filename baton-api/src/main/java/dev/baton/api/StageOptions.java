package dev.baton.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

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

    public static StageOptions defaults() {
        return DEFAULTS;
    }

    public StageOptions withTimeout(Duration timeout) {
        return new StageOptions(
                Objects.requireNonNull(timeout, "timeout"),
                failurePolicy
        );
    }

    public StageOptions withFailurePolicy(
            FailurePolicy failurePolicy
    ) {
        return new StageOptions(
                timeout,
                failurePolicy
        );
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }
}