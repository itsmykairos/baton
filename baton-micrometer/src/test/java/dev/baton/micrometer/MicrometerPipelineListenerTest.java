package dev.baton.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.testkit.RecordingListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerPipelineListenerTest {

    @Test
    void recordsStageAndRunTimersTaggedByPathAndStatus() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingListener done = new RecordingListener();
        Pipeline payment = Pipeline.builder()
                .stage("charge", context -> ContextPatch.empty())
                .build();
        Pipeline checkout = Pipeline.builder()
                .name("checkout")
                .listener(new MicrometerPipelineListener(registry))
                .stage("load", context -> {
                    Thread.sleep(20);
                    return ContextPatch.empty();
                })
                .stage(
                        "optional",
                        context -> {
                            throw new IllegalStateException("down");
                        },
                        StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .stage("payment", payment.asStage())
                .build();

        checkout.run(ContextView.empty(), RunOptions.defaults().withListener(done));
        checkout.run(ContextView.empty(), RunOptions.defaults().withListener(done));
        done.awaitRunsFinished(2, Duration.ofSeconds(5));
        // Listeners of one run are called in order, so the Micrometer listener has
        // seen RunFinished too once the recorder has.

        var load = registry.get(MicrometerPipelineListener.STAGE_DURATION)
                .tags("pipeline", "checkout", "stage", "load", "status", "SUCCEEDED")
                .timer();
        assertThat(load.count()).isEqualTo(2);
        assertThat(load.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(40);
        assertThat(registry.get(MicrometerPipelineListener.STAGE_DURATION)
                        .tags("stage", "optional", "status", "FAILED")
                        .timer()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get(MicrometerPipelineListener.STAGE_DURATION)
                        .tags("stage", "payment/charge")
                        .timer()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get(MicrometerPipelineListener.RUN_DURATION)
                        .tags("pipeline", "checkout", "status", "COMPLETED")
                        .timer()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get(MicrometerPipelineListener.ACTIVE_RUNS)
                        .tags("pipeline", "checkout")
                        .gauge()
                        .value())
                .isZero();
        assertThat(registry.find(MicrometerPipelineListener.DROPPED_EVENTS).counter())
                .isNull();
    }

    @Test
    void countsRetries() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingListener done = new RecordingListener();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Pipeline pipeline = Pipeline.builder()
                .name("flaky")
                .listener(new MicrometerPipelineListener(registry))
                .stage(
                        "call",
                        context -> {
                            if (calls.incrementAndGet() < 3) {
                                throw new java.io.IOException("down");
                            }
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withRetry(
                                        dev.baton.api.RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ZERO)))
                .build();

        pipeline.run(ContextView.empty(), RunOptions.defaults().withListener(done));
        done.awaitRunsFinished(1, Duration.ofSeconds(5));

        assertThat(registry.get(MicrometerPipelineListener.STAGE_RETRIES)
                        .tags("pipeline", "flaky", "stage", "call")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }
}
