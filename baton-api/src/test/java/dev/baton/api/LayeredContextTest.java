package dev.baton.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayeredContextTest {

    @Test
    void deepChainsKeepEveryValueInInsertionOrderAcrossFlattening() {
        int layers = 16 * 3 + 5;
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
    void neverFlatteningHandlesVeryDeepChainsWithoutRecursion() {
        int layers = 5_000;
        ContextView context = ContextView.empty();
        for (int i = 0; i < layers; i++) {
            context = ContextPatch.builder().put(ContextKey.of("k" + i, Integer.class), i).build()
                    .applyTo(context);
        }

        assertThat(context.keys()).hasSize(layers);
        assertThat(context.keys().iterator().next().name()).isEqualTo("k0");
        assertThat(context.get(ContextKey.of("k0", Integer.class))).isZero();
        assertThat(context.get(ContextKey.of("k4999", Integer.class))).isEqualTo(4999);
        assertThat(context.keyNamed("k2500")).isPresent();
        // Flattening a deep chain (here via a type replacement) is also iterative.
        ContextView replaced = ContextPatch.builder().put(ContextKey.of("k10", String.class), "ten").build()
                .applyTo(context, BatonConfig.builder().typeClashPolicy(TypeClashPolicy.REPLACE).build());
        assertThat(replaced.get(ContextKey.of("k10", String.class))).isEqualTo("ten");
        assertThat(replaced.contains(ContextKey.of("k10", Integer.class))).isFalse();
        assertThat(replaced.keys()).hasSize(layers);
    }

    @Test
    void everyFlattenDepthGivesTheSameContext() {
        for (int depth : new int[] {0, 1, 3, 16}) {
            BatonConfig config = BatonConfig.builder().contextFlattenDepth(depth).build();
            ContextView context = ContextView.empty();
            for (int i = 0; i < 40; i++) {
                ContextPatch.Builder patch = ContextPatch.builder().put(ContextKey.of("k" + i, Integer.class), i);
                if (i % 5 == 0) {
                    patch.put(ContextKey.of("shared", Integer.class), i);
                }
                context = patch.build().applyTo(context, config);
            }
            assertThat(context.keys()).as("depth %d", depth).hasSize(41);
            assertThat(context.get(ContextKey.of("shared", Integer.class))).as("depth %d", depth).isEqualTo(35);
            assertThat(context.get(ContextKey.of("k39", Integer.class))).isEqualTo(39);
        }
    }

    @Test
    void laterLevelsOverrideEarlierValuesWithoutDuplicatingKeys() {
        ContextKey<String> status = ContextKey.of("status", String.class);
        ContextKey<String> other = ContextKey.of("other", String.class);
        ContextView context = ContextView.builder().put(status, "new").put(other, "x").build();
        for (int i = 0; i < 16 + 3; i++) {
            context = ContextPatch.builder().put(status, "v" + i).build().applyTo(context);
        }

        assertThat(context.get(status)).isEqualTo("v" + (16 + 2));
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
