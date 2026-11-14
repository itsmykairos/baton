# Baton

Baton runs pipelines of stages on Java 21 virtual threads. A pipeline is a list of
layers: each layer is one sequential stage or a group of parallel stages. Stages read
an immutable context snapshot and return a patch of typed writes, so they never share
mutable state.

Status: `0.1.0-SNAPSHOT`, not yet published. Build it from source (see below).

## Modules

| Module | What it is | Depend on it with |
|---|---|---|
| `baton-api` | The public API (`dev.baton.api`). The only module your code should compile against. | `implementation` |
| `baton-core` | The runtime. Exports no packages; found at startup through `ServiceLoader`. | `runtimeOnly` |
| `baton-testkit` | Helpers for testing stages and inspecting results, and a `RecordingListener`. | `testImplementation` |
| `baton-micrometer` | Optional: run and stage metrics for Micrometer. | `implementation` |
| `baton-opentelemetry` | Optional: one trace per run, one span per stage, for OpenTelemetry. | `implementation` |
| `baton-visualize` | Optional: Mermaid diagrams of pipelines and runs. | `implementation` |
| `baton-slf4j` | Optional: carries the SLF4J MDC into stage threads, with run and stage keys. | `implementation` |
| `baton-spring-boot-starter` | Optional: Spring Boot 4.1 auto-configuration, pipelines in `application.yml`, `/actuator/baton`. | `implementation` |
| `baton-examples-spring` | A runnable Spring Boot web app with `/actuator/baton` and a dashboard (not published). | — |
| `baton-integration-tests` | Black-box tests of the API (not published). | — |
| `baton-examples` | A runnable checkout pipeline (not published). | — |
| `baton-benchmarks` | JMH benchmarks of engine overhead (not published). See [docs/performance.md](docs/performance.md). | — |

All modules except the test and example modules are JPMS modules (`dev.baton.api`,
`dev.baton.core`, `dev.baton.testkit`). On the module path, `requires dev.baton.api`
is enough: the runtime is bound as a `PipelineProvider` service. On the classpath it is
found through `META-INF/services`.

## Quick start

```java
static final ContextKey<String> ORDER_ID = ContextKey.of("orderId", String.class);
static final ContextKey<String> CUSTOMER = ContextKey.of("customer", String.class);
static final ContextKey<Integer> TOTAL = ContextKey.of("total", Integer.class);

Pipeline checkout = Pipeline.builder()
        .stage("load", context -> ContextPatch.builder().put(ORDER_ID, "A-1").build())
        .parallel("enrich", parallel -> {
            parallel.stage("customer", context ->
                    ContextPatch.builder().put(CUSTOMER, "Ada").build());
            parallel.stage("price", context -> {
                var base = context.tasks().fork(() -> 40);   // child tasks on virtual threads
                var tax = context.tasks().fork(() -> 2);
                return ContextPatch.builder().put(TOTAL, base.join() + tax.join()).build();
            }, StageOptions.defaults().withTimeout(Duration.ofSeconds(2)));
        })
        .build();

PipelineResult result = checkout.run(ContextView.empty());      // or start(...) for async
result.context().get(TOTAL);                                     // 42
```

`Pipeline.asStage()` turns a pipeline into a stage of another pipeline.

## How a run behaves

**Context**
- `ContextKey`s are values: keys with the same name and type are equal.
- A context holds one type per name. Writing a name with a different type is rejected.
- Stages in one parallel layer all read the same snapshot. Their patches are merged
  once every stage in the layer has finished.
- If two stages in one layer write the same key, both are reported FAILED with a
  `ContextMergeException`. The other stages' writes are still applied.
- Across layers, a later write overwrites an earlier value.

**Failure policy** (per stage, set with `StageOptions.withFailurePolicy`)
- `FAIL_FAST` (the default): a failure or timeout cancels the rest of its parallel
  layer at once, and no later layer starts. The run ends `FAILED` or `TIMED_OUT`.
- `CONTINUE`: the run keeps going, and ends `COMPLETED` with `isSuccessful() == false`.

**Required inputs**
- A stage lists the keys it needs in `requiredReads()`.
- If any of them is missing when the stage would start, the stage is `BLOCKED`, or
  `SKIPPED` with `skipIfInputsMissing()`. Neither stops the run.

**Timeouts and cancellation**
- A stage timeout covers the stage, its child tasks and any nested pipeline it runs.
- A timed-out stage reports a `StageTimeoutException` that says which limit expired:
  the stage's own timeout, or its run's or nested stage's deadline. A child task cut
  off by its stage's deadline throws it from `join()`.
