package dev.baton.examples.spring;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageFallback;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Baton starter in a Spring Boot app.
 *
 * <ul>
 *   <li>{@code checkout} is declared in {@code application.yml} from the stage beans
 *       below;</li>
 *   <li>{@code payment} is declared in code with the injected, pre-configured
 *       builder, and nested into {@code checkout} by name;</li>
 *   <li>log lines show which stage wrote them, through the MDC propagation the
 *       starter wires automatically ({@code %X{baton.stage}} in the log pattern).</li>
 * </ul>
 */
@SpringBootApplication
public class SpringExample {

    static final ContextKey<String> ORDER = ContextKey.of("order", String.class);
    static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    static final ContextKey<String> RECOMMENDATION = ContextKey.of("recommendation", String.class);
    static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);

    private static final Logger LOG = LoggerFactory.getLogger(SpringExample.class);

    public static void main(String[] args) {
        SpringApplication.run(SpringExample.class, args);
    }

    @Bean
    Stage loadOrder() {
        return context -> {
            LOG.info("loading order");
            return ContextPatch.builder().put(ORDER, "A-1001").build();
        };
    }

    @Bean
    Stage customer() {
        return context -> {
            LOG.info("looking up customer");
            return ContextPatch.builder().put(CUSTOMER, "Ada").build();
        };
    }

    @Bean
    Stage recommendations() {
        return context -> {
            LOG.info("asking a slow recommendation service");
            Thread.sleep(5_000);
            return ContextPatch.empty();
        };
    }

    @Bean
    StageFallback noRecommendations() {
        return (context, failure) -> {
            LOG.info("recommendations unavailable ({}), using none", failure.getClass().getSimpleName());
            return ContextPatch.builder().put(RECOMMENDATION, "none").build();
        };
    }

    @Bean
    Pipeline payment(Pipeline.Builder builder) {
        AtomicInteger calls = new AtomicInteger();
        return builder.name("payment")
                .stage("charge", context -> {
                    if (calls.incrementAndGet() == 1) {
                        LOG.info("payment gateway timed out");
                        throw new java.io.IOException("gateway timeout");
                    }
                    LOG.info("charged order {}", context.context().get(ORDER));
                    return ContextPatch.builder().put(RECEIPT, "pay-" + context.context().get(ORDER)).build();
                }, StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)
                        .withFixedDelay(Duration.ofMillis(50))))
                .build();
    }

    @Bean
    CommandLineRunner runCheckout(@Qualifier("checkout") Pipeline checkout) {
        return args -> {
            MDC.put("requestId", "req-42");
            try {
                LOG.info("starting checkout");
                PipelineResult result = checkout.run(ContextView.empty());
                LOG.info("checkout {} - receipt {}, recommendation {}", result.status(),
                        result.context().get(RECEIPT), result.context().get(RECOMMENDATION));
                print(result.stages(), "  ");
            } finally {
                MDC.clear();
            }
        };
    }

    private static void print(List<StageResult> stages, String indent) {
        for (StageResult stage : stages) {
            System.out.printf("%s%s %s%s%n", indent, stage.stageId(), stage.status(),
                    stage.attempts() > 1 ? " (" + stage.attempts() + " attempts)" : "");
            print(stage.children(), indent + "  ");
        }
    }
}
