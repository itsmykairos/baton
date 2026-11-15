# Fan out and aggregate

## Parallel lookups, then one stage that combines them

Put independent calls in one parallel layer. They all read the same context snapshot
and run at the same time. The next layer starts when every one of them has finished,
and sees all their writes.

```java
--8<-- "FanOutAndAggregateTest.java:layers"
```

- If two stages in one layer write the same key, both are reported `FAILED` with a
  `ContextMergeException`. Give each stage its own keys, or choose another
  [`layerConflictPolicy`](../configuration.md).
- `BatonConfig.maxParallelStages` caps how many stages of a layer run at once, for
  example to be gentle with a downstream service.

## One child task per item

When the number of calls depends on the data, fork child tasks inside one stage. Each
runs on its own virtual thread, under the stage's timeout.

```java
--8<-- "FanOutAndAggregateTest.java:children"
```

- Children can't outlive their stage: when the stage returns, unfinished children are
  cancelled.
- A child that failed and was never joined fails its stage.
- A child cut off by the stage's deadline throws `StageTimeoutException` from `join()`.
- `BatonConfig.maxChildTasks` caps how many children of one stage run at once.
