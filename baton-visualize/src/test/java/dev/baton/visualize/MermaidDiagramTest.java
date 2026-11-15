package dev.baton.visualize;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.LayerConflictPolicy;
import dev.baton.api.OverwritePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageOptions;
import dev.baton.api.description.LayerDescription;
import dev.baton.api.description.PipelineDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MermaidDiagramTest {

    private static final ContextKey<String> ORDER = ContextKey.of("order", String.class);
    private static final ContextKey<String> PHONE = ContextKey.of("phone", String.class);
    private static final ContextKey<String> OFFER = ContextKey.of("offer", String.class);

    private static Pipeline checkout() {
        Pipeline payment = Pipeline.builder()
                .name("payment")
                .stage(
                        "authorize",
                        context -> ContextPatch.empty(),
                        StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)))
                .stage("capture", context -> ContextPatch.empty())
                .build();
        return Pipeline.builder(BatonConfig.builder()
                        .layerConflictPolicy(LayerConflictPolicy.LAST_WINS)
                        .build())
                .name("checkout")
                .stage(
                        "load",
                        context -> ContextPatch.builder().put(ORDER, "A-1").build())
                .parallel("enrich", parallel -> {
                    parallel.stage(
                            "customer",
                            context -> ContextPatch.builder().put(OFFER, "a").build());
                    parallel.stage(
                            "recommend",
                            context -> ContextPatch.builder().put(OFFER, "b").build(),
                            StageOptions.defaults()
                                    .withTimeout(Duration.ofMillis(100))
                                    .withFallback((context, failure) -> ContextPatch.empty()));
                })
                .stage("payment", payment.asStage(), StageOptions.defaults().withTimeout(Duration.ofSeconds(2)))
                .stage(
                        "sms",
                        needs(PHONE),
                        StageOptions.defaults()
                                .skipIfInputsMissing()
                                .withOverwritePolicy(OverwritePolicy.KEEP_EXISTING))
                .stage(
                        "audit",
                        context -> ContextPatch.empty(),
                        StageOptions.defaults()
                                .withCondition(context -> true)
                                .withFailurePolicy(FailurePolicy.CONTINUE))
                .build();
    }

    @Test
    void describeReportsLayersOptionsAndNestedPipelines() {
        PipelineDescription description = checkout().describe();

        assertThat(description.name()).isEqualTo("checkout");
        assertThat(description.layers())
                .extracting(LayerDescription::kind)
                .containsExactly(
                        LayerDescription.Kind.SEQUENTIAL,
                        LayerDescription.Kind.PARALLEL,
                        LayerDescription.Kind.SEQUENTIAL,
                        LayerDescription.Kind.SEQUENTIAL,
                        LayerDescription.Kind.SEQUENTIAL);
        assertThat(description.config().layerConflictPolicy()).isEqualTo(LayerConflictPolicy.LAST_WINS);
        assertThat(description.stage("payment/authorize"))
                .hasValueSatisfying(stage ->
                        assertThat(stage.options().retry().maxAttempts()).isEqualTo(3));
        assertThat(description.stage("payment").orElseThrow().nested())
                .hasValueSatisfying(nested -> assertThat(nested.name()).isEqualTo("payment"));
        assertThat(description.stage("sms").orElseThrow().requiredReads()).containsExactly(PHONE);
        assertThat(description.stage("load").orElseThrow().stageType()).isEqualTo("lambda");
        assertThat(description.stage("missing")).isEmpty();
        assertThat(description.stage("payment/missing")).isEmpty();
    }

    @Test
    void structureDiagramShowsLayersParallelBoxesNestedBoxesAndOptions() throws IOException {
        String diagram = MermaidDiagram.of(checkout().describe());
        write("structure.mmd", diagram);

        assertThat(diagram).startsWith("---\ntitle: \"checkout\"\n---\nflowchart TB\n");
        assertThat(diagram).contains("subgraph layer2 [\"enrich (parallel)\"]");
        assertThat(diagram).contains("<b>recommend</b><br/>timeout 100 ms · fallback");
        assertThat(diagram)
                .contains(
                        "subgraph stage5 [\"<b>payment</b> (nested)\"]",
                        "stage5_info[\"timeout 2 s\"]",
                        "class stage5_info info",
                        "stage5_info ~~~ stage6");
        assertThat(diagram).contains("<b>authorize</b><br/>retry x3");
        assertThat(diagram).contains("<b>sms</b><br/>optional inputs · overwrite KEEP_EXISTING · needs phone");
        assertThat(diagram).contains("<b>audit</b><br/>conditional · CONTINUE");
        // Edges fan out to and in from each parallel stage, so they sit side by side.
        assertThat(diagram)
                .contains(
                        "start --> stage1",
                        "stage1 --> stage3",
                        "stage1 --> stage4",
                        "stage3 --> stage5",
                        "stage4 --> stage5",
                        "--> finish");
        // The nested pipeline chains its own layers inside its box.
        assertThat(diagram).contains("stage6 --> stage7");
        assertThat(diagram).doesNotContain("classDef succeeded");
    }

    @Test
    void resultOverlayColorsStagesAndMarksStagesNotReached() throws Exception {
        Pipeline pipeline = checkout();
        PipelineResult result = pipeline.run(ContextView.empty());
        String diagram = MermaidDiagram.of(pipeline.describe(), result);
        write("result.mmd", diagram);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(diagram).contains("title: \"checkout - COMPLETED\"");
        assertThat(diagram).contains("classDef succeeded", "class stage1 succeeded", "class stage8 skipped");
        assertThat(diagram).contains("SUCCEEDED · ");
        assertThat(diagram).contains("conflict on offer: LAST_WINS");
        assertThat(diagram).contains("class stage6 succeeded");
    }

    @Test
    void failedRunShowsTheFailureAndStagesItNeverReached() throws Exception {
        Pipeline pipeline = Pipeline.builder()
                .name("broken")
                .stage("ok", context -> ContextPatch.empty())
                .stage("fails", context -> {
                    throw new IllegalStateException("boom");
                })
                .stage("never", context -> ContextPatch.empty())
                .build();
        PipelineResult result = pipeline.run(ContextView.empty());
        String diagram = MermaidDiagram.of(pipeline.describe(), result);
        write("failed.mmd", diagram);

        assertThat(diagram).contains("title: \"broken - FAILED (not successful)\"");
        assertThat(diagram).contains("class stage1 succeeded", "class stage2 failed", "class stage3 notReached");
        assertThat(diagram).contains("<b>never</b><br/>not reached");
    }

    @Test
    void optionsCanBeHiddenAndLayoutTurned() {
        String diagram = MermaidDiagram.builder(checkout().describe())
                .leftToRight()
                .showOptions(false)
                .render();

        assertThat(diagram).contains("flowchart LR");
        assertThat(diagram).contains("[\"<b>recommend</b>\"]");
        assertThat(diagram).doesNotContain("timeout");
    }

    @Test
    void consecutiveParallelLayersMeetAtAJoinPoint() throws IOException {
        Pipeline pipeline = Pipeline.builder()
                .name("fan")
                .parallel("first", parallel -> {
                    parallel.stage("a", context -> ContextPatch.empty());
                    parallel.stage("b", context -> ContextPatch.empty());
                })
                .parallel("second", parallel -> {
                    parallel.stage("c", context -> ContextPatch.empty());
                    parallel.stage("d", context -> ContextPatch.empty());
                })
                .build();
        String diagram = MermaidDiagram.of(pipeline.describe());
        write("fan.mmd", diagram);

        assertThat(diagram).contains("(( ))", "class join");
        assertThat(diagram.lines().filter(line -> line.contains(" --> ")).count())
                .as("2 + 2 join edges, 2 from start, 2 to end")
                .isEqualTo(8);
    }

    @Test
    void labelsAreEscaped() throws IOException {
        Pipeline pipeline = Pipeline.builder()
                .name("quote \"me\"")
                .stage("a<b>&\"c\"", context -> ContextPatch.empty())
                .build();
        String diagram = MermaidDiagram.of(pipeline.describe());
        write("escaped.mmd", diagram);

        assertThat(diagram).contains("title: \"quote 'me'\"");
        assertThat(diagram).contains("<b>a#lt;b#gt;#amp;#quot;c#quot;</b>");
    }

    @Test
    void lineBreaksInIdsAndNamesDoNotBreakTheDiagram() throws IOException {
        Pipeline pipeline = Pipeline.builder()
                .name("multi\nline")
                .stage("load\norder", context -> ContextPatch.empty())
                .build();
        String diagram = MermaidDiagram.of(pipeline.describe());
        write("newlines.mmd", diagram);

        assertThat(diagram).contains("title: \"multi line\"", "<b>load order</b>");
        assertThat(diagram.lines()).noneMatch(line -> line.equals("order</b>\"]"));
    }

    private static Stage needs(ContextKey<?> key) {
        return new Stage() {
            @Override
            public ContextPatch execute(StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return Set.of(key);
            }
        };
    }

    /** Writes sample diagrams to build/diagrams for inspection in a Mermaid renderer. */
    private static void write(String name, String diagram) throws IOException {
        Path dir = Path.of("build", "diagrams");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), diagram);
    }
}
