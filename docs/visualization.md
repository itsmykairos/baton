# Visualization

## Describing a pipeline

`Pipeline.describe()` returns a `PipelineDescription`: the pipeline's structure, built
from the definition with nothing run. It contains:
- each layer, sequential or parallel, in order;
- each stage's id, type and resolved `StageOptions`, including the pipeline's default
  stage options;
- each stage's required inputs;
- nested pipelines, described recursively;
- the pipeline's resolved `BatonConfig`.

```java
PipelineDescription description = checkout.describe();
description.stage("payment/authorize").orElseThrow().options().retry().maxAttempts();  // 3
```

It's read-only data for documentation, diagrams and tooling. A `Pipeline` implementation
outside `baton-core` may not support it, in which case `describe()` throws
`UnsupportedOperationException`.

## Mermaid diagrams

The optional `baton-visualize` module renders a description as a
[Mermaid flowchart](https://mermaid.js.org/syntax/flowchart.html). It depends only on
`baton-api`.

```java
String design = MermaidDiagram.of(checkout.describe());           // structure
String run    = MermaidDiagram.of(checkout.describe(), result);   // a run overlaid on it
String wide   = MermaidDiagram.builder(checkout.describe())
        .leftToRight()
        .showOptions(false)
        .render();
```

Paste the output into a ` ```mermaid ` block in Markdown, where GitHub, GitLab and most
documentation sites render it, or into the Mermaid live editor.

**What a diagram shows**
- **Layers** run from `start` to `end`.
- **Parallel layers** are dashed boxes with their stages side by side. Two parallel
  layers in a row meet at a small join point.
- **Nested pipelines** are boxes holding their own layers. The nested stage's details
  sit at the top of its box.
- **Stage labels** list the options that change behaviour:
  - timeout, retries and attempt timeout;
  - fallback, condition and optional inputs;
  - failure policy if not FAIL_FAST, and overwrite policy;
  - required inputs, as `needs …`.

**With a run result**
- The title shows the run's status.
- Each stage is colored by its status, with its duration and, if retried, its attempts.
- Stages the run never reached are faint and marked "not reached".
- Resolved key conflicts are listed on the stages involved.

| Status | Color |
|---|---|
| SUCCEEDED | green |
| RECOVERED | amber |
| SKIPPED | grey, dashed |
| FAILED | red |
| TIMED_OUT | red, dashed |
| BLOCKED | orange |
| CANCELLED | purple |
| not reached | white, faint |

To see one, run the example: `./gradlew :examples:basic:run --args=--mermaid`.
