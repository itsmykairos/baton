package dev.baton.api;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable snapshot of typed values. Stages read a context view and return
 * a {@link ContextPatch}; they never modify a context.
 */
public interface ContextView {

    /**
     * Returns the value stored under {@code key}.
     *
     * @param key the key
     * @param <T> the value type
     * @return the value
     * @throws NoSuchElementException if the key is absent
     */
    <T> T get(ContextKey<T> key);

    /**
     * Looks up the value stored under {@code key}.
     *
     * @param key the key
     * @param <T> the value type
     * @return the value, or empty if the key is absent
     */
    <T> Optional<T> find(ContextKey<T> key);

    /**
     * Checks whether {@code key} is present.
     *
     * @param key the key
     * @return true if the key is present
     */
    boolean contains(ContextKey<?> key);

    /** {@return the keys present, in insertion order} */
    Set<ContextKey<?>> keys();

    /**
     * Finds the key stored under a name. A context holds at most one type per name,
     * so there is at most one. The default scans {@link #keys()}; the built-in
     * implementation uses an index.
     *
     * @param name the key name
     * @return the key with that name, or empty if none is present
     */
    default Optional<ContextKey<?>> keyNamed(String name) {
        java.util.Objects.requireNonNull(name, "name");
        for (ContextKey<?> key : keys()) {
            if (key.name().equals(name)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** {@return a context with no values} */
    static ContextView empty() {
        return ImmutableContextView.empty();
    }

    /** {@return a builder for a new context} */
    static Builder builder() {
        return new ImmutableContextView.BuilderImpl();
    }

    /**
     * Like {@link #get}, with a message that says the value was required.
     *
     * @param key the key
     * @param <T> the value type
     * @return the value
     * @throws NoSuchElementException if the key is absent
     */
    default <T> T require(ContextKey<T> key) {
        return find(key)
                .orElseThrow(() -> new NoSuchElementException("Required context value is missing: " + key.name()));
    }

    /** Builds a {@link ContextView}. */
    interface Builder {

        /**
         * Adds or replaces a value.
         *
         * @param key the key
         * @param value a non-null instance of the key's type
         * @param <T> the value type
         * @return this builder
         * @throws IllegalArgumentException if the value has the wrong type, or the
         *         key's name is already used with a different type
         */
        <T> Builder put(ContextKey<T> key, T value);

        /** {@return the immutable context} */
        ContextView build();
    }
}
