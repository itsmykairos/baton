# Performance

Engine overhead, measured with JMH (`baton-benchmarks`). Every stage is trivial, so
the numbers are Baton's own cost. Real stages that do I/O will dwarf them.

Apple M1 (8 cores), Java 21. Average time per run, and allocation per run. Each
benchmark runs 1 fork, with 3 × 1 s warmup and 5 × 1 s measurement.

| Benchmark | What it runs | Before | After |
|---|---|---|---|
| `sequential10` | 10 sequential stages | 50.9 µs · 22 KB | 49.0 µs · 23 KB |
| `sequential10WithListener` | same, plus a no-op listener | 105.7 µs · 61 KB | **59.1 µs · 26 KB** |
| `sequential10WithTimeouts` | same, every stage with a 30 s timeout | 73.1 µs · 25 KB | 73.4 µs · 24 KB |
| `sequential200Writes` | 200 sequential stages, each writing a new key | 2,895 µs · 5.1 MB | **1,155 µs · 0.97 MB** |
| `parallel100` | one parallel layer of 100 stages | 75.5 µs · 167 KB | 76.3 µs · 167 KB |
| `nested5` | a pipeline nested 5 levels deep | 27.6 µs · 20 KB | 26.9 µs · 20 KB |
| `baselineSequential10` | 10 tasks one after another on bare virtual threads | 40.4 µs · 3.7 KB | — |
| `baselineParallel100` | 100 tasks at once on bare virtual threads | 25–47 µs · 35 KB | — |

The `baseline*` rows are a floor, not a target: no context, results, deadlines or
cancellation.

## What was changed

- **Context growth was quadratic.**
  - *Before:* applying a layer's patch copied the whole context about three times:
    the value map, the immutable copy, and a name index for the type-clash check. The
    merge also rebuilt a name map of every key.
  - *Now:* a context is a chain of small levels. Applying a patch adds one level holding
    only its writes, with its own name index. The chain was flattened every 16 levels;
    that depth is now configurable and defaults to never (see "Flatten depth" below).
    Clash checks use the new `ContextView.keyNamed(name)` instead of scanning.
  - *Result:* 200 sequential writes run 2.5× faster and allocate 5× less. The cost per
    layer no longer grows with the context.
- **Listeners doubled a run's cost.**
  - *Before:* each run's event queue was an `ArrayBlockingQueue(8192)`, allocating
    about 32 KB of slots up front. Every event woke the delivery thread, which meant
    about 22 park/unpark cycles per run, competing with the stages for carrier threads.
    Run ids used `SecureRandom`.
  - *Now:* the queue is lock-free and bounded by a counter. Producers never wake the
    delivery thread: it drains in batches at least every 10 ms, and at once when the run
    finishes. Run ids come from `ThreadLocalRandom`.
  - *Result:* a listener adds about 10 µs to a 10-stage run, instead of 55 µs. Events can
    now reach listeners up to about 10 ms late; their timestamps are unchanged.

## Flatten depth

`BatonConfig.contextFlattenDepth` trades write cost against read cost.
`ContextDepthBenchmark` runs 200 sequential stages, each writing one key, at several
depths. In `writesAndReads`, every stage also reads every key written so far, the worst
case for a deep chain. Runs used 8 measurement iterations.

| Depth | `writes` | `writesAndReads` |
|---|---|---|
| 0 (never, default) | 1,268 µs · 650 KB | 5,402 µs · 654 KB |
| 4 | 1,205 µs · 1.19 MB | 1,458 µs · 1.20 MB |
| 16 | 1,107 µs · 799 KB | 1,679 µs · 820 KB |
| 64 | 1,113 µs · 707 KB | 2,855 µs · 720 KB |

- **When reads dominate:** small depths read fastest but copy more often.
- **When writes dominate:** never flattening uses the least memory.

[configuration.md](configuration.md#choosing-contextflattendepth) turns this into
guidance.

With configuration in place, the engine benchmarks at default settings:
- `sequential10`: 49.8 µs · 23.8 KB
- `parallel100`: 72.9 µs · 164 KB
- `nested5`: 28.8 µs · 23.4 KB
- `sequential10WithListener`: 57.3 µs · 27 KB

All are within noise of the numbers above. The conflict resolver allocates nothing
unless a conflict must drop or fail a write.

## Known costs, not changed

- **Timeouts:** about 2.4 µs per stage with a timeout, to register and cancel its
  deadline on the shared `ScheduledThreadPoolExecutor`. A timing wheel could reduce it,
  but it is small next to any stage that does real work.
- **Parallel stages:** about 0.5 µs and 1.3 KB per stage above bare virtual threads.
  That pays for the tracked task, the child-task scope, cancellation registration and
  the stage result.
- **Per stage overall:** about 1 µs over a bare virtual-thread hop for sequential stages.

## Running the benchmarks

```bash
./gradlew :baton-benchmarks:jmh                               # everything (~2 minutes)
./gradlew :baton-benchmarks:jmh -Pjmh="sequential200 -prof gc" # one benchmark, with allocation
```

Arguments in `-Pjmh` go straight to JMH: a regex filter, then options such as `-f`,
`-wi`, `-i` or `-prof`.
