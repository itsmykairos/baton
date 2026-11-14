package dev.baton.spring.boot;

import dev.baton.api.BatonConfig;

/**
 * Adjusts the application's {@link BatonConfig} after {@code baton.*} properties and
 * context propagator beans have been applied. Declare one or more as beans; they run
 * in {@link org.springframework.core.annotation.Order} order.
 */
@FunctionalInterface
public interface BatonConfigCustomizer {

    /**
     * Customizes the config.
     *
     * @param config the builder, already holding the configured settings
     */
    void customize(BatonConfig.Builder config);
}
