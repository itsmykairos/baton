package dev.baton.benchmarks;

import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import dev.baton.api.StageOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-run overhead of the engine for trivial stages, so the numbers are the
 * framework's own cost. The {@code baseline*} benchmarks do the same hops with a
 * bare virtual-thread executor, as a lower bound.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class EngineBenchmark {

    private static final int STAGES = 10;
    private static final int WIDE = 100;
    private static final int WRITES = 200;
    private static final int DEPTH = 5;

    private Pipeline sequential;
    private Pipeline sequentialWithTimeouts;
    private Pipeline sequentialWithListener;
    private Pipeline sequentialWrites;
    private Pipeline parallel;
    private Pipeline nested;
    private ExecutorService baselineExecutor;

    @Setup
    public void setUp() {
        sequential = sequential(StageOptions.defaults(), false);
        sequentialWithTimeouts = sequential(StageOptions.defaults().withTimeout(Duration.ofSeconds(30)), false);
        sequentialWithListener = sequential(StageOptions.defaults(), true);

        Pipeline.Builder writes = Pipeline.builder();
        for (int i = 0; i < WRITES; i++) {
            ContextKey<Integer> key = ContextKey.of("k" + i, Integer.class);
            int value = i;
            writes.stage(
                    "w" + i, context -> ContextPatch.builder().put(key, value).build());
        }
        sequentialWrites = writes.build();

        parallel = Pipeline.builder()
                .parallel("wide", layer -> {
                    for (int i = 0; i < WIDE; i++) {
                        layer.stage("p" + i, context -> ContextPatch.empty());
                    }
                })
                .build();

        Pipeline inner = Pipeline.builder()
                .stage("leaf", context -> ContextPatch.empty())
                .build();
        for (int level = 0; level < DEPTH - 1; level++) {
            inner = Pipeline.builder().stage("level" + level, inner.asStage()).build();
        }
        nested = inner;

        baselineExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private static Pipeline sequential(StageOptions options, boolean listener) {
        Pipeline.Builder builder = Pipeline.builder();
        if (listener) {
            builder.listener(event -> {});
        }
        for (int i = 0; i < STAGES; i++) {
            builder.stage("s" + i, context -> ContextPatch.empty(), options);
        }
        return builder.build();
    }

    @Benchmark
    public PipelineResult sequential10() throws InterruptedException {
        return sequential.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult sequential10WithTimeouts() throws InterruptedException {
        return sequentialWithTimeouts.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult sequential10WithListener() throws InterruptedException {
        return sequentialWithListener.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult sequential200Writes() throws InterruptedException {
        return sequentialWrites.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult parallel100() throws InterruptedException {
        return parallel.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult nested5() throws InterruptedException {
        return nested.run(ContextView.empty());
    }

    /** Ten tasks one after another on virtual threads: the floor for sequential10. */
    @Benchmark
    public Object baselineSequential10() throws Exception {
        Object last = null;
        for (int i = 0; i < STAGES; i++) {
            last = baselineExecutor.submit(() -> ContextPatch.empty()).get();
        }
        return last;
    }

    /** A hundred tasks at once on virtual threads: the floor for parallel100. */
    @Benchmark
    public Object baselineParallel100() throws Exception {
        List<Future<ContextPatch>> futures = new ArrayList<>(WIDE);
        for (int i = 0; i < WIDE; i++) {
            futures.add(baselineExecutor.submit(() -> ContextPatch.empty()));
        }
        Object last = null;
        for (Future<ContextPatch> future : futures) {
            last = future.get();
        }
        return last;
    }
}
