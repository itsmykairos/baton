package dev.baton.spring.boot;

import dev.baton.api.ContextView;
import dev.baton.api.RetryPolicy;
import dev.baton.api.StageFallback;
import dev.baton.api.StageOptions;
import dev.baton.spring.boot.BatonProperties.RetrySpec;
import dev.baton.spring.boot.BatonProperties.StageSpec;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/** Turns configured stage options into {@link StageOptions}. */
final class StageSpecs {

    private StageSpecs() {}

    /** True if the spec sets any stage option. */
    static boolean hasOptions(StageSpec spec) {
        return spec.timeout() != null
                || spec.failurePolicy() != null
                || spec.retry() != null
                || spec.fallback() != null
                || spec.condition() != null
                || spec.skipIfInputsMissing() != null
                || spec.overwritePolicy() != null;
    }

    /**
     * Applies the options {@code spec} sets on top of {@code base}.
     *
     * @param where the property path, for error messages
     */
    @SuppressWarnings("unchecked")
    static StageOptions apply(StageOptions base, StageSpec spec, BeanFactory beans, String where) {
        StageOptions options = base;
        if (spec.timeout() != null) {
            options = options.withTimeout(spec.timeout());
        }
        if (spec.failurePolicy() != null) {
            options = options.withFailurePolicy(spec.failurePolicy());
        }
        if (spec.retry() != null) {
            options = options.withRetry(retry(spec.retry(), beans, where + ".retry"));
        }
        if (spec.fallback() != null) {
            options = options.withFallback(bean(beans, spec.fallback(), StageFallback.class, where + ".fallback"));
        }
        if (spec.condition() != null) {
            options = options.withCondition(
                    (Predicate<ContextView>) bean(beans, spec.condition(), Predicate.class, where + ".condition"));
        }
        if (Boolean.TRUE.equals(spec.skipIfInputsMissing())) {
            options = options.skipIfInputsMissing();
        }
        if (spec.overwritePolicy() != null) {
            options = options.withOverwritePolicy(spec.overwritePolicy());
        }
        return options;
    }

    private static RetryPolicy retry(RetrySpec spec, BeanFactory beans, String where) {
        try {
            return buildRetry(spec, beans, where);
        } catch (IllegalArgumentException invalid) {
            // RetryPolicy's own message does not say which property is wrong.
            throw new IllegalStateException(where + ": " + invalid.getMessage(), invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static RetryPolicy buildRetry(RetrySpec spec, BeanFactory beans, String where) {
        if (spec.maxAttempts() == null) {
            throw new IllegalStateException(where + ".max-attempts is required");
        }
        RetryPolicy retry = RetryPolicy.maxAttempts(spec.maxAttempts());
        if (spec.backoff() != null) {
            double multiplier = spec.multiplier() != null ? spec.multiplier() : 1.0;
            Duration maxDelay = spec.maxDelay() != null
                    ? spec.maxDelay()
                    : multiplier > 1.0 ? spec.backoff().multipliedBy(32) : spec.backoff();
            retry = retry.withBackoff(spec.backoff(), multiplier, maxDelay);
        }
        if (spec.jitter() != null) {
            retry = retry.withJitter(spec.jitter());
        }
        if (spec.attemptTimeout() != null) {
            retry = retry.withAttemptTimeout(spec.attemptTimeout());
        }
        if (spec.retryOn() != null) {
            retry = retry.retryOn(
                    (Predicate<Throwable>) bean(beans, spec.retryOn(), Predicate.class, where + ".retry-on"));
        }
        return retry;
    }

    static <T> T bean(BeanFactory beans, String name, Class<T> type, String where) {
        try {
            return beans.getBean(name, type);
        } catch (NoSuchBeanDefinitionException missing) {
            throw new IllegalStateException(
                    where + ": no " + type.getSimpleName() + " bean named '" + name + "'", missing);
        }
    }
}
