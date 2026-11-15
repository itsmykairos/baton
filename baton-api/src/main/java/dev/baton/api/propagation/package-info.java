/**
 * Carrying thread-bound context (log MDC, tracing context, security context, …)
 * from the caller into the threads that run stages and child tasks.
 *
 * <p>Register {@link dev.baton.api.propagation.ContextPropagator}s with
 * {@link dev.baton.api.BatonConfig.Builder#contextPropagator}. Stage code can ask
 * {@link dev.baton.api.propagation.BatonContext#current()} which run and stage it is
 * working for.</p>
 */
package dev.baton.api.propagation;
