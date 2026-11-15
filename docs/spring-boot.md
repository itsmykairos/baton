# Spring Boot

`baton-spring-boot-starter` targets Spring Boot 4.1. It sets up Baton from
`application.yml`, wires the integrations it finds, lets you declare whole pipelines in
configuration, and adds an Actuator endpoint.

```kotlin
dependencies {
    implementation(platform("io.github.kairos-tools:baton-bom:0.1.0-RC1"))
    implementation("io.github.kairos-tools:baton-spring-boot-starter")
    // Optional: metrics and tracing are wired when these are present.
    implementation("io.github.kairos-tools:baton-micrometer")
    implementation("io.github.kairos-tools:baton-opentelemetry")
}
```

A runnable web app is in `examples/spring-boot`:

```bash
./gradlew :examples:spring-boot:run
```

Then open <http://localhost:8080>. The page draws the configured `checkout` pipeline
from `/actuator/baton/checkout`. **Run checkout** calls `POST /checkout`, which runs the
pipeline and redraws the diagram with the run's statuses, durations and attempts. The
console shows each request's log lines, labelled with the stage that wrote them.

## Settings from `application.yml`

Every `baton.*` property maps to a `BatonConfig` setting (see
[configuration.md](configuration.md)). Unset properties keep the built-in defaults, and
IDEs autocomplete the properties from the generated metadata.

```yaml
baton:
  context.flatten-depth: 16
  conflicts:
    layer: last-wins            # fail | first-wins | last-wins | skip-key
    overwrite: keep-existing    # override | keep-existing | fail
    type-clash: fail            # fail | replace
  execution:
    max-parallel-stages: 32
    max-child-tasks: 16
    run-timeout: 30s
  events:
    buffer-capacity: 8192
    batch-interval: 10ms
    overflow-policy: drop-oldest
  default-pipeline-name: orders
  default-stage:                # options for stages declared without options
    timeout: 2s
    retry: { max-attempts: 3, backoff: 100ms, multiplier: 2 }
  integrations:
    mdc: true                   # default true
    metrics: true               # default true; needs baton-micrometer + a MeterRegistry
    tracing: propagator         # propagator (default) | listener | none; needs baton-opentelemetry + an OpenTelemetry bean
```

The resulting `BatonConfig` bean also includes:
- every `ContextPropagator` bean;
- the changes of every `BatonConfigCustomizer` bean, for anything properties can't
  express.

## Pipelines as beans

Inject the pre-configured, prototype-scoped `Pipeline.Builder`:

```java
@Bean
Pipeline payment(Pipeline.Builder builder, Stage charge) {
    return builder.name("payment")
            .stage("charge", charge, StageOptions.defaults().withRetry(RetryPolicy.maxAttempts(3)))
            .build();
}
```

The builder already has:
- the application's `BatonConfig`;
- every `PipelineListener` bean. The built-in metrics and tracing listeners come before
  your own.

## Pipelines in `application.yml`

Each entry under `baton.pipelines` becomes a `Pipeline` bean named after its key, built
from `Stage` beans:

```yaml
baton:
  pipelines:
    checkout:
      timeout: 5s
      layers:
        - stage: loadOrder            # a Stage bean
          id: load-order              # optional; defaults to the bean name
        - parallel: enrich            # a parallel layer
          stages:
            - stage: customer
            - stage: recommendations
              timeout: 100ms
              fallback: noRecommendations     # a StageFallback bean
        - pipeline: payment           # a Pipeline bean (from YAML or @Bean), run nested
          retry: { max-attempts: 3, backoff: 50ms }
        - stage: vipGift
          condition: isVip            # a Predicate<ContextView> bean
          failure-policy: continue
          skip-if-inputs-missing: true
          overwrite-policy: keep-existing
```

**Stage options**
- Per stage: `timeout`, `failure-policy`, `retry` (`max-attempts`, `backoff`,
  `multiplier`, `max-delay`, `jitter`, `attempt-timeout`, `retry-on`), `fallback`,
  `condition`, `skip-if-inputs-missing` and `overwrite-policy`.
- Options you don't set come from `baton.default-stage`.

**Checked at startup.** The app fails to start, with the property path in the error,
when:
- a layer names more than one of `stage`, `pipeline` or `parallel`, or none of them;
- a parallel layer has no stages, or contains another parallel layer;
- configured pipelines nest each other in a cycle;
- a referenced bean doesn't exist.

For example: `baton.pipelines.checkout.layers[2].stage: no Stage bean named 'loadOrdr'`.

## Integrations

| Integration | Active when | Turn off with |
|---|---|---|
| MDC propagation (`baton.runId`, `baton.pipeline`, `baton.stage` + caller's MDC) | always (SLF4J is in every Boot app) | `baton.integrations.mdc=false` |
| Micrometer metrics | `baton-micrometer` + a `MeterRegistry` bean | `baton.integrations.metrics=false` |
| OpenTelemetry live stage spans | `baton-opentelemetry` + an `OpenTelemetry` bean | `baton.integrations.tracing=listener` or `none` |

The metrics and tracing integrations find the `MeterRegistry` or `OpenTelemetry` bean
on first use. So they work whatever order Spring sets beans up in, and they do nothing
if the bean never appears.

## Actuator endpoint

With Actuator present and the endpoint exposed
(`management.endpoints.web.exposure.include=baton`):

- `GET /actuator/baton` lists every `Pipeline` bean, with its name, layer count and
  stage count.
- `GET /actuator/baton/{bean}` returns one pipeline (the starter is compiled with
  `-parameters`, which Actuator needs to map `{bean}`):
  - its layers and stages, with their options, required inputs and nested pipelines;
  - its effective settings;
  - a Mermaid diagram (see [visualization.md](visualization.md)).
