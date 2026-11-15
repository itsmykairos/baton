package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.testkit.PipelineResults;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NestedPipelinesTest {

    static final ContextKey<String> ORDER_ID = ContextKey.of("orderId", String.class);
    static final ContextKey<String> AUTHORIZATION = ContextKey.of("authorization", String.class);
    static final ContextKey<String> PAYMENT_ID = ContextKey.of("paymentId", String.class);
    static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);

    @Test
    void pipelineAsAStage() throws Exception {
        // --8<-- [start:nested]
        // A reusable pipeline of its own...
        Pipeline payment = Pipeline.builder()
                .name("payment")
                .stage(
                        "authorize",
                        stage -> ContextPatch.builder()
                                .put(AUTHORIZATION, "auth-" + stage.context().get(ORDER_ID))
                                .build())
                .stage(
                        "capture",
                        stage -> ContextPatch.builder()
                                .put(PAYMENT_ID, "pay-" + stage.context().get(AUTHORIZATION))
                                .build())
                .build();

        // ...used as one stage of another, with its own stage options.
        Pipeline checkout = Pipeline.builder()
                .stage(
                        "load",
                        stage -> ContextPatch.builder().put(ORDER_ID, "A-1").build())
                .stage("payment", payment.asStage(), StageOptions.defaults().withTimeout(Duration.ofSeconds(5)))
                .stage(
                        "receipt",
                        stage -> ContextPatch.builder()
                                .put(RECEIPT, "receipt for " + stage.context().get(PAYMENT_ID))
                                .build())
                .build();

        PipelineResult result = checkout.run(ContextView.empty());
        result.context().get(RECEIPT); // "receipt for pay-auth-A-1"
        // The child's stages are reported under the nested stage, with paths.
        PipelineResults.stage(result, "payment/capture").status(); // SUCCEEDED
        // --8<-- [end:nested]

        assertThat(result.context().get(RECEIPT)).isEqualTo("receipt for pay-auth-A-1");
        assertThat(PipelineResults.stage(result, "payment").children()).hasSize(2);
        assertThat(PipelineResults.stage(result, "payment/capture").status()).isEqualTo(StageStatus.SUCCEEDED);
    }
}
