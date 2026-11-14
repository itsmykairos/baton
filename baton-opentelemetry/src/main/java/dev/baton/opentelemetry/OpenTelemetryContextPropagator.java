package dev.baton.opentelemetry;

import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/**
 * Carries the OpenTelemetry {@link Context} into the threads that run stages, and
 * opens a <em>live</em> span for the run and for each stage while it runs.
 *
 * <p>Because the stage span is current while stage code runs, spans that stage code
 * creates (HTTP clients, database calls, its own spans) become its children, and
 * the whole run is one trace under the caller's current span:</p>
 *
 * <pre>
 * request                (the caller's span)
 * └─ checkout            (the run)
 *    ├─ load-order       (a stage)
 *    │  └─ GET /orders   (made by stage code)
 *    └─ payment          (a nested pipeline stage)
 *       └─ charge        (a stage of the nested pipeline)
 * </pre>
 *
 * <p>Child tasks, timed attempts, conditions and fallbacks run with their stage's
 * span current but get no span of their own. A span ends when its work physically
 * ends: a stage that timed out but keeps running ends its span late. Failed stages
 * and runs that did not complete are marked {@link StatusCode#ERROR}.</p>
 *
 * <p>Use this instead of {@link OpenTelemetryPipelineListener} when stage code's own
 * spans should nest under stages; using both records each stage twice.</p>
 *
 * <pre>{@code
 * BatonConfig.builder().contextPropagator(new OpenTelemetryContextPropagator(openTelemetry)).build();
 * }</pre>
 */
public final class OpenTelemetryContextPropagator implements ContextPropagator<Context> {

    private static final AttributeKey<String> PIPELINE_NAME =
            AttributeKey.stringKey(OpenTelemetryPipelineListener.PIPELINE_NAME);
    private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey(OpenTelemetryPipelineListener.RUN_ID);
    private static final AttributeKey<String> STAGE_PATH =
            AttributeKey.stringKey(OpenTelemetryPipelineListener.STAGE_PATH);

    private final Tracer tracer;

    /**
     * Creates the propagator.
     *
     * @param openTelemetry the OpenTelemetry instance to get a tracer from
     */
    public OpenTelemetryContextPropagator(OpenTelemetry openTelemetry) {
        this.tracer = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .getTracer(OpenTelemetryPipelineListener.INSTRUMENTATION_NAME);
    }

    @Override
    public Context capture() {
        return Context.current();
    }

    @Override
    public Restoration restore(Context captured, ExecutionScope scope) {
        Context parent = captured == null ? Context.root() : captured;
        Span span = switch (scope.kind()) {
            case RUN ->
                tracer.spanBuilder(scope.pipelineName())
                        .setParent(parent)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute(PIPELINE_NAME, scope.pipelineName())
                        .setAttribute(RUN_ID, scope.runId().toString())
                        .startSpan();
            case STAGE ->
                tracer.spanBuilder(scope.stage().orElseThrow().stageId())
                        .setParent(parent)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute(PIPELINE_NAME, scope.pipelineName())
                        .setAttribute(RUN_ID, scope.runId().toString())
                        .setAttribute(STAGE_PATH, scope.stage().orElseThrow().toString())
                        .startSpan();
            default -> null;
        };
        Scope current = (span == null ? parent : parent.with(span)).makeCurrent();
        return new Restoration() {
            @Override
            public void failed(Throwable failure) {
                if (span != null) {
                    span.setStatus(StatusCode.ERROR, failure.getClass().getSimpleName());
                    span.recordException(failure);
                }
            }

            @Override
            public void close() {
                current.close();
                if (span != null) {
                    span.end();
                }
            }
        };
    }
}
