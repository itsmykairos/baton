# Conditional stages

## Declare the inputs a stage needs

A stage lists the keys it can't run without in `requiredReads()`. Lambdas can't
declare them, so write a class:

```java
--8<-- "ConditionalStagesTest.java:required-reads"
```

If a required key is missing when the stage would start, it's `BLOCKED` and doesn't
run. That doesn't stop the run, and `describe()` and the diagrams show the inputs.

## Run a stage only sometimes

```java
--8<-- "ConditionalStagesTest.java:conditions"
```

- `withCondition` is evaluated just before the stage would start, against the context
  it would read. It should be quick, and it runs bounded by the run's deadline.
- `skipIfInputsMissing()` turns `BLOCKED` into `SKIPPED`.
- A `SKIPPED` stage never starts: it has no `StageStarted` event and 0 attempts.
- Stages that need a skipped stage's output are `BLOCKED`, or `SKIPPED` if they also
  skip on missing inputs.
- A condition that throws makes the stage `FAILED`, and its fallback and failure policy
  apply as usual.
