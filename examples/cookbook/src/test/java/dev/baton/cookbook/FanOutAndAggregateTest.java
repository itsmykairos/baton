package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.TaskHandle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FanOutAndAggregateTest {

    static final ContextKey<String> USER_ID = ContextKey.of("userId", String.class);
    static final ContextKey<String> PROFILE = ContextKey.of("profile", String.class);
    static final ContextKey<Integer> ORDERS = ContextKey.of("orders", Integer.class);
    static final ContextKey<Integer> POINTS = ContextKey.of("points", Integer.class);
    static final ContextKey<String> SUMMARY = ContextKey.of("summary", String.class);
    static final ContextKey<Integer> STOCK = ContextKey.of("stock", Integer.class);

    @Test
    void parallelLookupsThenAggregate() throws Exception {
        // --8<-- [start:layers]
        Pipeline dashboard = Pipeline.builder()
                // Three independent lookups: they all read the same snapshot and run at once.
                .parallel("lookups", parallel -> {
                    parallel.stage(
                            "profile",
                            stage -> ContextPatch.builder()
                                    .put(PROFILE, "Ada (" + stage.context().get(USER_ID) + ")")
                                    .build());
                    parallel.stage(
                            "orders",
                            stage -> ContextPatch.builder().put(ORDERS, 3).build());
                    parallel.stage(
                            "points",
                            stage -> ContextPatch.builder().put(POINTS, 120).build());
                })
                // The next layer starts once every lookup has finished, and sees all their writes.
                .stage("summary", stage -> {
                    ContextView context = stage.context();
                    String summary = context.get(PROFILE) + ": " + context.get(ORDERS) + " orders, "
                            + context.get(POINTS) + " points";
                    return ContextPatch.builder().put(SUMMARY, summary).build();
                })
                .build();

        PipelineResult result =
                dashboard.run(ContextView.builder().put(USER_ID, "u-7").build());
        result.context().get(SUMMARY); // "Ada (u-7): 3 orders, 120 points"
        // --8<-- [end:layers]

        assertThat(result.context().get(SUMMARY)).isEqualTo("Ada (u-7): 3 orders, 120 points");
    }

    @Test
    void childTasksPerItem() throws Exception {
        List<String> skus = List.of("sku-1", "sku-2", "sku-3");
        // --8<-- [start:children]
        Pipeline stockCheck = Pipeline.builder()
                .stage("stock", stage -> {
                    // One child task per item, all running at once on virtual threads.
                    List<TaskHandle<Integer>> lookups = new ArrayList<>();
                    for (String sku : skus) {
                        lookups.add(stage.tasks().fork(() -> lookUpStock(sku)));
                    }
                    int total = 0;
                    for (TaskHandle<Integer> lookup : lookups) {
                        total += lookup.join();
                    }
                    return ContextPatch.builder().put(STOCK, total).build();
                })
                .build();
        // --8<-- [end:children]

        assertThat(stockCheck.run(ContextView.empty()).context().get(STOCK)).isEqualTo(15);
    }

    private static int lookUpStock(String sku) {
        return 5;
    }
}
