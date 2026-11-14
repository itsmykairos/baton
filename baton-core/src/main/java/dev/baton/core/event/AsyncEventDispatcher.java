package dev.baton.core.event;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongFunction;

/**
 * Delivers one run's events to its listeners on a dedicated virtual thread.
 *
 * <p>Producers (stage and engine threads) only append to a lock-free queue and
 * never block or wake the delivery thread; when the queue holds {@code capacity}
 * events, further events are dropped and counted. The delivery thread drains the
 * queue in batches at least every {@link #BATCH_INTERVAL_MILLIS} ms, and at once
 * when the run finishes. Waking it for every event would cost a park/unpark per
 * event, which measurably slows short runs.</p>
 *
 * <p>The final {@link RunFinished} event is never dropped: it is delivered after
 * every queued event.</p>
 */
public final class AsyncEventDispatcher implements EventSink {

    /** Longest delay before a queued event is delivered while the run is active. */
    public static final long BATCH_INTERVAL_MILLIS = 10;

    private static final System.Logger LOG = System.getLogger("dev.baton.event");
    private static final long BATCH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(BATCH_INTERVAL_MILLIS);

    private final List<PipelineListener> listeners;
    private final int capacity;
    private final Queue<PipelineEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread deliveryThread;
    private volatile LongFunction<RunFinished> lastEvent;

    public AsyncEventDispatcher(List<PipelineListener> listeners, int capacity) {
        this.listeners = List.copyOf(listeners);
        this.capacity = capacity;
        this.deliveryThread = Thread.ofVirtual().name("baton-events").unstarted(this::drain);
        deliveryThread.start();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void emit(PipelineEvent event) {
        Objects.requireNonNull(event, "event");
        if (lastEvent != null) {
            dropped.incrementAndGet();
            return;
        }
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        queue.offer(event);
    }

    @Override
    public void finish(LongFunction<RunFinished> lastEvent) {
        Objects.requireNonNull(lastEvent, "lastEvent");
        if (this.lastEvent != null) {
            return;
        }
        this.lastEvent = lastEvent;
        LockSupport.unpark(deliveryThread);
    }

    private void drain() {
        for (;;) {
            for (PipelineEvent event; (event = queue.poll()) != null; ) {
                size.decrementAndGet();
                deliver(event);
            }
            LongFunction<RunFinished> finalEvent = lastEvent;
            if (finalEvent != null) {
                // Events emitted before finish() but appended after this thread saw
                // lastEvent are still in the queue: deliver them first.
                for (PipelineEvent event; (event = queue.poll()) != null; ) {
                    deliver(event);
                }
                deliver(finalEvent.apply(dropped.get()));
                return;
            }
            // An unpark from finish() before this park makes it return at once.
            LockSupport.parkNanos(this, BATCH_INTERVAL_NANOS);
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
