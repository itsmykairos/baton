package dev.baton.spring.boot;

import dev.baton.api.BatonConfig;
import dev.baton.api.Pipeline;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.slf4j.MdcContextPropagator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Auto-configures Baton for a Spring Boot application.
 *
 * <ul>
 *   <li>a {@link BatonConfig} bean from {@code baton.*} properties, every
 *       {@link ContextPropagator} bean and every {@link BatonConfigCustomizer};</li>
 *   <li>a prototype {@link Pipeline.Builder} bean, configured with that config and
 *       every {@link PipelineListener} bean: inject it into {@code @Bean} methods
 *       that build pipelines. The built-in metrics and tracing listeners come
 *       before application listeners;</li>
 *   <li>SLF4J MDC propagation ({@code baton.integrations.mdc}, on by default);</li>
 *   <li>Micrometer metrics when {@code baton-micrometer} and a {@code MeterRegistry}
 *       are present ({@code baton.integrations.metrics});</li>
 *   <li>OpenTelemetry tracing when {@code baton-opentelemetry} and an
 *       {@code OpenTelemetry} bean are present ({@code baton.integrations.tracing});</li>
 *   <li>a {@code Pipeline} bean for each pipeline under {@code baton.pipelines}.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(BatonProperties.class)
public class BatonAutoConfiguration {

    /** Creates the auto-configuration. */
    public BatonAutoConfiguration() {}

    /**
     * The application's Baton settings.
     *
     * @param properties the {@code baton.*} properties
     * @param propagators every context propagator bean, in order
     * @param customizers every customizer, in order
     * @param beans the bean factory, for beans named in {@code baton.default-stage}
     * @return the config passed to every pipeline builder
     */
    @Bean
    @ConditionalOnMissingBean
    public BatonConfig batonConfig(
            BatonProperties properties,
            ObjectProvider<ContextPropagator<?>> propagators,
            ObjectProvider<BatonConfigCustomizer> customizers,
            ListableBeanFactory beans) {
        BatonConfig.Builder config = BatonConfig.builder();
        apply(properties, config, beans);
        propagators.orderedStream().forEach(config::contextPropagator);
        customizers.orderedStream().forEach(customizer -> customizer.customize(config));
        return config.build();
    }

    /**
     * A new pipeline builder, already configured. Prototype-scoped: each injection
     * point gets its own builder.
     *
     * @param config the application's settings
     * @param listeners every pipeline listener bean, in order
     * @return a builder for one pipeline
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean
    public Pipeline.Builder batonPipelineBuilder(BatonConfig config, ObjectProvider<PipelineListener> listeners) {
        Pipeline.Builder builder = Pipeline.builder(config);
        listeners.orderedStream().forEach(builder::listener);
        return builder;
    }

    /**
     * Carries the SLF4J MDC, with run and stage keys, into stage threads.
     *
     * @return the MDC propagator
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(MdcContextPropagator.class)
    @ConditionalOnProperty(name = "baton.integrations.mdc", havingValue = "true", matchIfMissing = true)
    public MdcContextPropagator batonMdcContextPropagator() {
        return new MdcContextPropagator();
    }

    /**
     * Registers a {@code Pipeline} bean for each pipeline under {@code baton.pipelines}.
     *
     * @return the registrar
     */
    @Bean
    public static BatonPipelineRegistrar batonPipelineRegistrar() {
        return new BatonPipelineRegistrar();
    }

    private static void apply(BatonProperties properties, BatonConfig.Builder config, ListableBeanFactory beans) {
        var context = properties.context();
        if (context.flattenDepth() != null) {
            config.contextFlattenDepth(context.flattenDepth());
        }
        var conflicts = properties.conflicts();
        if (conflicts.layer() != null) {
            config.layerConflictPolicy(conflicts.layer());
        }
        if (conflicts.overwrite() != null) {
            config.overwritePolicy(conflicts.overwrite());
        }
        if (conflicts.typeClash() != null) {
            config.typeClashPolicy(conflicts.typeClash());
        }
        if (conflicts.equalValuesConflict() != null) {
            config.equalValuesConflict(conflicts.equalValuesConflict());
        }
        var execution = properties.execution();
        if (execution.maxParallelStages() != null) {
            config.maxParallelStages(execution.maxParallelStages());
        }
        if (execution.maxChildTasks() != null) {
            config.maxChildTasks(execution.maxChildTasks());
        }
        if (execution.unjoinedChildFailure() != null) {
            config.unjoinedChildFailure(execution.unjoinedChildFailure());
        }
        if (execution.runTimeout() != null) {
            config.runTimeout(execution.runTimeout());
        }
        var events = properties.events();
        if (events.bufferCapacity() != null) {
            config.eventBufferCapacity(events.bufferCapacity());
        }
        if (events.batchInterval() != null) {
            config.eventBatchInterval(events.batchInterval());
        }
        if (events.overflowPolicy() != null) {
            config.eventOverflowPolicy(events.overflowPolicy());
        }
        if (events.notifyNestedListeners() != null) {
            config.notifyNestedListeners(events.notifyNestedListeners());
        }
        if (properties.defaultPipelineName() != null) {
            config.defaultPipelineName(properties.defaultPipelineName());
        }
        if (properties.defaultStage() != null) {
            var defaults = properties.defaultStage();
            if (defaults.stage() != null
                    || defaults.pipeline() != null
                    || defaults.parallel() != null
                    || defaults.stages() != null
                    || defaults.id() != null) {
                throw new IllegalStateException("baton.default-stage takes stage options only "
                        + "(timeout, failure-policy, retry, fallback, condition, skip-if-inputs-missing, "
                        + "overwrite-policy), not stage, pipeline, parallel, stages or id");
            }
            config.defaultStageOptions(StageSpecs.apply(
                    dev.baton.api.StageOptions.defaults(), properties.defaultStage(), beans, "baton.default-stage"));
        }
    }

    /** Micrometer metrics, when {@code baton-micrometer} is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {"dev.baton.micrometer.MicrometerPipelineListener", "io.micrometer.core.instrument.MeterRegistry"})
    @ConditionalOnProperty(name = "baton.integrations.metrics", havingValue = "true", matchIfMissing = true)
    static class MicrometerIntegration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 10)
        PipelineListener batonMetricsListener(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            return LazyIntegrations.listener(registry, dev.baton.micrometer.MicrometerPipelineListener::new);
        }
    }

    /** OpenTelemetry tracing, when {@code baton-opentelemetry} is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {"dev.baton.opentelemetry.OpenTelemetryContextPropagator", "io.opentelemetry.api.OpenTelemetry"})
    static class OpenTelemetryIntegration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 10)
        @ConditionalOnProperty(name = "baton.integrations.tracing", havingValue = "propagator", matchIfMissing = true)
        ContextPropagator<Object> batonTracingPropagator(
                ObjectProvider<io.opentelemetry.api.OpenTelemetry> openTelemetry) {
            return LazyIntegrations.propagator(
                    openTelemetry, dev.baton.opentelemetry.OpenTelemetryContextPropagator::new);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 10)
        @ConditionalOnProperty(name = "baton.integrations.tracing", havingValue = "listener")
        PipelineListener batonTracingListener(ObjectProvider<io.opentelemetry.api.OpenTelemetry> openTelemetry) {
            return LazyIntegrations.listener(openTelemetry, dev.baton.opentelemetry.OpenTelemetryPipelineListener::new);
        }
    }
}
