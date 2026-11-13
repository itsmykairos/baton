package dev.baton.core.deadline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineSchedulerTest {

    @Test
    void runsActionWhenDeadlineExpires() throws Exception {
        try (DeadlineScheduler scheduler = new DeadlineScheduler()) {
            CountDownLatch fired = new CountDownLatch(1);
            scheduler.schedule(Deadline.after(Duration.ofMillis(30)), fired::countDown);

            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationPreventsScheduledAction() throws Exception {
        try (DeadlineScheduler scheduler = new DeadlineScheduler()) {
            AtomicBoolean fired = new AtomicBoolean();
            var registration = scheduler.schedule(
                    Deadline.after(Duration.ofMillis(150)),
                    () -> fired.set(true)
            );

            assertThat(registration.cancel()).isTrue();
            Thread.sleep(220);
            assertThat(fired).isFalse();
        }
    }
}
