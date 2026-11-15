package dev.baton.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The context implementation: an immutable chain of small levels.
 *
 * <p>Applying a patch adds one level holding only the patch's writes on top of
 * the previous context, instead of copying every value. Each level indexes its
 * own keys by name, so the one-type-per-name rule is checked without scanning
 * the whole context. With a flatten depth N, a patch that would make the chain
 * longer than N levels copies everything into one level instead, which caps
 * lookups at N levels; with {@link BatonConfig#NEVER_FLATTEN} the chain only
 * grows. All walks over the chain are iterative, so very deep chains are safe.</p>
 */
final class ImmutableContextView implements ContextView {

    private static final ImmutableContextView EMPTY = new ImmutableContextView(null, Map.of(), Map.of(), 0);

    /** The level below, or null for the bottom level. */
    private final ImmutableContextView parent;
    /** This level's values; they hide the same keys in lower levels. */
    private final Map<ContextKey<?>, Object> values;
    /** This level's keys by name. */
    private final Map<String, ContextKey<?>> byName;

    private final int depth;
    /** All keys in insertion order, built on first use. */
    private volatile Set<ContextKey<?>> keys;

    private ImmutableContextView(
            ImmutableContextView parent,
            Map<ContextKey<?>, Object> values,
            Map<String, ContextKey<?>> byName,
            int depth) {
        this.parent = parent;
        this.values = values;
        this.byName = byName;
        this.depth = depth;
    }

    static ImmutableContextView empty() {
        return EMPTY;
    }

    /**
     * A single-level context holding {@code values}.
     *
     * @throws IllegalArgumentException if two keys share a name but not a type
     */
    static ImmutableContextView copyOf(Map<ContextKey<?>, Object> values) {
        if (values.isEmpty()) {
            return EMPTY;
        }
        Map<ContextKey<?>, Object> copy = new LinkedHashMap<>(values);
        return new ImmutableContextView(null, Collections.unmodifiableMap(copy), indexByName(copy.keySet()), 0);
    }

    /**
     * This context with {@code writes} applied; written keys replace existing values.
     * Costs O(writes) unless the chain is flattened.
     *
     * @param flattenDepth the most levels the chain may have, or
     *                     {@link BatonConfig#NEVER_FLATTEN}
     * @param replaceTypes true to replace a key whose name is written with another
     *                     type; false to reject it
     * @throws ContextMergeException if a written key's name is used here with another
     *         type and {@code replaceTypes} is false
     */
    ImmutableContextView with(Map<ContextKey<?>, Object> writes, int flattenDepth, boolean replaceTypes) {
        if (writes.isEmpty()) {
            return this;
        }
        java.util.List<ContextKey<?>> replaced = null;
        for (ContextKey<?> written : writes.keySet()) {
            ContextKey<?> existing = findKeyNamed(written.name());
            if (existing != null && existing.clashesWith(written)) {
                if (!replaceTypes) {
                    throw new ContextMergeException(typeClash(existing, written).getMessage());
                }
                if (replaced == null) {
                    replaced = new java.util.ArrayList<>();
                }
                replaced.add(existing);
            }
        }
        if (this == EMPTY) {
            return copyOf(writes);
        }
        // Replacing a type needs a flat copy, so the old key cannot be found in a lower level.
        boolean flatten = replaced != null || (flattenDepth != BatonConfig.NEVER_FLATTEN && depth + 2 > flattenDepth);
        if (flatten) {
            Map<ContextKey<?>, Object> flat = flatValues();
            if (replaced != null) {
                replaced.forEach(flat::remove);
            }
            flat.putAll(writes);
            return new ImmutableContextView(null, Collections.unmodifiableMap(flat), indexByName(flat.keySet()), 0);
        }
        Map<ContextKey<?>, Object> level = new LinkedHashMap<>(writes);
        return new ImmutableContextView(
                this, Collections.unmodifiableMap(level), indexByName(level.keySet()), depth + 1);
    }

    /** A context holds at most one type per key name. */
    private static Map<String, ContextKey<?>> indexByName(Set<ContextKey<?>> keys) {
        Map<String, ContextKey<?>> byName = new HashMap<>(Math.max(4, keys.size() * 2));
        for (ContextKey<?> key : keys) {
            ContextKey<?> previous = byName.putIfAbsent(key.name(), key);
            if (previous != null && previous.clashesWith(key)) {
                throw typeClash(previous, key);
            }
        }
        return byName;
    }

    static IllegalArgumentException typeClash(ContextKey<?> existing, ContextKey<?> added) {
        return new IllegalArgumentException("Context key '" + existing.name() + "' is already used with type "
                + existing.type().getName() + "; cannot also use type "
                + added.type().getName());
    }

    private ContextKey<?> findKeyNamed(String name) {
        for (ImmutableContextView level = this; level != null; level = level.parent) {
            ContextKey<?> key = level.byName.get(name);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    /** Looks a key up from the top level down; the first level holding it wins. */
    private Object lookup(ContextKey<?> key) {
        for (ImmutableContextView level = this; level != null; level = level.parent) {
            Object value = level.values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Every value in insertion order, with upper levels overriding lower ones. */
    private Map<ContextKey<?>, Object> flatValues() {
        Map<ContextKey<?>, Object> flat = new LinkedHashMap<>();
        for (ImmutableContextView level : levelsBottomUp()) {
            flat.putAll(level.values);
        }
        return flat;
    }

    /** The levels from the oldest to this one, collected without recursion. */
    private ImmutableContextView[] levelsBottomUp() {
        ImmutableContextView[] levels = new ImmutableContextView[depth + 1];
        int index = depth;
        for (ImmutableContextView level = this; level != null; level = level.parent) {
            levels[index--] = level;
        }
        return levels;
    }

    @Override
    public <T> T get(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = lookup(key);
        if (value == null) {
            throw new NoSuchElementException("Context value is missing: " + key.name());
        }
        return key.cast(value);
    }

    @Override
    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = lookup(key);
        return value == null ? Optional.empty() : Optional.of(key.cast(value));
    }

    @Override
    public boolean contains(ContextKey<?> key) {
        return lookup(Objects.requireNonNull(key, "key")) != null;
    }

    @Override
    public Optional<ContextKey<?>> keyNamed(String name) {
        return Optional.ofNullable(findKeyNamed(Objects.requireNonNull(name, "name")));
    }

    @Override
    public Set<ContextKey<?>> keys() {
        Set<ContextKey<?>> result = keys;
        if (result == null) {
            if (parent == null) {
                result = Collections.unmodifiableSet(values.keySet());
            } else {
                Set<ContextKey<?>> all = new LinkedHashSet<>();
                for (ImmutableContextView level : levelsBottomUp()) {
                    all.addAll(level.values.keySet());
                }
                result = Collections.unmodifiableSet(all);
            }
            keys = result;
        }
        return result;
    }

    static final class BuilderImpl implements ContextView.Builder {

        private final Map<ContextKey<?>, Object> values = new LinkedHashMap<>();
        private final Map<String, ContextKey<?>> byName = new HashMap<>();

        @Override
        public <T> BuilderImpl put(ContextKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");

            if (!key.type().isInstance(value)) {
                throw new IllegalArgumentException("Invalid value type for key: " + key.name());
            }
            ContextKey<?> existing = byName.putIfAbsent(key.name(), key);
            if (existing != null && existing.clashesWith(key)) {
                throw typeClash(existing, key);
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
