package dev.baton.spring.boot;

import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.PipelineListener;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Function;

/**
 * Integrations that need a bean from another auto-configuration (a MeterRegistry, an
 * OpenTelemetry). They look it up on first use, so they work regardless of the order
 * in which auto-configurations run, and do nothing if the bean never appears.
 */
final class LazyIntegrations {

    private LazyIntegrations() {
    }

    /** A listener created from a bean on first event; ignores events if the bean is absent. */
    static <B> PipelineListener listener(ObjectProvider<B> provider, Function<B, PipelineListener> factory) {
        return new PipelineListener() {
            private volatile Object delegate;

            @Override
            public void onEvent(PipelineEvent event) {
                Object current = delegate;
                if (current == null) {
                    synchronized (this) {
                        if (delegate == null) {
                            B bean = provider.getIfAvailable();
                            delegate = bean == null ? NONE : factory.apply(bean);
                        }
                        current = delegate;
                    }
                }
                if (current != NONE) {
                    ((PipelineListener) current).onEvent(event);
                }
            }
        };
    }

    /** A propagator created from a bean on first use; does nothing if the bean is absent. */
    @SuppressWarnings("unchecked")
    static <B> ContextPropagator<Object> propagator(ObjectProvider<B> provider,
                                                   Function<B, ContextPropagator<?>> factory) {
        return new ContextPropagator<>() {
            private volatile Object delegate;

            private ContextPropagator<Object> delegate() {
                Object current = delegate;
                if (current == null) {
                    synchronized (this) {
                        if (delegate == null) {
                            B bean = provider.getIfAvailable();
                            delegate = bean == null ? NONE : factory.apply(bean);
                        }
                        current = delegate;
                    }
                }
                return current == NONE ? null : (ContextPropagator<Object>) current;
            }

            @Override
            public Object capture() {
                ContextPropagator<Object> propagator = delegate();
                return propagator == null ? null : propagator.capture();
            }

            @Override
            public Restoration restore(Object captured, ExecutionScope scope) {
                ContextPropagator<Object> propagator = delegate();
                return propagator == null ? Restoration.NONE : propagator.restore(captured, scope);
            }
        };
    }

    private static final Object NONE = new Object();
}
