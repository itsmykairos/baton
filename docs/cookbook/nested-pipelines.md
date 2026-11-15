# Nested pipelines

`Pipeline.asStage()` turns a whole pipeline into one stage of another:

```java
--8<-- "NestedPipelinesTest.java:nested"
```

- The child runs inside the parent's run, on the parent's threads, and reads the
  context the nested stage would read.
- When the child completes, every key it wrote is handed back to the parent, as the
  nested stage's patch.
- The nested stage's timeout covers the whole child run. The child's own
  `Builder.timeout` still applies, capped by it.
- Cancelling the nested stage, or a FAIL_FAST failure of a sibling, cancels the child
  and everything below it.
- The child's stage results are in `StageResult.children()`, and
  `PipelineResults.stage` finds them by path, such as `payment/capture`.
  `isSuccessful()` and `hasFailures()` look at every level.
- Events of the child's stages go to the outermost run's listeners, with paths like
  `payment/capture`.
