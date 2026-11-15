# Async runs and cancellation

`run` blocks until the run ends. `start` returns a `PipelineRun` at once, so a caller
can keep working, check progress, or cancel:

```java
--8<-- "AsyncRunsTest.java:start"
```

Later, cancel it or wait for the result:

```java
--8<-- "AsyncRunsTest.java:cancel"
```

- `cancel()` completes every active stage as `CANCELLED` at once and interrupts their
  threads. Child tasks, nested pipelines, conditions and fallbacks are cancelled too.
- `await()` returns the result. After a cancellation it lists the stages that had
  already finished, and its context holds the writes of the layers that completed.
- `start(input, options)` takes the same `RunOptions` as `run`, including a timeout.
