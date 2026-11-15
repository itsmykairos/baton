package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageOptions;
import dev.baton.api.TaskHandle;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuickStartTest {

    // --8<-- [start:keys]
    static final ContextKey<String> ORDER_ID = ContextKey.of("orderId", String.class);
    static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    static final ContextKey<Integer> TOTAL = ContextKey.of("total", Integer.class);
    // --8<-- [end:keys]

    @Test
    void firstPipeline() throws Exception {
        // --8<-- [start:pipeline]
        Pipeline checkout = Pipeline.builder()
                .name("checkout")
                .stage(
                        "load",
                        stage -> ContextPatch.builder().put(ORDER_ID, "A-1").build())
                .parallel("enrich", parallel -> {
                    parallel.stage(
                            "customer",
                            stage -> ContextPatch.builder().put(CUSTOMER, "Ada").build());
                    parallel.stage(
                            "price",
                            stage -> {
                                // Child tasks run on their own virtual threads.
                                TaskHandle<Integer> base = stage.tasks().fork(() -> 40);
                                TaskHandle<Integer> tax = stage.tasks().fork(() -> 2);
                                return ContextPatch.builder()
                                        .put(TOTAL, base.join() + tax.join())
                                        .build();
                            },
                            StageOptions.defaults().withTimeout(Duration.ofSeconds(2)));
                })
                .build();
        // --8<-- [end:pipeline]

        // --8<-- [start:run]
        PipelineResult result = checkout.run(ContextView.empty());

        result.status(); // COMPLETED
        result.context().get(TOTAL); // 42
        result.context().get(CUSTOMER); // "Ada"
        // --8<-- [end:run]

        assertThat(result.status()).isEqualTo(PipelineStatus.COMPLETED);
        assertThat(result.context().get(TOTAL)).isEqualTo(42);
        assertThat(result.context().get(CUSTOMER)).isEqualTo("Ada");
    }
}
