# Lesson 22: Honest benchmarking with JMH

## What you'll learn

- Why a hand-rolled `System.nanoTime()` loop almost never measures what you think it measures
- The two benchmark-killers the JIT deploys — dead-code elimination and constant folding — each demonstrated with its real score against the honest variant
- JMH's core vocabulary: `@Benchmark`, `@State`, `@BenchmarkMode`, `@Warmup`/`@Measurement`, `@Fork`, and the `Blackhole`
- How to read JMH output: score, error, units, warmup lines — and why JMH's own footer is the most honest sentence in the Java ecosystem

---

## Why this matters

Every Java team has a benchmark story. Someone writes a loop around a method, times it with `System.nanoTime()`, and posts a number: "the new implementation is 3x faster." The number is precise, repeatable-looking — and wrong. Not wrong because the engineer was careless, but wrong because measuring code on a JIT-compiled runtime is a genuinely hard problem, and the naive approach hits every trap the runtime sets.

By now you own every mechanism behind those traps. [Lesson 18](18-tiered-compilation.md) showed that your code starts in the interpreter and climbs through C1 to C2 — so a timed region that starts cold measures the warmup, not the code. [Lesson 15](../part-3-memory/15-escape-analysis.md) showed that C2 deletes work it can prove unobservable — and made a promise: *a benchmark whose result is never used measures nothing.* [Lesson 20](20-inlining-and-deoptimization.md) showed that inlining rewrites call boundaries, which is what makes the second killer — constant folding — possible even when you *do* return the result.

This lesson collects the debt. We will build three benchmarks of the *same computation*: one the JIT deletes, one the JIT precomputes, and one it is forced to actually execute. The first two will report numbers roughly eight times "faster" than the third — numbers that describe the benchmark, not the code. That gap is why JMH exists: it is the OpenJDK project's own benchmarking harness, written by the same engineers who write the JIT, precisely so that the harness and the optimizer stop fighting each other. If you remember one sentence from this lesson, make it this one: **benchmarks measure the benchmark.**

---

## The concept

### Anatomy of a lie

Here is the benchmark every engineer writes first:

```java
long start = System.nanoTime();
for (int i = 0; i < 1_000_000; i++) {
    hash(seed);
}
long elapsed = System.nanoTime() - start;
```

It lies four ways at once:

1. **No warmup.** The first thousands of calls run in the interpreter, then C1, then C2 — the tier climb from [Lesson 18](18-tiered-compilation.md) happens *inside* the timed region. Worse, the compilation itself burns CPU in the middle of your measurement. You timed the JVM getting ready, not the code.
2. **Dead-code elimination.** The loop's results are never used. Once `hash` is inlined and C2 can prove the computation is unobservable, dead-code elimination deletes it — the same family of proof [Lesson 15](../part-3-memory/15-escape-analysis.md) watched erase two hundred million allocations. You timed an empty loop.
3. **Constant folding.** If `seed` is a constant the JIT can see, C2 doesn't just delete dead work — it *precomputes live work*. Inlining exposes the constant, constant propagation folds the whole computation at compile time, and the "benchmark" returns a ready-made answer. Returning the result does not save you; the fold happens upstream of the return.
4. **No isolation, no statistics.** One JVM, one shot, no error bars. The same JVM also ran your IDE's agent, the class loading, and whatever else the process did first. A single number with no variance is not a measurement; it is an anecdote with a decimal point.

### What JMH buys you

JMH attacks all four lies structurally. You write the workload; JMH's **annotation processor** generates the timed harness around it at compile time — a `jmh_generated` package of runner classes you never write or maintain. The annotations are the vocabulary:

| Annotation / type | What it buys |
|---|---|
| `@Benchmark` | Marks a method as a workload; the processor builds the measurement loop around it |
| `@State(Scope.Thread)` | An object holding benchmark *data*; its mutable fields are values the JIT cannot see through |
| `@BenchmarkMode(Mode.AverageTime)` | What quantity to measure — here, average time per operation |
| `@OutputTimeUnit(TimeUnit.NANOSECONDS)` | The units of the reported score |
| `@Warmup(iterations = 3, time = 1)` | Timed-but-discarded iterations, so the JIT reaches steady state *before* measurement |
| `@Measurement(iterations = 3, time = 1)` | The iterations that actually count |
| `@Fork(1)` | Run the benchmarks in a fresh, clean JVM, separate from whatever launched the run |
| `Blackhole` | A sink that consumes values so the JIT cannot prove them unused — the anti-DCE device |

