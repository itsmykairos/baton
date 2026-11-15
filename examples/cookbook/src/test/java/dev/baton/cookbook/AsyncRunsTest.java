package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineRun;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageResult;
import dev.baton.api.WorkerTermination;
import dev.baton.testkit.PipelineResults;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class AsyncRunsTest {

    @Test
    void startThenCancel() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Pipeline export = Pipeline.builder()
                .stage("export", stage -> {
                    started.countDown();
                    Thread.sleep(60_000); // long-running, and interruptible
                    return ContextPatch.empty();
                })
                .build();

        // --8<-- [start:start]
        PipelineRun run = export.start(ContextView.empty()); // returns at once
        run.status(); // RUNNING
        // --8<-- [end:start]
        started.await();
        // --8<-- [start:cancel]
        run.cancel(); // cancels every active stage, without waiting for their threads
        PipelineResult result = run.await();
        result.status(); // CANCELLED

        // Interruption is cooperative: this says whether the worker was seen to exit.
        StageResult stage = PipelineResults.stage(result, "export");
        stage.workerTermination(); // TERMINATED, or UNCONFIRMED if it is still running
        // --8<-- [end:cancel]

        assertThat(result.status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(stage.workerTermination()).isIn(WorkerTermination.TERMINATED, WorkerTermination.UNCONFIRMED);
    }
}
