package dev.baton.core;

import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.core.definition.PipelineDefinition;
import dev.baton.core.execution.PipelineExecutionSession;

import java.util.Objects;

public final class DefaultPipeline implements Pipeline {

    private final PipelineDefinition definition;

    public DefaultPipeline(PipelineDefinition definition) {
        this.definition = Objects.requireNonNull(
                definition,
                "definition"
        );
    }

    /** The immutable definition; used by the engine to run this pipeline nested in another. */
    public PipelineDefinition definition() {
        return definition;
    }

    @Override
    public PipelineResult run(ContextView input)
            throws InterruptedException {

        return new PipelineExecutionSession(
                definition,
                Objects.requireNonNull(input, "input")
        ).executeSync();
    }

    @Override
    public PipelineRun start(ContextView input) {
        PipelineExecutionSession session =
                new PipelineExecutionSession(
                        definition,
                        Objects.requireNonNull(input, "input")
                );

        session.startAsync();
        return session;
    }
}