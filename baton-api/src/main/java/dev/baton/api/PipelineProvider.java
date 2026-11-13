package dev.baton.api;

import java.util.ServiceLoader;

public interface PipelineProvider {

    Pipeline.Builder builder();

    static PipelineProvider load() {

        return ServiceLoader
                .load(PipelineProvider.class)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No Baton PipelineProvider found. "
                                + "Add baton-core to the runtime classpath."
                        )
                );
    }
}