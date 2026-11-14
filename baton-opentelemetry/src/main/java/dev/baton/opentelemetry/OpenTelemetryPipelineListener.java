package dev.baton.opentelemetry;

import dev.baton.api.StageStatus;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StageRetrying;
import dev.baton.api.event.StagePath;
import dev.baton.api.event.StageStarted;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns Baton runs into OpenTelemetry traces: one span per run, with one child
 * span per stage, nested to match nested pipelines. Spans use the timestamps
 * carried by the events, so asynchronous delivery does not distort durations.
 *
 * <p>FAILED and TIMED_OUT stages and runs get status {@link StatusCode#ERROR} and a
 * recorded exception. Each retry adds a {@value #RETRY_EVENT} span event with the
 * failed attempt, its exception and the backoff. Attributes: {@value #PIPELINE_NAME}, {@value #RUN_ID},
 * {@value #RUN_STATUS}, {@value #STAGE_PATH}, {@value #STAGE_STATUS}, {@value #STAGE_ATTEMPTS},
 * {@value #WORKER_TERMINATION} and {@value #DROPPED_EVENTS}.</p>
 *
 * <p>Register it on the builder to trace every run as its own trace, or create one
 * with {@link #forCurrentContext(OpenTelemetry)} and pass it in
 * {@code RunOptions.withListener} to make one run a child of the caller's current
 * span. Spans created by stage code itself are not linked to the stage span.
 * Instances are thread-safe.</p>
 */
public final class OpenTelemetryPipelineListener implements PipelineListener {

    /** Instrumentation scope name. */
    public static final String INSTRUMENTATION_NAME = "dev.baton";
    /** Attribute: the outermost pipeline's name. */
    public static final String PIPELINE_NAME = "baton.pipeline.name";
    /** Attribute: the run id. */
    public static final String RUN_ID = "baton.run.id";
    /** Attribute: the run's terminal status. */
    public static final String RUN_STATUS = "baton.run.status";
    /** Attribute: the stage path, such as {@code payment/charge}. */
    public static final String STAGE_PATH = "baton.stage.path";
    /** Attribute: the stage status. */
    public static final String STAGE_STATUS = "baton.stage.status";
    /** Attribute: how many attempts the stage started. */
    public static final String STAGE_ATTEMPTS = "baton.stage.attempts";
    /** Span event name for a retried attempt. */
    public static final String RETRY_EVENT = "baton.retry";
    /** Attribute: what is known about the stage worker's exit. */
    public static final String WORKER_TERMINATION = "baton.stage.worker_termination";
    /** Attribute: events dropped because listeners fell behind. */
    public static final String DROPPED_EVENTS = "baton.events.dropped";

    private static final AttributeKey<String> PIPELINE_NAME_KEY = AttributeKey.stringKey(PIPELINE_NAME);
    private static final AttributeKey<String> RUN_ID_KEY = AttributeKey.stringKey(RUN_ID);
    private static final AttributeKey<String> RUN_STATUS_KEY = AttributeKey.stringKey(RUN_STATUS);
    private static final AttributeKey<String> STAGE_PATH_KEY = AttributeKey.stringKey(STAGE_PATH);
    private static final AttributeKey<String> STAGE_STATUS_KEY = AttributeKey.stringKey(STAGE_STATUS);
    private static final AttributeKey<Long> STAGE_ATTEMPTS_KEY = AttributeKey.longKey(STAGE_ATTEMPTS);
    private static final AttributeKey<Long> FAILED_ATTEMPT_KEY = AttributeKey.longKey("baton.retry.failed_attempt");
    private static final AttributeKey<Long> DELAY_MS_KEY = AttributeKey.longKey("baton.retry.delay_ms");
    private static final AttributeKey<String> RETRY_EXCEPTION_KEY = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> WORKER_TERMINATION_KEY = AttributeKey.stringKey(WORKER_TERMINATION);
    private static final AttributeKey<Long> DROPPED_EVENTS_KEY = AttributeKey.longKey(DROPPED_EVENTS);

    private final Tracer tracer;
    private final Context parent;
    private final Map<UUID, RunSpans> runs = new ConcurrentHashMap<>();

    /**
     * Creates a listener whose runs each start a new trace.
     *
     * @param openTelemetry the OpenTelemetry instance to get a tracer from
     */
    public OpenTelemetryPipelineListener(OpenTelemetry openTelemetry) {
        this(openTelemetry, Context.root());
    }

    private OpenTelemetryPipelineListener(OpenTelemetry openTelemetry, Context parent) {
        this.tracer = Objects.requireNonNull(openTelemetry, "openTelemetry").getTracer(INSTRUMENTATION_NAME);
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    /**
     * Creates a listener for one run whose span is a child of the calling thread's
     * current context. Create it right before {@code run} or {@code start}.
     *
     * @param openTelemetry the OpenTelemetry instance to get a tracer from
     * @return a listener to pass to {@code RunOptions.withListener}
     */
    public static OpenTelemetryPipelineListener forCurrentContext(OpenTelemetry openTelemetry) {
        return new OpenTelemetryPipelineListener(openTelemetry, Context.current());
    }

    @Override
    public void onEvent(PipelineEvent event) {
        switch (event) {
            case RunStarted started -> runs.put(started.runId(), new RunSpans(startRun(started)));
            case StageStarted started -> spans(started).stages.put(started.path(),
                    startStage(started.path(), started.pipelineName(), spans(started), started.timestamp()));
            case StageRetrying retrying -> {
                Span span = spans(retrying).stages.get(retrying.path());
                if (span != null) {
                    span.addEvent(RETRY_EVENT, io.opentelemetry.api.common.Attributes.of(
                            FAILED_ATTEMPT_KEY, (long) retrying.failedAttempt(),
                            DELAY_MS_KEY, retrying.delay().toMillis(),
                            RETRY_EXCEPTION_KEY, retrying.failure().getClass().getName()),
                            retrying.timestamp());
                }
            }
            case StageFinished finished -> finishStage(finished);
            case RunFinished finished -> finishRun(finished);
        }
    }

    private Span startRun(RunStarted started) {
        return tracer.spanBuilder(started.pipelineName())
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(started.timestamp())
                .setAttribute(PIPELINE_NAME_KEY, started.pipelineName())
                .setAttribute(RUN_ID_KEY, started.runId().toString())
                .startSpan();
    }

    /** Spans of a run; created on demand if its RunStarted event was dropped. */
    private RunSpans spans(PipelineEvent event) {
        return runs.computeIfAbsent(event.runId(), id -> new RunSpans(
                startRun(new RunStarted(id, event.pipelineName(), event.timestamp()))));
    }

    private Span startStage(StagePath path, String pipelineName, RunSpans run, Instant start) {
        Span parentSpan = run.run;
        if (path.depth() > 0) {
            StagePath parentPath = new StagePath(path.segments().subList(0, path.segments().size() - 1));
            parentSpan = run.stages.getOrDefault(parentPath, run.run);
        }
        return tracer.spanBuilder(path.stageId())
                .setParent(Context.root().with(parentSpan))
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(start)
                .setAttribute(PIPELINE_NAME_KEY, pipelineName)
                .setAttribute(STAGE_PATH_KEY, path.toString())
                .startSpan();
    }

    private void finishStage(StageFinished finished) {
        RunSpans run = spans(finished);
        Span span = run.stages.remove(finished.path());
        if (span == null) {
            // Never started (BLOCKED or cancelled before start), or its start was dropped.
            span = startStage(finished.path(), finished.pipelineName(), run,
                    finished.timestamp().minus(finished.duration()));
        }
        span.setAttribute(STAGE_STATUS_KEY, finished.status().name());
        span.setAttribute(WORKER_TERMINATION_KEY, finished.workerTermination().name());
        span.setAttribute(STAGE_ATTEMPTS_KEY, (long) finished.attempts());
        if (finished.status() == StageStatus.FAILED || finished.status() == StageStatus.TIMED_OUT) {
            span.setStatus(StatusCode.ERROR, finished.status().name());
            finished.failure().ifPresent(span::recordException);
        }
        span.end(finished.timestamp());
    }

    private void finishRun(RunFinished finished) {
        RunSpans run = runs.remove(finished.runId());
        if (run == null) {
            run = new RunSpans(startRun(new RunStarted(finished.runId(), finished.pipelineName(),
                    finished.timestamp().minus(finished.duration()))));
        }
        // Stages whose finish event was dropped.
        run.stages.values().forEach(span -> span.end(finished.timestamp()));
        Span span = run.run;
        span.setAttribute(RUN_STATUS_KEY, finished.status().name());
        span.setAttribute(DROPPED_EVENTS_KEY, finished.droppedEvents());
        if (finished.status() == dev.baton.api.PipelineStatus.FAILED
                || finished.status() == dev.baton.api.PipelineStatus.TIMED_OUT) {
            span.setStatus(StatusCode.ERROR, finished.status().name());
            finished.failure().ifPresent(span::recordException);
        }
        span.end(finished.timestamp());
    }

    /** One run's open spans. Events of one run are delivered sequentially. */
    private static final class RunSpans {
        final Span run;
        final Map<StagePath, Span> stages = new HashMap<>();

        RunSpans(Span run) {
            this.run = run;
        }
    }
}
