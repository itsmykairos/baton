# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Baton is a Java 21 library that runs pipelines of stages on virtual threads. A pipeline is
a list of layers (one sequential stage, or a group of parallel stages). Stages read an
immutable context snapshot and return a patch of typed writes. The README describes the
user-facing behaviour, and `docs/` holds the design notes:
- `architecture.md`, `configuration.md`, `propagation.md`, `performance.md`,
  `visualization.md`, `spring-boot.md`.

## Commands

Gradle 9 wrapper, Java 21 toolchain. Configuration cache and build cache are on.

```bash
./gradlew build                                   # compile, all tests, Javadoc (warnings fail public modules), spotlessCheck, apiCheck
./gradlew spotlessApply                           # format Java sources (palantir-java-format) and build files
./gradlew :baton-core:test                        # one module's tests
./gradlew :baton-core:test --tests '*NestedPipelineTest'                                # one class
./gradlew :baton-core:test --tests '*NestedPipelineTest.childInheritsNestedStageDeadline'  # one method
./gradlew test --rerun-tasks -q                   # force a rerun; the build cache otherwise skips unchanged tests
./gradlew apiDump                                 # after an intended public API change: rewrite api/<module>.api
./gradlew cV                                      # the current version, from git tags (axion-release)
./gradlew publishToMavenLocal                     # inspect what would be published (signing only with a key)
./gradlew :examples:basic:run --args=--mermaid    # plain example; prints a Mermaid diagram of its run
./gradlew :examples:spring-boot:run               # Spring Boot web example on http://localhost:8080
./gradlew :examples:cookbook:test docsJavadoc     # the docs site's samples, and Javadoc into build/docs-api
mkdocs serve                                      # preview the docs site (pip install -r requirements-docs.txt)
./gradlew :baton-benchmarks:jmh -Pjmh="sequential200 -prof gc"   # JMH; -Pjmh passes a filter and JMH options
```

**Test results:** `<module>/build/test-results/test/*.xml`. Tests are JUnit 5 with AssertJ.
Core tests are whitebox: they live in `dev.baton.core.execution` and use
`DefaultPipelineBuilder` directly. `baton-integration-tests` uses only the public API.

## Module boundaries (JPMS; keep them intact)

- **`baton-api`** (`dev.baton.api`, `.event`, `.description`, `.propagation`) is the only
  compile-time dependency for users. Everything public lives here.
- **`baton-core`** exports nothing. It is found through `ServiceLoader` (`provides
  PipelineProvider`), so code outside core never imports core types.
- **Optional integrations** each depend only on `baton-api` plus their own library:
  `baton-micrometer`, `baton-opentelemetry`, `baton-slf4j`, `baton-visualize` and
  `baton-spring-boot-starter`.
- **`baton-testkit`** holds helpers for users' tests.
- **`baton-integration-tests`** has `ModularityIT`, which loads api and core into a real
  module layer and asserts the exported packages. Adding an exported package means
  updating that test.
- **Examples:** each folder under `examples/` with a `build.gradle.kts` is included
  automatically as `:examples:<folder>`. To add one, create the folder with its own build
  file (apply `application`); nothing else needs registering. Examples are never published.
  `examples/cookbook` is tests only: the docs site's recipes.
- **Non-modular modules:** the Spring starter and the adapters are automatic modules
  (`Automatic-Module-Name` in the jar manifest), not JPMS modules.

## Execution architecture (spans many files in `baton-core/.../execution`)

### Run setup

`PipelineExecutionSession` is one run. It:
- merges the pipeline's `BatonConfig` with the run's;
- builds a thread-per-task executor from `config.threadFactory()`;
- owns an `ExecutionControl` (cancellation and task registration);
- owns `RunEvents` (events) and `Propagation` (context propagators);
- drives the outermost `ExecutionEngine`.

### `ExecutionEngine.execute`, per layer

1. **Settle before start.** `settleBeforeStart` settles stages that won't run:
   - BLOCKED or SKIPPED for missing inputs;
   - SKIPPED for a false condition;
   - FAILED when a condition throws. Conditions run as bounded tasks.
