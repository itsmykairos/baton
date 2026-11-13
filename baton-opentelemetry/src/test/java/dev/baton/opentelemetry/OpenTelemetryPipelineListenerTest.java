package dev.baton.opentelemetry;

import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.testkit.RecordingListener;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryPipelineListenerTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();

    @Test
    void runBecomesATraceWithNestedStageSpans() throws Exception {
        RecordingListener done = new RecordingListener();
        Pipeline payment = Pipeline.builder()
                .stage("charge", context -> ContextPatch.empty())
                .build();
        Pipeline checkout = Pipeline.builder()
                .name("checkout")
                .listener(new OpenTelemetryPipelineListener(otel))
                .stage("load", context -> {
                    Thread.sleep(20);
                    return ContextPatch.empty();
                })
                .stage("optional", context -> {
                    throw new IllegalStateException("down");
                }, StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .stage("payment", payment.asStage())
                .build();

        checkout.run(ContextView.empty(), RunOptions.defaults().withListener(done));
        done.awaitRunsFinished(1, Duration.ofSeconds(5));

        Map<String, SpanData> spans = exporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, Function.identity()));
        assertThat(spans).containsOnlyKeys("checkout", "load", "optional", "payment", "charge");

        SpanData run = spans.get("checkout");
        assertThat(run.getParentSpanContext().isValid()).isFalse();
        assertThat(spans.get("load").getParentSpanId()).isEqualTo(run.getSpanId());
        assertThat(spans.get("charge").getParentSpanId()).isEqualTo(spans.get("payment").getSpanId());
        assertThat(spans.values()).extracting(SpanData::getTraceId).containsOnly(run.getTraceId());

        SpanData load = spans.get("load");
        assertThat(load.getEndEpochNanos() - load.getStartEpochNanos())
                .isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos());
        assertThat(load.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                OpenTelemetryPipelineListener.STAGE_STATUS))).isEqualTo("SUCCEEDED");

        SpanData optional = spans.get("optional");
        assertThat(optional.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(optional.getEvents()).extracting(event -> event.getName()).contains("exception");
        assertThat(run.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(spans.get("charge").getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                OpenTelemetryPipelineListener.STAGE_PATH))).isEqualTo("payment/charge");
    }

    @Test
    void forCurrentContextMakesTheRunAChildOfTheCallersSpan() throws Exception {
        RecordingListener done = new RecordingListener();
        Pipeline pipeline = Pipeline.builder()
                .name("job")
                .stage("work", context -> ContextPatch.empty())
                .build();

        Span request = otel.getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignored = request.makeCurrent()) {
            pipeline.run(ContextView.empty(), RunOptions.defaults()
                    .withListener(OpenTelemetryPipelineListener.forCurrentContext(otel))
                    .withListener(done));
        }
        done.awaitRunsFinished(1, Duration.ofSeconds(5));
        request.end();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData job = spans.stream().filter(span -> span.getName().equals("job")).findFirst().orElseThrow();
        assertThat(job.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
        assertThat(job.getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
    }

    @Test
    void failedRunIsAnError() throws Exception {
        RecordingListener done = new RecordingListener();
        Pipeline pipeline = Pipeline.builder()
                .name("broken")
                .listener(new OpenTelemetryPipelineListener(otel))
                .stage("fails", context -> {
                    throw new IllegalStateException("boom");
                })
                .stage("never", context -> ContextPatch.empty())
                .build();

        pipeline.run(ContextView.empty(), RunOptions.defaults().withListener(done));
        done.awaitRunsFinished(1, Duration.ofSeconds(5));

        Map<String, SpanData> spans = exporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, Function.identity()));
        assertThat(spans).containsOnlyKeys("broken", "fails");
        assertThat(spans.get("broken").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }
}
