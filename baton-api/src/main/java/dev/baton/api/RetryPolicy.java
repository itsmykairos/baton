package dev.baton.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * How a stage retries after a failed attempt. Immutable; each {@code with} method
 * returns a copy.
 *
 * <p>The stage's {@link StageOptions#withTimeout timeout} covers every attempt and
 * every backoff wait. A retry is skipped when its backoff would not finish before
 * that deadline. {@link #withAttemptTimeout} additionally limits each attempt; an
 * attempt cut off this way fails with {@link StageAttemptTimeoutException}, which
 * is retryable by default.</p>
 *
 * <p>Backoff before attempt {@code n + 1} is
 * {@code min(initialDelay * multiplier^(n-1), maxDelay)}, reduced by up to
 * {@code jitter} of itself at random.</p>
 *
 * <pre>{@code
 * StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)
 *         .withBackoff(Duration.ofMillis(100), 2.0, Duration.ofSeconds(2))
 *         .withAttemptTimeout(Duration.ofSeconds(1))
 *         .retryOn(failure -> failure instanceof IOException));
 * }</pre>
 */
public final class RetryPolicy {

    private static final Predicate<Throwable> DEFAULT_RETRY_ON =
            failure -> failure instanceof Exception && !(failure instanceof CancellationException);

    private static final RetryPolicy NONE =
            new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0, null, DEFAULT_RETRY_ON);

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final double jitter;
    private final Duration attemptTimeout;
    private final Predicate<Throwable> retryOn;

    private RetryPolicy(
            int maxAttempts,
            Duration initialDelay,
            double multiplier,
            Duration maxDelay,
            double jitter,
            Duration attemptTimeout,
            Predicate<Throwable> retryOn) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
        this.attemptTimeout = attemptTimeout;
        this.retryOn = retryOn;
    }

    /** {@return a policy with a single attempt (no retries)} */
    public static RetryPolicy none() {
        return NONE;
    }

    /**
     * Creates a policy with up to {@code maxAttempts} attempts, an exponential
     * backoff of 100 ms doubling up to 10 s, no jitter, no per-attempt timeout,
     * and retrying on any {@link Exception} except cancellation.
     *
     * @param maxAttempts total attempts including the first; at least 1
     * @return the policy
     * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
     */
    public static RetryPolicy maxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        return new RetryPolicy(
                maxAttempts, Duration.ofMillis(100), 2.0, Duration.ofSeconds(10), 0.0, null, DEFAULT_RETRY_ON);
    }

    /**
     * Sets exponential backoff.
     *
     * @param initialDelay the wait before the second attempt; zero or positive
     * @param multiplier how much each wait grows; at least 1.0
     * @param maxDelay the longest wait; at least {@code initialDelay}
     * @return a copy with this backoff
     * @throws IllegalArgumentException if an argument is out of range
     */
    public RetryPolicy withBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative()
                || multiplier < 1.0
                || Double.isNaN(multiplier)
                || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "Require initialDelay >= 0, multiplier >= 1 and maxDelay >= initialDelay");
        }
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter, attemptTimeout, retryOn);
    }

    /**
     * Sets a fixed wait between attempts.
     *
     * @param delay the wait; zero or positive
     * @return a copy with this backoff
     */
    public RetryPolicy withFixedDelay(Duration delay) {
        return withBackoff(delay, 1.0, delay);
    }

    /**
     * Randomly shortens each wait by up to this fraction of itself, so retries
     * from many runs do not line up.
     *
     * @param jitter a fraction from 0.0 (none) to 1.0
     * @return a copy with this jitter
     * @throws IllegalArgumentException if the fraction is out of range
     */
    public RetryPolicy withJitter(double jitter) {
        if (!(jitter >= 0.0 && jitter <= 1.0)) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
        }
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter, attemptTimeout, retryOn);
    }

    /**
     * Limits each attempt. The stage's own timeout still bounds the total.
     *
     * @param timeout a positive limit per attempt
     * @return a copy with this per-attempt timeout
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public RetryPolicy withAttemptTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter, timeout, retryOn);
    }

    /**
     * Sets which failures are retried. Cancellation is never retried.
     *
     * @param retryOn true for failures worth another attempt
     * @return a copy with this predicate
     */
    public RetryPolicy retryOn(Predicate<Throwable> retryOn) {
        Objects.requireNonNull(retryOn, "retryOn");
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter, attemptTimeout, retryOn);
    }

    /** {@return the total number of attempts, including the first} */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** {@return the per-attempt timeout, if any} */
    public java.util.Optional<Duration> attemptTimeout() {
        return java.util.Optional.ofNullable(attemptTimeout);
    }

    /**
     * Decides whether a failed attempt should be retried.
     *
     * @param attempt the attempt that failed, starting at 1
     * @param failure its failure
     * @return true if another attempt is allowed and the failure is retryable
     */
    public boolean shouldRetry(int attempt, Throwable failure) {
        return attempt < maxAttempts && !(failure instanceof CancellationException) && retryOn.test(failure);
    }

    /**
     * Computes the wait after a failed attempt.
     *
     * @param attempt the attempt that failed, starting at 1
     * @return the wait before the next attempt
     */
    public Duration delayAfter(int attempt) {
        double nanos = initialDelay.toNanos() * Math.pow(multiplier, Math.max(0, attempt - 1));
        long capped = (long) Math.min(nanos, (double) maxDelay.toNanos());
        if (jitter > 0.0 && capped > 0) {
            capped -= (long) (capped * jitter * ThreadLocalRandom.current().nextDouble());
        }
        return Duration.ofNanos(capped);
    }
}
