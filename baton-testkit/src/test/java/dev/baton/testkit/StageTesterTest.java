package dev.baton.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StageTesterTest {

    private static final ContextKey<Integer> IN = ContextKey.of("in", Integer.class);
    private static final ContextKey<Integer> OUT = ContextKey.of("out", Integer.class);

    private static final Stage DOUBLER = new Stage() {
        @Override
        public ContextPatch execute(StageContext context) throws Exception {
            var doubled = context.tasks().fork(() -> context.context().get(IN) * 2);
            return ContextPatch.builder().put(OUT, doubled.join()).build();
        }

        @Override
        public Set<ContextKey<?>> requiredReads() {
            return Set.of(IN);
        }
    };

    @Test
    void runsStageWithForkedChildren() throws Exception {
        ContextView result = StageTester.runAndApply(
                DOUBLER, ContextView.builder().put(IN, 21).build());

        assertThat(result.get(OUT)).isEqualTo(42);
    }

    @Test
    void reportsWhatWouldBlockTheStage() {
        assertThatThrownBy(() -> StageTester.run(DOUBLER, ContextView.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKED")
                .hasMessageContaining("in");
    }

    @Test
    void stageFailurePropagates() {
        Stage fails = context -> {
            throw new IllegalArgumentException("bad input");
        };

        assertThatThrownBy(() -> StageTester.run(fails, ContextView.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad input");
    }

    @Test
    void closingTheContextInterruptsRunningChildren() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        TestStageContext context = TestStageContext.empty();
        context.tasks().fork(() -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return null;
        });

        context.close();

        assertThat(context.forkedTaskCount()).isEqualTo(1);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> context.tasks().fork(() -> 1)).isInstanceOf(IllegalStateException.class);
    }
}
