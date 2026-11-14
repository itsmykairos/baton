package dev.baton.spring.boot;

import dev.baton.api.EventOverflowPolicy;
import dev.baton.api.FailurePolicy;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.OverwritePolicy;
import dev.baton.api.TypeClashPolicy;
import dev.baton.api.UnjoinedChildFailurePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Baton settings under the {@code baton} prefix. Every value is optional: an unset
 * value keeps Baton's built-in default. See {@code docs/configuration.md} for what
 * each setting does.
 *
 * <pre>
 * baton:
 *   context.flatten-depth: 16
 *   conflicts.layer: last-wins
 *   execution.max-parallel-stages: 32
 *   events.batch-interval: 20ms
 *   integrations.tracing: propagator
 *   pipelines:
 *     checkout:
 *       layers:
 *         - stage: loadOrder
 * </pre>
 *
 * @param context context settings
 * @param conflicts key-conflict policies
 * @param execution execution limits and defaults
 * @param events event delivery settings
 * @param defaultPipelineName the name of pipelines built without one
 * @param defaultStage options for stages declared without options
 * @param integrations which detected integrations to wire
 * @param pipelines pipelines declared in configuration, by bean name
 */
@ConfigurationProperties("baton")
public record BatonProperties(
        @DefaultValue Context context,
        @DefaultValue Conflicts conflicts,
        @DefaultValue Execution execution,
        @DefaultValue Events events,
        String defaultPipelineName,
        StageSpec defaultStage,
        @DefaultValue Integrations integrations,
        Map<String, PipelineSpec> pipelines
) {

    /**
     * Context settings.
     *
     * @param flattenDepth levels before the context is flattened; 0 means never
     */
    public record Context(Integer flattenDepth) {
    }

    /**
     * Key-conflict policies.
     *
     * @param layer two stages of one layer writing a key
     * @param overwrite a stage writing a key already in the context
     * @param typeClash a stage writing a name held with another type
     * @param equalValuesConflict whether equal same-layer writes conflict
     */
    public record Conflicts(
            LayerConflictPolicy layer,
            OverwritePolicy overwrite,
            TypeClashPolicy typeClash,
            Boolean equalValuesConflict
    ) {
    }

    /**
     * Execution limits and defaults.
     *
     * @param maxParallelStages stages of one layer running at once; 0 means unlimited
     * @param maxChildTasks child tasks of one stage running at once; 0 means unlimited
     * @param unjoinedChildFailure what an unjoined, failed child task does
     * @param runTimeout the timeout for a whole run
     */
    public record Execution(
            Integer maxParallelStages,
            Integer maxChildTasks,
            UnjoinedChildFailurePolicy unjoinedChildFailure,
            Duration runTimeout
    ) {
    }

    /**
     * Event delivery settings.
     *
     * @param bufferCapacity undelivered events a run may queue
     * @param batchInterval the longest delay before queued events are delivered
     * @param overflowPolicy which event is dropped when the queue is full
     * @param notifyNestedListeners whether nested pipelines' own listeners get events
     */
    public record Events(
            Integer bufferCapacity,
            Duration batchInterval,
            EventOverflowPolicy overflowPolicy,
            Boolean notifyNestedListeners
    ) {
    }

    /**
     * Which integrations to wire when they are on the classpath.
     *
     * @param mdc carry the SLF4J MDC into stage threads, with run and stage keys
     * @param metrics record Micrometer metrics, when {@code baton-micrometer} and a
     *                {@code MeterRegistry} are present
     * @param tracing how to trace runs, when {@code baton-opentelemetry} and an
     *                {@code OpenTelemetry} bean are present
     */
    public record Integrations(
            @DefaultValue("true") boolean mdc,
            @DefaultValue("true") boolean metrics,
            @DefaultValue("propagator") TracingMode tracing
    ) {
    }

    /**
     * A pipeline declared in configuration. It becomes a {@code Pipeline} bean named
     * after its key under {@code baton.pipelines}.
     *
     * @param name the pipeline name; defaults to its key
     * @param timeout the run timeout
     * @param layers the layers, in order
     */
    public record PipelineSpec(String name, Duration timeout, List<StageSpec> layers) {
    }

    /**
     * One layer of a configured pipeline, or one stage of a parallel layer. A layer
     * names exactly one of {@code stage} (a {@code Stage} bean), {@code pipeline} (a
     * {@code Pipeline} bean, run nested) or {@code parallel} (a layer id, with its
     * stages under {@code stages}). The other fields set the stage's options;
     * unset ones come from {@code baton.default-stage}.
     *
     * @param stage the name of a {@code Stage} bean
     * @param pipeline the name of a {@code Pipeline} bean to run nested
     * @param parallel the id of a parallel layer
     * @param stages the stages of a parallel layer
     * @param id the stage id; defaults to the bean name
     * @param timeout the stage timeout, covering every attempt
     * @param failurePolicy what a failure means for the run
     * @param retry the retry policy
     * @param fallback the name of a {@code StageFallback} bean
     * @param condition the name of a {@code Predicate<ContextView>} bean
     * @param skipIfInputsMissing skip, instead of block, when inputs are missing
     * @param overwritePolicy this stage's overwrite policy
     */
    public record StageSpec(
            String stage,
            String pipeline,
            String parallel,
            List<StageSpec> stages,
            String id,
            Duration timeout,
            FailurePolicy failurePolicy,
            RetrySpec retry,
            String fallback,
            String condition,
            Boolean skipIfInputsMissing,
            OverwritePolicy overwritePolicy
    ) {
    }

    /**
     * A retry policy.
     *
     * @param maxAttempts total attempts, including the first
     * @param backoff the wait before the second attempt
     * @param multiplier how much each wait grows; defaults to 1 (a fixed delay)
     * @param maxDelay the longest wait
     * @param jitter a fraction from 0 to 1 to shorten waits by at random
     * @param attemptTimeout a limit per attempt
     * @param retryOn the name of a {@code Predicate<Throwable>} bean deciding which
     *                failures are retried
     */
    public record RetrySpec(
            Integer maxAttempts,
            Duration backoff,
            Double multiplier,
            Duration maxDelay,
            Double jitter,
            Duration attemptTimeout,
            String retryOn
    ) {
    }

    /** How runs are traced with OpenTelemetry. */
    public enum TracingMode {
        /**
         * Live run and stage spans, current while stage code runs, so its own spans
         * nest under the stage ({@code OpenTelemetryContextPropagator}).
         */
        PROPAGATOR,
        /** Spans rebuilt from events after the fact ({@code OpenTelemetryPipelineListener}). */
        LISTENER,
        /** No tracing. */
        NONE
    }
}
