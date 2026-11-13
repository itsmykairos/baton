package dev.baton.core.deadline;

import java.time.Duration;
import java.util.Objects;

/**
 * An absolute monotonic deadline.
 *
 * <p>Deadlines are based on {@link System#nanoTime()} and must only be used
 * for measuring elapsed time. They are not wall-clock timestamps.</p>
 */
public final class Deadline {

    private static final Deadline UNLIMITED = new Deadline(true, 0L);

    private final boolean unlimited;
    private final long deadlineNanos;

    private Deadline(boolean unlimited, long deadlineNanos) {
        this.unlimited = unlimited;
        this.deadlineNanos = deadlineNanos;
    }

    /** Returns a deadline that never expires. */
    public static Deadline unlimited() {
        return UNLIMITED;
    }

    /**
     * Creates a deadline relative to the current monotonic time.
     *
     * <p>Durations larger than the representable {@code nanoTime} interval
     * are capped at the largest positive interval. This avoids overflow and
     * is effectively unlimited for any practical pipeline execution.</p>
     */
    public static Deadline after(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }

        long timeoutNanos;
        try {
            timeoutNanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }

        long now = System.nanoTime();
        return new Deadline(false, now + timeoutNanos);
    }

    /** Returns whether this deadline has no expiration. */
    public boolean isUnlimited() {
        return unlimited;
    }

    /** Returns whether this finite deadline has expired. */
    public boolean isExpired() {
        return !unlimited && remainingNanos() == 0L;
    }

    /**
     * Returns the remaining time in nanoseconds, or {@link Long#MAX_VALUE}
     * for an unlimited deadline.
     */
    public long remainingNanos() {
        if (unlimited) {
            return Long.MAX_VALUE;
        }

        // Subtraction is intentionally used with nanoTime values. It remains
        // valid across nanoTime's arbitrary origin as long as intervals are
        // less than 2^63 nanoseconds, which after() guarantees.
        long remaining = deadlineNanos - System.nanoTime();
        return Math.max(remaining, 0L);
    }

    /**
     * Returns the earlier of this deadline and {@code other}.
     */
    public Deadline min(Deadline other) {
        Objects.requireNonNull(other, "other");

        if (unlimited) {
            return other;
        }
        if (other.unlimited) {
            return this;
        }

        return remainingNanos() <= other.remainingNanos() ? this : other;
    }

    /** Returns the absolute monotonic deadline for internal scheduling. */
    public long deadlineNanos() {
        if (unlimited) {
            return Long.MAX_VALUE;
        }
        return deadlineNanos;
    }
}
