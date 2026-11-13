# Baton Phase 3.4 — Concurrency Hardening

This is an incremental patch for the Phase 3.3 runtime. It does not change `baton-api` or any public contract.

## Changes

- Makes stage-task registration and pipeline cancellation linearizable using one small registration lock.
- Takes a stable snapshot of active tasks while holding the lock, then performs cancellation outside the lock. This avoids invoking child-scope cleanup while holding the registration lock.
- Ensures a task registered concurrently with cancellation is either in the cancellation snapshot or immediately cancelled by registration.
- Adds stress coverage for the registration/cancellation race and task cancellation versus normal completion.
- Adds parallel-layer tests for definition-order result/patch handling and atomic failure on duplicate context writes.

## Apply

Apply the archive over the existing multi-module project. It contains only changed/new files under `baton-core`; keep all existing root Gradle files and other modules.

## Verification

- All 24 `baton-core` main Java source files compiled with Java 21 and `-Xlint:all` against the existing `baton-api` JAR.
- A standalone Java 21 smoke test passed for deterministic parallel result ordering, successful patch merging, and duplicate-write conflict handling.
- The new JUnit tests were added but could not be executed in this environment because the Gradle executable and JUnit/AssertJ dependency JARs are not available here. Run the normal project Gradle build and test suite after applying.
