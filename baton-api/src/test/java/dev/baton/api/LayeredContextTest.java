package dev.baton.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayeredContextTest {

    @Test
    void deepChainsKeepEveryValueInInsertionOrderAcrossFlattening() {
        int layers = ImmutableContextView.MAX_DEPTH * 3 + 5;
        ContextView context = ContextView.empty();
        List<ContextKey<?>> expected = new ArrayList<>();
        for (int i = 0; i < layers; i++) {
            ContextKey<Integer> key = ContextKey.of("k" + i, Integer.class);
            expected.add(key);
            context = ContextPatch.builder().put(key, i).build().applyTo(context);
        }

        assertThat(context.keys()).containsExactlyElementsOf(expected);
        for (int i = 0; i < layers; i++) {
            assertThat(context.get(ContextKey.of("k" + i, Integer.class))).isEqualTo(i);
        }
    }

    @Test
    void laterLevelsOverrideEarlierValuesWithoutDuplicatingKeys() {
        ContextKey<String> status = ContextKey.of("status", String.class);
        ContextKey<String> other = ContextKey.of("other", String.class);
        ContextView context = ContextView.builder().put(status, "new").put(other, "x").build();
        for (int i = 0; i < ImmutableContextView.MAX_DEPTH + 3; i++) {
            context = ContextPatch.builder().put(status, "v" + i).build().applyTo(context);
        }

        assertThat(context.get(status)).isEqualTo("v" + (ImmutableContextView.MAX_DEPTH + 2));
        assertThat(context.keys()).containsExactly(status, other);
    }

    @Test
    void earlierSnapshotsAreUnchangedByLaterPatches() {
        ContextKey<String> key = ContextKey.of("k", String.class);
        ContextView before = ContextView.builder().put(key, "before").build();
        ContextView after = ContextPatch.builder().put(key, "after").build().applyTo(before);

        assertThat(before.get(key)).isEqualTo("before");
        assertThat(after.get(key)).isEqualTo("after");
    }

    @Test
    void nameClashIsDetectedAgainstAnyLevel() {
        ContextKey<String> idAsString = ContextKey.of("id", String.class);
        ContextView context = ContextView.builder().put(idAsString, "7").build();
        for (int i = 0; i < 5; i++) {
            context = ContextPatch.builder().put(ContextKey.of("n" + i, Integer.class), i).build()
                    .applyTo(context);
        }
        ContextView deep = context;

        assertThat(deep.keyNamed("id")).contains(idAsString);
        assertThat(deep.keyNamed("missing")).isEmpty();
        assertThatThrownBy(() -> ContextPatch.builder().put(ContextKey.of("id", Integer.class), 7).build()
                .applyTo(deep))
                .isInstanceOf(ContextMergeException.class)
                .hasMessageContaining("'id'");
    }

    @Test
    void customContextViewsStillWorkWithPatches() {
        ContextKey<String> key = ContextKey.of("k", String.class);
        ContextView inner = ContextView.builder().put(key, "v").build();
        ContextView custom = new ContextView() {
            @Override
            public <T> T get(ContextKey<T> k) {
                return inner.get(k);
            }

            @Override
            public <T> java.util.Optional<T> find(ContextKey<T> k) {
                return inner.find(k);
            }

            @Override
            public boolean contains(ContextKey<?> k) {
                return inner.contains(k);
            }

            @Override
            public java.util.Set<ContextKey<?>> keys() {
                return inner.keys();
            }
        };

        ContextKey<Integer> added = ContextKey.of("added", Integer.class);
        ContextView result = ContextPatch.builder().put(added, 1).build().applyTo(custom);

        assertThat(result.get(key)).isEqualTo("v");
        assertThat(result.get(added)).isEqualTo(1);
        assertThat(custom.keyNamed("k")).contains(key);
        assertThatThrownBy(() -> ContextPatch.builder().put(ContextKey.of("k", Integer.class), 1).build()
                .applyTo(custom)).isInstanceOf(ContextMergeException.class);
    }
}
