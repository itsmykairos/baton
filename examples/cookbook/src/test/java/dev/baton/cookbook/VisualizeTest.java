package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.visualize.MermaidDiagram;
import org.junit.jupiter.api.Test;

class VisualizeTest {

    @Test
    void mermaid() throws Exception {
        Pipeline checkout = Pipeline.builder()
                .name("checkout")
                .stage("load", stage -> ContextPatch.empty())
                .parallel("enrich", parallel -> {
                    parallel.stage("customer", stage -> ContextPatch.empty());
                    parallel.stage("price", stage -> ContextPatch.empty());
                })
                .build();

        // --8<-- [start:mermaid]
        // The structure: layers, stages, their options and nested pipelines.
        String structure = MermaidDiagram.of(checkout.describe());

        // A run overlaid on it: stages colored by status, with durations and attempts.
        PipelineResult result = checkout.run(ContextView.empty());
        String run = MermaidDiagram.builder(checkout.describe())
                .result(result)
                .leftToRight()
                .render();
        // Paste either into a ```mermaid block in Markdown, or into mermaid.live.
        // --8<-- [end:mermaid]

        assertThat(structure).contains("flowchart TB", "customer", "price");
        assertThat(run).contains("load");
    }
}
