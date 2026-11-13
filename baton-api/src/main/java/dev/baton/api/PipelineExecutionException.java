package dev.baton.api;

import java.util.Objects;

/**
 * Thrown by a nested pipeline stage whose child run ended
 * {@link PipelineStatus#FAILED} or {@link PipelineStatus#TIMED_OUT}.
 * The cause is the child run's own failure.
 */
public final class PipelineExecutionException
        extends Exception {

    private static final long serialVersionUID = 1L;

    /** Not serialized: results hold live context values. */
    private final transient PipelineResult result;

    /**
     * Creates the exception.
     *
     * @param result the unsuccessful child result
     */
    public PipelineExecutionException(PipelineResult result) {
        super(
                "Nested pipeline did not complete successfully: "
                        + Objects.requireNonNull(result, "result").status(),
                result.failure().orElse(null)
        );

        this.result = result;
    }

    /** {@return the child pipeline's result} */
    public PipelineResult result() {
        return result;
    }
}
