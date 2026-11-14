package dev.baton.api;

/**
 * Supplies a replacement patch when a stage fails or times out, after any retries.
 * The stage is then reported {@link StageStatus#RECOVERED}: the patch is applied,
 * the run continues, and the original failure stays in {@link StageResult#failure()}.
 *
 * <p>The fallback is not called for cancelled or BLOCKED stages, or when the
 * enclosing run or nested-stage deadline has already expired. It runs on the
 * thread that coordinates the stage's layer and is not subject to the stage
 * timeout, so it should be quick: a default, a cached value, a degraded result.
 * If it throws, the stage is FAILED with that exception and the original failure
 * attached as suppressed.</p>
 */
@FunctionalInterface
public interface StageFallback {

    /**
     * Produces the replacement patch.
     *
     * @param context the context snapshot the stage read
     * @param failure why the stage failed or timed out
     * @return the writes to apply instead; never null
     * @throws Exception if no replacement can be produced
     */
    ContextPatch recover(ContextView context, Throwable failure) throws Exception;
}
