/**
 * Baton's public API: pipelines, stages, context and results.
 *
 * <p>An implementation is found with {@link java.util.ServiceLoader}; add
 * {@code dev.baton.core} to the module path or classpath.</p>
 */
module dev.baton.api {
    exports dev.baton.api;
    exports dev.baton.api.event;
    exports dev.baton.api.description;

    uses dev.baton.api.PipelineProvider;
}
