# Configuration

Baton's behaviour is tuned with `BatonConfig`, an immutable set of optional settings.
An unset setting is inherited, ending at the built-in default (today's behaviour), so
code that sets nothing behaves as before.

```java
// One app-wide config, shared by every pipeline the application builds.
static final BatonConfig APP = BatonConfig.builder()
        .contextFlattenDepth(16)
        .layerConflictPolicy(LayerConflictPolicy.LAST_WINS)
        .maxParallelStages(32)
        .build();

Pipeline checkout = Pipeline.builder(APP)                       // app settings
        .config(BatonConfig.builder().overwritePolicy(OverwritePolicy.FAIL).build())  // this pipeline
        .stage("price", priceStage, StageOptions.defaults()
                .withOverwritePolicy(OverwritePolicy.OVERRIDE))  // this stage
        ...
        .build();

checkout.run(input, RunOptions.defaults()
        .withConfig(BatonConfig.builder().runTimeout(Duration.ofSeconds(2)).build()));  // this run
```

## Precedence

From lowest to highest:

1. **Built-in defaults:** listed below.
2. **App:** `Pipeline.builder(appConfig)`.
3. **Pipeline:** `Builder.config(cfg)`, which can be called more than once. Later calls
   win, and settings are resolved at `build()`. `Builder.timeout(d)` is a shortcut.
4. **Run:** `RunOptions.withConfig(cfg)`. `withTimeout` and `withEventBufferCapacity`
   are shortcuts.
5. **Stage:** `StageOptions.withOverwritePolicy`, plus the per-stage options
   `StageOptions` already has (timeout, retry, fallback, condition…).

`BatonConfig.merge(other)` overlays only the settings `other` sets, and
`toBuilder()` starts a new config from an existing one.

## Scope in nested pipelines

| Scope | Settings | Where they come from |
|---|---|---|
| **Run** | thread factory, all event settings, clock, listener error handler | the outermost run; they apply to nested pipelines too |
| **Pipeline** | everything else | each pipeline's own config. A nested pipeline keeps its own, and a run's config overlays only the outermost pipeline |

## Settings

### Context and key conflicts (pipeline-scoped)

| Setting | Default | Options |
|---|---|---|
| `contextFlattenDepth(int)` | `0` (never flatten) | `0`, or a positive number of levels (see below) |
| `layerConflictPolicy` | `FAIL` | `FAIL` · `FIRST_WINS` · `LAST_WINS` · `SKIP_KEY` |
| `overwritePolicy` | `OVERRIDE` | `OVERRIDE` · `KEEP_EXISTING` · `FAIL`; can be set per stage |
| `typeClashPolicy` | `FAIL` | `FAIL` · `REPLACE` |
| `equalValuesConflict(boolean)` | `true` | `false` merges equal same-layer writes into one |

**Same-layer conflict.** Two or more stages of one parallel layer write the same key name.
- `FAIL`: every writer is FAILED with a `ContextMergeException`, and the run fails.
- `FIRST_WINS` / `LAST_WINS`: the first or last writer in definition order wins. All the
  stages succeed, and the losers' writes of that key are dropped. Their other writes
  still apply.
- `SKIP_KEY`: nobody's write of that key is applied, and the stages still succeed.

**Overwrite.** A stage writes a key that is already in the context, from the input or an
earlier layer.
- `OVERRIDE`: the new value wins. This is normal enrichment, so it is not recorded as a
  conflict.
- `KEEP_EXISTING`: the write is dropped.
- `FAIL`: the stage is FAILED.

**Type clash.** A stage writes a name the context already holds with another type.
- `FAIL`: the stage is FAILED.
- `REPLACE`: the old key and its value are removed, and the new key is written. This
  forces a flat copy of the context.

**Visibility.** Every conflict, however it was resolved, is recorded.
- It is listed in `PipelineResult.conflicts()` as a `KeyConflict` (kind, key, stage paths
  from the outermost pipeline, resolution). Conflicts in nested pipelines are included.
- It is sent to listeners as a `ContextConflict` event.
- Micrometer counts it as `baton.context.conflicts{pipeline, kind, resolution}`, and
  OpenTelemetry adds a `baton.conflict` event to the run span.

### Choosing `contextFlattenDepth`

A context is a stack of small levels, and each layer's patch adds one level holding only
its writes. A read checks the levels from newest to oldest.

- **Depth N:** once the stack would exceed N levels, the next patch copies every value
  into a single level. That's one full copy every N layers, and a read checks at most N
  levels.
- **0 (the default):** never copy. Writes are as cheap as possible, but a read of an
  early or missing key walks one level per layer applied so far.

Measured on 200 sequential stages, each writing one key (Apple M1, Java 21;
[performance.md](performance.md)):

| Depth | Stages only write | Every stage also reads every earlier key |
|---|---|---|
| 0 | 1.27 ms · 650 KB | 5.40 ms · 654 KB |
| 4 | 1.20 ms · 1.19 MB | 1.46 ms · 1.20 MB |
| 16 | 1.11 ms · 799 KB | 1.68 ms · 820 KB |
| 64 | 1.11 ms · 707 KB | 2.86 ms · 720 KB |

**Guidance**
- **Up to about 20 layers, or stages that mostly write:** keep `0`. It uses the least
  memory, and reads walk only a few levels.
- **Many layers (dozens to hundreds) where stages read a lot of the context:** use
  `8`–`16`. Reads stay short, and the copying is spread out.
- **Many layers, few reads, memory matters:** `0`, or a large depth such as `64`.
- **Very small depths (1–4)** make reads fastest but copy often, so they use the most
  memory.

Depth only affects speed and memory. Every depth gives exactly the same context.

### Execution

| Setting | Scope | Default | Meaning |
|---|---|---|---|
| `threadFactory(ThreadFactory)` | run | virtual threads named `baton-stage` | each run creates its own thread-per-task executor from it, so cancellation can shut it down. For example `Thread.ofVirtual().name("checkout-", 0).factory()` |
| `maxParallelStages(int)` | pipeline | `0` (unlimited) | at most N stages of a parallel layer run at once, and the rest start as slots free up. A waiting stage's timeout starts when it starts. Under FAIL_FAST, waiting stages are CANCELLED without running |
| `maxChildTasks(int)` | pipeline | `0` (unlimited) | at most N forked child tasks of one stage run at once. `fork` never blocks: the child waits for a slot, within the stage's deadline |
| `defaultStageOptions(StageOptions)` | pipeline | `StageOptions.defaults()` | used for stages added without options |
| `unjoinedChildFailure` | pipeline | `FAIL_STAGE` | `IGNORE` logs the failure (logger `dev.baton`) and lets the stage succeed |
| `runTimeout(Duration)` | pipeline | none | limit for a whole run |

### Events (run-scoped)

| Setting | Default | Meaning |
|---|---|---|
| `eventBufferCapacity(int)` | 8192 | how many undelivered events a run may queue |
| `eventBatchInterval(Duration)` | 10 ms | the longest delay before queued events are delivered during a run |
| `eventOverflowPolicy` | `DROP_NEWEST` | `DROP_OLDEST` discards the oldest queued event instead. Drops are counted in `RunFinished.droppedEvents()` either way |
| `listenerErrorHandler(ListenerErrorHandler)` | log a WARNING to `dev.baton.event` | called when a listener throws; delivery continues |
| `notifyNestedListeners(boolean)` | `false` | `true` also sends a nested pipeline's events to its own builder listeners, with paths relative to it and its own run id |
| `clock(Clock)` | system UTC | event timestamps only; durations always use `nanoTime` |
| `defaultPipelineName(String)` | `"pipeline"` | name of pipelines built without `Builder.name` |

## Not configurable

These are guarantees, not policies:
- one type per key name within a context (unless `typeClashPolicy` is `REPLACE`);
- cancellation and timeout semantics;
- never waiting for worker threads to exit.
