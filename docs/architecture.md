# Baton architecture (initial)

## Module boundaries

`baton-api` is the compatibility boundary. It should contain only types required by callers
and stage authors. It must not expose `ExecutorService`, virtual-thread factories, internal
scheduler types, or implementation-specific cancellation machinery.

The boundary is enforced with JPMS: `dev.baton.api` exports only `dev.baton.api` and
`uses PipelineProvider`; `dev.baton.core` exports nothing and `provides` the
`PipelineProvider`. `baton-integration-tests` compiles against the API and testkit only,
with core on the runtime classpath, and checks the module descriptors.

`baton-core` implements the API. Keep packages organized by responsibility:

- `execution`: pipeline and layer coordination
- `context`: immutable context views, keys, patches, merge rules
- `concurrency`: stage-owned task scopes and task handles
- `deadline`: monotonic deadline propagation and timeout decisions
- `nested`: composition of child pipelines and hierarchical execution reporting
- `internal`: implementation details that are not part of the public API

`baton-testkit` contains helpers that make it straightforward to test user-defined stages
and pipeline behavior without depending on core internals.

`examples/` holds runnable examples, one Gradle project per folder (`:examples:<folder>`),
included automatically by `settings.gradle.kts`. They are not published and must not
become dependencies of the library modules.

## Runtime direction

- A stage is the unit of execution and owns its child-task scope.
- A stage scope uses a virtual-thread-per-task executor in the initial implementation.
- On normal completion, close the scope and collect task outcomes.
- On timeout, fail-fast, or parent cancellation, request interruption and never wait
  for worker exit. A FAIL_FAST failure in a parallel layer cancels its unfinished siblings
  immediately.
- If a stage returns while a child task it never joined has already failed, the stage
  fails with that child's exception.
- Timeouts for all runs share one daemon scheduler thread; each registration is
  cancelled individually and its action runs on a virtual thread.
- Record worker termination separately; interruption is cooperative and does not guarantee
  that a task has stopped.
- Child pipelines are ordinary pipelines composed by a parent stage. They have their own
  layers, inherit the parent's absolute deadline, and propagate cancellation to active
  descendants.
  The engine runs a core pipeline adapted with `Pipeline.asStage()` on the parent's
  executor with its own execution control: each child stage's deadline is capped by the
  nested stage's deadline, and cancelling the nested stage cancels the child control,
  which cascades to descendants. A COMPLETED child makes the nested stage SUCCEEDED and
  hands back every key it wrote; child stage results appear in `StageResult.children()`,
  and `isSuccessful()` / `hasFailures()` check them recursively. Other `Pipeline`
  implementations fall back to `NestedPipelineStage.execute()`.
- Context snapshots are immutable. A stage returns one patch for the pipeline to merge.
  `ImmutableContextView` is a chain of small levels. Applying a patch adds one level with
  only its writes and its own name index, so a layer's merge costs O(writes) rather than
  O(context).
  - The chain is flattened every `contextFlattenDepth` levels, or never with the
    default 0.
  - All walks over the chain are iterative, so deep chains cannot overflow the stack.
- Settings come from `BatonConfig`, layered app → pipeline → run → stage
  (see [configuration.md](configuration.md)).
  - Each engine resolves its pipeline-scoped settings from its own definition.
  - Run-scoped settings (thread factory, events, clock) come from the outermost run.
- The engine's `resolveLayer` applies the conflict policies before a layer's merge.
  - It only allocates when a conflict must drop or fail a write.
  - Conflicts are recorded per engine and propagated to parent engines, so a run's
    result lists nested conflicts with full paths.
  Writes to the same key by two stages of one parallel layer are a conflict: those stages
  are FAILED and their patches are not applied. Across layers, later writes overwrite
  earlier values.
- BLOCKED stages (missing `requiredReads`) never stop a run; `requiredReads` is checked at
  run time, not at build time.

## Spring Boot starter

- `BatonAutoConfiguration` builds one `BatonConfig` bean from `baton.*` properties,
  `ContextPropagator` beans and `BatonConfigCustomizer`s.
- It exposes a prototype `Pipeline.Builder` with every `PipelineListener` bean attached.
- Metrics and tracing integrations resolve their `MeterRegistry` or `OpenTelemetry`
  through `ObjectProvider` on first use, so they don't depend on auto-configuration
  ordering.
- `BatonPipelineRegistrar`, a bean-definition registry post-processor, binds
  `baton.pipelines`, validates the layouts and checks for cycles, then registers one
  `Pipeline` bean per entry. Each is built through the prototype builder when it is
  created.
- `BatonEndpoint` reports `describe()` for every `Pipeline` bean when Actuator is present
  and the endpoint is exposed.

## Context propagation

