package dev.baton.spring.boot;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.Pipeline;
import dev.baton.api.description.LayerDescription;
import dev.baton.api.description.PipelineDescription;
import dev.baton.api.description.StageDescription;
import dev.baton.visualize.MermaidDiagram;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * The {@code baton} Actuator endpoint: what pipelines this application has.
 *
 * <ul>
 *   <li>{@code GET /actuator/baton}: every {@link Pipeline} bean, with its name and
 *       size;</li>
 *   <li>{@code GET /actuator/baton/{bean}}: one pipeline's layers, stages and
 *       options, its effective settings, and a Mermaid diagram.</li>
 * </ul>
 *
 * <p>Read-only. Like any endpoint, it must be exposed, for example with
 * {@code management.endpoints.web.exposure.include=baton}.</p>
 */
@Endpoint(id = "baton")
public class BatonEndpoint {

    private final ListableBeanFactory beans;

    /**
     * Creates the endpoint.
     *
     * @param beans where to find {@link Pipeline} beans
     */
    public BatonEndpoint(ListableBeanFactory beans) {
        this.beans = beans;
    }

    /**
     * Lists every pipeline bean.
     *
     * @return pipelines by bean name
     */
    @ReadOperation
    public PipelinesReport pipelines() {
        Map<String, PipelineSummary> pipelines = new TreeMap<>();
        beans.getBeansOfType(Pipeline.class).forEach((bean, pipeline) -> {
            PipelineDescription description = describe(pipeline);
            pipelines.put(
                    bean,
                    description == null
                            ? new PipelineSummary(null, 0, 0, false)
                            : new PipelineSummary(
                                    description.name(),
                                    description.layers().size(),
                                    description.stages().size(),
                                    true));
        });
        return new PipelinesReport(pipelines);
    }

    /**
     * Describes one pipeline bean.
     *
     * @param bean the bean name
     * @return its structure, settings and diagram; null if there is no such pipeline
     *         or it cannot describe itself
     */
    @ReadOperation
    public PipelineReport pipeline(@Selector String bean) {
        if (!beans.containsBean(bean) || !beans.isTypeMatch(bean, Pipeline.class)) {
            return null;
        }
        PipelineDescription description = describe(beans.getBean(bean, Pipeline.class));
        if (description == null) {
            return null;
        }
        return new PipelineReport(
                bean,
                description.name(),
                settings(description.config()),
                layers(description),
                MermaidDiagram.of(description));
    }

    private static PipelineDescription describe(Pipeline pipeline) {
        try {
            return pipeline.describe();
        } catch (UnsupportedOperationException opaque) {
            return null;
        }
    }

    private static List<LayerReport> layers(PipelineDescription description) {
        return description.layers().stream()
                .map(layer -> new LayerReport(
                        layer.id(),
                        layer.kind() == LayerDescription.Kind.PARALLEL,
                        layer.stages().stream().map(BatonEndpoint::stage).toList()))
                .toList();
    }

    private static StageReport stage(StageDescription stage) {
        var options = stage.options();
        return new StageReport(
                stage.id(),
                stage.stageType(),
                options.timeout().map(Object::toString).orElse(null),
                options.failurePolicy().name(),
                options.retry().maxAttempts(),
                options.fallback().isPresent(),
                options.condition().isPresent(),
                options.skipsIfInputsMissing(),
                options.overwritePolicy().map(Enum::name).orElse(null),
                stage.requiredReads().stream().map(ContextKey::name).sorted().toList(),
                stage.nested()
                        .map(nested -> new NestedReport(nested.name(), layers(nested)))
                        .orElse(null));
    }

    private static SettingsReport settings(BatonConfig config) {
        return new SettingsReport(
                config.contextFlattenDepth(),
                config.layerConflictPolicy().name(),
                config.overwritePolicy().name(),
                config.typeClashPolicy().name(),
                config.maxParallelStages(),
                config.maxChildTasks(),
                config.runTimeout().map(Object::toString).orElse(null),
                config.contextPropagators().stream()
                        .map(propagator -> propagator.getClass().getName())
                        .toList());
    }

    /**
     * Every pipeline bean.
     *
     * @param pipelines summaries by bean name
     */
    public record PipelinesReport(Map<String, PipelineSummary> pipelines) {}

    /**
     * One pipeline in the list.
     *
     * @param name the pipeline name
     * @param layers how many layers it has
     * @param stages how many stages it has (not counting nested pipelines)
     * @param describable whether its structure can be shown
     */
    public record PipelineSummary(String name, int layers, int stages, boolean describable) {}

    /**
     * One pipeline in detail.
     *
     * @param bean the bean name
     * @param name the pipeline name
     * @param settings its effective settings
     * @param layers its layers, in order
     * @param mermaid a Mermaid flowchart of it
     */
    public record PipelineReport(
            String bean, String name, SettingsReport settings, List<LayerReport> layers, String mermaid) {}

    /**
     * A pipeline's effective settings.
     *
     * @param contextFlattenDepth levels before flattening; 0 means never
     * @param layerConflictPolicy same-layer conflict policy
     * @param overwritePolicy overwrite policy
     * @param typeClashPolicy type-clash policy
     * @param maxParallelStages parallel stage limit; 0 means unlimited
     * @param maxChildTasks child task limit; 0 means unlimited
     * @param runTimeout the run timeout, if any
     * @param contextPropagators the context propagators' classes
     */
    public record SettingsReport(
            int contextFlattenDepth,
            String layerConflictPolicy,
            String overwritePolicy,
            String typeClashPolicy,
            int maxParallelStages,
            int maxChildTasks,
            String runTimeout,
            List<String> contextPropagators) {}

    /**
     * One layer.
     *
     * @param id the layer id
     * @param parallel whether its stages run in parallel
     * @param stages its stages
     */
    public record LayerReport(String id, boolean parallel, List<StageReport> stages) {}

    /**
     * One stage.
     *
     * @param id the stage id
     * @param type the stage implementation's class name
     * @param timeout the stage timeout, if any
     * @param failurePolicy the failure policy
     * @param maxAttempts total attempts allowed
     * @param fallback whether it has a fallback
     * @param conditional whether it has a condition
     * @param optionalInputs whether missing inputs skip it
     * @param overwritePolicy its own overwrite policy, if any
     * @param needs its required context keys
     * @param nested the pipeline it runs, if it is a nested pipeline stage
     */
    public record StageReport(
            String id,
            String type,
            String timeout,
            String failurePolicy,
            int maxAttempts,
            boolean fallback,
            boolean conditional,
            boolean optionalInputs,
            String overwritePolicy,
            List<String> needs,
            NestedReport nested) {}

    /**
     * A nested pipeline.
     *
     * @param name its name
     * @param layers its layers
     */
    public record NestedReport(String name, List<LayerReport> layers) {}
}
