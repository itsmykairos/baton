package dev.baton.api;

import java.util.ServiceLoader;

/**
 * Service interface implemented by a Baton runtime such as {@code dev.baton.core}.
 * Applications normally call {@link Pipeline#builder()} instead.
 */
public interface PipelineProvider {

    /** {@return a new, empty pipeline builder} */
    Pipeline.Builder builder();

    /**
     * Finds the first available provider with {@link ServiceLoader}.
     *
     * @return the provider
     * @throws IllegalStateException if no runtime is on the classpath or module path
     */
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
