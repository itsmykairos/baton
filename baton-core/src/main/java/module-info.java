/**
 * Baton's default runtime. Exports no packages: callers use {@code dev.baton.api},
 * which finds this implementation through its {@code PipelineProvider} service.
 */
module dev.baton.core {
    requires dev.baton.api;

    provides dev.baton.api.PipelineProvider
            with dev.baton.core.DefaultPipelineProvider;
}
