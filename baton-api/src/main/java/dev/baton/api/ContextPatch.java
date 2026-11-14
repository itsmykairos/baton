package dev.baton.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The writes a stage returns. Immutable; build one with {@link #builder()}.
 */
public final class ContextPatch {

    private static final ContextPatch EMPTY = new ContextPatch(Map.of());

    private final Map<ContextKey<?>, Object> writes;

    private ContextPatch(Map<ContextKey<?>, Object> writes) {
        this.writes = Collections.unmodifiableMap(new LinkedHashMap<>(writes));
    }

    /** {@return a patch with no writes} */
    public static ContextPatch empty() {
        return EMPTY;
    }

    /** {@return a builder for a new patch} */
    public static Builder builder() {
        return new Builder();
    }

    /** {@return true if the patch writes nothing} */
    public boolean isEmpty() {
        return writes.isEmpty();
    }

    /** {@return the written keys, in insertion order} */
    public Set<ContextKey<?>> writtenKeys() {
        return writes.keySet();
    }

    /**
     * Looks up the value this patch writes for {@code key}.
     *
     * @param key the key
     * @param <T> the value type
     * @return the written value, or empty if this patch does not write the key
     */
    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");

        return writes.containsKey(key) ? Optional.of(key.cast(writes.get(key))) : Optional.empty();
    }

    /**
     * Returns {@code base} with this patch's writes applied. Written keys replace
     * existing values: a later layer may overwrite a value written by an earlier
     * layer or supplied in the pipeline input.
     *
     * <p>Uses the default settings: the context is never flattened, and a type
     * clash is rejected. See {@link #applyTo(ContextView, BatonConfig)}.</p>
     *
     * @param base the context to apply this patch to
     * @return a new context with the writes applied
     * @throws ContextMergeException if a written key's name is already used in
     *         {@code base} with a different type
     */
    public ContextView applyTo(ContextView base) {
        return applyTo(base, BatonConfig.defaults());
    }

    /**
     * Returns {@code base} with this patch's writes applied, using the config's
     * {@link BatonConfig#contextFlattenDepth() flatten depth} and
     * {@link BatonConfig#typeClashPolicy() type-clash policy}. Written keys replace
     * existing values.
     *
     * @param base the context to apply this patch to
     * @param config the settings to apply with
     * @return a new context with the writes applied
     * @throws ContextMergeException if a written key's name is already used in
     *         {@code base} with a different type and the policy is
     *         {@link TypeClashPolicy#FAIL}
     */
    public ContextView applyTo(ContextView base, BatonConfig config) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(config, "config");
        boolean replaceTypes = config.typeClashPolicy() == TypeClashPolicy.REPLACE;

        if (base instanceof ImmutableContextView view) {
            // Adds one small level instead of copying the whole context.
            return view.with(writes, config.contextFlattenDepth(), replaceTypes);
        }
        java.util.List<ContextKey<?>> replaced = new java.util.ArrayList<>();
        for (ContextKey<?> written : writes.keySet()) {
            ContextKey<?> existing = base.keyNamed(written.name()).orElse(null);
            if (existing != null && existing.clashesWith(written)) {
                if (!replaceTypes) {
                    throw new ContextMergeException(
                            ImmutableContextView.typeClash(existing, written).getMessage());
                }
                replaced.add(existing);
            }
        }

        Map<ContextKey<?>, Object> merged = copyValues(base);
        replaced.forEach(merged::remove);
        merged.putAll(writes);

        return ImmutableContextView.copyOf(merged);
    }

    /**
     * Merges patches.
     * Duplicate keys are conflicts, even when values are equal, and so are two
     * keys with the same name but different types.
     *
     * <p>The engine uses this for the stages of one parallel layer only; conflicts
     * are never detected across layers (see {@link #applyTo(ContextView)}).</p>
     *
     * @param patches the patches to combine
     * @return one patch holding every write
     * @throws ContextMergeException if two patches write the same key name
     */
    public static ContextPatch merge(Collection<ContextPatch> patches) {
        Objects.requireNonNull(patches, "patches");

        Map<ContextKey<?>, Object> merged = new LinkedHashMap<>();
        Set<String> names = new java.util.HashSet<>();

        for (ContextPatch patch : patches) {
            Objects.requireNonNull(patch, "patch");

            for (var entry : patch.writes.entrySet()) {
                if (!names.add(entry.getKey().name())) {
                    throw new ContextMergeException("Multiple stages wrote context key: "
                            + entry.getKey().name());
                }

                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged.isEmpty() ? EMPTY : new ContextPatch(merged);
    }

    /**
     * Calculates added or changed values.
     * Context values are compared using equals(), so rewriting a key with an
     * equal value is not reported.
     *
     * @param before the earlier context
     * @param after the later context
     * @return the keys of {@code after} that are new or have a different value
     */
    public static ContextPatch diff(ContextView before, ContextView after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        Builder builder = builder();

        for (ContextKey<?> key : after.keys()) {
            Object newValue = valueOf(after, key);

            if (!before.contains(key) || !Objects.equals(valueOf(before, key), newValue)) {
                putUntyped(builder, key, newValue);
            }
        }

        return builder.build();
    }

    private static Map<ContextKey<?>, Object> copyValues(ContextView view) {
        Map<ContextKey<?>, Object> copy = new LinkedHashMap<>();

        for (ContextKey<?> key : view.keys()) {
            copy.put(key, valueOf(view, key));
        }

        return copy;
    }

    private static Object valueOf(ContextView view, ContextKey<?> key) {
        return view.find(castKey(key)).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static ContextKey<Object> castKey(ContextKey<?> key) {
        return (ContextKey<Object>) key;
    }

    private static void putUntyped(Builder builder, ContextKey<?> key, Object value) {
        builder.put(castKey(key), value);
    }

    /** Builds a {@link ContextPatch}. */
    public static final class Builder {

        private final Map<ContextKey<?>, Object> writes = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Adds a write.
         *
         * @param key the key
         * @param value a non-null instance of the key's type
         * @param <T> the value type
         * @return this builder
         * @throws IllegalArgumentException if the value has the wrong type, the key
         *         is already written, or its name is already written with another type
         */
        public <T> Builder put(ContextKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");

            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException("Invalid value type for key: " + key.name());
            }

            if (writes.containsKey(key)) {
                throw new IllegalArgumentException("Key written twice in one patch: " + key.name());
            }
            for (ContextKey<?> existing : writes.keySet()) {
                if (existing.clashesWith(key)) {
                    throw ImmutableContextView.typeClash(existing, key);
                }
            }

            writes.put(key, value);
            return this;
        }

        /** {@return the immutable patch} */
        public ContextPatch build() {
            return writes.isEmpty() ? EMPTY : new ContextPatch(writes);
        }
    }
}
