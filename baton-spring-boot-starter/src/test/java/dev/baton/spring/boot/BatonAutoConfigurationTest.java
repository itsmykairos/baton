package dev.baton.spring.boot;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageFallback;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import dev.baton.micrometer.MicrometerPipelineListener;
import dev.baton.slf4j.MdcContextPropagator;
import dev.baton.testkit.PipelineResults;
import dev.baton.testkit.RecordingListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class BatonAutoConfigurationTest {

    static final ContextKey<String> ORDER = ContextKey.of("order", String.class);
    static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
    static final ContextKey<String> RECOMMENDATION = ContextKey.of("recommendation", String.class);
    static final ContextKey<String> RECEIPT = ContextKey.of("receipt", String.class);
    static final ContextKey<String> VIP = ContextKey.of("vip", String.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BatonAutoConfiguration.class,
                    BatonEndpointAutoConfiguration.class));

    /** Stage beans and a code-declared nested pipeline. */
    @Configuration(proxyBeanMethods = false)
    static class Stages {
        final AtomicInteger chargeCalls = new AtomicInteger();

        @Bean
        Stage loadOrder() {
            return context -> ContextPatch.builder().put(ORDER, "A-1").build();
        }

        @Bean
        Stage customer() {
            return context -> ContextPatch.builder().put(CUSTOMER, "Ada").build();
        }

        @Bean
        Stage recommendations() {
            return context -> {
                Thread.sleep(5_000);
                return ContextPatch.empty();
            };
        }

        @Bean
        StageFallback noRecommendations() {
            return (context, failure) -> ContextPatch.builder().put(RECOMMENDATION, "none").build();
        }

        @Bean
        Predicate<ContextView> isVip() {
            return context -> context.find(CUSTOMER).map("Ada"::equals).orElse(false);
        }

        @Bean
        Stage vipGift() {
            return context -> ContextPatch.builder().put(VIP, "gift").build();
        }

        @Bean
        Stage charge() {
            return context -> {
                if (chargeCalls.incrementAndGet() == 1) {
                    throw new java.io.IOException("gateway down");
                }
                return ContextPatch.builder().put(RECEIPT, "paid " + context.context().get(ORDER)).build();
            };
        }

        @Bean
        Pipeline payment(Pipeline.Builder builder, @Qualifier("charge") Stage charge) {
            return builder.name("payment")
                    .stage("charge", charge, StageOptions.defaults()
                            .withRetry(RetryPolicy.maxAttempts(3).withFixedDelay(Duration.ZERO)))
                    .build();
        }
    }

    private static final String[] CHECKOUT_YAML = {
            "baton.pipelines.checkout.timeout=5s",
            "baton.pipelines.checkout.layers[0].stage=loadOrder",
            "baton.pipelines.checkout.layers[0].id=load-order",
            "baton.pipelines.checkout.layers[1].parallel=enrich",
            "baton.pipelines.checkout.layers[1].stages[0].stage=customer",
            "baton.pipelines.checkout.layers[1].stages[1].stage=recommendations",
            "baton.pipelines.checkout.layers[1].stages[1].timeout=100ms",
            "baton.pipelines.checkout.layers[1].stages[1].fallback=noRecommendations",
            "baton.pipelines.checkout.layers[2].pipeline=payment",
            "baton.pipelines.checkout.layers[3].stage=vipGift",
            "baton.pipelines.checkout.layers[3].condition=isVip",
            "baton.pipelines.checkout.layers[3].failure-policy=continue",
    };

    // ---- config from properties ----------------------------------------------------

    @Test
    void propertiesBecomeTheApplicationsBatonConfig() {
        runner.withPropertyValues(
                        "baton.context.flatten-depth=16",
                        "baton.conflicts.layer=last-wins",
                        "baton.conflicts.overwrite=keep-existing",
                        "baton.execution.max-parallel-stages=4",
                        "baton.execution.run-timeout=30s",
                        "baton.events.batch-interval=20ms",
                        "baton.default-pipeline-name=orders",
                        "baton.default-stage.timeout=2s",
                        "baton.default-stage.retry.max-attempts=3",
                        "baton.default-stage.retry.backoff=50ms",
                        "baton.default-stage.retry.multiplier=2")
                .run(context -> {
                    BatonConfig config = context.getBean(BatonConfig.class);
                    assertThat(config.contextFlattenDepth()).isEqualTo(16);
                    assertThat(config.layerConflictPolicy()).isEqualTo(LayerConflictPolicy.LAST_WINS);
                    assertThat(config.overwritePolicy()).isEqualTo(dev.baton.api.OverwritePolicy.KEEP_EXISTING);
                    assertThat(config.maxParallelStages()).isEqualTo(4);
                    assertThat(config.runTimeout()).contains(Duration.ofSeconds(30));
                    assertThat(config.eventBatchInterval()).isEqualTo(Duration.ofMillis(20));
                    assertThat(config.defaultPipelineName()).isEqualTo("orders");
                    assertThat(config.defaultStageOptions().timeout()).contains(Duration.ofSeconds(2));
                    assertThat(config.defaultStageOptions().retry().maxAttempts()).isEqualTo(3);
                    assertThat(config.defaultStageOptions().retry().delayAfter(2)).isEqualTo(Duration.ofMillis(100));
                    // Unset settings keep the built-in defaults.
                    assertThat(config.typeClashPolicy()).isEqualTo(dev.baton.api.TypeClashPolicy.FAIL);
                    assertThat(config.contextPropagators()).hasAtLeastOneElementOfType(MdcContextPropagator.class);
                });
    }

    @Test
    void mdcPropagationCanBeTurnedOff() {
        runner.withPropertyValues("baton.integrations.mdc=false").run(context ->
                assertThat(context.getBean(BatonConfig.class).contextPropagators())
                        .noneMatch(MdcContextPropagator.class::isInstance));
    }

    @Test
    void propagatorBeansAndCustomizersApply() {
        ContextPropagator<String> tenant = new ContextPropagator<>() {
            @Override
            public String capture() {
                return "t1";
            }

            @Override
            public Restoration restore(String captured, ExecutionScope scope) {
                return Restoration.NONE;
            }
        };
        runner.withBean("tenantPropagator", ContextPropagator.class, () -> tenant)
                .withBean(BatonConfigCustomizer.class, () -> config -> config.maxChildTasks(5))
                .run(context -> {
                    BatonConfig config = context.getBean(BatonConfig.class);
                    assertThat(config.contextPropagators()).contains(tenant);
                    assertThat(config.maxChildTasks()).isEqualTo(5);
                });
    }

    @Test
    void injectedBuildersArePerPipelineAndCarryListenerBeans() {
        runner.withUserConfiguration(RecorderConfig.class).run(context -> {
            Pipeline.Builder first = context.getBean(Pipeline.Builder.class);
            Pipeline.Builder second = context.getBean(Pipeline.Builder.class);
            assertThat(first).isNotSameAs(second);

            first.stage("a", c -> ContextPatch.empty()).build().run(ContextView.empty());
            context.getBean(RecordingListener.class).awaitRunsFinished(1, Duration.ofSeconds(5));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RecorderConfig {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        RecordingListener recorder() {
            return new RecordingListener();
        }
    }

    // ---- pipelines from configuration ------------------------------------------------

    @Test
    void configuredPipelineRunsWithParallelLayersNestedPipelinesAndOptions() {
        runner.withUserConfiguration(Stages.class).withPropertyValues(CHECKOUT_YAML).run(context -> {
            Pipeline checkout = context.getBean("checkout", Pipeline.class);
            PipelineResult result = checkout.run(ContextView.empty());

            PipelineResults.requireSuccess(result);
            assertThat(result.stages()).extracting("stageId")
                    .containsExactly("load-order", "customer", "recommendations", "payment", "vipGift");
            assertThat(PipelineResults.stage(result, "recommendations").status()).isEqualTo(StageStatus.RECOVERED);
            assertThat(PipelineResults.stage(result, "payment/charge").attempts()).isEqualTo(2);
            assertThat(result.context().get(RECEIPT)).isEqualTo("paid A-1");
            assertThat(result.context().get(VIP)).isEqualTo("gift");
            assertThat(checkout.describe().name()).isEqualTo("checkout");
            assertThat(checkout.describe().config().runTimeout()).contains(Duration.ofSeconds(5));
            assertThat(checkout.describe().stage("vipGift").orElseThrow().options().failurePolicy())
                    .isEqualTo(dev.baton.api.FailurePolicy.CONTINUE);
        });
    }

    @Test
    void configuredPipelinesCanNestEachOther() {
        runner.withUserConfiguration(Stages.class).withPropertyValues(
                        "baton.pipelines.inner.layers[0].stage=loadOrder",
                        "baton.pipelines.outer.name=outer-flow",
                        "baton.pipelines.outer.layers[0].pipeline=inner",
                        "baton.pipelines.outer.layers[1].stage=customer")
                .run(context -> {
                    PipelineResult result = context.getBean("outer", Pipeline.class).run(ContextView.empty());
                    PipelineResults.requireSuccess(result);
                    assertThat(result.context().get(ORDER)).isEqualTo("A-1");
                    assertThat(PipelineResults.stage(result, "inner/loadOrder").status())
                            .isEqualTo(StageStatus.SUCCEEDED);
                });
    }

    @Test
    void misconfiguredPipelinesFailStartupWithThePropertyPath() {
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.p.layers[0].stage=missing")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.pipelines.p.layers[0].stage: no Stage bean named 'missing'"));
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.p.layers[0].stage=customer",
                        "baton.pipelines.p.layers[0].pipeline=payment")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.pipelines.p.layers[0] must name exactly one of stage, pipeline or parallel"));
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.a.layers[0].pipeline=b",
                        "baton.pipelines.b.layers[0].pipeline=a")
                .run(context -> assertThat(startupFailure(context)).contains("cycle: a -> b -> a"));
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.p.layers[0].parallel=x",
                        "baton.pipelines.p.layers[0].stages[0].stage=customer",
                        "baton.pipelines.p.layers[0].timeout=2s")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.pipelines.p.layers[0]: options and id cannot be set on a parallel layer"));
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.p.layers[0].stage=customer",
                        "baton.pipelines.p.layers[0].retry.max-attempts=3",
                        "baton.pipelines.p.layers[0].retry.backoff=2s",
                        "baton.pipelines.p.layers[0].retry.max-delay=1s")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.pipelines.p.layers[0].retry: Require initialDelay"));
        runner.withPropertyValues("baton.default-stage.stage=customer")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.default-stage takes stage options only"));
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues("baton.pipelines.p.layers[0].parallel=x")
                .run(context -> assertThat(startupFailure(context))
                        .contains("baton.pipelines.p.layers[0].stages must list"));
    }

    private static String startupFailure(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        StringBuilder messages = new StringBuilder();
        for (Throwable failure = context.getStartupFailure(); failure != null; failure = failure.getCause()) {
            messages.append(failure.getMessage()).append('\n');
        }
        return messages.toString();
    }

    // ---- integrations ----------------------------------------------------------------

    @Test
    void micrometerMetricsAreRecordedWhenARegistryIsPresent() {
        runner.withUserConfiguration(Stages.class, RecorderConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(CHECKOUT_YAML)
                .run(context -> {
                    context.getBean("checkout", Pipeline.class).run(ContextView.empty());
                    context.getBean(RecordingListener.class).awaitRunsFinished(1, Duration.ofSeconds(5));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get(MicrometerPipelineListener.STAGE_DURATION)
                            .tags("pipeline", "checkout", "stage", "payment/charge").timer().count()).isEqualTo(1);
                    assertThat(registry.get(MicrometerPipelineListener.STAGE_RETRIES).counter().count())
                            .isEqualTo(1.0);
                });
    }

    @Test
    void runsStillWorkWithoutAMeterRegistry() {
        runner.withUserConfiguration(Stages.class).withPropertyValues(CHECKOUT_YAML).run(context ->
                assertThat(context.getBean("checkout", Pipeline.class).run(ContextView.empty()).isSuccessful())
                        .isTrue());
    }

    @Test
    void tracingUsesLiveStageSpansByDefault() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        runner.withUserConfiguration(Stages.class)
                .withBean(OpenTelemetry.class, () -> sdk(exporter))
                .withPropertyValues(CHECKOUT_YAML)
                .run(context -> {
                    context.getBean("checkout", Pipeline.class).run(ContextView.empty());
                    assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                            .contains("checkout", "load-order", "payment", "charge");
                    SpanData charge = exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("charge")).findFirst().orElseThrow();
                    SpanData payment = exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("payment")).findFirst().orElseThrow();
                    assertThat(charge.getParentSpanId()).isEqualTo(payment.getSpanId());
                });
    }

    @Test
    void tracingCanUseTheListenerOrBeOff() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        runner.withUserConfiguration(Stages.class, RecorderConfig.class)
                .withBean(OpenTelemetry.class, () -> sdk(exporter))
                .withPropertyValues(CHECKOUT_YAML)
                .withPropertyValues("baton.integrations.tracing=listener")
                .run(context -> {
                    assertThat(context.getBean(BatonConfig.class).contextPropagators())
                            .allMatch(MdcContextPropagator.class::isInstance);
                    context.getBean("checkout", Pipeline.class).run(ContextView.empty());
                    context.getBean(RecordingListener.class).awaitRunsFinished(1, Duration.ofSeconds(5));
                    assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                            .contains("checkout", "charge");
                });
        InMemorySpanExporter none = InMemorySpanExporter.create();
        runner.withUserConfiguration(Stages.class)
                .withBean(OpenTelemetry.class, () -> sdk(none))
                .withPropertyValues(CHECKOUT_YAML)
                .withPropertyValues("baton.integrations.tracing=none")
                .run(context -> {
                    context.getBean("checkout", Pipeline.class).run(ContextView.empty());
                    assertThat(none.getFinishedSpanItems()).isEmpty();
                });
    }

    private static OpenTelemetry sdk(InMemorySpanExporter exporter) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                .build();
    }

    // ---- actuator endpoint -----------------------------------------------------------

    @Test
    void endpointDescribesPipelinesWhenExposed() {
        runner.withUserConfiguration(Stages.class)
                .withPropertyValues(CHECKOUT_YAML)
                .withPropertyValues("management.endpoints.web.exposure.include=baton")
                .run(context -> {
                    BatonEndpoint endpoint = context.getBean(BatonEndpoint.class);

                    var list = endpoint.pipelines().pipelines();
                    assertThat(list).containsOnlyKeys("checkout", "payment");
                    assertThat(list.get("checkout").layers()).isEqualTo(4);
                    assertThat(list.get("checkout").stages()).isEqualTo(5);

                    var checkout = endpoint.pipeline("checkout");
                    assertThat(checkout.name()).isEqualTo("checkout");
                    assertThat(checkout.layers()).extracting(BatonEndpoint.LayerReport::id)
                            .containsExactly("load-order", "enrich", "payment", "vipGift");
                    assertThat(checkout.layers().get(1).parallel()).isTrue();
                    var recommendations = checkout.layers().get(1).stages().get(1);
                    assertThat(recommendations.timeout()).isEqualTo("PT0.1S");
                    assertThat(recommendations.fallback()).isTrue();
                    assertThat(checkout.layers().get(2).stages().getFirst().nested().name()).isEqualTo("payment");
                    assertThat(checkout.settings().runTimeout()).isEqualTo("PT5S");
                    assertThat(checkout.mermaid()).startsWith("---\ntitle: \"checkout\"");
                    assertThat(endpoint.pipeline("missing")).isNull();
                });
    }

    @Test
    void endpointSelectorParameterKeepsItsNameForActuator() throws Exception {
        // Actuator maps /actuator/baton/{bean} by parameter name; it needs -parameters.
        var parameter = BatonEndpoint.class.getMethod("pipeline", String.class).getParameters()[0];
        assertThat(parameter.isNamePresent()).isTrue();
        assertThat(parameter.getName()).isEqualTo("bean");
    }

    @Test
    void endpointIsAbsentUnlessExposed() {
        runner.withUserConfiguration(Stages.class).run(context ->
                assertThat(context).doesNotHaveBean(BatonEndpoint.class));
    }
}
