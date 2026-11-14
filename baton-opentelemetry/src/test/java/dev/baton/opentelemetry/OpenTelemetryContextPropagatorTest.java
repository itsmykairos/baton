package dev.baton.opentelemetry;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.FailurePolicy;
import dev.baton.api.Pipeline;
import dev.baton.api.StageOptions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryContextPropagatorTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    private final Tracer appTracer = otel.getTracer("app");

    @Test
    void spansCreatedByStageCodeNestUnderLiveStageSpansInTheCallersTrace() throws Exception {
        Pipeline payment = Pipeline.builder()
                .stage("charge", context -> {
                    appTracer.spanBuilder("POST /charges").startSpan().end();
                    return ContextPatch.empty();
                })
                .build();
        Pipeline checkout = Pipeline.builder(BatonConfig.builder()
                        .contextPropagator(new OpenTelemetryContextPropagator(otel)).build())
                .name("checkout")
                .stage("load", context -> {
                    appTracer.spanBuilder("GET /orders").startSpan().end();
                    context.tasks().fork(() -> {
                        appTracer.spanBuilder("SELECT items").startSpan().end();
                        return null;
                    }).join();
                    return ContextPatch.empty();
                })
                .stage("payment", payment.asStage())
                .build();

        Span request = appTracer.spanBuilder("request").startSpan();
        try (Scope ignored = request.makeCurrent()) {
            checkout.run(ContextView.empty());
        } finally {
            request.end();
        }

        Map<String, SpanData> spans = exporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, Function.identity()));
        assertThat(spans).containsOnlyKeys("request", "checkout", "load", "GET /orders", "SELECT items",
                "payment", "charge", "POST /charges");
        assertThat(spans.values()).extracting(SpanData::getTraceId)
                .containsOnly(request.getSpanContext().getTraceId());
        assertThat(parentOf(spans, "checkout")).isEqualTo("request");
        assertThat(parentOf(spans, "load")).isEqualTo("checkout");
        assertThat(parentOf(spans, "GET /orders")).isEqualTo("load");
        assertThat(parentOf(spans, "SELECT items")).isEqualTo("load");
        assertThat(parentOf(spans, "payment")).isEqualTo("checkout");
        assertThat(parentOf(spans, "charge")).isEqualTo("payment");
        assertThat(parentOf(spans, "POST /charges")).isEqualTo("charge");
        assertThat(spans.get("charge").getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                OpenTelemetryPipelineListener.STAGE_PATH))).isEqualTo("payment/charge");
    }

    @Test
    void failedStagesAndRunsAreErrors() throws Exception {
        Pipeline pipeline = Pipeline.builder(BatonConfig.builder()
                        .contextPropagator(new OpenTelemetryContextPropagator(otel)).build())
                .name("broken")
                .stage("tolerated", context -> {
                    throw new IllegalStateException("down");
                }, StageOptions.defaults().withFailurePolicy(FailurePolicy.CONTINUE))
                .stage("fails", context -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        pipeline.run(ContextView.empty());

        Map<String, SpanData> spans = exporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, Function.identity()));
        assertThat(spans.get("tolerated").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.get("fails").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.get("broken").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.get("broken").getParentSpanContext().isValid()).isFalse();
    }

    private static String parentOf(Map<String, SpanData> spans, String name) {
        String parentId = spans.get(name).getParentSpanId();
        return spans.values().stream().filter(span -> span.getSpanId().equals(parentId))
                .map(SpanData::getName).findFirst().orElse("<none>");
    }
}
