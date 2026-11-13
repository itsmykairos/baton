package dev.baton.api.event;

import java.util.List;
import java.util.Objects;

/**
 * The position of a stage in a run: the ids of the nested pipeline stages that
 * contain it, then its own id. Rendered as {@code "payment/charge"}.
 *
 * @param segments stage ids from the outermost pipeline down; never empty
 */
public record StagePath(List<String> segments) {

    /**
     * Validates and copies the segments.
     *
     * @param segments stage ids from the outermost pipeline down; never empty
     */
    public StagePath {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A stage path needs at least one segment");
        }
    }

    /**
     * Creates a path.
     *
     * @param segments stage ids from the outermost pipeline down
     * @return the path
     */
    public static StagePath of(String... segments) {
        return new StagePath(List.of(segments));
    }

    /** {@return the stage's own id (the last segment)} */
    public String stageId() {
        return segments.getLast();
    }

    /** {@return the number of nested pipelines above this stage (0 at top level)} */
    public int depth() {
        return segments.size() - 1;
    }

    /**
     * Returns the path of a stage inside this one.
     *
     * @param childId the id of a stage in the pipeline this stage runs
     * @return the child's path
     */
    public StagePath child(String childId) {
        Objects.requireNonNull(childId, "childId");
        return new StagePath(java.util.stream.Stream.concat(segments.stream(), java.util.stream.Stream.of(childId)).toList());
    }

    /** {@return the segments joined with {@code /}} */
    @Override
    public String toString() {
        return String.join("/", segments);
    }
}
