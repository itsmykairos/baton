# Retries, timeouts and fallbacks

## Retry a flaky call with backoff

```java
--8<-- "ResilienceTest.java:retry"
```

- The stage timeout covers every attempt and every backoff wait. A retry whose backoff
  can't finish in time isn't attempted.
- `withAttemptTimeout` cuts off one slow attempt with `StageAttemptTimeoutException`,
  which is retried. If the last attempt ends that way, the stage is `TIMED_OUT`.
- Each attempt gets fresh child tasks, or a fresh child run for a nested pipeline.
- Every retry sends a `StageRetrying` event.

## Fall back when an optional stage fails or is too slow

```java
--8<-- "ResilienceTest.java:fallback"
```

- A `RECOVERED` stage counts as success, and its original failure stays in
  `failure()`.
- The fallback runs on its own thread, bounded by the run's deadline, so a hanging
  fallback can't hold the run past its timeout.
- Fallbacks aren't used once the run is cancelled or its deadline has passed.

## Keep going past a failure

The default failure policy, `FAIL_FAST`, cancels the rest of the layer and stops the
run. `CONTINUE` reports the failure and carries on:

```java
--8<-- "ResilienceTest.java:continue"
```

## Limit a whole run

```java
--8<-- "ResilienceTest.java:run-timeout"
```

When the run's deadline passes, active stages time out, no further layer starts, and
the run ends `TIMED_OUT`. Every stage's own deadline is capped by the run's.

!!! tip "Interruption is cooperative"
    A timeout or cancellation completes the stage at once and interrupts its thread,
    but Baton never waits for the thread to exit. Blocking calls that respond to
    interruption (`Thread.sleep`, most I/O, `join`) stop promptly.
    `StageResult.workerTermination()` reports whether the exit was seen.
