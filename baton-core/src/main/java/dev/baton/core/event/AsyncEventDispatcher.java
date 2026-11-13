package dev.baton.core.event;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * Delivers one run's events to its listeners on a dedicated virtual thread.
 *
 * <p>Producers (stage and engine threads) only offer to a bounded queue and never
 * block; when it is full the event is dropped and counted. The final
 * {@link RunFinished} event is never dropped: it is delivered once the queue has
 * drained.</p>
 */
public final class AsyncEventDispatcher implements EventSink {

    private static final System.Logger LOG = System.getLogger("dev.baton.event");
    private static final Object END = new Object();

    private final List<PipelineListener> listeners;
    private final BlockingQueue<Object> queue;
    private final AtomicLong dropped = new AtomicLong();
    private volatile LongFunction<RunFinished> lastEvent;

    public AsyncEventDispatcher(List<PipelineListener> listeners, int capacity) {
        this.listeners = List.copyOf(listeners);
        this.queue = new ArrayBlockingQueue<>(capacity);
        Thread.ofVirtual().name("baton-events").start(this::drain);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void emit(PipelineEvent event) {
        Objects.requireNonNull(event, "event");
        if (lastEvent != null || !queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void finish(LongFunction<RunFinished> lastEvent) {
        Objects.requireNonNull(lastEvent, "lastEvent");
        if (this.lastEvent != null) {
            return;
        }
        this.lastEvent = lastEvent;
        // If the queue is full, the drain loop notices lastEvent once it empties.
        queue.offer(END);
    }

    private void drain() {
        for (;;) {
            Object next;
            try {
                next = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                // Nothing interrupts this thread; keep delivering.
                continue;
            }
            LongFunction<RunFinished> finalEvent = lastEvent;
            if (next == END || (next == null && finalEvent != null && queue.isEmpty())) {
                // Events that raced past finish() land after END; they count as dropped.
                for (Object late; (late = queue.poll()) != null; ) {
                    if (late != END) {
                        dropped.incrementAndGet();
                    }
                }
                deliver(finalEvent.apply(dropped.get()));
                return;
            }
            if (next != null) {
                deliver((PipelineEvent) next);
            }
        }
    }

    private void deliver(PipelineEvent event) {
        for (PipelineListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "Pipeline listener " + listener + " failed on " + event.getClass().getSimpleName(),
                        failure);
            }
        }
    }
}