- `PipelineRun.cancel()` cancels every active stage.
- Neither waits for worker threads to exit. Interruption is cooperative, and
  `StageResult.workerTermination()` reports whether the worker's exit was confirmed.

**Retries, fallbacks and run timeouts**

```java
StageOptions.defaults()
        .withTimeout(Duration.ofSeconds(3))                         // total, across all attempts
        .withRetry(RetryPolicy.maxAttempts(3)
                .withBackoff(Duration.ofMillis(100), 2.0, Duration.ofSeconds(1))
                .withJitter(0.2)
                .withAttemptTimeout(Duration.ofSeconds(1))          // cut off one slow attempt
                .retryOn(failure -> failure instanceof IOException))
        .withFallback((context, failure) -> ContextPatch.builder().put(RECS, List.of()).build());

Pipeline.builder().timeout(Duration.ofSeconds(10));                 // every run
pipeline.run(input, RunOptions.defaults().withTimeout(Duration.ofSeconds(2)));  // this run
```

- **Retries.**
  - Each attempt gets a fresh child-task scope, or a fresh child run for a nested pipeline.
  - The stage timeout covers every attempt and backoff wait. A retry is skipped if its
    backoff cannot finish in time.
  - An attempt cut off by `withAttemptTimeout` fails with
    `StageAttemptTimeoutException`, which is retried by default. If the last attempt ends
    that way, the stage is TIMED_OUT.
  - `StageResult.attempts()` reports how many attempts ran.
- **Fallback.**
  - A stage that fails or times out after its retries can be `RECOVERED`. The
    fallback's patch is applied and the run continues.
  - A RECOVERED stage counts as success, and the original failure stays in `failure()`.
  - The fallback isn't used once the run is cancelled or its deadline has passed.
  - Fallbacks and conditions run on their own threads, bounded by the run's (or
    nested stage's) deadline. A hanging one can't hold a run past its timeout, and
    FAIL_FAST and cancellation stop them.
- **Run timeout.**
  - When the run timeout expires, active stages time out, no further layer starts, and
    the run ends `TIMED_OUT`.
  - A nested pipeline's own timeout also applies when it runs nested, capped by the
    nested stage's deadline.

**Conditional stages**

```java
StageOptions.defaults().withCondition(context -> context.get(TOTAL) > 10_000);  // run only if true
StageOptions.defaults().skipIfInputsMissing();          // missing requiredReads → SKIPPED, not BLOCKED
```

- A stage that doesn't run because of either option is reported `SKIPPED`. It never
  starts, so it has no `StageStarted` event and `attempts()` is 0.
- SKIPPED counts as success and never triggers FAIL_FAST. Stages that need a skipped
  stage's output are BLOCKED, or SKIPPED too if they also skip on missing inputs.
- Conditions are evaluated just before the stage would start, against the context it
  would read, so they see values written by earlier layers. They should be quick.
- A condition that throws makes the stage FAILED, and the fallback and failure policy
  apply as usual. Under FAIL_FAST, no stage of that parallel layer starts.

**Child tasks**
- Forked children cannot outlive their stage. When the stage returns, unfinished
  children are cancelled.
- If a child already failed and was never joined, the stage fails with that child's
  exception.

**Nested pipelines**
- A child pipeline runs on the parent's executor, under the nested stage's deadline,
  and is cancelled along with it.
- A child that ends COMPLETED makes the nested stage SUCCEEDED and hands back every
  key it wrote.
- The child's stage results appear in `StageResult.children()`.
- `isSuccessful()` and `hasFailures()` check those children at every level.

## Configuration

Behaviour that used to be fixed is now set with `BatonConfig`: context flattening,
key-conflict policies, parallelism limits, thread factory, default stage options and
event delivery. An app config passed to `Pipeline.builder(appConfig)` applies to every
pipeline. Each pipeline (`Builder.config`), run (`RunOptions.withConfig`) and stage can
override it. Unset settings keep today's behaviour.

```java
static final BatonConfig APP = BatonConfig.builder()
        .contextFlattenDepth(16)                              // default 0 = never flatten
        .layerConflictPolicy(LayerConflictPolicy.LAST_WINS)   // default FAIL
        .overwritePolicy(OverwritePolicy.KEEP_EXISTING)       // default OVERRIDE
        .maxParallelStages(32)
        .build();
```

Every key conflict is listed in `PipelineResult.conflicts()` and sent as a
`ContextConflict` event. [docs/configuration.md](docs/configuration.md) lists every
setting, its scope and precedence, and how to choose a flatten depth.

## Spring Boot

Add `baton-spring-boot-starter` and Baton is configured from `application.yml`.
- MDC, Micrometer and OpenTelemetry are wired automatically when present.
- Pipelines can be `@Bean`s built with an injected, pre-configured `Pipeline.Builder`,
  or declared entirely in YAML from `Stage` beans.
