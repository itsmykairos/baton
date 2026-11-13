/**
 * Run and stage events for observability.
 *
 * <p>Register a {@link dev.baton.api.event.PipelineListener} with
 * {@link dev.baton.api.Pipeline.Builder#listener} (every run) or
 * {@link dev.baton.api.RunOptions#withListener} (one run). Events are delivered
 * asynchronously, so listeners never slow the pipeline; a run without listeners
 * creates no events at all.</p>
 */
package dev.baton.api.event;
