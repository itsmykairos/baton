package dev.baton.it;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextMergeException;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Argument and state validation at the public API boundary. */
class ApiValidationIT {

    private static final Stage NOOP = context -> ContextPatch.empty();

    @Test
    void keysWithSameNameAndTypeAreEqual() {
        ContextKey<String> first = ContextKey.of("user", String.class);
        ContextKey<String> recreated = ContextKey.of("user", String.class);

        assertThat(recreated).isEqualTo(first).hasSameHashCodeAs(first);
        assertThat(ContextKey.of("user", Integer.class)).isNotEqualTo(first);
        assertThat(ContextView.builder().put(first, "ada").build().get(recreated)).isEqualTo("ada");
    }

    @Test
    void contextRejectsOneNameWithTwoTypes() {
        ContextKey<String> asString = ContextKey.of("id", String.class);
        ContextKey<Integer> asInteger = ContextKey.of("id", Integer.class);

        assertThatThrownBy(() -> ContextView.builder().put(asString, "a").put(asInteger, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
        assertThatThrownBy(() -> ContextPatch.builder().put(asString, "a").put(asInteger, 1))
                .isInstanceOf(IllegalArgumentException.class);

        ContextView base = ContextView.builder().put(asString, "a").build();
        ContextPatch patch = ContextPatch.builder().put(asInteger, 1).build();
        assertThatThrownBy(() -> patch.applyTo(base)).isInstanceOf(ContextMergeException.class);
    }

    @Test
    void keyAndValueArgumentsAreValidated() {
        assertThatThrownBy(() -> ContextKey.of(null, String.class)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextKey.of(" ", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextKey.of("x", null)).isInstanceOf(NullPointerException.class);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ContextKey<Object> raw = (ContextKey) ContextKey.of("n", Integer.class);
        assertThatThrownBy(() -> ContextView.builder().put(raw, "not a number"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextPatch.builder().put(raw, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void stageOptionsRejectNonPositiveTimeouts() {
        assertThatThrownBy(() -> StageOptions.defaults().withTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StageOptions.defaults().withTimeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StageOptions.defaults().withFailurePolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void retryAndRunOptionsValidateArguments() {
        assertThatThrownBy(() -> dev.baton.api.RetryPolicy.maxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class);
        var policy = dev.baton.api.RetryPolicy.maxAttempts(3);
        assertThatThrownBy(() -> policy.withBackoff(Duration.ofMillis(-1), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withBackoff(Duration.ofMillis(10), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withBackoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withJitter(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withAttemptTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryOn(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StageOptions.defaults().withRetry(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StageOptions.defaults().withFallback(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StageOptions.defaults().withCondition(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dev.baton.api.RunOptions.defaults().withTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dev.baton.api.RunOptions.defaults().withEventBufferCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pipeline.builder().timeout(Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batonConfigValidatesArgumentsAndLayersSettings() {
        var builder = dev.baton.api.BatonConfig.builder();
        assertThatThrownBy(() -> builder.contextFlattenDepth(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxParallelStages(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxChildTasks(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.eventBufferCapacity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.eventBatchInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.runTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.defaultPipelineName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.layerConflictPolicy(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.clock(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Pipeline.builder((dev.baton.api.BatonConfig) null))
                .isInstanceOf(NullPointerException.class);

        var defaults = dev.baton.api.BatonConfig.defaults();
        assertThat(defaults.contextFlattenDepth()).isEqualTo(dev.baton.api.BatonConfig.NEVER_FLATTEN);
        assertThat(defaults.layerConflictPolicy()).isEqualTo(dev.baton.api.LayerConflictPolicy.FAIL);
        assertThat(defaults.overwritePolicy()).isEqualTo(dev.baton.api.OverwritePolicy.OVERRIDE);
        assertThat(defaults.maxParallelStages()).isEqualTo(dev.baton.api.BatonConfig.UNLIMITED);

        var app = dev.baton.api.BatonConfig.builder().contextFlattenDepth(16).maxParallelStages(4).build();
        var pipeline = dev.baton.api.BatonConfig.builder().maxParallelStages(8).build();
        var merged = app.merge(pipeline);
        assertThat(merged.contextFlattenDepth()).isEqualTo(16);
        assertThat(merged.maxParallelStages()).isEqualTo(8);
        assertThat(merged.toBuilder().maxParallelStages(2).build().contextFlattenDepth()).isEqualTo(16);
    }

    @Test
    void backoffGrowsAndIsCapped() {
        var policy = dev.baton.api.RetryPolicy.maxAttempts(10)
                .withBackoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(350));

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMillis(350));
        assertThat(policy.withJitter(0.5).delayAfter(2))
                .isBetween(Duration.ofMillis(100), Duration.ofMillis(200));
        assertThat(policy.shouldRetry(9, new java.io.IOException())).isTrue();
        assertThat(policy.shouldRetry(10, new java.io.IOException())).isFalse();
        assertThat(policy.shouldRetry(1, new java.util.concurrent.CancellationException())).isFalse();
        assertThat(policy.shouldRetry(1, new AssertionError())).isFalse();
    }

    @Test
    void builderRejectsInvalidDefinitions() {
        assertThatThrownBy(() -> Pipeline.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> Pipeline.builder().stage(" ", NOOP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pipeline.builder().stage("a", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Pipeline.builder().stage("a", NOOP).stage("a", NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate stage id");
        assertThatThrownBy(() -> Pipeline.builder().parallel("p", parallel -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pipeline.builder().stage("a", NOOP)
                .parallel("p", parallel -> parallel.stage("a", NOOP)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderCannotBeReused() {
        Pipeline.Builder builder = Pipeline.builder().stage("a", NOOP);
        builder.build();

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> builder.stage("b", NOOP)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidRequiredReadsAreRejectedAtBuildTime() {
        Stage nullReads = new Stage() {
            @Override
            public ContextPatch execute(dev.baton.api.StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return null;
            }
        };
        Stage nullKey = new Stage() {
            @Override
            public ContextPatch execute(dev.baton.api.StageContext context) {
                return ContextPatch.empty();
            }

            @Override
            public Set<ContextKey<?>> requiredReads() {
                return Collections.singleton(null);
            }
        };

        assertThatThrownBy(() -> Pipeline.builder().stage("nullReads", nullReads))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullReads");
        assertThatThrownBy(() -> Pipeline.builder().stage("nullKey", nullKey))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullKey");
    }

    @Test
    void runArgumentsAreValidated() {
        Pipeline pipeline = Pipeline.builder().stage("a", NOOP).build();

        assertThatThrownBy(() -> pipeline.run(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pipeline.start(null)).isInstanceOf(NullPointerException.class);
    }
}
