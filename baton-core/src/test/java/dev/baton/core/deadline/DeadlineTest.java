package dev.baton.core.deadline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeadlineTest {

    @Test
    void unlimitedDeadlineNeverExpires() {
        Deadline deadline = Deadline.unlimited();

        assertThat(deadline.isUnlimited()).isTrue();
        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remainingNanos()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void finiteDeadlineHasPositiveRemainingTimeWhenCreated() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));

        assertThat(deadline.isUnlimited()).isFalse();
        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remainingNanos()).isPositive();
        assertThat(deadline.remainingNanos())
                .isLessThanOrEqualTo(Duration.ofSeconds(5).toNanos());
    }

    @Test
    void rejectsNonPositiveDuration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Deadline.after(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Deadline.after(Duration.ofNanos(-1)));
    }

    @Test
    void minReturnsEarlierDeadline() {
        Deadline shortDeadline = Deadline.after(Duration.ofSeconds(1));
        Deadline longDeadline = Deadline.after(Duration.ofSeconds(10));

        assertThat(shortDeadline.min(longDeadline)).isSameAs(shortDeadline);
        assertThat(longDeadline.min(shortDeadline)).isSameAs(shortDeadline);
    }

    @Test
    void finiteDeadlineIsEarlierThanUnlimitedDeadline() {
        Deadline finite = Deadline.after(Duration.ofSeconds(1));

        assertThat(Deadline.unlimited().min(finite)).isSameAs(finite);
        assertThat(finite.min(Deadline.unlimited())).isSameAs(finite);
    }

    @Test
    void supportsDurationsLargerThanNanosCanRepresent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(deadline.isUnlimited()).isFalse();
        assertThat(deadline.remainingNanos()).isPositive();
    }
}