- `/actuator/baton` shows every pipeline, with a Mermaid diagram.

```yaml
baton:
  execution.max-parallel-stages: 32
  pipelines:
    checkout:
      layers:
        - stage: loadOrder
        - parallel: enrich
          stages: [{ stage: customer }, { stage: recommendations, timeout: 100ms, fallback: noRecommendations }]
        - pipeline: payment
```

See [docs/spring-boot.md](docs/spring-boot.md). To try it, run
`./gradlew :baton-examples-spring:run` and open <http://localhost:8080>: the page draws
the pipeline from `/actuator/baton` and runs checkouts on demand.

## Context propagation

Stage code runs on other threads than the caller, so thread-bound context is carried
across by `ContextPropagator`s registered in `BatonConfig`. Context is captured at
every hop (caller, run, stage, child task, nested pipeline) and restored around the
work.

```java
BatonConfig.builder()
        .contextPropagator(new MdcContextPropagator())                         // log MDC + run/stage keys
        .contextPropagator(new OpenTelemetryContextPropagator(openTelemetry))  // live run and stage spans
        .build();

BatonContext.current();   // inside a stage: run id, pipeline, stage path
```

See [docs/propagation.md](docs/propagation.md).

## Visualization

`Pipeline.describe()` returns the pipeline's structure: layers, stages, options,
required inputs and nested pipelines. The optional `baton-visualize` module renders it
as a Mermaid flowchart, with or without a run's result overlaid (stages colored by
status, with durations, attempts and conflicts).

```java
String diagram = MermaidDiagram.of(pipeline.describe(), result);   // paste into a ```mermaid block
```

See [docs/visualization.md](docs/visualization.md).

## Observability

Runs emit events (`RunStarted`, `StageStarted`, `StageFinished`, `RunFinished`, in
`dev.baton.api.event`) to `PipelineListener`s, plus `StageRetrying` before each retry:

```java
Pipeline checkout = Pipeline.builder()
        .name("checkout")                                           // tags every event
        .listener(new MicrometerPipelineListener(meterRegistry))    // every run
        .listener(new OpenTelemetryPipelineListener(openTelemetry))
        ...
        .build();

checkout.run(input, RunOptions.defaults()
        .withListener(OpenTelemetryPipelineListener.forCurrentContext(openTelemetry)));  // this run only
```

**Delivery**
- Delivery is asynchronous. Each run with listeners gets a bounded queue, drained by
  one virtual thread, so a slow or failing listener never slows or breaks the pipeline.
- If the queue fills, further events of that run are dropped and counted in
  `RunFinished.droppedEvents()`. The queue size is set with
  `RunOptions.withEventBufferCapacity`.
- A run without listeners creates no queue, no thread and no event objects.
- Events of one run arrive in order, and `RunFinished` is always last.
  `RunFinished` can arrive after `run()` returns.
- Events are delivered in batches, at least every 10 ms during a run and at once when
  it ends. Their timestamps are when they happened.

**Nested pipelines**
- Their events go to the outermost run's listeners, with paths like `payment/charge`.

**Adapters** (optional modules that depend only on `baton-api` and their own library)
- `baton-micrometer` records `baton.stage.duration`, `baton.stage.retries`,
  `baton.run.duration`, `baton.runs.active` and `baton.events.dropped`.
- `baton-opentelemetry` creates a span per run and per stage, using the events'
  timestamps. FAILED and TIMED_OUT stages and runs are marked as errors.

[docs/architecture.md](docs/architecture.md) has the design notes, and the Javadoc
of `dev.baton.api` has the full contract.

## Testing your stages

```java
// One stage, no pipeline:
ContextView out = StageTester.runAndApply(new PriceStage(),
        ContextView.builder().put(ORDER_ID, "A-1").build());

// Events, for one run:
RecordingListener recorder = new RecordingListener();
pipeline.run(input, RunOptions.defaults().withListener(recorder));
recorder.awaitRunsFinished(1, Duration.ofSeconds(5));
recorder.stageFinished("payment/charge").status();

// A whole run, including nested stages:
PipelineResults.requireSuccess(result);   // AssertionError with a readable result tree
StageResult charge = PipelineResults.stage(result, "payment/charge");
System.out.println(PipelineResults.describe(result));
```

## Build and run

```bash
./gradlew build                    # compile, test, Javadoc (warnings fail the API build)
./gradlew :baton-examples:run      # the checkout example, on the module path
./gradlew :baton-benchmarks:jmh    # engine benchmarks (~2 minutes)
```

Requires JDK 21. The Gradle wrapper downloads everything else.
