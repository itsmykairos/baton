package dev.baton.testkit;

import dev.baton.api.PipelineResult;
import dev.baton.api.StageResult;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Helpers for inspecting a {@link PipelineResult}, including nested pipelines.
 * Framework-neutral: failures are plain {@link AssertionError}s.
 */
public final class PipelineResults {

    private PipelineResults() {}

    /**
     * Finds a stage by path. Nested stages are separated by {@code /}, for example
     * {@code "checkout/payment/charge"}.
     *
     * @param result the pipeline result
     * @param path stage ids from the top level down, separated by {@code /}
     * @return the stage result
     * @throws NoSuchElementException if a path segment does not match, naming the
     *         ids available at that level
     */
    public static StageResult stage(PipelineResult result, String path) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(path, "path");
        List<StageResult> level = result.stages();
        StageResult found = null;
        for (String id : path.split("/", -1)) {
            found = level.stream()
                    .filter(stage -> stage.stageId().equals(id))
                    .findFirst()
                    .orElseThrow(noStage(id, path, level));
            level = found.children();
        }
        return found;
    }

    /**
     * Throws if the run was not successful, with {@link #describe} as the message.
     *
     * @param result the pipeline result
     * @throws AssertionError if {@link PipelineResult#isSuccessful()} is false
     */
    public static void requireSuccess(PipelineResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.isSuccessful()) {
            throw new AssertionError("Pipeline was not successful:\n" + describe(result));
        }
    }

    /**
     * Renders the result as an indented tree, one stage per line, for test
     * failure messages and logs.
     *
     * @param result the pipeline result
     * @return the rendered tree
     */
    public static String describe(PipelineResult result) {
        Objects.requireNonNull(result, "result");
        StringBuilder text = new StringBuilder(result.status().toString());
        result.failure().ifPresent(failure -> text.append(": ").append(summary(failure)));
        appendStages(text, result.stages(), 1);
        return text.toString();
    }

    private static void appendStages(StringBuilder text, List<StageResult> stages, int depth) {
        for (StageResult stage : stages) {
            text.append('\n')
                    .append("  ".repeat(depth))
                    .append("- ")
                    .append(stage.stageId())
                    .append(' ')
                    .append(stage.status())
                    .append(" (")
                    .append(stage.duration().toMillis())
                    .append(" ms)");
            stage.failure().ifPresent(failure -> text.append(": ").append(summary(failure)));
            appendStages(text, stage.children(), depth + 1);
        }
    }

    private static String summary(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static java.util.function.Supplier<NoSuchElementException> noStage(
            String id, String path, List<StageResult> level) {
        return () -> new NoSuchElementException("No stage '" + id + "' in path '" + path
                + "'; available: "
                + level.stream().map(StageResult::stageId).collect(Collectors.joining(", ", "[", "]")));
    }
}
