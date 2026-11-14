package dev.baton.testkit;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Stage;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Runs a single stage outside a pipeline.
 *
 * <pre>{@code
 * ContextPatch patch = StageTester.run(new PriceStage(), ContextView.builder()
 *         .put(ORDER, order)
 *         .build());
 * }</pre>
 */
public final class StageTester {

    private StageTester() {
    }

    /**
     * Runs {@code stage} against {@code input} and returns its patch.
     *
     * @param stage the stage under test
     * @param input the context snapshot the stage reads
     * @return the patch the stage returned
     * @throws IllegalStateException if a key from {@link Stage#requiredReads()} is
     *         missing (the runtime would report the stage BLOCKED), or the stage
     *         returned {@code null}
     * @throws Exception whatever the stage throws
     */
    public static ContextPatch run(Stage stage, ContextView input) throws Exception {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(input, "input");
        String missing = stage.requiredReads().stream()
                .filter(key -> !input.contains(key))
                .map(ContextKey::name)
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Stage would be BLOCKED; missing required keys: " + missing);
        }
        try (TestStageContext context = TestStageContext.of(input)) {
            ContextPatch patch = stage.execute(context);
            if (patch == null) {
                throw new IllegalStateException("Stage returned a null ContextPatch");
            }
            return patch;
        }
    }

    /**
     * Runs {@code stage} and applies its patch to {@code input}.
     *
     * @param stage the stage under test
     * @param input the context snapshot the stage reads
     * @return {@code input} with the stage's writes applied
     * @throws Exception as for {@link #run(Stage, ContextView)}
     */
    public static ContextView runAndApply(Stage stage, ContextView input) throws Exception {
        return run(stage, input).applyTo(input);
    }

    /**
     * Runs {@code stage} and applies its patch to {@code input} with the config's
     * flatten depth and type-clash policy, as a pipeline using that config would.
     *
     * @param stage the stage under test
     * @param input the context snapshot the stage reads
     * @param config the settings to apply the patch with
     * @return {@code input} with the stage's writes applied
     * @throws Exception as for {@link #run(Stage, ContextView)}
     */
    public static ContextView runAndApply(Stage stage, ContextView input, BatonConfig config) throws Exception {
        return run(stage, input).applyTo(input, config);
    }
}
