package dev.baton.micrometer;

import dev.baton.api.event.ContextConflict;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.RunStarted;
import dev.baton.api.event.StageFinished;
import dev.baton.api.event.StageRetrying;
import dev.baton.api.event.StageStarted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records Baton run and stage metrics in a Micrometer {@link MeterRegistry}.
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Name</th><th>Type</th><th>Tags</th></tr>
 *   <tr><td>{@value #STAGE_DURATION}</td><td>timer</td><td>pipeline, stage (path), status</td></tr>
 *   <tr><td>{@value #STAGE_RETRIES}</td><td>counter</td><td>pipeline, stage (path)</td></tr>
 *   <tr><td>{@value #CONTEXT_CONFLICTS}</td><td>counter</td><td>pipeline, kind, resolution</td></tr>
 *   <tr><td>{@value #RUN_DURATION}</td><td>timer</td><td>pipeline, status</td></tr>
 *   <tr><td>{@value #ACTIVE_RUNS}</td><td>gauge</td><td>pipeline</td></tr>
 *   <tr><td>{@value #DROPPED_EVENTS}</td><td>counter</td><td>pipeline</td></tr>
 * </table>
 *
 * <p>Tag values come from pipeline names and stage ids, which are fixed when a
 * pipeline is built, so cardinality stays bounded. Register one instance per
 * registry, on the builder ({@code Pipeline.Builder.listener}) so every run is
 * measured. It is thread-safe.</p>
 */
public final class MicrometerPipelineListener implements PipelineListener {

    /** Timer for stage durations. */
    public static final String STAGE_DURATION = "baton.stage.duration";
    /** Counter of retried stage attempts. */
    public static final String STAGE_RETRIES = "baton.stage.retries";
    /** Counter of context write conflicts, by kind and resolution. */
    public static final String CONTEXT_CONFLICTS = "baton.context.conflicts";
    /** Timer for run durations. */
    public static final String RUN_DURATION = "baton.run.duration";
    /** Gauge of runs in progress. */
    public static final String ACTIVE_RUNS = "baton.runs.active";
    /** Counter of events dropped because listeners fell behind. */
    public static final String DROPPED_EVENTS = "baton.events.dropped";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> activeRuns = new ConcurrentHashMap<>();

    /**
     * Creates a listener that records into {@code registry}.
     *
     * @param registry the registry to record into
     */
    public MicrometerPipelineListener(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onEvent(PipelineEvent event) {
        switch (event) {
            case RunStarted started -> active(started.pipelineName()).incrementAndGet();
            case StageStarted ignored -> {}
            case ContextConflict conflict ->
                Counter.builder(CONTEXT_CONFLICTS)
                        .description("Context write conflicts and how they were resolved")
                        .tag("pipeline", conflict.pipelineName())
                        .tag("kind", conflict.conflict().kind().name())
                        .tag("resolution", conflict.conflict().resolution().name())
                        .register(registry)
                        .increment();
            case StageRetrying retrying ->
                Counter.builder(STAGE_RETRIES)
                        .description("Stage attempts that failed and were retried")
                        .tag("pipeline", retrying.pipelineName())
                        .tag("stage", retrying.path().toString())
                        .register(registry)
                        .increment();
            case StageFinished finished ->
                Timer.builder(STAGE_DURATION)
                        .description("Time from a stage's start to its logical outcome")
                        .tag("pipeline", finished.pipelineName())
                        .tag("stage", finished.path().toString())
                        .tag("status", finished.status().name())
                        .register(registry)
                        .record(finished.duration());
            case RunFinished finished -> {
                active(finished.pipelineName()).decrementAndGet();
                Timer.builder(RUN_DURATION)
                        .description("Time from a run's start to its terminal outcome")
                        .tag("pipeline", finished.pipelineName())
                        .tag("status", finished.status().name())
                        .register(registry)
                        .record(finished.duration());
                if (finished.droppedEvents() > 0) {
                    Counter.builder(DROPPED_EVENTS)
                            .description("Events dropped because listeners fell behind")
                            .tag("pipeline", finished.pipelineName())
                            .register(registry)
                            .increment(finished.droppedEvents());
                }
            }
        }
    }

    private AtomicInteger active(String pipelineName) {
        return activeRuns.computeIfAbsent(pipelineName, name -> {
            AtomicInteger count = new AtomicInteger();
            Gauge.builder(ACTIVE_RUNS, count, AtomicInteger::get)
                    .description("Runs in progress")
                    .tag("pipeline", name)
                    .register(registry);
            return count;
        });
    }
}
