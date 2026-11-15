# ADR 0001: Java 21 and virtual threads

- Status: Proposed
- Decision: Use Java 21 as the minimum runtime and use virtual threads internally for
  blocking stage and child-task execution.
- Rationale: Virtual threads make blocking I/O-oriented stages easier to scale without
  forcing a reactive programming model into the public API.
- Consequences: Virtual threads do not limit access to scarce resources. Database and
  HTTP connection pools, semaphores, and other application-level limits remain necessary.
  Cancellation is cooperative; an interrupt request is not proof of termination.
