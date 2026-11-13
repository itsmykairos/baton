# Baton

Baton is a small Java pipeline orchestration library.

## Project status

This repository is an initial multi-module Gradle Kotlin DSL skeleton. The public API and
runtime implementation have not been added yet. Their contracts should be implemented
against the architecture and concurrency requirements before being treated as stable.

## Requirements

- JDK 21
- Gradle (the wrapper should be added when the project chooses its Gradle distribution)

## Modules

| Module | Responsibility |
|---|---|
| `baton-api` | Public contracts and user-facing types; no runtime implementation |
| `baton-core` | Pipeline engine, stage execution, deadlines, context handling, managed child tasks, nested pipelines |
| `baton-testkit` | Reusable test fixtures and assertions for Baton users |
| `baton-examples` | Small runnable examples demonstrating the public API |

## Build

```bash
./gradle build
```

Once a Gradle wrapper is added, use:

```bash
./gradlew clean build
```

## Design guardrails for v1

- Java 21 baseline; use virtual threads internally.
- Keep public API independent of executor implementation details.
- Treat timeout as an outcome, not proof that the underlying worker stopped.
- Keep cancellation requests distinct from confirmed worker termination.
- Stage-owned child tasks must not outlive their stage.
- Nested pipelines use the same pipeline semantics and inherit the parent's absolute deadline.
- Context is read through immutable views; stages return patches rather than mutating shared state.
- No reactive engine, distributed execution, detached tasks, or transaction management in v1.
