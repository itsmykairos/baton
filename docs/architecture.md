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

`baton-examples` contains runnable examples only. It should not become a dependency of
the library modules.

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
  Writes to the same key by two stages of one parallel layer are a conflict: those stages
  are FAILED and their patches are not applied. Across layers, later writes overwrite
  earlier values.
- BLOCKED stages (missing `requiredReads`) never stop a run; `requiredReads` is checked at
  run time, not at build time.

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
- Fallbacks run on the layer's coordinating thread, after the stage's outcome is known
  and before FAIL_FAST is evaluated. So a RECOVERED stage never stops the run.
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
  - Producers only `offer`; when the queue is full, the event is dropped and counted.
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
