package dev.baton.spring.boot;

import dev.baton.api.BatonConfig;
import dev.baton.api.Pipeline;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;
import dev.baton.spring.boot.BatonProperties.PipelineSpec;
import dev.baton.spring.boot.BatonProperties.StageSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Registers a {@link Pipeline} bean for each pipeline declared under
 * {@code baton.pipelines}, named after its key.
 *
 * <pre>
 * baton:
 *   pipelines:
 *     checkout:                    # bean name
 *       timeout: 5s
 *       layers:
 *         - stage: loadOrder       # a Stage bean
 *         - parallel: enrich       # a parallel layer
 *           stages:
 *             - stage: customer
 *             - stage: recommendations
 *               timeout: 100ms
 *               fallback: noRecommendations   # a StageFallback bean
 *         - pipeline: payment      # a Pipeline bean (configured or @Bean), run nested
 *           retry: { max-attempts: 3, backoff: 50ms, multiplier: 2 }
 * </pre>
 *
 * <p>The layout is validated at startup: each layer must name exactly one of
 * {@code stage}, {@code pipeline} or {@code parallel}, and configured pipelines must
 * not nest each other in a cycle. Stage, pipeline, fallback and condition beans are
 * looked up when the pipeline bean is created; a missing one fails startup with the
 * property path that names it.</p>
 */
public final class BatonPipelineRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;

    /** Creates the registrar; Spring calls it while bean definitions are loaded. */
    public BatonPipelineRegistrar() {}

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        Map<String, PipelineSpec> specs = Binder.get(environment)
                .bind("baton.pipelines", Bindable.mapOf(String.class, PipelineSpec.class))
                .orElse(Map.of());
        validate(specs);
        BeanFactory beans = (BeanFactory) registry;
        specs.forEach((key, spec) -> {
            RootBeanDefinition definition = new RootBeanDefinition(Pipeline.class, () -> build(key, spec, beans));
            definition.setDescription("Pipeline configured under baton.pipelines." + key);
            registry.registerBeanDefinition(key, definition);
        });
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}

    // ---- validation --------------------------------------------------------------

    static void validate(Map<String, PipelineSpec> specs) {
        Map<String, Set<String>> nests = new LinkedHashMap<>();
        specs.forEach((key, spec) -> {
            String where = "baton.pipelines." + key;
            if (spec.layers() == null || spec.layers().isEmpty()) {
                throw new IllegalStateException(where + ".layers must list at least one layer");
            }
            Set<String> nested = new HashSet<>();
            for (int i = 0; i < spec.layers().size(); i++) {
                StageSpec layer = spec.layers().get(i);
                String path = where + ".layers[" + i + "]";
                if (layer.parallel() != null) {
                    requireOne(layer, path, true);
                    if (layer.id() != null || StageSpecs.hasOptions(layer)) {
                        throw new IllegalStateException(path + ": options and id cannot be set on a parallel "
                                + "layer; set them on each entry under stages");
                    }
                    if (layer.stages() == null || layer.stages().isEmpty()) {
                        throw new IllegalStateException(path + ".stages must list the parallel layer's stages");
                    }
                    for (int j = 0; j < layer.stages().size(); j++) {
                        StageSpec stage = layer.stages().get(j);
                        String stagePath = path + ".stages[" + j + "]";
                        if (stage.parallel() != null || stage.stages() != null) {
                            throw new IllegalStateException(stagePath + ": parallel layers cannot be nested");
                        }
                        requireOne(stage, stagePath, false);
                        if (stage.pipeline() != null) {
                            nested.add(stage.pipeline());
                        }
                    }
                } else {
                    requireOne(layer, path, false);
                    if (layer.stages() != null) {
                        throw new IllegalStateException(path + ".stages is only allowed with parallel");
                    }
                    if (layer.pipeline() != null) {
                        nested.add(layer.pipeline());
                    }
                }
            }
            nests.put(key, nested);
        });
        for (String key : nests.keySet()) {
            findCycle(key, nests, new ArrayList<>());
        }
    }

    private static void requireOne(StageSpec spec, String path, boolean parallel) {
        int named = (spec.stage() != null ? 1 : 0) + (spec.pipeline() != null ? 1 : 0) + (parallel ? 1 : 0);
        if (named != 1) {
            throw new IllegalStateException(path + " must name exactly one of stage, pipeline or parallel");
        }
    }

    private static void findCycle(String key, Map<String, Set<String>> nests, List<String> trail) {
        if (trail.contains(key)) {
            List<String> cycle = new ArrayList<>(trail.subList(trail.indexOf(key), trail.size()));
            cycle.add(key);
            throw new IllegalStateException(
                    "baton.pipelines nest each other in a cycle: " + String.join(" -> ", cycle));
        }
        Set<String> nested = nests.get(key);
        if (nested == null) {
            return; // a Pipeline bean declared in code, not in configuration
        }
        trail.add(key);
        for (String child : nested) {
            findCycle(child, nests, trail);
        }
        trail.removeLast();
    }

    // ---- building ----------------------------------------------------------------

    static Pipeline build(String key, PipelineSpec spec, BeanFactory beans) {
        BatonConfig config = beans.getBean(BatonConfig.class);
        Pipeline.Builder builder = beans.getBean(Pipeline.Builder.class);
        builder.name(spec.name() != null ? spec.name() : key);
        if (spec.timeout() != null) {
            builder.timeout(spec.timeout());
        }
        for (int i = 0; i < spec.layers().size(); i++) {
            StageSpec layer = spec.layers().get(i);
            String path = "baton.pipelines." + key + ".layers[" + i + "]";
            if (layer.parallel() != null) {
                builder.parallel(layer.parallel(), parallel -> {
                    for (int j = 0; j < layer.stages().size(); j++) {
                        add(
                                parallel::stage,
                                parallel::stage,
                                layer.stages().get(j),
                                beans,
                                config,
                                path + ".stages[" + j + "]");
                    }
                });
            } else {
                add(builder::stage, builder::stage, layer, beans, config, path);
            }
        }
        return builder.build();
    }

    private static void add(
            WithoutOptions withoutOptions,
            WithOptions withOptions,
            StageSpec spec,
            BeanFactory beans,
            BatonConfig config,
            String path) {
        Stage stage;
        String id;
        if (spec.stage() != null) {
            stage = StageSpecs.bean(beans, spec.stage(), Stage.class, path + ".stage");
            id = spec.id() != null ? spec.id() : spec.stage();
        } else {
            stage = StageSpecs.bean(beans, spec.pipeline(), Pipeline.class, path + ".pipeline")
                    .asStage();
            id = spec.id() != null ? spec.id() : spec.pipeline();
        }
        if (StageSpecs.hasOptions(spec)) {
            StageOptions options = StageSpecs.apply(config.defaultStageOptions(), spec, beans, path);
            withOptions.add(id, stage, options);
        } else {
            // Inherits the pipeline's default stage options.
            withoutOptions.add(id, stage);
        }
    }

    @FunctionalInterface
    private interface WithoutOptions {
        void add(String id, Stage stage);
    }

    @FunctionalInterface
    private interface WithOptions {
        void add(String id, Stage stage, StageOptions options);
    }
}