Two of these deserve a second look because they are the *semantic* fixes, not just plumbing:

**The `Blackhole`** is how JMH wins the fight against dead-code elimination. `bh.consume(result)` hands the value to something the JIT may not delete — from C2's point of view the result is observably used, so the work that produced it must happen. Returning a value from a `@Benchmark` method works too: JMH consumes returned values for you. What does *not* work is computing into the void.

**`@State` fields** are how JMH wins against constant folding. A *mutable instance field* of the state object cannot be constant-folded: the JIT must reload it on every operation, because nothing in the language promises it stays put. That is the entire difference between the lie and the truth in the demo below — `hash(42L)` folds, `hash(seed)` where `seed` is a mutable field does not.

(Why doesn't `javac` fold `hash(42L)` itself? `javac` folds constant *expressions* — literals and compile-time constant variables — but never method calls. The bytecode contains real `invokestatic` work; only after C2 inlines `hash` and `mix` does the constant become visible and the fold possible. The trap is a JIT phenomenon, which is why this lesson is in Part 4.)

### Reading a score without lying to yourself

JMH reports `Score ± Error`, and both halves matter. The score is the mean of the per-iteration means; the error is the half-width of a 99.9% confidence interval. With three measurement iterations that interval is wide — honestly wide, which is the point. And when the run ends, JMH prints a paragraph beginning `REMEMBER: The numbers below are just data.` That footer is not boilerplate to skip; it is the senior-engineer summary of this entire lesson.

---

## Hands-on

The benchmark needs a third-party library — JMH itself — so by the course rule from [Lesson 06](../part-1-bytecode/06-generating-bytecode-with-asm.md) it lives in `labs/`, alongside ASM (lesson 06) and JOL ([Lesson 13](../part-3-memory/13-object-layout-jol.md)).

### 1. JMH joins `labs/`

Two dependencies join the POM — `jmh-core` (the runtime and annotations) and `jmh-generator-annprocess` (the annotation processor that generates the harness). 1.37 is the latest published 1.x release; the run below is the proof it works on the pinned Zulu 25.28. One build addition comes with them:

`labs/pom.xml` (new parts):

```xml
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-core</artifactId>
            <version>1.37</version>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-generator-annprocess</artifactId>
            <version>1.37</version>
        </dependency>
```

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.1</version>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.openjdk.jmh</groupId>
                            <artifactId>jmh-generator-annprocess</artifactId>
                            <version>1.37</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
```

Why the compiler-plugin block? JMH's processor generates the `jmh_generated` runner classes during compilation, and **since JDK 23 `javac` no longer runs annotation processors it discovers on the classpath unless you opt in.** This is not documentation folklore — verified on the pinned JDK: compiling `BenchmarkLies.java` by hand with the JMH jars on the classpath succeeds *silently* and generates nothing, while adding `-proc:full` produces `BenchmarkLies_jmhType.java`, `BenchmarkLies_honest_jmhTest.java` and friends. Without generated code, the runtime symptom is a crash before any benchmark runs:

```
Exception in thread "main" java.lang.RuntimeException: ERROR: Unable to find the resource: /META-INF/BenchmarkList
	at org.openjdk.jmh.runner.AbstractResourceReader.getReaders(AbstractResourceReader.java:98)
	at org.openjdk.jmh.runner.BenchmarkList.find(BenchmarkList.java:124)
	...
```

The `annotationProcessorPaths` entry is the Maven spelling of that opt-in. The `exec.mainClass` property pattern from lessons 06 and 13 is untouched — and plain `mvn -q compile exec:java` still defaults to the JOL tour.

### 2. Three benchmarks, one computation

`labs/src/main/java/org/javaguy/labs/jmh/BenchmarkLies.java`:

```java
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
```

The workload is deliberately small and pure: `mix` is the bit-mixing finalizer `java.util.SplittableRandom` uses internally — shifts, XORs and `long` multiplies, nothing else. That purity is what makes the demo clean: no allocation for escape analysis to argue about, no I/O, and integer arithmetic that C2 can fold completely once a constant input is exposed. All three benchmarks wrap the *same* `hash` — the only differences are what happens to the input (field vs literal) and the result (dropped, returned, consumed). Every difference in the scores is therefore the JIT's doing, not the workload's.

The class-level annotations are the vocabulary from the concept section, pinned small so the whole run takes about half a minute: one fork, three 1-second warmup iterations, three 1-second measurement iterations per benchmark.

### 3. The run — and why it is `exec:exec`, not `exec:java`

Lessons 06 and 13 ran their demos with `exec:java`. Try that pattern here and JMH falls over before the first iteration:

```bash
cd labs && mvn -q compile exec:java -Dexec.mainClass=org.javaguy.labs.jmh.BenchmarkLies
```

```
# Benchmark: org.javaguy.labs.jmh.BenchmarkLies.constantFolded

# Run progress: 0.00% complete, ETA 00:00:18
# Fork: 1 of 1
Error: Could not find or load main class org.openjdk.jmh.runner.ForkedMain
Caused by: java.lang.ClassNotFoundException: org.openjdk.jmh.runner.ForkedMain
<forked VM failed with exit code 1>
```

The failure is a direct consequence of two things you already know. `exec:java` runs `main` *inside Maven's own JVM* (lesson 06 established this — it is why the class loader in its output is an `ExecJavaClassLoader`). Then `@Fork(1)` makes JMH launch a *fresh* JVM for measurement, and the forked JVM's classpath is reconstructed from the runner JVM's — but inside Maven, the classpath is Maven's own (the `classworlds.conf` machinery), not the project's. The fork cannot find even JMH's own `ForkedMain`.

The fix is a different goal of the same plugin, first use in this course. **`exec:exec` starts a brand-new OS process** instead of calling `main` in-process, and its `%classpath` placeholder expands to the project's real runtime classpath — `target/classes` plus every dependency. That is exactly what a benchmark wants: a JVM that never ran Maven, never loaded Guice, never did anything but this benchmark:

```bash
cd labs && mvn -q compile exec:exec -Dexec.executable=java -Dexec.args="-cp %classpath org.javaguy.labs.jmh.BenchmarkLies"
```

### 4. Reading the output

The run takes about half a minute. It opens with two families of warnings you already own, then JMH's own header *(paths are machine-specific)*:

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::staticFieldBase has been called by com.google.inject.internal.aop.HiddenClassDefiner (file:/home/champez/.sdkman/candidates/maven/current/lib/guice-5.1.0-classes.jar)
...
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/home/champez/.m2/repository/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar)
...
# JMH version: 1.37
# VM version: JDK 25, OpenJDK 64-Bit Server VM, 25+36-LTS
# VM invoker: /home/champez/.sdkman/candidates/java/25-zulu/bin/java
# VM options: <none>
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: 3 iterations, 1 s each
# Measurement: 3 iterations, 1 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Average time, time/op
# Benchmark: org.javaguy.labs.jmh.BenchmarkLies.constantFolded
```

Read it line by line:

- **The warnings.** Two callers, both known. The `HiddenClassDefiner` block is Maven's own Guice wiring — [Lesson 06](../part-1-bytecode/06-generating-bytecode-with-asm.md) told you to ignore it. The second block is new but the mechanism is [Lesson 13](../part-3-memory/13-object-layout-jol.md)'s: `jmh-core` itself touches `sun.misc.Unsafe`, so the once-per-JVM deprecation nag fires in the runner JVM *and again inside every fork*. Lesson 13's `--sun-misc-unsafe-memory-access=allow` silences it — pass it before the main class for the runner and via JMH's `-jvmArgs` option (first use: extra JVM flags for the forked measurement JVMs) for the forks:

  ```bash
  mvn -q compile exec:exec -Dexec.executable=java -Dexec.args="--sun-misc-unsafe-memory-access=allow -cp %classpath org.javaguy.labs.jmh.BenchmarkLies -jvmArgs --sun-misc-unsafe-memory-access=allow"
  ```

  With that, only Maven's own four lines remain. The flag changes nothing about what is measured — it is permission, not optimization.
- **`# VM options: <none>`** — the quiet payoff of `exec:exec`. The measurement JVM is clean: no Maven flags, no agents, nothing leaked from the launcher. (Contrast with the failed `exec:java` attempt, where this line listed `classworlds.conf` and `maven.home`.)
- **`# Blackhole mode: compiler (auto-detected)`** — JDK 25 lets the *compiler itself* implement the blackhole, and JMH 1.37 uses that automatically. This is why the run ends with a `NOTE:` about Compiler Blackholes: scores are only comparable when the blackhole mode, JVM and flags match. Another way of saying "benchmarks measure the benchmark."
- **`# Warmup` / `# Measurement`** — our annotations, echoed back as the run's configuration.

Each benchmark then gets its own fork and its own iteration log. Here is `honest`, complete *(every number in this block varies from run to run and machine to machine — the shape is the signal)*:

```
# Benchmark: org.javaguy.labs.jmh.BenchmarkLies.honest

# Run progress: 66.67% complete, ETA 00:00:06
# Fork: 1 of 1
# Warmup Iteration   1: 6.204 ns/op
# Warmup Iteration   2: 6.206 ns/op
# Warmup Iteration   3: 6.123 ns/op
Iteration   1: 6.157 ns/op
Iteration   2: 6.150 ns/op
Iteration   3: 6.336 ns/op


Result "org.javaguy.labs.jmh.BenchmarkLies.honest":
  6.214 ±(99.9%) 1.925 ns/op [Average]
  (min, avg, max) = (6.150, 6.214, 6.336), stdev = 0.106
  CI (99.9%): [4.289, 8.139] (assumes normal distribution)
```

The `# Warmup Iteration` lines are [Lesson 18](18-tiered-compilation.md)'s tier climb made visible — this is where the interpreter-to-C2 transition happens, safely *outside* the measured numbers (in this run the method is tiny and stabilizes almost immediately; under load you may watch these lines wander — that wandering *is* the warmup lesson, and it is why these numbers are discarded). The unmarked `Iteration` lines are the only ones that count. The `Result` line is the vocabulary of honesty: **score** (the mean of iteration means), **± error** (the 99.9% confidence interval half-width — wide with `Cnt=3`, honestly so), and **units** (`ns/op`, nanoseconds per operation).

Then the summary table *(scores vary — treat yours as data, not as errata)*:

```
Benchmark                     Mode  Cnt  Score   Error  Units
BenchmarkLies.constantFolded  avgt    3  0.793 ± 0.130  ns/op
BenchmarkLies.deadCode        avgt    3  0.792 ± 0.063  ns/op
BenchmarkLies.honest          avgt    3  6.214 ± 1.925  ns/op
```

This table is the whole lesson in three lines. The two lies agree with each other almost perfectly — `0.793` vs `0.792` ns/op — and disagree with the truth by a factor of eight. And ~0.79 ns is a suspicious number in itself: on any modern CPU that is a couple of cycles, which is not the cost of three rounds of bit-mixing. It is the cost of the harness loop *doing nothing* — JMH measuring its own scaffolding while the JIT declines to run the workload. **A sub-nanosecond score for "real work" is the smell of a dead benchmark.** (If you want direct proof of *what* C2 emitted in each case, that is [Lesson 19](19-watching-the-jit.md)'s tooling — the optional assembly-dump aside lives there.)

### 5. Why each trap fires

- **`deadCode` ≈ free.** C2 inlines `hash` and the three `mix` calls ([Lesson 20](20-inlining-and-deoptimization.md)'s machinery), leaving pure integer arithmetic whose result nobody reads. Dead-code elimination deletes every instruction of it — the same optimizer family [Lesson 15](../part-3-memory/15-escape-analysis.md) watched erase allocations, applied to arithmetic. What remains to be timed is the empty harness loop.
- **`constantFolded` ≈ free, despite returning.** Inlining exposes the literal `42L`; constant propagation folds every shift, XOR and multiply into one precomputed `long`; the method returns a constant. Returning a value is *not* protection — JMH dutifully consumes the return, but the fold already happened upstream. `javac` couldn't fold it (method calls aren't constant expressions); the JIT could, because inlining made the constant visible.
- **`honest` pays full price.** `seed` is a mutable instance field, so C2 must reload it on every operation — there is no constant to fold. `bh.consume(...)` hands the result to the blackhole — on JDK 25 a compiler-level construct — so dead-code elimination cannot touch the producer. Reload, compute, consume: every shift, XOR and multiply executes, every time. The ~6 ns is what the work actually costs on this machine, on this JVM, with these flags — and on yours it will be a different number with the same shape.

---

## Try it yourself

1. Add a fourth benchmark, `public long returned() { return hash(seed); }` — no `Blackhole` anywhere. Predict its score relative to `honest` before you run it. (Returned values are consumed by JMH; predict *which* trap cannot fire here.)
2. Change `seed` from a mutable instance field to `private static final long SEED = 42L;` and adjust `honest` to use it. Predict the new score of `honest` before running. Which of the two honesty ingredients did you just remove, and which lie did `honest` become?
3. Set `@Warmup(iterations = 0)` and re-run. Watch `honest`'s first measurement iteration and its error bar. Explain the damage in [Lesson 18](18-tiered-compilation.md)'s vocabulary — which tiers are now inside the timed region?
4. Reproduce the missing-processor failure by hand: copy `BenchmarkLies.java` to a scratch directory, compile it with plain `javac -cp <jmh jars>`, and run it without the `jmh_generated` classes on the classpath. Note the run classpath needs `jmh-core` *plus its transitive dependencies* (`jopt-simple`, `commons-math3`) — with only the two JMH jars the run dies earlier, on `NoClassDefFoundError: joptsimple/OptionException`; `mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout` in `labs/` will print the exact list for you. Then recompile with `-proc:full` and compare. Why does the failure surface as a *resource* error (`/META-INF/BenchmarkList`) rather than a compile error?
5. Bump `@Measurement` to ten iterations and re-run. What happens to the `Error` column, and why? If you wanted to defend a number in a design review, which would you raise first — iterations or forks — and what does each one protect you against?

---

## Common mistakes

- **"A million iterations makes my `nanoTime` loop accurate."** Volume is not validity. Dead-code elimination, constant folding and the warmup problem do not care how many times you repeat the mistake — C2 deletes the millionth dead iteration as happily as the first. More iterations of a broken harness give you a more precise lie.
- **"I returned the result, so the JIT had to compute it."** `constantFolded` returned its result and still measured ~0.79 ns/op. Consumption defeats dead-code elimination; it does nothing against constant folding, which happens *upstream* of the return once inlining exposes a constant input.
- **"The JMH score is the truth about my code."** It is the truth about your *benchmark*: this method, this state, this JVM, these flags, this blackhole mode, this machine. JMH says so itself at the end of every run — `REMEMBER: The numbers below are just data.` Treat scores as evidence to investigate, not verdicts to quote.
- **`ERROR: Unable to find the resource: /META-INF/BenchmarkList`.** The annotation processor never ran, so no harness was generated. Since JDK 23, `javac` ignores processors discovered on the classpath unless you opt in — configure `annotationProcessorPaths` in Maven (as `labs/` does) or pass `-proc:full` to a hand-rolled `javac`.
- **Running JMH with `exec:java` like the other labs.** `exec:java` keeps you inside Maven's JVM; JMH's fork then can't reconstruct the project classpath and dies with `ClassNotFoundException: org.openjdk.jmh.runner.ForkedMain` — and even when in-process runs work, you are benchmarking next to Maven's guts. JMH runs in this course use `exec:exec` with `%classpath`.
- **"The `sun.misc.Unsafe` warnings mean my benchmark is broken."** Two known callers: Maven's Guice wiring (lesson 06's warning) and `jmh-core`'s own `Utils`. Both are noise you already know how to silence with [Lesson 13](../part-3-memory/13-object-layout-jol.md)'s `--sun-misc-unsafe-memory-access=allow`; neither affects the measurement.

---

## Check your understanding

**1. `deadCode` and `constantFolded` both score ~0.79 ns/op while `honest` scores ~6.2 ns/op — for the *same* computation. What does ~0.79 ns actually measure?**

<details>
<summary>Reveal answer</summary>

The harness itself — JMH's generated measurement loop with nothing inside it. In `deadCode`, C2 inlined `hash`/`mix`, saw the result was unobservable, and dead-code-eliminated the entire computation. In `constantFolded`, inlining exposed the literal `42L` and constant propagation folded nine integer operations into one precomputed `long`, so each call just returns a constant. A couple of CPU cycles per operation is the signature of a benchmark whose workload no longer exists at runtime.

</details>

**2. `javac` performs constant folding too. Why did `hash(42L)` survive compilation as real work, only to be folded later by the JIT?**

<details>
<summary>Reveal answer</summary>

`javac` folds constant *expressions* — literals and compile-time constant variables combined with operators — but it never folds method calls. The bytecode for `hash(42L)` contains a genuine `invokestatic`, and inside `hash`, three more. Only after C2 *inlines* those calls does the constant `42L` flow into the arithmetic, at which point constant propagation folds everything. That is why the trap is a JIT phenomenon and why it depends on [Lesson 20](20-inlining-and-deoptimization.md)'s inlining succeeding first.

</details>

**3. Name the two ingredients that make `honest` honest, and the specific JIT optimization each one defends against.**

<details>
<summary>Reveal answer</summary>

(1) The input `seed` is a **mutable instance field** of the `@State` object, which C2 must reload on every operation — there is no compile-time constant, so constant folding has nothing to fold. (2) The result is handed to **`Blackhole.consume`**, making it observably used, so dead-code elimination cannot delete the computation that produced it. Remove ingredient 1 and `honest` becomes `constantFolded`; remove ingredient 2 and it becomes `deadCode`.

</details>

**4. Why does `@Fork(1)` run the benchmarks in a fresh JVM instead of measuring in the JVM that launched the run?**

<details>
<summary>Reveal answer</summary>

Isolation. The launcher JVM — here, one started by Maven — has its own compilation history, loaded classes, GC state and agents, all of which pollute measurement. A forked JVM starts clean: the header's `# VM options: <none>` line is the receipt. Forking also makes runs *repeatable*, because each benchmark group gets the same pristine environment rather than inheriting whatever the previous benchmark did to the JVM. (The failed `exec:java` attempt showed the flip side: inside Maven, JMH could not even build a correct classpath for its fork.)

</details>

**5. A teammate runs the same `BenchmarkLies` on their laptop and gets noticeably different scores — `honest` at 9.1 ns/op, the traps at 1.2 ns/op. Is something broken?**

<details>
<summary>Reveal answer</summary>

No — different hardware, different background load, and possibly different JIT decisions produce different absolute numbers; that is why every output block in this lesson is labeled as varying. What should survive is the *shape*: the two traps near each other at harness-overhead levels, `honest` several times more expensive. Scores are only meaningfully comparable when the JVM, flags, blackhole mode and machine class match — which is exactly what JMH's `NOTE` about Compiler Blackholes is warning about. Compare shapes across machines; compare scores only across controlled, identical setups.

</details>

---

## Recap

- A naive `System.nanoTime()` loop lies four ways: no warmup ([Lesson 18](18-tiered-compilation.md)'s tiers pollute the timed region), dead-code elimination deletes unused work, constant folding precomputes constant-input work, and one un-isolated number has no error bars.
- JMH — the OpenJDK's own harness — fixes these structurally: `@Warmup`/`@Measurement` separate the tier climb from the data, `@Fork` isolates in a clean JVM, `Blackhole` consumption defeats DCE, `@State` mutable fields defeat folding, and every score ships with an error term.
- `javac` never folds method calls, so constant-input traps survive compilation; C2's inlining is what exposes the constant and enables the fold. Returning a result is not protection.
- Read a JMH score as `mean ± 99.9% CI`, in the stated units, from the stated VM — and trust the *shape* across machines, never the absolute number. Sub-nanosecond scores for "real work" mean the workload no longer exists.
- JMH lives in `labs/` (with ASM and JOL), runs via `exec:exec -Dexec.args="-cp %classpath ..."` — `exec:java` breaks JMH's forked JVM — and needs the annotation processor explicitly enabled, because JDK 23+ `javac` won't run classpath processors on its own.
- Benchmarks measure the benchmark. JMH's own `REMEMBER` footer says it; Part 5's GC benchmarks will lean on this vocabulary every time they trust a number.

**Previous:** [Lesson 21 — OSR & loop optimizations](21-osr-and-loop-optimizations.md) · **Next:** Lesson 23 — Reachability & references
