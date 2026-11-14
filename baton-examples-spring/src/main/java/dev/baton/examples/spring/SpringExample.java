package dev.baton.examples.spring;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.Pipeline;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageFallback;
import dev.baton.api.StageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Baton starter in a Spring Boot web app.
 *
 * <ul>
 *   <li>{@code checkout} is declared in {@code application.yml} from the stage beans
 *       below; {@code payment} is declared in code with the injected builder and
 *       nested into {@code checkout} by name;</li>
 *   <li>{@code POST /checkout} runs it ({@link CheckoutController});</li>
 *   <li>{@code /actuator/baton} shows every pipeline, and {@code /} renders them as
 *       diagrams;</li>
 *   <li>log lines show which stage wrote them, through the MDC propagation the
 *       starter wires automatically ({@code %X{baton.stage}} in the log pattern).</li>
 * </ul>
 */
@SpringBootApplication
public class SpringExample {

    static final ContextKey<String> ORDER_ID = ContextKey.of("orderId", String.class);
    static final ContextKey<String> ORDER = ContextKey.of("order", String.class);
    static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    static final ContextKey<String> RECOMMENDATION = ContextKey.of("recommendation", String.class);
    static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);

    private static final Logger LOG = LoggerFactory.getLogger(SpringExample.class);

    public static void main(String[] args) {
        SpringApplication.run(SpringExample.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        LOG.info("Ready: open http://localhost:8080  (actuator: http://localhost:8080/actuator/baton)");
    }

    @Bean
    Stage loadOrder() {
        return context -> {
            String orderId = context.context().find(ORDER_ID).orElse("A-1001");
            LOG.info("loading order {}", orderId);
            return ContextPatch.builder().put(ORDER, orderId).build();
        };
    }

    @Bean
    Stage customer() {
        return context -> {
            LOG.info("looking up customer");
            Thread.sleep(30);
            return ContextPatch.builder().put(CUSTOMER, "Ada").build();
        };
    }

    /** A flaky service: half the time it answers quickly, half the time it hangs. */
    @Bean
    Stage recommendations() {
        return context -> {
            boolean slow = ThreadLocalRandom.current().nextBoolean();
            LOG.info("asking the recommendation service ({})", slow ? "it will hang" : "it will answer");
            Thread.sleep(slow ? 5_000 : 20);
            return ContextPatch.builder().put(RECOMMENDATION, "socks").build();
        };
    }

    @Bean
    StageFallback noRecommendations() {
        return (context, failure) -> {
            LOG.info("recommendations unavailable ({}), using none", failure.getClass().getSimpleName());
            return ContextPatch.builder().put(RECOMMENDATION, "none").build();
        };
    }

    /** Declared in code; the first attempt of every charge fails, so each run shows a retry. */
    @Bean
    Pipeline payment(Pipeline.Builder builder) {
        AtomicInteger calls = new AtomicInteger();
        return builder.name("payment")
                .stage("charge", context -> {
                    if (calls.incrementAndGet() % 2 == 1) {
                        LOG.info("payment gateway timed out");
                        throw new java.io.IOException("gateway timeout");
                    }
                    LOG.info("charged order {}", context.context().get(ORDER));
                    return ContextPatch.builder().put(RECEIPT, "pay-" + context.context().get(ORDER)).build();
                }, StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)
                        .withFixedDelay(Duration.ofMillis(50))))
                .build();
    }
}
