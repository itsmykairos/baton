package dev.baton.api;

import java.util.Objects;

/**
 * A typed name for a value in a {@link ContextView}.
 *
 * <p>Keys are values: two keys are equal when they have the same name and the
 * same type, so a key can be recreated with {@link #of(String, Class)} instead of
 * shared as a constant. A context holds at most one type per name; writing a key
 * whose name is already used with a different type is rejected.</p>
 *
 * @param <T> the type of value stored under this key
 */
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

    /**
     * Creates a key.
     *
     * @param name non-blank key name
     * @param type the value type; values must be instances of it
     * @param <T> the value type
     * @return the key
     */
    public static <T> ContextKey<T> of(
            String name,
            Class<T> type
    ) {
        return new ContextKey<>(name, type);
    }

    /**
     * Returns the key name.
     *
     * @return the key name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the value type.
     *
     * @return the value type
     */
    public Class<T> type() {
        return type;
    }

    T cast(Object value) {
        return type.cast(value);
    }

    /** Keys are equal when their names and types are equal. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ContextKey<?> key
                && name.equals(key.name)
                && type.equals(key.type);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + type.hashCode();
    }

    /** True if {@code other} has the same name as this key but a different type. */
    boolean clashesWith(ContextKey<?> other) {
        return name.equals(other.name) && !type.equals(other.type);
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + ":" + type.getSimpleName() + "]";
    }
}