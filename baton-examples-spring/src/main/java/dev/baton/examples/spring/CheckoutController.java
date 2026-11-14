package dev.baton.examples.spring;

import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.StageResult;
import dev.baton.visualize.MermaidDiagram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the configured {@code checkout} pipeline per request. */
@RestController
class CheckoutController {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutController.class);
    private final AtomicInteger requests = new AtomicInteger();
    private final Pipeline checkout;

    CheckoutController(@Qualifier("checkout") Pipeline checkout) {
        this.checkout = checkout;
    }

    /**
     * Runs a checkout.
     *
     * @param orderId the order to check out
     * @return the run's outcome, stages and a Mermaid diagram of the run
     */
    @PostMapping("/checkout")
    CheckoutResponse checkout(@RequestParam(defaultValue = "A-1001") String orderId) throws InterruptedException {
        // The MDC propagator carries this into every stage's log lines.
        MDC.put("requestId", "req-" + requests.incrementAndGet());
        try {
            LOG.info("checkout requested for {}", orderId);
            PipelineResult result = checkout.run(ContextView.builder().put(SpringExample.ORDER_ID, orderId).build());
            LOG.info("checkout {}", result.status());
            List<StageView> stages = new ArrayList<>();
            flatten(result.stages(), "", stages);
            return new CheckoutResponse(
                    result.status().name(),
                    result.isSuccessful(),
                    result.context().find(SpringExample.RECEIPT).orElse(null),
                    result.context().find(SpringExample.RECOMMENDATION).orElse(null),
                    stages,
                    MermaidDiagram.of(checkout.describe(), result));
        } finally {
            MDC.remove("requestId");
        }
    }

    private static void flatten(List<StageResult> stages, String prefix, List<StageView> into) {
        for (StageResult stage : stages) {
            String path = prefix + stage.stageId();
            into.add(new StageView(path, stage.status().name(), stage.duration().toMillis(), stage.attempts(),
                    stage.failure().map(failure -> failure.getClass().getSimpleName() + ": " + failure.getMessage())
                            .orElse(null)));
            flatten(stage.children(), path + "/", into);
        }
    }

    /**
     * A checkout's outcome.
     *
     * @param status the run status
     * @param successful whether every stage succeeded (or was recovered or skipped)
     * @param receipt the payment receipt, if paid
     * @param recommendation the recommendation, or "none" from the fallback
     * @param stages every stage, nested ones as paths
     * @param mermaid a Mermaid diagram of this run
     */
    record CheckoutResponse(String status, boolean successful, String receipt, String recommendation,
                            List<StageView> stages, String mermaid) {
    }

    /**
     * One stage of a run.
     *
     * @param path the stage path
     * @param status its status
     * @param durationMs how long it ran
     * @param attempts how many attempts it took
     * @param failure its failure, if any
     */
    record StageView(String path, String status, long durationMs, int attempts, String failure) {
    }
}
