# Baton architecture (initial)

## Module boundaries

`baton-api` is the compatibility boundary. It should contain only types required by callers
and stage authors. It must not expose `ExecutorService`, virtual-thread factories, internal
scheduler types, or implementation-specific cancellation machinery.

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
- On timeout, fail-fast, or parent cancellation, request interruption with
  `shutdownNow()` and wait only for a bounded grace period.
- Record worker termination separately; interruption is cooperative and does not guarantee
  that a task has stopped.
- Child pipelines are ordinary pipelines composed by a parent stage. They have their own
  layers, inherit the parent's absolute deadline, and propagate cancellation to active
  descendants.
- Context snapshots are immutable. A stage returns one patch for the pipeline to merge.

## Deliberately deferred

- Reactive execution engine or Reactor dependency
- Multiple pluggable execution backends
- Distributed execution
- Detached/background tasks
- Database transaction or compensation management
- Dynamic DAG editing
