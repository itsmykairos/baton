package dev.baton.api;

import java.util.Objects;

public final class PipelineExecutionException
        extends Exception {

    private final PipelineResult result;

    public PipelineExecutionException(PipelineResult result) {
        super(
                "Nested pipeline did not complete successfully: "
                        + Objects.requireNonNull(result, "result").status(),
                result.failure().orElse(null)
        );

        this.result = result;
    }

    public PipelineResult result() {
        return result;
    }
}