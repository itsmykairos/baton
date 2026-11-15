package dev.baton.core.deadline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

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
            var registration = scheduler.schedule(Deadline.after(Duration.ofMillis(150)), () -> fired.set(true));

            assertThat(registration.cancel()).isTrue();
            Thread.sleep(220);
            assertThat(fired).isFalse();
        }
    }

    @Test
    void sharedSchedulerIsASingleInstanceThatCloseDoesNotStop() throws Exception {
        DeadlineScheduler shared = DeadlineScheduler.shared();
        assertThat(DeadlineScheduler.shared()).isSameAs(shared);

        shared.close();
        CountDownLatch fired = new CountDownLatch(1);
        shared.schedule(Deadline.after(Duration.ofMillis(20)), fired::countDown);
        assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sharedSchedulerCancellationPreventsAction() throws Exception {
        AtomicBoolean fired = new AtomicBoolean();
        var registration =
                DeadlineScheduler.shared().schedule(Deadline.after(Duration.ofMillis(100)), () -> fired.set(true));

        assertThat(registration.cancel()).isTrue();
        Thread.sleep(180);
        assertThat(fired).isFalse();
    }

    @Test
    void actionsRunOnVirtualThreads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        CountDownLatch fired = new CountDownLatch(1);
        DeadlineScheduler.shared().schedule(Deadline.after(Duration.ofMillis(10)), () -> {
            virtual.set(Thread.currentThread().isVirtual());
            fired.countDown();
        });

        assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(virtual).isTrue();
    }
}
