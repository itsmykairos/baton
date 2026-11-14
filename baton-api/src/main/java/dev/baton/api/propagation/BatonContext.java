package dev.baton.api.propagation;

import java.util.Optional;

/**
 * Tells code running inside a pipeline which run and stage it is working for.
 *
 * <pre>{@code
 * BatonContext.current().flatMap(ExecutionScope::stage)
 *         .ifPresent(path -> log.info("working for {}", path));
 * }</pre>
 *
 * <p>Available in stage code, child tasks, conditions and fallbacks. Always on: it
 * needs no configuration.</p>
 */
public final class BatonContext {

    private static final ThreadLocal<ExecutionScope> CURRENT = new ThreadLocal<>();

    /**
     * The propagator that maintains {@link #current()}. Runtimes install it before
     * any configured propagator; applications do not need to register it.
     */
    public static final ContextPropagator<Void> PROPAGATOR = new ContextPropagator<>() {
        @Override
        public Void capture() {
            return null;
        }

        @Override
        public Restoration restore(Void captured, ExecutionScope scope) {
            ExecutionScope previous = CURRENT.get();
            CURRENT.set(scope);
            return () -> {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            };
        }
    };

    private BatonContext() {}

    /** {@return the work the current thread is doing for a run, or empty outside a run} */
    public static Optional<ExecutionScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
