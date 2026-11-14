package dev.baton.examples;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An order checkout pipeline that shows the main Baton features:
 *
 * <ul>
 *   <li>sequential and parallel layers sharing an immutable context;</li>
 *   <li>child tasks forked and joined inside a stage;</li>
 *   <li>a slow optional stage recovered by a fallback after its timeout;</li>
 *   <li>a flaky payment call retried with backoff;</li>
 *   <li>a timeout for the whole run;</li>
 *   <li>conditional stages: one skipped by a condition, one by a missing input;</li>
 *   <li>one application-wide {@link BatonConfig} shared by every pipeline;</li>
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
    static final ContextKey<String> PHONE = ContextKey.of("phone", String.class);

    /**
     * The application's settings, shared by every pipeline it builds. Each pipeline
     * (Pipeline.Builder.config) or run (RunOptions.withConfig) can override them.
     */
    static final BatonConfig APP_CONFIG = BatonConfig.builder()
            .contextFlattenDepth(16)                            // long pipelines read a lot
            .layerConflictPolicy(LayerConflictPolicy.FAIL)      // two stages writing one key is a bug
            .maxParallelStages(8)                               // be gentle with downstream services
            .build();

    private BatonExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        AtomicInteger gatewayCalls = new AtomicInteger();
        Pipeline payment = Pipeline.builder(APP_CONFIG)
                .stage("authorize", context -> {
                    if (gatewayCalls.incrementAndGet() == 1) {
                        throw new IOException("payment gateway unavailable"); // first call fails
                    }
                    return ContextPatch.builder()
                            .put(PAYMENT_ID, "pay-" + context.context().get(ORDER_ID))
                            .build();
                }, StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)
                        .withBackoff(Duration.ofMillis(50), 2.0, Duration.ofMillis(500))))
                .stage("capture", requires(Set.of(PAYMENT_ID, TOTAL_CENTS), context ->
                        ContextPatch.builder()
                                .put(RECEIPT, "Captured " + formatCents(context.context().get(TOTAL_CENTS))
                                        + " with " + context.context().get(PAYMENT_ID))
                                .build()))
                .build();

        Pipeline checkout = Pipeline.builder(APP_CONFIG)
                .name("checkout")
                .timeout(Duration.ofSeconds(5))
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
                            .withFallback((context, failure) -> ContextPatch.builder()
                                    .put(RECOMMENDATION, "none").build()));
                })
                .stage("payment", payment.asStage(), StageOptions.defaults().withTimeout(Duration.ofSeconds(2)))
                .parallel("notify", parallel -> {
                    // Only large orders get a thank-you call.
                    parallel.stage("vip-call", context -> ContextPatch.empty(), StageOptions.defaults()
                            .withCondition(context -> context.get(TOTAL_CENTS) > 10_000));
                    // Optional: no phone number in this order, so the SMS is skipped, not blocked.
                    parallel.stage("sms", requires(Set.of(PHONE), context -> ContextPatch.empty()),
                            StageOptions.defaults().skipIfInputsMissing());
                })
                .build();

        PipelineResult result = checkout.run(ContextView.builder().put(ORDER_ID, "A-1001").build());

        System.out.println("Run: " + result.status() + (result.isSuccessful() ? " (successful)" : ""));
        print(result.stages(), 1);
        System.out.println("Customer: " + result.context().get(CUSTOMER));
        System.out.println("Receipt: " + result.context().get(RECEIPT));
        System.out.println("Recommendation: " + result.context().get(RECOMMENDATION));
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
            System.out.printf("%s- %s %s (%d ms%s)%n", "  ".repeat(depth), stage.stageId(),
                    stage.status(), stage.duration().toMillis(),
                    stage.attempts() > 1 ? ", " + stage.attempts() + " attempts" : "");
            print(stage.children(), depth + 1);
        }
    }

    private static String formatCents(int cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