2. **Submit.** Submit up to `maxParallelStages` stages as `TrackedTask`s.
3. **Observe.** Watch completions through a queue. Fallback tasks signal `index + size`.
   - A FAIL_FAST failure or timeout cancels the unfinished siblings.
   - Fallbacks run as their own tracked tasks, bounded by the enclosing deadline.
4. **Resolve.** `resolveLayer` applies the conflict policies (same-layer, overwrite,
   type clash), records `KeyConflict`s, and returns one patch.
5. **Apply.** `applyTo(context, config)` applies the patch.

### `TrackedTask`

Separates the **logical outcome** from **physical worker exit** (`WorkerLifecycle`).

**Guarantees:**
- Nothing ever waits for worker exit. A timed-out or cancelled stage completes
  logically at once, and its worker is reported `UNCONFIRMED`.
- `cancel()` returns only once the task is done. If another thread is mid-completion,
  it waits for that thread to publish the outcome.
- Cancelling a task whose deadline has passed reports a timeout, not a cancellation.

**Ordering rules:**
- `beforeStart` registers a task before it can start, time out or complete, so
  snapshots never miss it.
- A task's cancellation action (its child-task scope or nested child engine) runs
  before the worker is interrupted.

**Deadlines:** all runs share one `DeadlineScheduler.shared()`.

### Stages and nesting

- **Attempts:** `runAttempts` runs on the stage worker, with retries and backoff.
  `StageRun` and `CancelSlot` point cancellation at the current attempt.
- **Per-attempt timeouts:** an attempt with a per-attempt timeout is its own
  `TrackedTask`.
- **Nested pipelines:** a `DefaultPipeline` wrapped by `NestedPipelineStage` runs as a
  child `ExecutionEngine` on the nested stage's worker thread.
  - It shares the executor and the `Propagation`, and has its own `ExecutionControl`.
  - Its deadline is the minimum of the nested stage's deadline and the child's own
    `runTimeout`.
  - Cancelling the nested stage calls the child control's `cancel()`, which cascades
    down.
- **`children()` snapshot:** each engine's `snapshot()` runs under its `state` lock.
  Locks are only ever taken parent → child.

### Results, context and events

- **Public timeout type:** internal `TrackedTask.DeadlineExceededException` must never
  reach users. Stage results and `TaskHandle.join()` convert it to the public
  `StageTimeoutException`.
- **Context:** `ImmutableContextView` (in `baton-api`) is a chain of small levels. Each
  applied patch adds a level with its own name index. It is flattened every
  `contextFlattenDepth` levels; the default 0 means never. All walks over the chain are
  iterative. A context holds one type per key name.
- **Events:** each run with listeners has one `AsyncEventDispatcher`.
  - Producers never block or wake the delivery thread. It drains in batches, at least
    every 10 ms by default, and at once on finish.
  - `RunFinished` is never dropped and comes after any in-flight emit.
  - A run without listeners builds no events at all (`RunEvents.NONE`).
- **Propagation:** `Propagation.wrap` captures on the *submitting* thread and restores on
  the worker, at every hop: run, stage, attempt, child task, condition, fallback.
  - Because capture happens per hop, nested stages inherit their parent stage's context,
    such as an OpenTelemetry span.
  - `BatonContext.PROPAGATOR` is always first, with a fast path when it is the only
    propagator.

### Configuration and the starter

- **Config layering:** `BatonConfig` fields are nullable, meaning inherit. `merge`
  overlays them in this order: app (`Pipeline.builder(cfg)`), pipeline
  (`Builder.config`), run (`RunOptions.withConfig`), stage (`StageOptions`).
- **Scope:** run-scoped settings (thread factory, events, clock, error handler,
  propagators) come from the outermost run. Pipeline-scoped settings come from each
  pipeline's own config.
