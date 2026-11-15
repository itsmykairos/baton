# Changelog

All notable changes to Baton are recorded here. Versions follow
[Semantic Versioning](https://semver.org); while the version is 0.x, minor versions may
change the API. Every public API change shows in `api/<module>.api`.

## [0.1.0] - unreleased

First release.

### Pipelines and execution
- Pipelines of sequential and parallel layers on virtual threads, sharing an immutable
  context through typed `ContextKey`s and `ContextPatch` writes.
- Failure policies (`FAIL_FAST`, `CONTINUE`): FAIL_FAST cancels the rest of its layer at
  once.
- Stage timeouts, run timeouts and cancellation. These never wait for worker threads to
  exit, and timeouts are reported as `StageTimeoutException`.
- Child tasks forked from a stage (`StageTasks`). An unjoined failed child fails its
  stage.
- Nested pipelines (`Pipeline.asStage()`) run inside the parent run, inherit its deadline
  and cancellation, and report their stages through `StageResult.children()`.

### Resilience
- Retries with backoff, jitter and per-attempt timeouts (`RetryPolicy`).
- Fallbacks that recover a stage as `RECOVERED` (`StageFallback`).
- Conditional stages (`withCondition`, `skipIfInputsMissing`) reported as `SKIPPED`.

### Configuration
- `BatonConfig`, layered app → pipeline → run → stage:
  - context flatten depth;
  - key-conflict policies (same layer, overwrite, type clash), with every conflict
    reported in `PipelineResult.conflicts()`;
  - parallelism and child-task limits, thread factory and default stage options;
  - event delivery settings.

### Observability and context
- Asynchronous run and stage events (`PipelineListener`) that never slow a run.
- `baton-micrometer`: stage and run timers, retries, conflicts.
- `baton-opentelemetry`: tracing from events, or live run and stage spans through a
  context propagator.
- Context propagation (`ContextPropagator`, `BatonContext.current()`), with an SLF4J MDC
  propagator in `baton-slf4j`.

### Tooling
- `Pipeline.describe()` and Mermaid diagrams of pipelines and runs (`baton-visualize`).
- `baton-spring-boot-starter` for Spring Boot 4.1:
  - `baton.*` properties;
  - pipelines declared in YAML;
  - auto-wired MDC, metrics and tracing;
  - `/actuator/baton`.
- `baton-testkit`: `StageTester`, `PipelineResults` and `RecordingListener`.
- `baton-bom` to align module versions.
- A docs site with a getting-started guide, a cookbook of tested recipes and the
  Javadoc, at <https://kairos-tools.github.io/baton/>.
- JPMS modules: `dev.baton.api` is the only module applications compile against.

[0.1.0]: https://github.com/kairos-tools/baton/releases/tag/v0.1.0
