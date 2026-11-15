# Cookbook

Short recipes for common pipeline shapes. Each one is a test in
[`examples/cookbook`](https://github.com/kairos-tools/baton/tree/main/examples/cookbook),
so the code here compiles and runs against the current API. Run them with
`./gradlew :examples:cookbook:test`.

| Recipe | Shows |
|---|---|
| [Fan out and aggregate](fan-out.md) | Parallel layers, and child tasks per item. |
| [Retries, timeouts and fallbacks](resilience.md) | Backoff, attempt timeouts, fallbacks, `CONTINUE`, run timeouts. |
| [Conditional stages](conditional-stages.md) | Conditions, required inputs, `SKIPPED` and `BLOCKED`. |
| [Nested pipelines](nested-pipelines.md) | Reusing a pipeline as one stage of another. |
| [Async runs and cancellation](async-runs.md) | `start`, `await` and `cancel`. |
| [Events, metrics and logging](observability.md) | Listeners, the MDC in stage threads, the current stage. |
| [Testing stages](testing.md) | `StageTester`, `TestStageContext` and `PipelineResults`. |

The [Spring Boot](../spring-boot.md) guide covers pipelines declared in YAML, and
[Visualization](../visualization.md) covers Mermaid diagrams.
