package dev.baton.slf4j;

import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Carries the SLF4J {@link MDC} into the threads that run stages, child tasks,
 * conditions and fallbacks, and adds keys saying which run and stage the work is
 * for:
 *
 * <table>
 *   <caption>MDC keys added</caption>
 *   <tr><th>Key</th><th>Value</th></tr>
 *   <tr><td>{@value #RUN_ID}</td><td>the run id (the same as in events)</td></tr>
 *   <tr><td>{@value #PIPELINE}</td><td>the outermost pipeline's name</td></tr>
 *   <tr><td>{@value #STAGE}</td><td>the stage path, such as {@code payment/charge}; absent for the run itself</td></tr>
 * </table>
 *
 * <p>The thread's previous MDC is restored when the work ends, so pooled or reused
 * threads do not leak values between runs.</p>
 *
 * <pre>{@code
 * BatonConfig.builder().contextPropagator(new MdcContextPropagator()).build();
 * // logback pattern: %d %-5level [%X{baton.stage}] %msg%n
 * }</pre>
 */
public final class MdcContextPropagator implements ContextPropagator<Map<String, String>> {

    /** MDC key for the run id. */
    public static final String RUN_ID = "baton.runId";
    /** MDC key for the outermost pipeline's name. */
    public static final String PIPELINE = "baton.pipeline";
    /** MDC key for the stage path. */
    public static final String STAGE = "baton.stage";

    /** Creates the propagator. It holds no state and can be shared. */
    public MdcContextPropagator() {
    }

    @Override
    public Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public Restoration restore(Map<String, String> captured, ExecutionScope scope) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (captured == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(captured);
        }
        MDC.put(RUN_ID, scope.runId().toString());
        MDC.put(PIPELINE, scope.pipelineName());
        scope.stage().ifPresentOrElse(path -> MDC.put(STAGE, path.toString()), () -> MDC.remove(STAGE));
        return () -> {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        };
    }
}
