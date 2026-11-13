package dev.baton.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened in a pipeline run.
 *
 * <p>Events of nested pipelines are delivered to the listeners of the outermost
 * run, with a {@link StagePath} that includes the nested stage ids.</p>
 */
public sealed interface PipelineEvent
        permits RunStarted, StageStarted, StageFinished, RunFinished {

    /** {@return the id shared by every event of one run} */
    UUID runId();

    /** {@return the name of the outermost pipeline, as given to its builder} */
    String pipelineName();

    /** {@return when the event happened (wall-clock time)} */
    Instant timestamp();
}
