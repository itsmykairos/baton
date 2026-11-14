package dev.baton.visualize;

import dev.baton.api.ContextKey;
import dev.baton.api.FailurePolicy;
import dev.baton.api.KeyConflict;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.StageOptions;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.api.description.LayerDescription;
import dev.baton.api.description.PipelineDescription;
import dev.baton.api.description.StageDescription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a pipeline as a <a href="https://mermaid.js.org/syntax/flowchart.html">Mermaid
 * flowchart</a>, optionally overlaid with a run's result.
 *
 * <ul>
 *   <li>Layers run top to bottom, from {@code start} to {@code end}.</li>
 *   <li>A parallel layer is a box whose stages sit side by side.</li>
 *   <li>A nested pipeline is a box holding its own layers.</li>
 *   <li>Stage labels list the options that change behaviour: timeout, retries,
 *       fallback, condition, optional inputs, failure policy, overwrite policy and
 *       required inputs.</li>
 *   <li>With a result, each stage is colored by status and shows its duration and
 *       attempts; stages the run never reached are marked "not reached", and
 *       resolved key conflicts are listed on the stages involved.</li>
 * </ul>
 *
 * <pre>{@code
 * String diagram = MermaidDiagram.of(pipeline.describe());
 * String afterRun = MermaidDiagram.of(pipeline.describe(), result);
 * String wide = MermaidDiagram.builder(pipeline.describe()).leftToRight().showOptions(false).render();
 * }</pre>
 *
 * <p>The output is plain text: paste it into a {@code ```mermaid} block in Markdown
 * (GitHub, GitLab and most documentation sites render it), or into the Mermaid
 * live editor.</p>
 */
public final class MermaidDiagram {

    private final PipelineDescription description;
    private PipelineResult result;
    private boolean leftToRight;
    private boolean showOptions = true;

    // Rendering state.
    private final StringBuilder out = new StringBuilder();
    private final Map<String, String> classes = new LinkedHashMap<>();
    private final Map<String, StageResult> resultsByPath = new HashMap<>();
    private final Map<String, List<KeyConflict>> conflictsByPath = new HashMap<>();
    private int nextId;

    private MermaidDiagram(PipelineDescription description) {
        this.description = Objects.requireNonNull(description, "description");
    }

    /**
     * Renders a pipeline's structure with default settings.
     *
     * @param description the pipeline, from {@code Pipeline.describe()}
     * @return Mermaid flowchart source
     */
    public static String of(PipelineDescription description) {
        return builder(description).render();
    }

    /**
     * Renders a pipeline's structure overlaid with a run's result.
     *
     * @param description the pipeline, from {@code Pipeline.describe()}
     * @param result a result of running that pipeline
     * @return Mermaid flowchart source
     */
    public static String of(PipelineDescription description, PipelineResult result) {
        return builder(description).result(result).render();
    }

    /**
     * Starts a diagram with non-default settings.
     *
     * @param description the pipeline, from {@code Pipeline.describe()}
     * @return a diagram builder
     */
    public static MermaidDiagram builder(PipelineDescription description) {
        return new MermaidDiagram(description);
    }

    /**
     * Overlays a run's result.
     *
     * @param result a result of running the described pipeline
     * @return this diagram
     */
    public MermaidDiagram result(PipelineResult result) {
        this.result = Objects.requireNonNull(result, "result");
        return this;
    }

    /**
     * Lays layers out left to right instead of top to bottom; parallel stages are
     * then stacked vertically.
     *
     * @return this diagram
     */
    public MermaidDiagram leftToRight() {
        this.leftToRight = true;
        return this;
    }

    /**
     * Sets whether stage labels list options and required inputs.
     *
     * @param show false for stage ids (and run details) only
     * @return this diagram
     */
    public MermaidDiagram showOptions(boolean show) {
        this.showOptions = show;
        return this;
    }

    /**
     * Renders the diagram.
     *
     * @return Mermaid flowchart source
     */
    public String render() {
        out.setLength(0);
        classes.clear();
        resultsByPath.clear();
        conflictsByPath.clear();
        nextId = 0;
        if (result != null) {
            indexResults(result.stages(), "");
            for (KeyConflict conflict : result.conflicts()) {
                conflict.stages().forEach(path ->
                        conflictsByPath.computeIfAbsent(path.toString(), ignored -> new ArrayList<>()).add(conflict));
            }
        }

        String title = description.name();
        if (result != null) {
            title += " - " + result.status() + (result.isSuccessful() ? "" : " (not successful)");
        }
        out.append("---\ntitle: \"").append(title.replace("\"", "'")).append("\"\n---\n");
        out.append("flowchart ").append(leftToRight ? "LR" : "TB").append('\n');
        out.append("  start([\"start\"])\n");
        out.append("  finish([\"end\"])\n");
        List<List<String>> chain = new ArrayList<>();
        chain.add(List.of("start"));
        chain.addAll(renderLayers(description, "", "  "));
        chain.add(List.of("finish"));
        connect(chain, "  ");
        if (result != null) {
            appendClassDefinitions();
            classes.forEach((id, css) -> out.append("  class ").append(id).append(' ').append(css).append('\n'));
        }
        out.append("  classDef terminal fill:#f8f9fa,stroke:#5f6368,color:#202124\n");
        out.append("  classDef join fill:#bdc1c6,stroke:#bdc1c6\n");
        out.append("  classDef info fill:none,stroke:none,color:#5f6368\n");
        out.append("  class start,finish terminal\n");
        return out.toString();
    }

    /**
     * Renders a pipeline's layers. Returns, for each layer in order, the ids of its
     * elements: one for a sequential stage, one per stage for a parallel layer.
     * Edges go to each parallel stage rather than to the layer's box, because
     * Mermaid only lays out a box's stages side by side when nothing links to the
     * box itself.
     */
    private List<List<String>> renderLayers(PipelineDescription pipeline, String pathPrefix, String indent) {
        List<List<String>> layers = new ArrayList<>();
        for (LayerDescription layer : pipeline.layers()) {
            if (layer.kind() == LayerDescription.Kind.PARALLEL) {
                String id = "layer" + (++nextId);
                out.append(indent).append("subgraph ").append(id).append(" [\"")
                        .append(escape(layer.id())).append(" (parallel)\"]\n");
                List<String> stages = new ArrayList<>();
                for (StageDescription stage : layer.stages()) {
                    stages.add(renderStage(stage, pathPrefix, indent + "  "));
                }
                out.append(indent).append("end\n");
                out.append(indent).append("style ").append(id)
                        .append(" fill:#fafafa,stroke:#bdc1c6,stroke-dasharray:6 4\n");
                layers.add(stages);
            } else {
                layers.add(List.of(renderStage(layer.stages().getFirst(), pathPrefix, indent)));
            }
        }
        return layers;
    }

    /**
     * Links consecutive layers: every element of one to every element of the next,
     * through a small join point when both have several elements.
     */
    private void connect(List<List<String>> layers, String indent) {
        for (int i = 0; i + 1 < layers.size(); i++) {
            List<String> from = layers.get(i);
            List<String> to = layers.get(i + 1);
            if (from.size() > 1 && to.size() > 1) {
                String join = "join" + (++nextId);
                out.append(indent).append(join).append("(( ))\n");
                out.append(indent).append("class ").append(join).append(" join\n");
                from.forEach(id -> edge(id, join, indent));
                to.forEach(id -> edge(join, id, indent));
            } else {
                for (String source : from) {
                    for (String target : to) {
                        edge(source, target, indent);
                    }
                }
            }
        }
    }

    private void edge(String from, String to, String indent) {
        // An info node only positions the nested box's details; it is not a step.
        String arrow = from.endsWith("_info") ? " ~~~ " : " --> ";
        out.append(indent).append(from).append(arrow).append(to).append('\n');
    }

    /** Renders one stage as a node, or a nested pipeline as a box; returns its id. */
    private String renderStage(StageDescription stage, String pathPrefix, String indent) {
        String path = pathPrefix + stage.id();
        String id = "stage" + (++nextId);
        StageResult stageResult = resultsByPath.get(path);
        List<String> lines = labelLines(stage, path, stageResult);

        if (stage.nested().isPresent()) {
            // Mermaid does not make room for multi-line box titles, so the title is the
            // stage name and the details go in a borderless info node at the top.
            out.append(indent).append("subgraph ").append(id).append(" [\"").append(lines.getFirst())
                    .append("\"]\n");
            // Honoured because edges link the box itself, never the nodes inside it.
            out.append(indent).append("  direction ").append(leftToRight ? "LR" : "TB").append('\n');
            List<List<String>> inner = new ArrayList<>();
            if (lines.size() > 1) {
                String info = id + "_info";
                out.append(indent).append("  ").append(info).append("[\"")
                        .append(String.join("<br/>", lines.subList(1, lines.size()))).append("\"]\n");
                out.append(indent).append("  class ").append(info).append(" info\n");
                inner.add(List.of(info));
            }
            inner.addAll(renderLayers(stage.nested().get(), path + "/", indent + "  "));
            connect(inner, indent + "  ");
            out.append(indent).append("end\n");
        } else {
            out.append(indent).append(id).append("[\"").append(String.join("<br/>", lines)).append("\"]\n");
        }
        if (result != null) {
            classes.put(id, stageResult == null ? "notReached" : cssClass(stageResult.status()));
        }
        return id;
    }

    private List<String> labelLines(StageDescription stage, String path, StageResult stageResult) {
        List<String> lines = new ArrayList<>();
        lines.add("<b>" + escape(stage.id()) + "</b>" + (stage.nested().isPresent() ? " (nested)" : ""));
        if (showOptions) {
            String options = describeOptions(stage);
            if (!options.isEmpty()) {
                lines.add(options);
            }
        }
        if (result != null) {
            if (stageResult == null) {
                lines.add("not reached");
            } else {
                StringBuilder run = new StringBuilder(stageResult.status().name());
                if (stageResult.attempts() > 0) {
                    run.append(" · ").append(format(stageResult.duration()));
                }
                if (stageResult.attempts() > 1) {
                    run.append(" · ").append(stageResult.attempts()).append(" attempts");
                }
                lines.add(run.toString());
            }
            for (KeyConflict conflict : conflictsByPath.getOrDefault(path, List.of())) {
                lines.add("conflict on " + escape(conflict.key()) + ": " + conflict.resolution());
            }
        }
        return lines;
    }

    private static String describeOptions(StageDescription stage) {
        StageOptions options = stage.options();
        List<String> parts = new ArrayList<>();
        options.timeout().ifPresent(timeout -> parts.add("timeout " + format(timeout)));
        RetryPolicy retry = options.retry();
        if (retry.maxAttempts() > 1) {
            parts.add("retry x" + retry.maxAttempts());
        }
        retry.attemptTimeout().ifPresent(timeout -> parts.add("attempt timeout " + format(timeout)));
        if (options.fallback().isPresent()) {
            parts.add("fallback");
        }
        if (options.condition().isPresent()) {
            parts.add("conditional");
        }
        if (options.skipsIfInputsMissing()) {
            parts.add("optional inputs");
        }
        if (options.failurePolicy() != FailurePolicy.FAIL_FAST) {
            parts.add(options.failurePolicy().name());
        }
        options.overwritePolicy().ifPresent(policy -> parts.add("overwrite " + policy));
        if (!stage.requiredReads().isEmpty()) {
            parts.add("needs " + String.join(", ", stage.requiredReads().stream()
                    .map(ContextKey::name).sorted().map(MermaidDiagram::escape).toList()));
        }
        return String.join(" · ", parts);
    }

    private void indexResults(List<StageResult> stages, String pathPrefix) {
        for (StageResult stage : stages) {
            String path = pathPrefix + stage.stageId();
            resultsByPath.put(path, stage);
            indexResults(stage.children(), path + "/");
        }
    }

    private static String cssClass(StageStatus status) {
        return switch (status) {
            case SUCCEEDED -> "succeeded";
            case RECOVERED -> "recovered";
            case SKIPPED -> "skipped";
            case FAILED -> "failed";
            case TIMED_OUT -> "timedOut";
            case BLOCKED -> "blocked";
            case CANCELLED -> "cancelled";
        };
    }

    private void appendClassDefinitions() {
        out.append("  classDef succeeded fill:#e6f4ea,stroke:#1e8e3e,color:#0d652d\n");
        out.append("  classDef recovered fill:#fef7e0,stroke:#f9ab00,color:#7a4f01\n");
        out.append("  classDef skipped fill:#f1f3f4,stroke:#9aa0a6,color:#5f6368,stroke-dasharray:4 3\n");
        out.append("  classDef failed fill:#fce8e6,stroke:#d93025,color:#a50e0e\n");
        out.append("  classDef timedOut fill:#fce8e6,stroke:#d93025,color:#a50e0e,stroke-dasharray:4 3\n");
        out.append("  classDef blocked fill:#fff4e5,stroke:#e8710a,color:#8a4500\n");
        out.append("  classDef cancelled fill:#f3e8fd,stroke:#9334e6,color:#5b1f9e\n");
        out.append("  classDef notReached fill:#ffffff,stroke:#dadce0,color:#9aa0a6,stroke-dasharray:2 2\n");
    }

    private static String format(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + " ms";
        }
        double seconds = millis / 1_000.0;
        return (seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds)) + " s";
    }

    /** Escapes text for a quoted Mermaid label. */
    private static String escape(String text) {
        return text.replace("&", "#amp;")
                .replace("\"", "#quot;")
                .replace("<", "#lt;")
                .replace(">", "#gt;");
    }
}
