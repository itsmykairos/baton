# Context propagation

Stages, child tasks, conditions and fallbacks run on other threads than the code that
called `run()`. Anything bound to the caller's thread would be lost on the way:
- the SLF4J MDC;
- the OpenTelemetry context;
- a security context, tenant id or locale.

Context propagation carries it across.

## Which run and stage am I in?

Always on, with no configuration:

```java
BatonContext.current().ifPresent(scope -> {
    scope.runId();         // same id as the run's events
    scope.pipelineName();  // the outermost pipeline
    scope.stage();         // Optional<StagePath>, e.g. payment/charge
    scope.kind();          // RUN, STAGE, ATTEMPT, CHILD_TASK, CONDITION or FALLBACK
});
```

## Ready-made propagators

Register them with `BatonConfig.Builder.contextPropagator`. Propagators accumulate
across config layers, so app, pipeline and run propagators all apply.

```java
static final BatonConfig APP = BatonConfig.builder()
        .contextPropagator(new MdcContextPropagator())                     // baton-slf4j
        .contextPropagator(new OpenTelemetryContextPropagator(openTelemetry))  // baton-opentelemetry
        .build();
```

**`MdcContextPropagator`** (`baton-slf4j`)
- Copies the caller's MDC into every stage, child task, condition and fallback.
- Adds `baton.runId`, `baton.pipeline` and `baton.stage`, so a Logback or Log4j pattern
  such as `[%X{baton.stage}]` labels every line with its stage.
- The thread's previous MDC comes back when the work ends.

**`OpenTelemetryContextPropagator`** (`baton-opentelemetry`)
- Carries the caller's OpenTelemetry context.
- Opens live spans for the run and for each stage while they run, so spans created by
  stage code nest under the right stage, in the caller's trace:

```text
request             (caller)
└─ checkout         (run)
   ├─ load          (stage)
   │  ├─ GET /orders     (stage code)
   │  └─ SELECT items    (a child task's code)
   └─ payment       (nested pipeline stage)
      └─ charge     (stage of the nested pipeline)
         └─ POST /charges
```

Use it *instead of* `OpenTelemetryPipelineListener` when you want that nesting. The
listener rebuilds spans after the fact from events, so it can't be a parent of live
spans, and using both records each stage twice.

## Writing your own

```java
final class TenantPropagator implements ContextPropagator<String> {
    public String capture() {
        return TenantHolder.get();                       // on the submitting thread
    }

    public Restoration restore(String tenant, ExecutionScope scope) {
        String previous = TenantHolder.get();            // on the thread doing the work
        TenantHolder.set(tenant);
        return () -> TenantHolder.set(previous);         // when the work ends
    }
}
```

**How it works**
- `capture()` runs on the thread that submits the work. `restore()` runs on the
  thread that does it, and the returned `Restoration` is closed there when the work
  ends.
- `Restoration.failed(throwable)` is called first when the work failed. The
  OpenTelemetry propagator uses it to mark spans as errors.

**Where context is captured**
- Context is captured at every hop, not once per run:
  - caller → run;
  - run → each stage;
  - stage → its child tasks and timed attempts;
  - nested pipeline stage → the nested pipeline's stages.
- So anything a hop sets up, such as the nested stage's span, carries on to the hops
  below it.
- Conditions and fallbacks run with the run's context, restored for their own scope.

**Rules for propagators**
- Restorations are undone in reverse registration order.
- A propagator that throws is logged and skipped for that hop; the run is not
  affected.
- Propagators are run-scoped: a nested pipeline uses the outermost run's propagators.

**Cost:** with no propagators configured, the always-on `BatonContext` costs about
0.1 µs and 280 bytes per stage (see [performance.md](performance.md)).
