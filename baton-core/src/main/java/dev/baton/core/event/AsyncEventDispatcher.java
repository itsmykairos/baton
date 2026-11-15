package dev.baton.core.event;

import dev.baton.api.EventOverflowPolicy;
import dev.baton.api.ListenerErrorHandler;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.event.RunFinished;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * queue in batches at least every configured batch interval, and at once
 * when the run finishes. Waking it for every event would cost a park/unpark per
 * event, which measurably slows short runs.</p>
 *
 * <p>The final {@link RunFinished} event is never dropped: it is delivered after
 * every queued event.</p>
 */
public final class AsyncEventDispatcher implements EventSink {

    private final List<PipelineListener> listeners;
    private final int capacity;
    private final long batchIntervalNanos;
    private final EventOverflowPolicy overflow;
    private final ListenerErrorHandler errorHandler;
    private final Queue<PipelineEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    /** Emits in progress; finishing waits for them so none lands behind RunFinished. */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final Thread deliveryThread;
    private volatile LongFunction<RunFinished> lastEvent;

    public AsyncEventDispatcher(
            List<PipelineListener> listeners,
            int capacity,
            Duration batchInterval,
            EventOverflowPolicy overflow,
            ListenerErrorHandler errorHandler) {
        this.listeners = List.copyOf(listeners);
        this.capacity = capacity;
        this.batchIntervalNanos = batchInterval.toNanos();
        this.overflow = Objects.requireNonNull(overflow, "overflow");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
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
        inFlight.incrementAndGet();
        try {
            offer(event);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void offer(PipelineEvent event) {
        if (lastEvent != null) {
            dropped.incrementAndGet();
            return;
        }
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            dropped.incrementAndGet();
            if (overflow == EventOverflowPolicy.DROP_NEWEST) {
                return;
            }
            // DROP_OLDEST: make room by discarding the head, then queue the new event.
            if (queue.poll() == null) {
                return;
            }
            // The polled event's slot is reused by the new one, so size is unchanged.
            queue.offer(event);
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
        for (; ; ) {
            for (PipelineEvent event; (event = queue.poll()) != null; ) {
                size.decrementAndGet();
                deliver(event);
            }
            LongFunction<RunFinished> finalEvent = lastEvent;
            if (finalEvent != null) {
                // An emit that started before finish() may still be adding its event;
                // once none is in flight, every later emit sees lastEvent and is
                // counted as dropped, so the queue holds everything to deliver.
                while (inFlight.get() > 0) {
                    Thread.onSpinWait();
                }
                for (PipelineEvent event; (event = queue.poll()) != null; ) {
                    deliver(event);
                }
                deliver(finalEvent.apply(dropped.get()));
                return;
            }
            // An unpark from finish() before this park makes it return at once.
            LockSupport.parkNanos(this, batchIntervalNanos);
        }
    }

    private void deliver(PipelineEvent event) {
        for (PipelineListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable failure) {
                try {
                    errorHandler.onListenerError(listener, event, failure);
                } catch (Throwable handlerFailure) {
                    // A broken handler must not stop delivery.
                }
            }
        }
    }
}
