package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RunOptions;
import dev.baton.api.StageStatus;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.StageFinished;
import dev.baton.api.propagation.BatonContext;
import dev.baton.slf4j.MdcContextPropagator;
import dev.baton.testkit.RecordingListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ObservabilityTest {

    static final ContextKey<String> TRACE = ContextKey.of("trace", String.class);
    static final ContextKey<String> STAGE_PATH = ContextKey.of("stagePath", String.class);

    @Test
    void listenerSeesEveryStage() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        // --8<-- [start:listener]
        PipelineListener slowStages = event -> {
            if (event instanceof StageFinished finished && finished.duration().compareTo(Duration.ofMillis(500)) > 0) {
                log.add("slow stage " + finished.path() + ": " + finished.duration());
            }
        };

        Pipeline checkout = Pipeline.builder()
                .name("checkout") // tags every event
                .listener(slowStages) // every run of this pipeline
                .stage("load", stage -> ContextPatch.empty())
                .build();

        RecordingListener recorder = new RecordingListener();
        checkout.run(ContextView.empty(), RunOptions.defaults().withListener(recorder)); // this run only
        // --8<-- [end:listener]

        RunFinished finished = recorder.awaitRunsFinished(1, Duration.ofSeconds(5));
        assertThat(finished.droppedEvents()).isZero();
        assertThat(recorder.stageFinished("load").status()).isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void mdcFollowsStages() throws Exception {
        // --8<-- [start:mdc]
        BatonConfig app = BatonConfig.builder()
                .contextPropagator(new MdcContextPropagator()) // from baton-slf4j
                .build();

        Pipeline checkout = Pipeline.builder(app)
                .stage("charge", stage -> {
                    // The caller's MDC is here, plus the run id, pipeline and stage keys.
                    String trace = MDC.get("traceId");
                    String path = MDC.get(MdcContextPropagator.STAGE);
                    return ContextPatch.builder()
                            .put(TRACE, trace)
                            .put(STAGE_PATH, path)
                            .build();
                })
                .build();

        MDC.put("traceId", "t-42");
        PipelineResult result;
        try {
            result = checkout.run(ContextView.empty());
        } finally {
            MDC.remove("traceId");
        }
        result.context().get(TRACE); // "t-42"
        // --8<-- [end:mdc]

        assertThat(result.context().get(TRACE)).isEqualTo("t-42");
        assertThat(result.context().get(STAGE_PATH)).isEqualTo("charge");
    }

    @Test
    void currentScope() throws Exception {
        // --8<-- [start:scope]
        Pipeline pipeline = Pipeline.builder()
                .stage("whoami", stage -> {
                    String path = BatonContext.current()
                            .flatMap(scope -> scope.stage())
                            .map(Object::toString)
                            .orElse("?");
                    return ContextPatch.builder().put(STAGE_PATH, path).build();
                })
                .build();
        // --8<-- [end:scope]

        assertThat(pipeline.run(ContextView.empty()).context().get(STAGE_PATH)).isEqualTo("whoami");
    }
}
