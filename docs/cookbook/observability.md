# Events, metrics and logging

## React to events

A `PipelineListener` receives `RunStarted`, `StageStarted`, `StageRetrying`,
`StageFinished`, `ContextConflict` and `RunFinished`. Attach it to a pipeline, for
every run, or to one run:

```java
--8<-- "ObservabilityTest.java:listener"
```

- Events are delivered asynchronously, in batches, by one virtual thread per run. A
  slow or failing listener never slows or breaks the pipeline.
- Events of one run arrive in order, and `RunFinished` is always last. It can arrive
  after `run()` returns.
- A run with no listeners creates no events at all.

For metrics and traces, add an adapter instead of writing a listener:

| Module | Listener |
|---|---|
| `baton-micrometer` | `MicrometerPipelineListener`: stage and run timers, retries, conflicts, active runs. |
| `baton-opentelemetry` | `OpenTelemetryPipelineListener`: a span per run and per stage. For live spans that stage code can see, use `OpenTelemetryContextPropagator`. |

## Keep the logging MDC in stage threads

Stages run on other threads than the caller, so thread-bound context is lost unless a
`ContextPropagator` carries it. `baton-slf4j` carries the MDC, and adds the run id,
pipeline name and stage path:

```java
--8<-- "ObservabilityTest.java:mdc"
```

Context is captured at every hop (caller, stage, child task, nested pipeline, condition
and fallback) and restored around the work. See [Context propagation](../propagation.md).

## Know which stage is running

`BatonContext.current()` describes the code that is running now: the run id, the
pipeline and the stage path.

```java
--8<-- "ObservabilityTest.java:scope"
```
