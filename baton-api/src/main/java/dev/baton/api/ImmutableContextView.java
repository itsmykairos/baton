package dev.baton.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ImmutableContextView implements ContextView {

    private static final ImmutableContextView EMPTY =
            new ImmutableContextView(Map.of());

    private final Map<ContextKey<?>, Object> values;

    private ImmutableContextView(
            Map<ContextKey<?>, Object> values
    ) {
        this.values = Collections.unmodifiableMap(
                new LinkedHashMap<>(values)
        );
    }

    static ImmutableContextView empty() {
        return EMPTY;
    }

    static ImmutableContextView copyOf(
            Map<ContextKey<?>, Object> values
    ) {
        return values.isEmpty()
                ? EMPTY
                : new ImmutableContextView(values);
    }

    @Override
    public <T> T get(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");

        if (!values.containsKey(key)) {
            throw new NoSuchElementException(
                    "Context value is missing: " + key.name()
            );
        }

        return key.cast(values.get(key));
    }

    @Override
    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");

        return values.containsKey(key)
                ? Optional.of(key.cast(values.get(key)))
                : Optional.empty();
    }

    @Override
    public boolean contains(ContextKey<?> key) {
        return values.containsKey(
                Objects.requireNonNull(key, "key")
        );
    }

    @Override
    public Set<ContextKey<?>> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    Map<ContextKey<?>, Object> values() {
        return values;
    }

    static final class BuilderImpl implements ContextView.Builder {

        private final Map<ContextKey<?>, Object> values =
                new LinkedHashMap<>();

        @Override
        public <T> BuilderImpl put(
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

            values.put(key, value);
            return this;
        }

        @Override
        public ContextView build() {
            return ImmutableContextView.copyOf(values);
        }
    }
}