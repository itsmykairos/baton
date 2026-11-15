package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageOptions;
import dev.baton.api.StageStatus;
import dev.baton.testkit.PipelineResults;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionalStagesTest {

    static final ContextKey<Integer> TOTAL = ContextKey.of("total", Integer.class);
    static final ContextKey<String> PHONE = ContextKey.of("phone", String.class);
    static final ContextKey<String> REVIEW = ContextKey.of("review", String.class);
    static final ContextKey<String> SMS = ContextKey.of("sms", String.class);

    // --8<-- [start:required-reads]
    /** Texts the customer. Needs a phone number, which not every order has. */
    static final class SendSms implements Stage {
        @Override
        public ContextPatch execute(StageContext stage) {
            String phone = stage.context().get(PHONE);
            return ContextPatch.builder().put(SMS, "sent to " + phone).build();
        }

        @Override
        public Set<ContextKey<?>> requiredReads() {
            return Set.of(PHONE);
        }
    }
    // --8<-- [end:required-reads]

    @Test
    void conditionsAndMissingInputs() throws Exception {
        // --8<-- [start:conditions]
        Pipeline fulfil = Pipeline.builder()
                .stage(
                        "manual-review",
                        stage -> ContextPatch.builder().put(REVIEW, "queued").build(),
                        // Runs only for large orders; evaluated just before the stage would start.
                        StageOptions.defaults().withCondition(context -> context.get(TOTAL) > 10_000))
                .stage(
                        "sms",
                        new SendSms(),
                        // No phone number: SKIPPED instead of BLOCKED.
                        StageOptions.defaults().skipIfInputsMissing())
                .build();

        PipelineResult result = fulfil.run(ContextView.builder().put(TOTAL, 250).build());
        PipelineResults.stage(result, "manual-review").status(); // SKIPPED
        PipelineResults.stage(result, "sms").status(); // SKIPPED
        result.isSuccessful(); // true: SKIPPED counts as success
        // --8<-- [end:conditions]

        assertThat(PipelineResults.stage(result, "manual-review").status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(PipelineResults.stage(result, "sms").status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(result.isSuccessful()).isTrue();

        PipelineResult withPhone = fulfil.run(ContextView.builder()
                .put(TOTAL, 20_000)
                .put(PHONE, "+1 555 0100")
                .build());
        assertThat(withPhone.context().get(REVIEW)).isEqualTo("queued");
        assertThat(withPhone.context().get(SMS)).isEqualTo("sent to +1 555 0100");
    }

    @Test
    void blockedWithoutSkip() throws Exception {
        Pipeline notify = Pipeline.builder().stage("sms", new SendSms()).build();
        PipelineResult result = notify.run(ContextView.empty());
        assertThat(PipelineResults.stage(result, "sms").status()).isEqualTo(StageStatus.BLOCKED);
    }
}
