# Baton

Baton runs pipelines of stages on Java 21 virtual threads.

- A **pipeline** is a list of layers. Each layer is one stage, or a group of stages
  that run in parallel.
- A **stage** reads an immutable snapshot of the context and returns a patch of typed
  writes, so stages never share mutable state.
- Timeouts, retries, fallbacks, conditions, nested pipelines and cancellation are part
  of the engine. You don't write them by hand.

```java
--8<-- "QuickStartTest.java:pipeline"
```

## Where to go next

- **[Getting started](getting-started.md)**: add Baton to a build and run your first
  pipeline.
- **[Cookbook](cookbook/index.md)**: short recipes for common shapes: fan-out,
  retries, nested pipelines, cancellation, logging and testing.
- **Guides**: [configuration](configuration.md), [context propagation](propagation.md),
  [Spring Boot](spring-boot.md) and [diagrams](visualization.md).
- **[API reference](reference.md)**: the Javadoc of every published module.

Every code sample on this site is taken from a test that compiles and runs in the
build, so the samples always match the current API.
