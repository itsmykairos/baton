package dev.baton.benchmarks;

import dev.baton.api.BatonConfig;
import dev.baton.api.ContextKey;
import dev.baton.api.ContextPatch;
import dev.baton.api.ContextView;
import dev.baton.api.Pipeline;
import dev.baton.api.PipelineResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * How {@link BatonConfig#contextFlattenDepth()} trades write cost against read cost
 * on a pipeline of 200 sequential stages, each writing one new key.
 * {@code writes} only writes; {@code writesAndReads} also has every stage read
 * every key written so far, the worst case for a deep, unflattened chain.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class ContextDepthBenchmark {

    private static final int STAGES = 200;

    @Param({"0", "4", "16", "64"})
    public int depth;

    private Pipeline writes;
    private Pipeline writesAndReads;

    @Setup
    public void setUp() {
        BatonConfig config = BatonConfig.builder().contextFlattenDepth(depth).build();
        List<ContextKey<Integer>> keys = new ArrayList<>();
        for (int i = 0; i < STAGES; i++) {
            keys.add(ContextKey.of("k" + i, Integer.class));
        }

        Pipeline.Builder writeOnly = Pipeline.builder(config);
        Pipeline.Builder readHeavy = Pipeline.builder(config);
        for (int i = 0; i < STAGES; i++) {
            ContextKey<Integer> key = keys.get(i);
            int value = i;
            List<ContextKey<Integer>> earlier = keys.subList(0, i);
            writeOnly.stage("w" + i, context -> ContextPatch.builder().put(key, value).build());
            readHeavy.stage("w" + i, context -> {
                int sum = 0;
                for (ContextKey<Integer> read : earlier) {
                    sum += context.context().get(read);
                }
                return ContextPatch.builder().put(key, value + (sum & 1)).build();
            });
        }
        writes = writeOnly.build();
        writesAndReads = readHeavy.build();
    }

    @Benchmark
    public PipelineResult writes() throws InterruptedException {
        return writes.run(ContextView.empty());
    }

    @Benchmark
    public PipelineResult writesAndReads() throws InterruptedException {
        return writesAndReads.run(ContextView.empty());
    }
}
