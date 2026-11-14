package dev.baton.api;

import dev.baton.api.event.StagePath;
import java.util.List;
import java.util.Objects;

/**
 * A context write conflict found while merging a layer, and how it was resolved.
 * Listed in {@link PipelineResult#conflicts()} and sent as a
 * {@link dev.baton.api.event.ContextConflict} event.
 *
 * @param kind what kind of conflict it was
 * @param key the key name
 * @param stages the stages involved, in definition order
 * @param resolution what the configured policy did
 */
public record KeyConflict(Kind kind, String key, List<StagePath> stages, Resolution resolution) {

    /**
     * Validates and copies the record's fields.
     *
     * @param kind what kind of conflict it was
     * @param key the key name
     * @param stages the stages involved, in definition order
     * @param resolution what the configured policy did
     */
    public KeyConflict {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        stages = List.copyOf(stages);
        Objects.requireNonNull(resolution, "resolution");
    }

    /** The kind of conflict. */
    public enum Kind {
        /** Two or more stages of one layer wrote the key. See {@link LayerConflictPolicy}. */
        SAME_LAYER,
        /** A stage wrote a key already in the context. See {@link OverwritePolicy}. */
        OVERWRITE,
        /** A stage wrote a name the context holds with another type. See {@link TypeClashPolicy}. */
        TYPE_CLASH
    }

    /** How a conflict was resolved. */
    public enum Resolution {
        /** The stages involved were FAILED. */
        FAILED,
        /** The first writer's value was applied. */
        FIRST_WINS,
        /** The last writer's value was applied. */
        LAST_WINS,
        /** No value was applied for the key. */
        KEY_SKIPPED,
        /** The value already in the context was kept. */
        KEPT_EXISTING,
        /** The key of the other type was removed and the new one written. */
        REPLACED
    }
}
