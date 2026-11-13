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
