package dev.baton.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** The outcome of one stage in a pipeline run. */
public interface StageResult {

    /** {@return the stage id given to the builder} */
    String stageId();

    /** {@return the stage outcome} */
    StageStatus status();

    /** {@return how long the stage ran until its logical outcome; zero if it never started} */
    Duration duration();

    /** {@return the failure for FAILED, TIMED_OUT and CANCELLED stages} */
    Optional<Throwable> failure();

    /**
     * {@return the stage's patch when it returned one: always for SUCCEEDED, and for
     * a stage FAILED by a context merge conflict (not applied)}
     */
    Optional<ContextPatch> output();

    /** {@return what is known about the stage worker's physical exit} */
    WorkerTermination workerTermination();

    /**
     * Nested stage results.
     *
     * @return the child pipeline's stage results for a nested pipeline stage;
     *         empty for other stages
     */
    default List<StageResult> children() {
        return List.of();
    }
}
