package dev.baton.cookbook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.Stage;
import dev.baton.api.StageContext;
import dev.baton.api.StageResult;
import dev.baton.api.StageStatus;
import dev.baton.testkit.PipelineResults;
import dev.baton.testkit.StageTester;
import dev.baton.testkit.TestStageContext;
import org.junit.jupiter.api.Test;

class TestingStagesTest {

    static final ContextKey<Integer> QUANTITY = ContextKey.of("quantity", Integer.class);
    static final ContextKey<Integer> TOTAL = ContextKey.of("total", Integer.class);

    // --8<-- [start:stage]
    /** Prices an order line: 7 per item, looked up in a child task. */
    static final class PriceStage implements Stage {
        @Override
        public ContextPatch execute(StageContext stage) throws Exception {
            int quantity = stage.context().get(QUANTITY);
            int unitPrice = stage.tasks().fork(() -> 7).join();
            return ContextPatch.builder().put(TOTAL, quantity * unitPrice).build();
        }
    }
    // --8<-- [end:stage]

    // --8<-- [start:stage-tester]
    @Test
    void pricesTheLine() throws Exception {
        ContextView input = ContextView.builder().put(QUANTITY, 3).build();

        // Runs one stage without a pipeline and applies its patch to the input.
        ContextView output = StageTester.runAndApply(new PriceStage(), input);

        assertThat(output.get(TOTAL)).isEqualTo(21);
    }
    // --8<-- [end:stage-tester]

    // --8<-- [start:test-context]
    @Test
    void forksOneChildTask() throws Exception {
        try (TestStageContext context =
                TestStageContext.of(ContextView.builder().put(QUANTITY, 1).build())) {
            new PriceStage().execute(context);
            assertThat(context.forkedTaskCount()).isEqualTo(1);
        }
    }
    // --8<-- [end:test-context]

    // --8<-- [start:pipeline-results]
    @Test
    void wholeRun() throws Exception {
        Pipeline order = Pipeline.builder().stage("price", new PriceStage()).build();

        PipelineResult result = order.run(ContextView.builder().put(QUANTITY, 2).build());

        // Fails with a readable tree of every stage's status, timing and failure.
        PipelineResults.requireSuccess(result);
        StageResult price = PipelineResults.stage(result, "price");
        assertThat(price.status()).isEqualTo(StageStatus.SUCCEEDED);
        System.out.println(PipelineResults.describe(result));
    }
    // --8<-- [end:pipeline-results]
}
