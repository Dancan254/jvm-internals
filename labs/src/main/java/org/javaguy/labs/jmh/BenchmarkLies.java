package org.javaguy.labs.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkLies {

    // A mutable field: C2 cannot know this value at compile time.
    private long seed = 42;

    // A pure bit-mixing round (the SplitMix64 finalizer): shifts, XORs and
    // long multiplies only, so once it is inlined C2 can constant-fold the
    // whole thing when the input is known at compile time.
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        z = z ^ (z >>> 31);
        return z;
    }

    private static long hash(long z) {
        return mix(mix(mix(z)));
    }

    // Trap 1 — dead code: the result is never used, so after inlining
    // dead-code elimination can delete the whole computation.
    @Benchmark
    public void deadCode() {
        hash(seed);
    }

    // Trap 2 — constant folding: the input is a compile-time constant.
    // javac cannot fold method calls, so the bytecode contains real work;
    // C2 inlines hash/mix and precomputes the answer anyway.
    @Benchmark
    public long constantFolded() {
        return hash(42L);
    }

    // Honest — the input is a mutable field (unknown at compile time) and the
    // result is consumed by JMH's Blackhole, so the computation must happen.
    @Benchmark
    public void honest(Blackhole bh) {
        bh.consume(hash(seed));
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
