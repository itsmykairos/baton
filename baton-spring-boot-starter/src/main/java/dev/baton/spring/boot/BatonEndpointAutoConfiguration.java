package dev.baton.spring.boot;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Adds the {@link BatonEndpoint} when Actuator is present and the endpoint is exposed. */
@AutoConfiguration(after = BatonAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnAvailableEndpoint(BatonEndpoint.class)
public class BatonEndpointAutoConfiguration {

    /** Creates the auto-configuration. */
    public BatonEndpointAutoConfiguration() {
    }

    /**
     * The {@code baton} endpoint.
     *
     * @param beans where to find pipeline beans
     * @return the endpoint
     */
    @Bean
    @ConditionalOnMissingBean
    public BatonEndpoint batonEndpoint(ListableBeanFactory beans) {
        return new BatonEndpoint(beans);
    }
}
