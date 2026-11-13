package dev.baton.examples;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * An order checkout pipeline that shows the main Baton features:
 *
 * <ul>
 *   <li>sequential and parallel layers sharing an immutable context;</li>
 *   <li>child tasks forked and joined inside a stage;</li>
 *   <li>a stage timeout tolerated with {@link FailurePolicy#CONTINUE};</li>
 *   <li>a nested pipeline whose stages are reported as children.</li>
 * </ul>
 *
 * <p>Run with {@code ./gradlew :baton-examples:run}.</p>
 */
public final class BatonExample {

    static final ContextKey<String> ORDER_ID = ContextKey.of("orderId", String.class);
    static final ContextKey<List> ITEMS = ContextKey.of("items", List.class);
    static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    static final ContextKey<Integer> TOTAL_CENTS = ContextKey.of("totalCents", Integer.class);
    static final ContextKey<String> RECOMMENDATION = ContextKey.of("recommendation", String.class);
    static final ContextKey<String> PAYMENT_ID = ContextKey.of("paymentId", String.class);
    static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);

    private BatonExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        Pipeline payment = Pipeline.builder()
                .stage("authorize", context -> ContextPatch.builder()
                        .put(PAYMENT_ID, "pay-" + context.context().get(ORDER_ID))
                        .build())
                .stage("capture", requires(Set.of(PAYMENT_ID, TOTAL_CENTS), context ->
                        ContextPatch.builder()
                                .put(RECEIPT, "Captured " + formatCents(context.context().get(TOTAL_CENTS))
                                        + " with " + context.context().get(PAYMENT_ID))
                                .build()))
                .build();

        Pipeline checkout = Pipeline.builder()
                .stage("load-order", context -> ContextPatch.builder()
                        .put(ITEMS, List.of(1299, 450, 2000))
                        .build())
                .parallel("enrich", parallel -> {
                    parallel.stage("customer", context -> {
                        Thread.sleep(30); // simulated lookup
                        return ContextPatch.builder().put(CUSTOMER, "Ada").build();
                    });
                    parallel.stage("price", BatonExample::price);
                    parallel.stage("recommendations", context -> {
                        Thread.sleep(5_000); // a slow, optional service
                        return ContextPatch.builder().put(RECOMMENDATION, "socks").build();
                    }, StageOptions.defaults()
                            .withTimeout(Duration.ofMillis(100))
                            .withFailurePolicy(FailurePolicy.CONTINUE));
                })
                .stage("payment", payment.asStage(), StageOptions.defaults().withTimeout(Duration.ofSeconds(2)))
                .build();

        PipelineResult result = checkout.run(ContextView.builder().put(ORDER_ID, "A-1001").build());

        System.out.println("Run: " + result.status() + (result.isSuccessful() ? "" : " (with tolerated failures)"));
        print(result.stages(), 1);
        System.out.println("Customer: " + result.context().get(CUSTOMER));
        System.out.println("Receipt: " + result.context().get(RECEIPT));
        System.out.println("Recommendation present: " + result.context().contains(RECOMMENDATION));
    }

    /** Prices each item in a forked child task and joins them all before returning. */
    private static ContextPatch price(StageContext context) throws Exception {
        List<?> items = context.context().get(ITEMS);
        var tasks = items.stream()
                .map(item -> context.tasks().fork(() -> (Integer) item))
                .toList();
        int total = 0;
        for (var task : tasks) {
            total += task.join();
        }
        return ContextPatch.builder().put(TOTAL_CENTS, total).build();
    }

    private static Stage requires(Set<ContextKey<?>> keys, Stage stage) {
        return new Stage() {
            @Override
            public ContextPatch execute(StageContext context) throws Exception {
                return stage.execute(context);
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return keys;
            }
        };
    }

    private static void print(List<StageResult> stages, int depth) {
        for (StageResult stage : stages) {
            System.out.printf("%s- %s %s (%d ms)%n", "  ".repeat(depth), stage.stageId(),
                    stage.status(), stage.duration().toMillis());
            print(stage.children(), depth + 1);
        }
    }

    private static String formatCents(int cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
