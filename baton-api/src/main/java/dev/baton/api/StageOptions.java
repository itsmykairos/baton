package dev.baton.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Per-stage execution options. Immutable; each {@code with} method returns a copy. */
public final class StageOptions {

    private static final StageOptions DEFAULTS =
            new StageOptions(null, FailurePolicy.FAIL_FAST, RetryPolicy.none(), null, null, false, null);

    private final Duration timeout;
    private final FailurePolicy failurePolicy;
    private final RetryPolicy retry;
    private final StageFallback fallback;
    private final Predicate<ContextView> condition;
    private final boolean skipIfInputsMissing;
    private final OverwritePolicy overwritePolicy;

    private StageOptions(
            Duration timeout,
            FailurePolicy failurePolicy,
            RetryPolicy retry,
            StageFallback fallback,
            Predicate<ContextView> condition,
            boolean skipIfInputsMissing,
            OverwritePolicy overwritePolicy) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        this.timeout = timeout;
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.fallback = fallback;
        this.condition = condition;
        this.skipIfInputsMissing = skipIfInputsMissing;
        this.overwritePolicy = overwritePolicy;
    }

    /**
     * {@return no timeout, {@link FailurePolicy#FAIL_FAST}, no retries, no fallback,
     * no condition, and BLOCKED when required inputs are missing}
     */
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
        return new StageOptions(
                Objects.requireNonNull(timeout, "timeout"),
                failurePolicy,
                retry,
                fallback,
                condition,
                skipIfInputsMissing,
                overwritePolicy);
    }

    /**
     * Sets the failure policy.
     *
     * @param failurePolicy what a failure or timeout of this stage means for the run
     * @return a copy with this policy
     */
    public StageOptions withFailurePolicy(FailurePolicy failurePolicy) {
        return new StageOptions(
                timeout, failurePolicy, retry, fallback, condition, skipIfInputsMissing, overwritePolicy);
    }

    /**
     * Sets how failed attempts are retried.
     *
     * @param retry the retry policy; {@link RetryPolicy#none()} for a single attempt
     * @return a copy with this policy
     */
    public StageOptions withRetry(RetryPolicy retry) {
        return new StageOptions(
                timeout,
                failurePolicy,
                Objects.requireNonNull(retry, "retry"),
                fallback,
                condition,
                skipIfInputsMissing,
                overwritePolicy);
    }

    /**
     * Sets a fallback used when the stage fails or times out after any retries.
     *
     * @param fallback supplies a replacement patch
     * @return a copy with this fallback
     * @see StageFallback
     */
    public StageOptions withFallback(StageFallback fallback) {
        return new StageOptions(
                timeout,
                failurePolicy,
                retry,
                Objects.requireNonNull(fallback, "fallback"),
                condition,
                skipIfInputsMissing,
                overwritePolicy);
    }

    /**
     * Runs the stage only when {@code condition} holds for the context it would read;
     * otherwise the stage is {@link StageStatus#SKIPPED}.
     *
     * <p>The condition is evaluated once, just before the stage would start, on its
     * own thread bounded by the enclosing run or nested-stage deadline; cancellation
     * stops it, and outliving the deadline fails the stage with
     * {@link StageTimeoutException}. It should be quick and side-effect free.
     * It is checked after {@link Stage#requiredReads()}. If it throws, the stage is
     * FAILED with that exception (no retries; the fallback and failure policy apply
     * as usual).</p>
     *
     * @param condition true to run the stage
     * @return a copy with this condition
     */
    public StageOptions withCondition(Predicate<ContextView> condition) {
        return new StageOptions(
                timeout,
                failurePolicy,
                retry,
                fallback,
                Objects.requireNonNull(condition, "condition"),
                skipIfInputsMissing,
                overwritePolicy);
    }

    /**
     * Reports the stage {@link StageStatus#SKIPPED} instead of
     * {@link StageStatus#BLOCKED} when a key from {@link Stage#requiredReads()} is
     * missing, making the stage optional in this pipeline.
     *
     * @return a copy that skips on missing inputs
     */
    public StageOptions skipIfInputsMissing() {
        return new StageOptions(timeout, failurePolicy, retry, fallback, condition, true, overwritePolicy);
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

    /**
     * Overrides the pipeline's {@link BatonConfig#overwritePolicy()} for this stage's
     * writes to keys already in the context.
     *
     * @param policy the policy for this stage
     * @return a copy with this policy
     */
    public StageOptions withOverwritePolicy(OverwritePolicy policy) {
        return new StageOptions(
                timeout,
                failurePolicy,
                retry,
                fallback,
                condition,
                skipIfInputsMissing,
                Objects.requireNonNull(policy, "policy"));
    }

    /** {@return this stage's overwrite policy, if it overrides the pipeline's} */
    public Optional<OverwritePolicy> overwritePolicy() {
        return Optional.ofNullable(overwritePolicy);
    }

    /** {@return the run condition, if any} */
    public Optional<Predicate<ContextView>> condition() {
        return Optional.ofNullable(condition);
    }

    /** {@return true if missing required inputs make the stage SKIPPED rather than BLOCKED} */
    public boolean skipsIfInputsMissing() {
        return skipIfInputsMissing;
    }

    /** {@return the fallback, if any} */
    public Optional<StageFallback> fallback() {
        return Optional.ofNullable(fallback);
    }
}
