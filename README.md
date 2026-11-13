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
| `baton-testkit` | Helpers for testing stages and inspecting results. | `testImplementation` |
| `baton-integration-tests` | Black-box tests of the API (not published). | — |
| `baton-examples` | A runnable checkout pipeline (not published). | — |

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
- If any of them is missing when the stage would start, the stage is `BLOCKED`. A
  BLOCKED stage never stops the run.

**Timeouts and cancellation**
- A stage timeout covers the stage, its child tasks and any nested pipeline it runs.
- `PipelineRun.cancel()` cancels every active stage.
- Neither waits for worker threads to exit. Interruption is cooperative, and
  `StageResult.workerTermination()` reports whether the worker's exit was confirmed.

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

[docs/architecture.md](docs/architecture.md) has the design notes, and the Javadoc
of `dev.baton.api` has the full contract.

## Testing your stages

```java
// One stage, no pipeline:
ContextView out = StageTester.runAndApply(new PriceStage(),
        ContextView.builder().put(ORDER_ID, "A-1").build());

// A whole run, including nested stages:
PipelineResults.requireSuccess(result);   // AssertionError with a readable result tree
StageResult charge = PipelineResults.stage(result, "payment/charge");
System.out.println(PipelineResults.describe(result));
```

## Build and run

```bash
./gradlew build                    # compile, test, Javadoc (warnings fail the API build)
./gradlew :baton-examples:run      # the checkout example, on the module path
```

Requires JDK 21. The Gradle wrapper downloads everything else.
