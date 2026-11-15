package dev.baton.core;

import dev.baton.api.Pipeline;
import dev.baton.api.PipelineProvider;
import dev.baton.core.builder.DefaultPipelineBuilder;

public final class DefaultPipelineProvider implements PipelineProvider {

    @Override
    public Pipeline.Builder builder() {
        return new DefaultPipelineBuilder();
    }
}
