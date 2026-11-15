# API reference

Javadoc for every published module, built from the same commit as this site.

| Module | Javadoc |
|---|---|
| `baton-api`: pipelines, stages, context, results, events, configuration | [dev.baton.api](../api/baton-api/) |
| `baton-testkit`: helpers for tests | [dev.baton.testkit](../api/baton-testkit/) |
| `baton-micrometer`: metrics | [dev.baton.micrometer](../api/baton-micrometer/) |
| `baton-opentelemetry`: tracing and live spans | [dev.baton.opentelemetry](../api/baton-opentelemetry/) |
| `baton-slf4j`: the MDC in stage threads | [dev.baton.slf4j](../api/baton-slf4j/) |
| `baton-visualize`: Mermaid diagrams | [dev.baton.visualize](../api/baton-visualize/) |
| `baton-spring-boot-starter`: Spring Boot auto-configuration | [dev.baton.spring.boot](../api/baton-spring-boot-starter/) |

`baton-core` exports no API, and `baton-bom` has no code.

Every published module's public signatures are also recorded in its `api/<module>.api`
file in the repository, and the build fails on an unrecorded change.
