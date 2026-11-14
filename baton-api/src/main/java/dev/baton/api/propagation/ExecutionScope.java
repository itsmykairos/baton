package dev.baton.api.propagation;

import dev.baton.api.event.StagePath;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a thread is about to do for a run: the work a {@link ContextPropagator} is
 * restoring context for, and what {@link BatonContext#current()} reports.
 *
 * @param runId the run id, the same as in the run's events
 * @param pipelineName the outermost pipeline's name
 * @param stage the stage the work belongs to; empty for the run itself
 * @param kind what kind of work it is
 */
public record ExecutionScope(UUID runId, String pipelineName, Optional<StagePath> stage, Kind kind) {

    /**
     * Validates the record's fields.
     *
     * @param runId the run id
     * @param pipelineName the outermost pipeline's name
     * @param stage the stage the work belongs to; empty for the run itself
     * @param kind what kind of work it is
     */
    public ExecutionScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(pipelineName, "pipelineName");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(kind, "kind");
    }

    /** The kinds of work context is restored for. */
    public enum Kind {
        /** The run's coordination: from {@code run()} or {@code start()} to the result. */
        RUN,
        /** A stage, including all its retry attempts; for a nested pipeline stage, its child run. */
        STAGE,
        /** One retry attempt run on its own thread because of a per-attempt timeout. */
        ATTEMPT,
        /** A child task forked by a stage. */
        CHILD_TASK,
        /** Evaluating a stage's condition. */
        CONDITION,
        /** Running a stage's fallback. */
        FALLBACK
    }
}
