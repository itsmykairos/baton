package dev.baton.api;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

public interface ContextView {

    <T> T get(ContextKey<T> key);

    <T> Optional<T> find(ContextKey<T> key);

    boolean contains(ContextKey<?> key);

    Set<ContextKey<?>> keys();

    static ContextView empty() {
        return ImmutableContextView.empty();
    }

    static Builder builder() {
        return new ImmutableContextView.BuilderImpl();
    }

    default <T> T require(ContextKey<T> key) {
        return find(key).orElseThrow(() ->
                new NoSuchElementException(
                        "Required context value is missing: " + key.name()
                )
        );
    }

    interface Builder {

        <T> Builder put(ContextKey<T> key, T value);

        ContextView build();
    }
}