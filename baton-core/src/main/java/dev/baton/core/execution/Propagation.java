package dev.baton.core.execution;

import dev.baton.api.event.StagePath;
import dev.baton.api.propagation.BatonContext;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Carries a run's context propagators across thread hops: {@link #capture()} on the
 * submitting thread, {@link Snapshot#restore} on the thread that does the work.
 * {@link BatonContext#PROPAGATOR} always comes first, so
 * {@link BatonContext#current()} works everywhere.
 */
final class Propagation {

    private static final System.Logger LOG = System.getLogger("dev.baton");
    private static final Object FAILED_CAPTURE = new Object();

    private final List<ContextPropagator<Object>> propagators;
    private final UUID runId;
    private final String pipelineName;
    /** True when only {@link BatonContext} is installed: the common, allocation-light path. */
    private final boolean batonContextOnly;

    private final Snapshot batonContextSnapshot = new Snapshot(new Object[1]);

    @SuppressWarnings("unchecked")
    Propagation(UUID runId, String pipelineName, List<ContextPropagator<?>> configured) {
        this.runId = runId;
        this.pipelineName = pipelineName;
        List<ContextPropagator<Object>> all = new ArrayList<>(configured.size() + 1);
        all.add((ContextPropagator<Object>) (ContextPropagator<?>) BatonContext.PROPAGATOR);
        configured.forEach(propagator -> all.add((ContextPropagator<Object>) propagator));
        this.propagators = List.copyOf(all);
        this.batonContextOnly = configured.isEmpty();
    }

    ExecutionScope scope(ExecutionScope.Kind kind, StagePath stage) {
        return new ExecutionScope(runId, pipelineName, Optional.ofNullable(stage), kind);
    }

    /** Captures every propagator's state on the current (submitting) thread. */
    Snapshot capture() {
        if (batonContextOnly) {
            // BatonContext captures nothing, so one shared snapshot serves every hop.
            return batonContextSnapshot;
        }
        Object[] states = new Object[propagators.size()];
        for (int i = 0; i < states.length; i++) {
            try {
                states[i] = propagators.get(i).capture();
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.WARNING, "Context propagator failed to capture; skipped", failure);
                states[i] = FAILED_CAPTURE;
            }
        }
        return new Snapshot(states);
    }

    /** Wraps work so it runs with the current thread's context, restored for {@code scope}. */
    <T> Callable<T> wrap(Callable<T> work, ExecutionScope scope) {
        Snapshot snapshot = capture();
        return () -> {
            try (Restoration restoration = snapshot.restore(scope)) {
                try {
                    return work.call();
                } catch (Throwable failure) {
                    restoration.failed(failure);
                    throw failure;
                }
            }
        };
    }

    /** State captured on one thread, to restore on another. */
    final class Snapshot {
        private final Object[] states;

        private Snapshot(Object[] states) {
            this.states = states;
        }

        /** Restores every propagator, in order; the result undoes them in reverse. */
        Restoration restore(ExecutionScope scope) {
            if (batonContextOnly) {
                return BatonContext.PROPAGATOR.restore(null, scope);
            }
            List<Restoration> restored = new ArrayList<>(states.length);
            for (int i = 0; i < states.length; i++) {
                if (states[i] == FAILED_CAPTURE) {
                    continue;
                }
                try {
                    restored.add(propagators.get(i).restore(states[i], scope));
                } catch (Throwable failure) {
                    LOG.log(System.Logger.Level.WARNING, "Context propagator failed to restore; skipped", failure);
                }
            }
            return new Restorations(restored);
        }
    }

    /** Several restorations undone in reverse order; never throws. */
    private record Restorations(List<Restoration> restorations) implements Restoration {

        @Override
        public void failed(Throwable failure) {
            for (int i = restorations.size() - 1; i >= 0; i--) {
                try {
                    restorations.get(i).failed(failure);
                } catch (Throwable ignored) {
                    // A broken propagator must not affect the work's outcome.
                }
            }
        }

        @Override
        public void close() {
            for (int i = restorations.size() - 1; i >= 0; i--) {
                try {
                    restorations.get(i).close();
                } catch (Throwable ignored) {
                    // A broken propagator must not affect the work's outcome.
                }
            }
        }
    }
}
