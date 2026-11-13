package dev.baton.core.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExecutionControl {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Pipeline execution was cancelled");
        }
    }
}