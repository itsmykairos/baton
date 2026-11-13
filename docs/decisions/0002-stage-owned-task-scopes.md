# ADR 0002: Stage-owned child-task scopes

- Status: Proposed
- Decision: Child tasks are created through a stage-owned task scope and cannot be detached
  from the stage lifecycle.
- Rationale: Baton needs to track child-task completion, deadlines, cancellation requests,
  and termination status as part of the owning stage.
- Consequences: A stage returns one context patch after its work is complete. Child tasks
  should return values rather than mutate shared pipeline context. Cancellation of one
  stage scope must not shut down an executor shared by unrelated stages.
