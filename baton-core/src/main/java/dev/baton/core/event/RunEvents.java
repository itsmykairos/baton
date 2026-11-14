package dev.baton.core.event;

import dev.baton.api.BatonConfig;
import dev.baton.api.KeyConflict;
import dev.baton.api.PipelineResult;
import dev.baton.api.PipelineStatus;
import dev.baton.api.StageResult;
import dev.baton.api.event.ContextConflict;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StagePath;
import dev.baton.api.event.StageRetrying;
import dev.baton.api.event.StageStarted;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds and emits one run's events. Every method is a no-op, building nothing,
 * when the run has no listeners.
 */
public final class RunEvents {

    /** For runs without listeners. */
    public static final RunEvents NONE = new RunEvents(EventSink.NONE, null, "pipeline", Clock.systemUTC());

    private final EventSink sink;
    private final UUID runId;
    private final String pipelineName;
    private volatile long startedNanos;

    private final Clock clock;

    private RunEvents(EventSink sink, UUID runId, String pipelineName, Clock clock) {
        this.sink = sink;
        this.runId = runId;
        this.pipelineName = pipelineName;
        this.clock = clock;
    }

    /**
     * Creates the events of a new run: {@link #NONE} if there are no listeners,
     * otherwise a run with its own asynchronous dispatcher.
     */
    public static RunEvents create(String pipelineName, List<PipelineListener> listeners, BatonConfig config) {
        Objects.requireNonNull(pipelineName, "pipelineName");
        if (listeners.isEmpty()) {
            return NONE;
        }
        AsyncEventDispatcher dispatcher = new AsyncEventDispatcher(listeners, config.eventBufferCapacity(),
                config.eventBatchInterval(), config.eventOverflowPolicy(), config.listenerErrorHandler());
        return new RunEvents(dispatcher, newRunId(), pipelineName, config.clock());
    }

    /**
     * A random (version 4) UUID from {@link ThreadLocalRandom}. Run ids only need to
     * be unique, not unpredictable, so this avoids {@code SecureRandom} on every run.
     */
    static UUID newRunId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long most = (random.nextLong() & ~0xF000L) | 0x4000L;               // version 4
        long least = (random.nextLong() & ~(0x3L << 62)) | (0x2L << 62);    // IETF variant
        return new UUID(most, least);
    }

    public boolean enabled() {
        return sink.enabled();
    }

    public void runStarted() {
        startedNanos = System.nanoTime();
        if (enabled()) {
            sink.emit(new RunStarted(runId, pipelineName, Instant.now(clock)));
        }
    }

    /** Emits StageStarted and returns its timestamp, or null when disabled. */
    public Instant stageStarted(StagePath path) {
        if (!enabled()) {
            return null;
        }
        Instant now = Instant.now(clock);
        sink.emit(new StageStarted(runId, pipelineName, path, now));
        return now;
    }

    /**
     * Emits StageFinished. For a stage that started, the timestamp is its start
     * plus its duration (its logical end), even if the event is emitted later.
     */
    public void stageFinished(StagePath path, StageResult result, Instant startedAt) {
        if (!enabled()) {
            return;
        }
        Instant end = startedAt == null ? Instant.now(clock) : startedAt.plus(result.duration());
        sink.emit(new StageFinished(runId, pipelineName, path, result.status(), result.duration(),
                result.failure(), result.attempts(), result.workerTermination(), end));
    }

    /** Emits StageRetrying for a failed attempt that will be retried after {@code delay}. */
    public void stageRetrying(StagePath path, int failedAttempt, Throwable failure, Duration delay) {
        if (!enabled()) {
            return;
        }
        sink.emit(new StageRetrying(runId, pipelineName, path, failedAttempt, failure, delay, Instant.now(clock)));
    }

    /** Emits ContextConflict for a resolved key conflict. */
    public void conflict(KeyConflict conflict) {
        if (!enabled()) {
            return;
        }
        sink.emit(new ContextConflict(runId, pipelineName, conflict, Instant.now(clock)));
    }

    /** Ends the run's event stream with its outcome. */
    public void runFinished(PipelineResult result) {
        runFinished(result.status(), result.failure());
    }

    /** Ends the run's event stream when there is no result (an unexpected engine failure). */
    public void runFinished(PipelineStatus status, Optional<Throwable> failure) {
        if (!enabled()) {
            return;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        Instant now = Instant.now(clock);
        sink.finish(dropped -> new RunFinished(runId, pipelineName, status, duration, failure, dropped, now));
    }
}
