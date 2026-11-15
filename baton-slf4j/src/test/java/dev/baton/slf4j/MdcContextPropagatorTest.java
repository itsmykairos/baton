package dev.baton.slf4j;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcContextPropagatorTest {

    private static final BatonConfig WITH_MDC =
            BatonConfig.builder().contextPropagator(new MdcContextPropagator()).build();

    @Test
    void stageAndChildTaskLogsCarryTheCallersMdcAndTheRunAndStage() throws Exception {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        Pipeline payment = Pipeline.builder()
                .stage("charge", context -> {
                    seen.put("charge", MDC.getCopyOfContextMap());
                    return ContextPatch.empty();
                })
                .build();
        Pipeline checkout = Pipeline.builder(WITH_MDC)
                .name("checkout")
                .stage("load", context -> {
                    seen.put("load", MDC.getCopyOfContextMap());
                    seen.put(
                            "child",
                            context.tasks().fork(MDC::getCopyOfContextMap).join());
                    return ContextPatch.empty();
                })
                .stage("payment", payment.asStage())
                .build();

        MDC.put("requestId", "req-42");
        try {
            checkout.run(ContextView.empty());
            assertThat(MDC.getCopyOfContextMap())
                    .as("caller MDC unchanged")
                    .containsOnly(Map.entry("requestId", "req-42"));
        } finally {
            MDC.clear();
        }

        assertThat(seen.get("load"))
                .containsEntry("requestId", "req-42")
                .containsEntry(MdcContextPropagator.PIPELINE, "checkout")
                .containsEntry(MdcContextPropagator.STAGE, "load")
                .containsKey(MdcContextPropagator.RUN_ID);
        assertThat(seen.get("child"))
                .containsEntry(MdcContextPropagator.STAGE, "load")
                .containsEntry("requestId", "req-42");
        assertThat(seen.get("charge"))
                .containsEntry(MdcContextPropagator.STAGE, "payment/charge")
                .containsEntry("requestId", "req-42")
                .containsEntry(MdcContextPropagator.RUN_ID, seen.get("load").get(MdcContextPropagator.RUN_ID));
    }

    @Test
    void stageThreadsDoNotLeakMdcBetweenRuns() throws Exception {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        Pipeline pipeline = Pipeline.builder(WITH_MDC)
                .stage("work", context -> {
                    seen.put("work", MDC.getCopyOfContextMap());
                    return ContextPatch.empty();
                })
                .build();

        MDC.put("tenant", "a");
        pipeline.run(ContextView.empty());
        MDC.clear();
        pipeline.run(ContextView.empty());

        assertThat(seen.get("work")).doesNotContainKey("tenant").containsKey(MdcContextPropagator.STAGE);
    }
}
