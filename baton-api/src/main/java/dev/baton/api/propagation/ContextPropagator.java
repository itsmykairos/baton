package dev.baton.api.propagation;

/**
 * Carries thread-bound context from the thread that submits work for a run to the
 * thread that runs it.
 *
 * <p>Baton calls {@link #capture()} on the submitting thread, then
 * {@link #restore(Object, ExecutionScope)} on the thread that runs the work, and
 * closes the returned {@link Restoration} on that thread when the work ends. This
 * happens at every hop:</p>
 * <ul>
 *   <li>the caller of {@code run()}/{@code start()} into the run ({@link ExecutionScope.Kind#RUN});</li>
 *   <li>the run into each stage ({@code STAGE}), and a stage into its child tasks
 *       ({@code CHILD_TASK}) and timed attempts ({@code ATTEMPT});</li>
 *   <li>a nested pipeline stage into the nested pipeline's stages, so context set up
 *       for the nested stage (such as a span) carries on down;</li>
 *   <li>around conditions ({@code CONDITION}) and fallbacks ({@code FALLBACK}).</li>
 * </ul>
 *
 * <p>Restorations nest: each restore happens inside the context of the hop above
 * it. Restorations of one hop are closed in reverse registration order.
 * Implementations must be thread-safe and must not throw; a propagator that
 * throws is skipped for that hop.</p>
 *
 * @param <T> the captured state
 */
public interface ContextPropagator<T> {

    /**
     * Captures the current thread's context.
     *
     * @return the state to restore elsewhere; may be null
     */
    T capture();

    /**
     * Restores captured state on the current thread before it does the work in
     * {@code scope}.
     *
     * @param captured what {@link #capture()} returned on the submitting thread
     * @param scope the work about to run
     * @return what undoes the restoration when the work ends
     */
    Restoration restore(T captured, ExecutionScope scope);
}
