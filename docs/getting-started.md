# Getting started

## Add Baton to your build

Baton needs Java 21. Import the BOM to align module versions, then compile against
`baton-api` and add `baton-core` at run time.

=== "Gradle (Kotlin)"

    ```kotlin
    dependencies {
        implementation(platform("io.github.kairos-tools:baton-bom:0.1.0"))
        implementation("io.github.kairos-tools:baton-api")
        runtimeOnly("io.github.kairos-tools:baton-core")
        testImplementation("io.github.kairos-tools:baton-testkit")
    }
    ```

=== "Maven"

    ```xml
    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>io.github.kairos-tools</groupId>
          <artifactId>baton-bom</artifactId>
          <version>0.1.0</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>

    <dependencies>
      <dependency>
        <groupId>io.github.kairos-tools</groupId>
        <artifactId>baton-api</artifactId>
      </dependency>
      <dependency>
        <groupId>io.github.kairos-tools</groupId>
        <artifactId>baton-core</artifactId>
        <scope>runtime</scope>
      </dependency>
      <dependency>
        <groupId>io.github.kairos-tools</groupId>
        <artifactId>baton-testkit</artifactId>
        <scope>test</scope>
      </dependency>
    </dependencies>
    ```

!!! note "Not on Maven Central yet"
    0.1.0 hasn't been published yet. Until it is, run `./gradlew publishToMavenLocal`
    in a clone of the repository, and add `mavenLocal()` to your repositories.

`baton-api` is the only module your code compiles against. The engine in `baton-core`
is found at startup through `ServiceLoader`. On the module path, `requires dev.baton.api`
is enough.

## Your first pipeline

Values move between stages through the **context**, under typed keys. Keys with the
same name and type are equal, so they're usually constants:

```java
--8<-- "QuickStartTest.java:keys"
```

A pipeline is built from layers. Here one stage loads the order, then two stages enrich
it in parallel. The `price` stage forks child tasks and has a timeout:

```java
--8<-- "QuickStartTest.java:pipeline"
```

Each stage gets a `StageContext`: `context()` is the snapshot it reads, and `tasks()`
forks child tasks that can't outlive the stage. A stage returns a `ContextPatch`. The
patches of one layer are merged when the whole layer has finished.

## Run it

```java
--8<-- "QuickStartTest.java:run"
```

`run` blocks until the run ends; [`start`](cookbook/async-runs.md) returns at once.
The result has the run's status, the final context and a result for each stage:
its status, duration, attempts and failure.

| Stage status | Meaning |
|---|---|
| `SUCCEEDED` | Returned a patch. |
| `RECOVERED` | Failed or timed out, and its fallback supplied a patch. Counts as success. |
| `SKIPPED` | Didn't run: its condition was false, or inputs were missing with `skipIfInputsMissing()`. Counts as success. |
| `BLOCKED` | Didn't run: a key in its `requiredReads()` was missing. |
| `FAILED` | Threw, or wrote a key that conflicts with a sibling. |
| `TIMED_OUT` | Hit its timeout, or its run's or nested stage's deadline. |
| `CANCELLED` | Stopped by cancellation, or by a FAIL_FAST failure of a sibling. |

By default a failure is `FAIL_FAST`: it cancels the rest of its layer and stops the
run. [Retries, timeouts and fallbacks](cookbook/resilience.md) shows the other options.

## Next steps

- Browse the [cookbook](cookbook/index.md).
- Test stages on their own with [`baton-testkit`](cookbook/testing.md).
- Using Spring Boot? See [Spring Boot](spring-boot.md): pipelines can be declared in
  `application.yml`.
