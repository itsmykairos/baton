package dev.baton.core.concurrency;

import dev.baton.api.WorkerTermination;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks a worker's physical lifecycle independently of its logical outcome.
 *
 * <p>The worker wrapper must call {@link #workerExited()} from its finally
 * block. Cancellation requests never mark a running worker as terminated.</p>
 */
public final class WorkerLifecycle {

    public enum State {
        QUEUED,
        RUNNING,
        CANCEL_REQUESTED,
        TERMINATED
    }

    private final AtomicReference<State> state =
            new AtomicReference<>(State.QUEUED);
    private final CountDownLatch workerExit = new CountDownLatch(1);

    private volatile Thread worker;
    private volatile boolean started;

    /**
     * Attempts to transition from QUEUED to RUNNING for the current thread.
     * Returns false if cancellation or another terminal transition won.
     */
    public boolean tryStart() {
        Thread current = Thread.currentThread();
        worker = current;

        if (!state.compareAndSet(State.QUEUED, State.RUNNING)) {
            worker = null;
            return false;
        }

        started = true;
        return true;
    }

    /**
     * Marks a submitted task as unable to start, for example after executor
     * submission rejection or cancellation while queued.
     */
    public boolean markNotStarted() {
        if (!state.compareAndSet(State.QUEUED, State.TERMINATED)) {
            return false;
        }

        workerExit.countDown();
        return true;
    }

    /**
     * Requests cancellation. A running worker is interrupted, but remains
     * CANCEL_REQUESTED until its wrapper reports physical exit.
     */
    public boolean requestCancellation() {
        for (;;) {
            State current = state.get();

            switch (current) {
                case QUEUED -> {
                    if (markNotStarted()) {
                        return true;
                    }
                }
                case RUNNING -> {
                    if (state.compareAndSet(State.RUNNING, State.CANCEL_REQUESTED)) {
                        interruptWorker();
                        return true;
                    }
                }
                case CANCEL_REQUESTED -> {
                    interruptWorker();
                    return true;
                }
                case TERMINATED -> {
                    return false;
                }
            }
        }
    }

    /** Must be called by the worker wrapper from its finally block. */
    public void workerExited() {
        state.set(State.TERMINATED);
        worker = null;
        workerExit.countDown();
    }

    public State state() {
        return state.get();
    }

    public boolean hasStarted() {
        return started;
    }

    /**
     * Returns the strongest termination fact currently known.
     */
    public WorkerTermination termination() {
        State current = state.get();
        if (current == State.CANCEL_REQUESTED || current == State.RUNNING) {
            // These states can only be reached after the worker-start CAS.
            return WorkerTermination.UNCONFIRMED;
        }
        if (current == State.TERMINATED) {
            return started
                    ? WorkerTermination.TERMINATED
                    : WorkerTermination.NOT_STARTED;
        }
        return WorkerTermination.NOT_STARTED;
    }

    /** Waits for physical worker exit, not merely logical cancellation. */
    public boolean awaitExit(long timeout, TimeUnit unit)
            throws InterruptedException {
        return workerExit.await(timeout, unit);
    }

    public boolean hasExited() {
        return workerExit.getCount() == 0L;
    }

    private void interruptWorker() {
        Thread running = worker;
        if (running != null) {
            running.interrupt();
        }
    }
}
