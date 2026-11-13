package dev.baton.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ContextPatch {

    private static final ContextPatch EMPTY =
            new ContextPatch(Map.of());

    private final Map<ContextKey<?>, Object> writes;

    private ContextPatch(Map<ContextKey<?>, Object> writes) {
        this.writes = Collections.unmodifiableMap(
                new LinkedHashMap<>(writes)
        );
    }

    public static ContextPatch empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return writes.isEmpty();
    }

    public Set<ContextKey<?>> writtenKeys() {
        return writes.keySet();
    }

    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");

        return writes.containsKey(key)
                ? Optional.of(key.cast(writes.get(key)))
                : Optional.empty();
    }

    public ContextView applyTo(ContextView base) {
        Objects.requireNonNull(base, "base");

        Map<ContextKey<?>, Object> merged = copyValues(base);
        merged.putAll(writes);

        return ImmutableContextView.copyOf(merged);
    }

    /**
     * Merges patches.
     * Duplicate keys are conflicts, even when values are equal.
     */
    public static ContextPatch merge(
            Collection<ContextPatch> patches
    ) {
        Objects.requireNonNull(patches, "patches");

        Map<ContextKey<?>, Object> merged =
                new LinkedHashMap<>();

        for (ContextPatch patch : patches) {
            Objects.requireNonNull(patch, "patch");

            for (var entry : patch.writes.entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    throw new ContextMergeException(
                            "Multiple stages wrote context key: "
                                    + entry.getKey().name()
                    );
                }

                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged.isEmpty()
                ? EMPTY
                : new ContextPatch(merged);
    }

    /**
     * Calculates added or changed values.
     * Context values are compared using equals().
     */
    public static ContextPatch diff(
            ContextView before,
            ContextView after
    ) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        Builder builder = builder();

        for (ContextKey<?> key : after.keys()) {
            Object newValue = valueOf(after, key);

            if (!before.contains(key)
                    || !Objects.equals(valueOf(before, key), newValue)) {
                putUntyped(builder, key, newValue);
            }
        }

        return builder.build();
    }

    private static Map<ContextKey<?>, Object> copyValues(
            ContextView view
    ) {
        Map<ContextKey<?>, Object> copy =
                new LinkedHashMap<>();

        for (ContextKey<?> key : view.keys()) {
            copy.put(key, valueOf(view, key));
        }

        return copy;
    }

    private static Object valueOf(
            ContextView view,
            ContextKey<?> key
    ) {
        return view.find(castKey(key)).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static ContextKey<Object> castKey(
            ContextKey<?> key
    ) {
        return (ContextKey<Object>) key;
    }

    private static void putUntyped(
            Builder builder,
            ContextKey<?> key,
            Object value
    ) {
        builder.put(castKey(key), value);
    }

    public static final class Builder {

        private final Map<ContextKey<?>, Object> writes =
                new LinkedHashMap<>();

        private Builder() {
        }

        public <T> Builder put(
                ContextKey<T> key,
                T value
        ) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");

            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException(
                        "Invalid value type for key: " + key.name()
                );
            }

            if (writes.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Key written twice in one patch: " + key.name()
                );
            }

            writes.put(key, value);
            return this;
        }

        public ContextPatch build() {
            return writes.isEmpty()
                    ? EMPTY
                    : new ContextPatch(writes);
        }
    }
}