- **Propagators accumulate** across layers and are de-duplicated by instance.
- **Spring starter** (`dev.baton.spring.boot`):
  - `BatonAutoConfiguration` builds a `BatonConfig` bean from `baton.*` properties
    (`BatonProperties` records, with generated IDE metadata) and a prototype
    `Pipeline.Builder` bean.
  - Micrometer and OpenTelemetry are wired lazily through `ObjectProvider`, so
    auto-configuration order doesn't matter.
  - `BatonPipelineRegistrar` registers `baton.pipelines` YAML pipelines as beans, after
    validating them at startup with property paths in the errors.
  - `BatonEndpoint` provides `/actuator/baton`. The starter compiles with `-parameters`,
    which Actuator's `@Selector` mapping needs.

## Build and release

- The root `build.gradle.kts` holds the conventions:
  - the `published` module map with POM descriptions, used by `com.vanniktech.maven.publish`
    to publish to the Maven Central Portal;
  - the `ApiDump` and `ApiCheck` tasks, which run `javap -public` over each published
    module except core and the BOM.
- `apiCheck` is part of `check`, so `build` fails on any unrecorded public API change.
  Run `apiDump` and commit the `.api` file when the change is intended.
- Spotless formats every module's Java sources with palantir-java-format (4 spaces, 120
  columns, Javadoc left alone) and trims whitespace in build files and workflows.
  `spotlessCheck` is part of `check`, so run `./gradlew spotlessApply` before committing.
  Formatter and plugin versions are in `gradle/libs.versions.toml`.
- Coordinates are `io.github.kairos-tools:<module>`, with `group` in `gradle.properties`.
  The version comes from git tags (axion-release, `incrementPrerelease`): exactly
  `0.1.0-RC1` on the tag `v0.1.0-RC1`, and a `-SNAPSHOT` between tags. Never put a
  `version=` back in `gradle.properties`. Java packages stay `dev.baton.*`.
- CI (`.github/workflows/ci.yml`) runs `build` plus repeated core tests. The Docs and Release
  workflows check out with `fetch-depth: 0` so the version sees the tags (CI doesn't need
  the version). Releases are manual: the Release
  workflow takes the version, tags it and uploads it; then you press Publish in the
  Central Portal. `docs/releasing.md` has the
  steps, and `CHANGELOG.md` records user-visible changes.

## Rules for this repository

- **Git:** commit only when the user asks. Before every commit, run
  `git branch --show-current` and report the branch. Each phase of work lives on a
  `feat/vN` branch; don't assume which.
- **API boundaries:** new public types go in `baton-api`. Core stays unexported.
  Integrations are separate optional modules depending only on `baton-api`, and are
  never added as dependencies of `baton-core` or `baton-api`.
- **Concurrency bar:** changes to the engine, `TrackedTask`, `DefaultStageTasks`,
  `ExecutionControl`, events or propagation must pass about 25 forced reruns before
  they're called done. Several races here only appeared under repetition.

  ```bash
  for i in $(seq 1 25); do ./gradlew test --rerun-tasks -q >/dev/null 2>&1 || echo "run $i FAILED"; done
  ```
- **Dependencies:** pin versions in `gradle/libs.versions.toml`. Use only stable releases
  that are at least a few weeks old; check Maven Central's `Last-Modified` header. Spring
  Boot stays on the 4.1 line. Test-only libraries stay test-scoped (for example
  Logback, which the MDC tests need because `slf4j-simple` has a no-op MDC).
- **Docs in step:** a behaviour change updates the matching `docs/*.md`, the README, and
  the public Javadoc.
  - `docs/` is also the docs site (`mkdocs.yml`, Material for MkDocs, pinned in
    `requirements-docs.txt`), published to GitHub Pages from `main` by
    `.github/workflows/docs.yml`, with each module's Javadoc under `api/`.
  - Java on the site comes from the cookbook's recipe tests (`examples/cookbook`),
    included between `// --8<-- [start:name]` and `// --8<-- [end:name]` markers.
    Write new samples there, not inline, so they compile and run in the build.
  - The site builds with `strict: true`: broken links and missing snippets fail it.
  - Javadoc warnings fail the build for `baton-api`, `baton-testkit`, the adapters,
    `baton-visualize` and the starter.
  - Internal modules (`baton-core`, the examples) are not lint-checked.
  - `docs/performance.md` records benchmark numbers. Re-measure with `baton-benchmarks`
    when a change touches a hot path.
