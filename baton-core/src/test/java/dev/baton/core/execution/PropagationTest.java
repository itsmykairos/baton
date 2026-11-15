package dev.baton.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.RetryPolicy;
import dev.baton.api.RunOptions;
import dev.baton.api.StageOptions;
import dev.baton.api.event.PipelineEvent;
import dev.baton.api.event.RunStarted;
import dev.baton.api.propagation.BatonContext;
import dev.baton.api.propagation.ContextPropagator;
import dev.baton.api.propagation.ExecutionScope;
import dev.baton.api.propagation.Restoration;
import dev.baton.core.builder.DefaultPipelineBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PropagationTest {

    private static final ContextKey<String> SEEN = ContextKey.of("seen", String.class);

    /**
     * Carries a "trail" thread-local. Restoring for a STAGE appends the stage id, so
     * nested hops show where context was captured.
     */
    static final class TrailPropagator implements ContextPropagator<String> {
        static final ThreadLocal<String> TRAIL = new ThreadLocal<>();
        final AtomicInteger restores = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<ExecutionScope> scopes = new CopyOnWriteArrayList<>();

        @Override
        public String capture() {
            return TRAIL.get();
        }

        @Override
        public Restoration restore(String captured, ExecutionScope scope) {
            restores.incrementAndGet();
            scopes.add(scope);
            String previous = TRAIL.get();
            TRAIL.set(
                    scope.kind() == ExecutionScope.Kind.STAGE
                            ? captured + ">" + scope.stage().orElseThrow().stageId()
                            : captured);
            return new Restoration() {
                @Override
                public void failed(Throwable failure) {
                    failures.add(failure);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                    TRAIL.set(previous);
                }
            };
        }
    }

    private static String trail() {
        return TrailPropagator.TRAIL.get();
    }

    private static String scope() {
        return BatonContext.current()
                .map(scope ->
                        scope.kind() + " " + scope.stage().map(Object::toString).orElse("-"))
                .orElse("none");
    }

    @Test
    void stageCodeChildTasksConditionsAndFallbacksSeeTheCallersContext() throws Exception {
        TrailPropagator propagator = new TrailPropagator();
        Map<String, String> seen = new ConcurrentHashMap<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .config(BatonConfig.builder().contextPropagator(propagator).build())
                .stage(
                        "work",
                        context -> {
                            seen.put("stage", trail() + " | " + scope());
                            String child = context.tasks()
                                    .fork(() -> trail() + " | " + scope())
                                    .join();
                            seen.put("child", child);
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults().withCondition(context -> {
                            seen.put("condition", trail() + " | " + scope());
                            return true;
                        }))
                .stage(
                        "fails",
                        context -> {
                            throw new IllegalStateException("boom");
                        },
                        StageOptions.defaults().withFallback((context, failure) -> {
                            seen.put("fallback", trail() + " | " + scope());
                            return ContextPatch.empty();
                        }))
                .build();

        TrailPropagator.TRAIL.set("caller");
        PipelineResult result;
        try {
            result = pipeline.run(ContextView.empty());
        } finally {
            assertThat(trail()).as("caller thread restored").isEqualTo("caller");
            TrailPropagator.TRAIL.remove();
        }

        assertThat(result.isSuccessful()).isTrue();
        assertThat(seen)
                .containsEntry("stage", "caller>work | STAGE work")
                .containsEntry("child", "caller>work | CHILD_TASK work")
                .containsEntry("condition", "caller | CONDITION work")
                .containsEntry("fallback", "caller | FALLBACK fails");
        assertThat(propagator.restores.get()).isEqualTo(propagator.closes.get());
        assertThat(BatonContext.current()).isEmpty();
    }

    @Test
    void nestedPipelineStagesInheritTheNestedStagesContext() throws Exception {
        TrailPropagator propagator = new TrailPropagator();
        Map<String, String> seen = new ConcurrentHashMap<>();
        Pipeline child = new DefaultPipelineBuilder()
                .stage("charge", context -> {
                    seen.put("charge", trail() + " | " + scope());
                    return ContextPatch.empty();
                })
                .build();
        Pipeline parent = new DefaultPipelineBuilder()
                .config(BatonConfig.builder().contextPropagator(propagator).build())
                .stage("payment", child.asStage())
                .build();

        TrailPropagator.TRAIL.set("caller");
        try {
            parent.run(ContextView.empty());
        } finally {
            TrailPropagator.TRAIL.remove();
        }

        assertThat(seen).containsEntry("charge", "caller>payment>charge | STAGE payment/charge");
    }

    @Test
    void timedAttemptsRunWithTheStagesContext() throws Exception {
        TrailPropagator propagator = new TrailPropagator();
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .config(BatonConfig.builder().contextPropagator(propagator).build())
                .stage(
                        "flaky",
                        context -> {
                            seen.add(trail() + " | " + scope());
                            if (calls.incrementAndGet() == 1) {
                                throw new java.io.IOException("down");
                            }
                            return ContextPatch.empty();
                        },
                        StageOptions.defaults()
                                .withRetry(RetryPolicy.maxAttempts(2)
                                        .withFixedDelay(Duration.ZERO)
                                        .withAttemptTimeout(Duration.ofSeconds(1))))
                .build();

        TrailPropagator.TRAIL.set("caller");
        try {
            assertThat(pipeline.run(ContextView.empty()).isSuccessful()).isTrue();
        } finally {
            TrailPropagator.TRAIL.remove();
        }

        assertThat(seen).containsExactly("caller>flaky | ATTEMPT flaky", "caller>flaky | ATTEMPT flaky");
    }

    @Test
    void asyncRunsCaptureOnTheCallingThread() throws Exception {
        TrailPropagator propagator = new TrailPropagator();
        Map<String, String> seen = new ConcurrentHashMap<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .stage("work", context -> {
                    seen.put("stage", trail());
                    return ContextPatch.empty();
                })
                .build();

        TrailPropagator.TRAIL.set("starter");
        var run = pipeline.start(
                ContextView.empty(),
                RunOptions.defaults()
                        .withConfig(BatonConfig.builder()
                                .contextPropagator(propagator)
                                .build()));
        TrailPropagator.TRAIL.remove();
        run.await();

        assertThat(seen).containsEntry("stage", "starter>work");
        assertThat(propagator.scopes)
                .first()
                .satisfies(scope -> assertThat(scope.kind()).isEqualTo(ExecutionScope.Kind.RUN));
    }

    @Test
    void failuresAreReportedToRestorations() throws Exception {
        TrailPropagator propagator = new TrailPropagator();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .config(BatonConfig.builder().contextPropagator(propagator).build())
                .stage("fails", context -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        pipeline.run(ContextView.empty());

        // Once for the stage, once for the run that it failed.
        assertThat(propagator.failures)
                .hasSize(2)
                .allSatisfy(failure -> assertThat(failure).hasMessage("boom"));
        assertThat(propagator.restores.get()).isEqualTo(propagator.closes.get());
    }

    @Test
    void aThrowingPropagatorIsSkippedAndTheRunSucceeds() throws Exception {
        ContextPropagator<Object> broken = new ContextPropagator<>() {
            @Override
            public Object capture() {
                throw new IllegalStateException("capture bug");
            }

            @Override
            public Restoration restore(Object captured, ExecutionScope scope) {
                throw new IllegalStateException("restore bug");
            }
        };
        Pipeline pipeline = new DefaultPipelineBuilder()
                .config(BatonConfig.builder().contextPropagator(broken).build())
                .stage(
                        "work",
                        context -> ContextPatch.builder().put(SEEN, scope()).build())
                .build();

        PipelineResult result = pipeline.run(ContextView.empty());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.context().get(SEEN)).isEqualTo("STAGE work");
    }

    @Test
    void scopeCarriesTheRunIdSentInEventsAndPropagatorsAccumulate() throws Exception {
        TrailPropagator app = new TrailPropagator();
        TrailPropagator run = new TrailPropagator();
        List<PipelineEvent> events = new CopyOnWriteArrayList<>();
        Map<String, Object> seen = new ConcurrentHashMap<>();
        Pipeline pipeline = new DefaultPipelineBuilder()
                .name("orders")
                .config(BatonConfig.builder().contextPropagator(app).build())
                .listener(events::add)
                .stage("work", context -> {
                    BatonContext.current().ifPresent(scope -> {
                        seen.put("runId", scope.runId());
                        seen.put("pipeline", scope.pipelineName());
                    });
                    return ContextPatch.empty();
                })
                .build();

        pipeline.run(
                ContextView.empty(),
                RunOptions.defaults()
                        .withConfig(BatonConfig.builder().contextPropagator(run).build()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(app.restores.get()).isPositive();
        assertThat(run.restores.get()).isEqualTo(app.restores.get());
        assertThat(seen.get("pipeline")).isEqualTo("orders");
        assertThat(seen.get("runId")).isEqualTo(((RunStarted) events.getFirst()).runId());
    }
}
