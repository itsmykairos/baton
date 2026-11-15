package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.RetryPolicy;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.testkit.PipelineResults;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilienceTest {

    static final ContextKey<String> PAYMENT_ID = ContextKey.of("paymentId", String.class);
    static final ContextKey<List> RECOMMENDATIONS = ContextKey.of("recommendations", List.class);
    static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);

    @Test
    void retryWithBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // --8<-- [start:retry]
        StageOptions flakyGateway = StageOptions.defaults()
                .withTimeout(Duration.ofSeconds(3)) // covers every attempt and backoff wait
                .withRetry(RetryPolicy.maxAttempts(3)
                        .withBackoff(Duration.ofMillis(50), 2.0, Duration.ofSeconds(1))
                        .withJitter(0.2)
                        .withAttemptTimeout(Duration.ofSeconds(1)) // cut off one slow attempt
                        .retryOn(failure -> failure instanceof IOException));

        Pipeline payment = Pipeline.builder()
                .stage(
                        "charge",
                        stage -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IOException("gateway unavailable");
                            }
                            return ContextPatch.builder()
                                    .put(PAYMENT_ID, "pay-1")
                                    .build();
                        },
                        flakyGateway)
                .build();

        PipelineResult result = payment.run(ContextView.empty());
        PipelineResults.stage(result, "charge").attempts(); // 2
        // --8<-- [end:retry]

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(PipelineResults.stage(result, "charge").attempts()).isEqualTo(2);
    }

    @Test
    void fallbackAfterTimeout() throws Exception {
        // --8<-- [start:fallback]
        Pipeline product = Pipeline.builder()
                .stage(
                        "recommendations",
                        stage -> {
                            Thread.sleep(5_000); // a slow, optional service
                            return ContextPatch.builder()
                                    .put(RECOMMENDATIONS, List.of("socks"))
                                    .build();
                        },
                        StageOptions.defaults()
                                .withTimeout(Duration.ofMillis(100))
                                .withFallback((context, failure) -> ContextPatch.builder()
                                        .put(RECOMMENDATIONS, List.of())
                                        .build()))
                .build();

        PipelineResult result = product.run(ContextView.empty());
        StageResult recommendations = PipelineResults.stage(result, "recommendations");
        recommendations.status(); // RECOVERED: counts as success
        recommendations.failure(); // the original StageTimeoutException
        result.context().get(RECOMMENDATIONS); // []
        // --8<-- [end:fallback]

        assertThat(result.isSuccessful()).isTrue();
        assertThat(recommendations.status()).isEqualTo(StageStatus.RECOVERED);
        assertThat(result.context().get(RECOMMENDATIONS)).isEmpty();
    }

    @Test
    void continuePastAFailure() throws Exception {
        // --8<-- [start:continue]
        Pipeline order = Pipeline.builder()
                .parallel("after-payment", parallel -> {
                    parallel.stage(
                            "audit",
                            stage -> ContextPatch.builder().put(AUDIT, "ok").build());
                    parallel.stage(
                            "email",
                            stage -> {
                                throw new IOException("mail server down");
                            },
                            // A failure here is reported, but doesn't stop the run.
                            StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE));
                })
                .build();

        PipelineResult result = order.run(ContextView.empty());
        result.status(); // COMPLETED
        result.isSuccessful(); // false: "email" FAILED
        // --8<-- [end:continue]

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(PipelineResults.stage(result, "email").status()).isEqualTo(StageStatus.FAILED);
    }

    @Test
    void runTimeout() throws Exception {
        // --8<-- [start:run-timeout]
        Pipeline report = Pipeline.builder()
                .timeout(Duration.ofSeconds(10)) // every run of this pipeline
                .stage("slow", stage -> {
                    Thread.sleep(5_000);
                    return ContextPatch.empty();
                })
                .build();

        // This run only: a tighter limit.
        PipelineResult result =
                report.run(ContextView.empty(), RunOptions.defaults().withTimeout(Duration.ofMillis(100)));
        result.status(); // TIMED_OUT
        // --8<-- [end:run-timeout]

        assertThat(result.status()).isEqualTo(PipelineStatus.TIMED_OUT);
    }
}