- `Propagation` (core) wraps every hop: run, stage, timed attempt, child task,
  condition and fallback.
  - It captures the configured `ContextPropagator`s on the submitting thread, restores
    them on the thread doing the work, and undoes them in reverse order.
  - `BatonContext.PROPAGATOR` always comes first. With no other propagators, a shared
    snapshot and a direct thread-local set keep the overhead small.
- A nested pipeline's engine coordinates on the nested stage's worker thread, so its
  stage submissions capture the nested stage's restored context. This is what makes
  live spans and MDC stage keys nest.
- Every run has a run id, shared by `BatonContext`, events and propagators.

## Visualization

- `Pipeline.describe()` is implemented by `DefaultPipeline` from its definition. It
  includes resolved stage options and nested descriptions; nested pipelines that
  cannot describe themselves are left out.
- Rendering lives in the optional `baton-visualize` module, which depends on
  `baton-api` only.
- `MermaidDiagram` emits flowchart text. Edges target each parallel stage rather than
  the layer's box, because Mermaid only honours a box's layout when nothing links to
  the box itself.

## Conditional stages

- Before a layer submits anything, `settleBeforeStart` decides each stage's fate:
  - missing `requiredReads` → BLOCKED, or SKIPPED with `skipIfInputsMissing()`;
  - a false condition → SKIPPED;
  - a condition that throws → FAILED, then the fallback may make it RECOVERED.
- If a settled result triggers FAIL_FAST, none of the layer's other stages is submitted;
  they are reported CANCELLED before start.
- Settled stages have no task, no StageStarted event and 0 attempts.
- SKIPPED counts as success in `isSuccessful()` (recursively for nested runs) and never
  triggers a failure policy.

## Resilience

- A stage's worker runs an attempt loop (`ExecutionEngine.runAttempts`).
  - Each attempt builds a fresh `DefaultStageTasks` scope, or a fresh child engine for a
    nested stage, and registers what must be cancelled in the stage's `CancelSlot`.
  - With a per-attempt timeout, the attempt runs as its own `TrackedTask` under
    `min(stage deadline, attempt timeout)`. Expiry is reported as
    `StageAttemptTimeoutException`, unless the stage deadline itself expired.
- The stage deadline bounds all attempts and backoff waits.
  - Backoff is a `Thread.sleep` on the worker, so cancellation and expiry interrupt it.
  - A retry whose backoff would outlast the deadline is not attempted.
- Fallbacks run as their own tracked tasks once the stage's outcome is known, bounded
  by the enclosing (run or nested stage) deadline. The layer keeps observing other
  stages meanwhile. FAIL_FAST is evaluated on the outcome after the fallback, so a
  RECOVERED stage never stops the run.
  - FAIL_FAST and cancellation cancel a pending fallback, and the stage keeps its own
    outcome.
  - Conditions also run as tracked tasks bounded by the enclosing deadline. A
    condition or fallback that outlives it fails with `StageTimeoutException`.
  - RECOVERED patches take part in conflict detection like SUCCEEDED ones.
  - Fallbacks are skipped when the run is cancelled or the enclosing deadline expired.
- The run timeout is the outermost engine's inherited deadline, and every stage deadline
  is capped by it. The engine also checks it:
  - before each layer;
  - after a layer in which a stage timed out, so CONTINUE stages cannot let a timed-out
    run end COMPLETED.
- A nested pipeline's builder timeout is capped by the nested stage's deadline.

## Observability

- The engine emits run and stage events only when a run has listeners: the pipeline's
  own (builder) plus the run's (`RunOptions`). Without listeners, it builds no events and
  starts no threads.
- Each run with listeners has an `AsyncEventDispatcher`: a bounded queue drained by one
  virtual thread.
  - Producers only append to a lock-free queue and never wake the delivery thread; when
    the queue is full, the event is dropped and counted.
  - The delivery thread drains in batches at least every 10 ms, and is unparked when the
    run finishes. Waking it per event measurably slowed short runs.
  - The final `RunFinished` is never dropped. It is delivered after the queue drains,
    and carries the drop count.
  - A listener that throws is logged through `System.Logger` (`dev.baton.event`) and
    skipped.
- `StageStarted` is emitted when a stage is submitted. `StageFinished` is emitted after
  the layer's merge, so conflict statuses are final. Its timestamp is the stage's
  logical end (start + duration).
- Nested pipelines share the outermost run's events, and their paths are prefixed with
  the nested stage ids. A nested pipeline's own builder listeners are not used.
- Metrics and tracing live in optional modules (`baton-micrometer`,
  `baton-opentelemetry`). They depend on `baton-api` only, never on `baton-core`.

## Deliberately deferred

- Reactive execution engine or Reactor dependency
- Multiple pluggable execution backends
- Distributed execution
- Detached/background tasks
- Database transaction or compensation management
- Dynamic DAG editing
