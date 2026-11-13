package dev.baton.api;

import java.util.Objects;

public final class ContextKey<T> {

    private final String name;
    private final Class<T> type;

    private ContextKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> ContextKey<T> of(
            String name,
            Class<T> type
    ) {
        return new ContextKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    T cast(Object value) {
        return type.cast(value);
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + ":" + type.getSimpleName() + "]";
    }
}