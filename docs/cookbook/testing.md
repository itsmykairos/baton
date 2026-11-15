# Testing stages

`baton-testkit` has helpers for testing stages on their own, and for checking whole
runs. Add it with `testImplementation`.

```java
--8<-- "TestingStagesTest.java:stage"
```

## One stage, no pipeline

```java
--8<-- "TestingStagesTest.java:stage-tester"
```

`StageTester.run` returns the patch instead, and an overload takes a `BatonConfig` for
overwrite and type-clash behaviour.

## Inspect what a stage did

`TestStageContext` is a real `StageContext` for calling `execute` yourself. Its child
tasks run on virtual threads, and closing it cancels any still running:

```java
--8<-- "TestingStagesTest.java:test-context"
```

## A whole run

```java
--8<-- "TestingStagesTest.java:pipeline-results"
```

`PipelineResults.stage` takes a path, so `"payment/capture"` reaches into a nested
pipeline. To check events, run with a `RecordingListener` and call
`awaitRunsFinished` before asserting; see
[Events, metrics and logging](observability.md).
