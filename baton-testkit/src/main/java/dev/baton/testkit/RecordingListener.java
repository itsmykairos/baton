package dev.baton.testkit;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import dev.baton.api.event.StageFinished;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * A {@link PipelineListener} that records every event, for assertions in tests.
 *
 * <p>Events are delivered asynchronously, so wait for the run's last event with
 * {@link #awaitRunsFinished(int, Duration)} before asserting:</p>
 *
 * <pre>{@code
 * RecordingListener recorder = new RecordingListener();
 * pipeline.run(input, RunOptions.defaults().withListener(recorder));
 * recorder.awaitRunsFinished(1, Duration.ofSeconds(5));
 * assertThat(recorder.stageFinished("payment/charge").status()).isEqualTo(SUCCEEDED);
 * }</pre>
 */
public final class RecordingListener implements PipelineListener {

    private final List<PipelineEvent> events = new ArrayList<>();
    private int runsFinished;

    /** Creates an empty recorder. */
    public RecordingListener() {
    }

    @Override
    public synchronized void onEvent(PipelineEvent event) {
        events.add(event);
        if (event instanceof RunFinished) {
            runsFinished++;
            notifyAll();
        }
    }

    /** {@return a copy of every event recorded so far, in delivery order} */
    public synchronized List<PipelineEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the recorded events of one type.
     *
     * @param type the event type, such as {@code StageFinished.class}
     * @param <T> the event type
     * @return matching events, in delivery order
     */
    public synchronized <T extends PipelineEvent> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Returns the {@link StageFinished} event of a stage.
     *
     * @param path the stage path, such as {@code "payment/charge"}
     * @return the most recent matching event
     * @throws NoSuchElementException if no such event was recorded
     */
    public StageFinished stageFinished(String path) {
        List<StageFinished> matches = events(StageFinished.class).stream()
                .filter(event -> event.path().toString().equals(path))
                .toList();
        if (matches.isEmpty()) {
            throw new NoSuchElementException("No StageFinished event for '" + path + "'");
        }
        return matches.getLast();
    }

    /**
     * Waits until at least {@code count} runs have delivered their {@link RunFinished}.
     *
     * @param count how many runs to wait for
     * @param timeout how long to wait
     * @return the most recent {@link RunFinished}
     * @throws TimeoutException if fewer runs finished in time
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public synchronized RunFinished awaitRunsFinished(int count, Duration timeout)
            throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (runsFinished < count) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                throw new TimeoutException(runsFinished + " of " + count + " runs finished");
            }
            wait(remainingMillis);
        }
        return events(RunFinished.class).getLast();
    }

    /** Forgets every recorded event. */
    public synchronized void clear() {
        events.clear();
        runsFinished = 0;
    }
}
