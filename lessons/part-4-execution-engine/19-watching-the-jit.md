# Lesson 19: Watching the JIT work

## What you'll learn

- Watching a method climb from interpreted to C1-compiled to C2-compiled *live*, phase by phase
- The code cache: where the JIT stores generated machine code, and its three segments — non-nmethods, profiled nmethods, non-profiled nmethods
- Four windows into the cache: `-XX:+PrintCompilation` recap, `-XX:+PrintCodeCache` at exit, `jcmd Compiler.codecache` live, `-Xlog:codecache` for events
- What actually happens when the code cache fills up — captured, not theorized — and why it's a silent performance cliff, not a crash

---

## Why this matters

Here's a production incident that ruins weekends. A service gets deployed, warms up, runs beautifully for hours — and then, over a few minutes, p99 latency degrades 10×. No exception. No GC storm. CPU is *busier*, not idle. Restart fixes it... for a while. The cause, when someone finally finds it, is a single line in the log that appeared once and never again:

```
OpenJDK 64-Bit Server VM warning: CodeCache is full. Compiler has been disabled.
```

The JVM ran out of room for compiled machine code, switched the JIT off, and kept serving traffic — correctly, and 10–30× slower, forever. Nothing alerts on this by default. The teams that catch it are the ones who know the code cache exists, know it's a bounded resource, and know which two commands show its occupancy.

[Lesson 18](18-tiered-compilation.md) taught you to read `-XX:+PrintCompilation`: tiers, the `%`/`s`/`b`/`n`/`!` markers, the line format. That log tells you *what got compiled*. This lesson is about *where it goes* — the code cache, a region of native memory most Java engineers have never looked at — and what happens when that region runs out. The vocabulary you build here (segments, sweeper, `ReservedCodeCacheSize`, `full_count`) is what lessons 21–22 and the Part 6 diagnostics assume you have.

---

## The concept

### Compiled code has to live somewhere

Lesson 18's pipeline — interpreter → C1 → C2 — produces machine code. Machine code is bytes, and bytes need an address. The JIT writes every compiled method into a native-memory region called the **code cache**: outside the heap (no `-Xmx` coverage, no GC management in the usual sense), outside Metaspace, invisible to every tool that only knows about Java objects. When [Lesson 17](../part-3-memory/17-off-heap-memory.md) listed Native Memory Tracking's categories, the `Code` line — "the JIT's code cache" — was this region. A JVM's real memory footprint is the heap *plus* the code cache and friends; this lesson finally opens the `Code` box.

One property matters more than any other: **the code cache is a fixed-size region, reserved up front.** The JIT cannot grow it at runtime. On this machine the default is 240 MB, and you already saw it once — [Lesson 12](../part-3-memory/12-runtime-data-areas.md)'s `jcmd VM.flags` output included `ReservedCodeCacheSize=251658240` (251658240 bytes = 240 MB) right next to `+SegmentedCodeCache` and three segment sizes. At the time that was just ergonomic noise. Now it's the subject.

### The segmented code cache

Since JDK 9, HotSpot splits the cache into three segments, and you can see all three in any snapshot:

| Segment | What's stored there |
|---|---|
| **non-nmethods** | JVM-internal code that isn't a Java method: adapters (the shims between interpreted and compiled calling conventions), stubs, and other runtime blobs |
| **profiled nmethods** | Compiled Java methods that still carry profiling instrumentation — C1's tier 2/3 output, on their way up |
| **non-profiled nmethods** | Compiled Java methods at their final form — C2's tier 4 output (and C1's tier 1), profiling stripped |

("nmethod" is HotSpot's internal name for a compiled Java method — the unit the code cache stores.)

Why segment? Two reasons, both about housekeeping. First, **locality**: profiled code has a short expected lifespan — it exists to be replaced by a better compilation — so keeping it together keeps the long-lived code densely packed. Second, **sweeping**: the code cache has its own reclamation, a *sweeper* that walks nmethods and frees the ones that are dead (made not entrant and then zombie — the lifecycle lesson 20 details) or cold. Segmentation lets the sweeper concentrate where the churn is. The code cache, in other words, has its own little garbage collector — and just like the real one, it only matters when the space gets tight.

### When the cache fills

If the JIT asks for space and the sweeper can't free enough, three things happen, in order:

1. The JVM prints `CodeCache is full. Compiler has been disabled.` — **once**, to stderr.
2. The compiler threads stand down. Methods already compiled keep running as machine code; everything else — including every method that would have gotten hot tomorrow — runs **interpreted, permanently**. There is no restart of compilation and no second warning.
3. The application keeps running. Correctly. At interpreter speed.

That asymmetry — total correctness, catastrophic slowness, one log line — is exactly why this failure mode eats teams. It is also why the demo below keeps everything hot on purpose: a cache full of *cold* nmethods gets swept; a cache full of *live* nmethods is genuinely full.

### The four windows

You already own the first; this lesson adds three. All four show the same underlying structure at different moments:

- `-XX:+PrintCompilation` — the *event stream*: each compilation as it happens. [Lesson 18](18-tiered-compilation.md)'s tool, with its marker table.
- `-XX:+PrintCodeCache` — first use in this course — an *exit-time snapshot*: segment-by-segment sizes, usage, and blob counts, printed when the JVM shuts down. A product flag, so it works on any production JDK with no unlocking.
- `jcmd <pid> Compiler.codecache` — the same snapshot from a *live* JVM, through the diagnostic socket [Lesson 00](../part-0-the-machine/00-setup-and-toolchain.md) introduced. This is the one you'd run against production.
- `-Xlog:codecache` — the `-Xlog` syntax from [Lesson 00](../part-0-the-machine/00-setup-and-toolchain.md) with the `codecache` tag — the *cache's own narration*: sweep cycles under pressure, and the full event when it happens.

---

## Hands-on

All commands run from the samples directory:

```bash
cd ~/jvm-internals-samples/lesson19
```

### 1. A program with distinct cold, warm and hot phases

`CompileWatch.java` is built so each phase leaves a different signature in the compilation log. The `cold` phase is deliberately tiny (100 calls — below every compilation threshold, so our code stays interpreted). The `warm` phase hammers two methods with 200,000 calls. The `hot` phase switches to text work — `String.format` and a regex — whose real purpose is to drag *hundreds of JDK methods* into the compiler's queue, the way a real service does when it starts serving traffic. Between phases the program sleeps for two seconds so the log timestamps show the gaps, and at the end it holds for 30 seconds so you can attach `jcmd` in section 4. Every loop has an explicit bound; the whole run is about 35 seconds:

```java
import java.util.regex.Pattern;

public class CompileWatch {

    static final Pattern WORD = Pattern.compile("[a-z]+");

    static long sink;

    static long work(int x) {
        long r = x;
        for (int i = 0; i < 50; i++) {
            r = (r * 31 + i) ^ (r >>> 7);
        }
        return r;
    }

    static void phase(String name, int iterations, boolean text) {
        System.out.println("--- " + name + " phase: " + iterations + " iterations ---");
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += work(i);
            if (text) {
                String s = String.format("iteration-%d payload", i);
                if (WORD.matcher(s).matches()) {
                    acc++;
                }
            }
        }
        sink = acc;
    }

    public static void main(String[] args) throws Exception {
        phase("cold", 100, false);
        Thread.sleep(2_000);
        phase("warm", 200_000, false);
        Thread.sleep(2_000);
        phase("hot", 100_000, true);
        System.out.println("--- done computing (sink=" + sink + "); holding 30s for jcmd ---");
        Thread.sleep(30_000);
        System.out.println("bye");
    }
}
```

The `sink` field is the same trick as [Lesson 15](../part-3-memory/15-escape-analysis.md)'s: the results are consumed so the loops survive dead-code elimination (lesson 22 is the full treatment). Compile:

```bash
javac CompileWatch.java
```

### 2. Watch the ramp live

Run with `-XX:+PrintCompilation` — lesson 18's flag, so no re-explanation here; recall that the first column is milliseconds since JVM start, the second is the compilation ID, and the markers (`%`, `s`, `b`, `n`, `!`) are in lesson 18's table. `tee` keeps a copy for analysis:

```bash
java -XX:+PrintCompilation CompileWatch 2>&1 | tee compilation.log
```

*(Every block in this lesson varies: timestamps, compilation IDs, ordering across compiler threads, even which tier a method reaches first. Read these as one real run's shape, not a promise.)*

The program's own phase markers land in the same stream, so you can slice the log by phase. Here's the run's skeleton — all JVM-startup lines and the ~290 hot-phase lines elided:

```
--- cold phase: 100 iterations ---
--- warm phase: 200000 iterations ---
2058   64       3       CompileWatch::work (33 bytes)
2059   65       4       CompileWatch::work (33 bytes)
2062   64       3       CompileWatch::work (33 bytes)   made not entrant: not used
2073   66 %     3       CompileWatch::phase @ 18 (84 bytes)
2076   67       3       CompileWatch::phase (84 bytes)
2080   68 %     4       CompileWatch::phase @ 18 (84 bytes)
2084   66 %     3       CompileWatch::phase @ 18 (84 bytes)   made not entrant: OSR invalidation of lower levels
2091   68 %     4       CompileWatch::phase @ 18 (84 bytes)   made not entrant: uncommon trap
--- hot phase: 100000 iterations ---
```

Read it phase by phase:

- **Cold: total silence.** Not one compilation line in the entire window — 100 calls never crossed a single threshold, so `CompileWatch::work` and `CompileWatch::phase` ran **purely interpreted**, and the interpreter leaves no trace in this log. That's the first reading skill: *absence of a line means interpreted*, not "not running."
- **Warm (~2.1 s): our methods climb.** `work` appears at tier 3, is recompiled at tier 4 three milliseconds later, and the tier-3 version is `made not entrant` — retired now that a better one exists. Then `phase` follows with `%`-marked entries — *on-stack replacement*, the running method's loop swapped to compiled code mid-flight (lesson 21 teaches OSR properly): OSR at tier 3, ordinary tier 3, OSR at tier 4. Two `made not entrant` reasons show up — `OSR invalidation of lower levels` and `uncommon trap`; both are deoptimization vocabulary, and lesson 20 owns them. For now just note: **methods are compiled more than once, and old versions are discarded.** The cache is a place with turnover, not an archive.
- **Hot (~2.1–4.3 s): the storm.** In this window the log prints ~287 lines and almost none are ours — a sample:

```
4107  103       3       java.util.regex.Pattern$BmpCharPropertyGreedy::match (89 bytes)
4108  101       3       java.util.regex.Pattern$$Lambda/0x800000027::is (9 bytes)
4108  102       3       java.util.regex.Pattern::lambda$Single$0 (11 bytes)
4109  118       3       java.util.regex.Matcher::search (154 bytes)
4109  119       3       java.util.regex.Pattern$Start::match (90 bytes)
4110  107       3       java.util.regex.Matcher::checkMatch (19 bytes)
4110  108       3       java.util.regex.Matcher::hasMatch (13 bytes)
4112  124       4       java.util.regex.Pattern$BmpCharPropertyGreedy::match (89 bytes)
```

`String.format` and the regex engine pull their machinery through the tiers — including a generated lambda class (`Pattern$$Lambda/...`), because lambdas are real classes with real methods. Watch one method climb within eight lines: `BmpCharPropertyGreedy::match` at tier 3 on 4107 ms, recompiled at tier 4 on 4112 ms. This is what "warmup" actually is for a real service: not your five hot methods, but *thousands of library methods* crossing thresholds.

Don't trust the prose — count. This `awk` slices the log at the phase markers and tallies compilation lines per window:

```bash
awk '/--- cold/{p="cold"} /--- warm/{p="warm"} /--- hot/{p="hot"} /--- done/{p="hold"} /^[0-9]/{c[p==""?"startup":p]++} END{print "startup:", c["startup"]+0; print "cold:", c["cold"]+0; print "warm:", c["warm"]+0; print "hot:", c["hot"]+0; print "hold:", c["hold"]+0}' compilation.log
```

```
startup: 65
cold: 0
warm: 8
hot: 287
hold: 18
```

*(Counts vary per run — the shape, small-small-huge, is the point.)* Two things to notice. The `warm` window's 8 lines are *entirely* our two methods (counting a couple twice — turnover again), while `hot` is 30× busier and almost entirely JDK code. And `hold` isn't zero: after our program stops computing, the compiler threads finish draining their queue — the JIT is a background crew that keeps working while your code idles.

### 3. Where did the compiled code go? The exit-time snapshot

Now the same story from the memory side. `-XX:+PrintCodeCache` (first use, explained in the concept section) prints the cache's final state at JVM exit. Run the smallest possible program first for a baseline — `Hello.java` is a one-line `main` — then the full `CompileWatch`:

```bash
java -XX:+PrintCodeCache Hello
```

```
CodeHeap 'non-profiled nmethods': size=120032Kb used=2Kb max_used=2Kb free=120029Kb
 bounds [0x00007c2773ec8000, 0x00007c2774138000, 0x00007c277b400000]
CodeHeap 'profiled nmethods': size=120028Kb used=3Kb max_used=3Kb free=120024Kb
 bounds [0x00007c276c400000, 0x00007c276c670000, 0x00007c2773937000]
CodeHeap 'non-nmethods': size=5700Kb used=1196Kb max_used=1210Kb free=4503Kb
 bounds [0x00007c2773937000, 0x00007c2773ba7000, 0x00007c2773ec8000]
CodeCache: size=245760Kb, used=1201Kb, max_used=1215Kb, free=244556Kb
 total_blobs=304, nmethods=7, adapters=206, full_count=0
Compilation: enabled, stopped_count=0, restarted_count=0
```

```bash
java -XX:+PrintCodeCache CompileWatch
```

```
CodeHeap 'non-profiled nmethods': size=120032Kb used=149Kb max_used=149Kb free=119882Kb
 bounds [0x00007ab23fec8000, 0x00007ab240138000, 0x00007ab247400000]
CodeHeap 'profiled nmethods': size=120028Kb used=258Kb max_used=258Kb free=119769Kb
 bounds [0x00007ab238400000, 0x00007ab238670000, 0x00007ab23f937000]
CodeHeap 'non-nmethods': size=5700Kb used=1298Kb max_used=1351Kb free=4401Kb
 bounds [0x00007ab23f937000, 0x00007ab23fba7000, 0x00007ab23fec8000]
CodeCache: size=245760Kb, used=1705Kb, max_used=1758Kb, free=244052Kb
 total_blobs=690, nmethods=305, adapters=290, full_count=0
Compilation: enabled, stopped_count=0, restarted_count=0
```

*(Addresses, and to a lesser degree the `used` numbers, vary per run.)* Line by line:

- The three `CodeHeap` blocks are the segments from the concept section, in the flesh. `size` is the segment's reservation, carved out of the 240 MB total at startup — on this machine 5700K + 120028K + 120028K = 245756K ≈ the `CodeCache: size=245760Kb` line, matching the `NonNMethodCodeHeapSize`/`ProfiledCodeHeapSize`/`NonProfiledCodeHeapSize` values in lesson 12's flag dump. `bounds` gives each segment's address range — three disjoint native-memory ranges, which is what "segmented" physically means.
- `used` / `max_used` / `free`: current occupancy, high-water mark, remainder. A JVM that compiled almost nothing (Hello) still burns ~1.2 MB — the non-nmethods segment carries the interpreter stubs and adapters the JVM itself needs. Our 35-second workout added ~500 KB and grew nmethods from 7 to **305** — those 305 are section 2's event stream, at rest.
- `total_blobs` counts everything in the cache (nmethods + adapters + stubs); `full_count=0` means the cache never filled — remember this field's name for section 5.
- `Compilation: enabled` — the compiler ran to the end. Also remember this line.

### 4. The same snapshot, live, with `jcmd`

Exit-time is the wrong moment for production questions. `CompileWatch`'s final 30-second hold exists so you can attach while it idles. Run it in the background:

```bash
java CompileWatch &
```

Find it (the `jcmd -l` self-listing caveat from [Lesson 00](../part-0-the-machine/00-setup-and-toolchain.md) applies — match on `CompileWatch`, and use your own PID):

```bash
jcmd -l | grep CompileWatch
```

```
195451 CompileWatch
```

*(PID varies.)* `jcmd help` shows a whole `Compiler.*` command family:

```bash
jcmd 195451 help | grep Compiler
```

```
Compiler.CodeHeap_Analytics
Compiler.codecache
Compiler.codelist
Compiler.directives_add
Compiler.directives_clear
Compiler.directives_print
Compiler.directives_remove
Compiler.memory
Compiler.perfmap
Compiler.queue
```

`Compiler.codecache` — first live use in the course — prints the same layout as `-XX:+PrintCodeCache`, from the running process:

```bash
jcmd 195451 Compiler.codecache
```

```
195451:
CodeHeap 'non-profiled nmethods': size=120032Kb used=149Kb max_used=149Kb free=119882Kb
 bounds [0x00007ab23fec8000, 0x00007ab240138000, 0x00007ab247400000]
CodeHeap 'profiled nmethods': size=120028Kb used=258Kb max_used=258Kb free=119769Kb
 bounds [0x00007ab238400000, 0x00007ab238670000, 0x00007ab23f937000]
CodeHeap 'non-nmethods': size=5700Kb used=1298Kb max_used=1351Kb free=4401Kb
 bounds [0x00007ab23f937000, 0x00007ab23fba7000, 0x00007ab23fec8000]
CodeCache: size=245760Kb, used=1705Kb, max_used=1758Kb, free=244052Kb
 total_blobs=690, nmethods=305, adapters=290, full_count=0
Compilation: enabled, stopped_count=0, restarted_count=0
```

Same numbers as the exit snapshot because the workload is deterministic — your live numbers will differ slightly run to run. This is the production command: `used` against `size` per segment, `full_count`, and whether compilation is still `enabled`. Worth a monitoring check; nobody gets paged for it today, which is precisely the problem.

### 5. Fill the cache on purpose

Time to break it. Filling a 240 MB cache honestly takes a huge application, so we shrink the room instead of growing the crowd: `-XX:ReservedCodeCacheSize` (first use in this course) sets the total reservation at startup, overriding the 240 MB default. It's the flag from lesson 12's ergonomic dump, now used as a scalpel. There is a floor — the JVM refuses to boot with less room than it needs for its own stubs:

```bash
java -XX:ReservedCodeCacheSize=2m Hello
```

```
Invalid ReservedCodeCacheSize: 2048K. Must be at least InitialCodeCacheSize=2496K.
Error: Could not create the Java Virtual Machine.
Error: A fatal exception has occurred. Program will exit.
```

And there's the second problem: filling the cache *fairly* is hard, because the sweeper unloads cold nmethods to make room. Even a workload built to stress it survives a floor-sized cache if the hot set is small enough — this demo's own 400-class variant runs at 2496 KB without ever filling, because the sweeper reclaims cold JVM-startup code as fast as the new code arrives. A genuinely full cache needs more *live* compiled code than fits — enough that the sweeper finds nothing it's allowed to reclaim. That's not an artificial scenario: it's what happens to applications whose frameworks generate code — ORMs, expression languages, template engines, scripting hosts — thousands of distinct hot methods, all in use.

`CacheFlooder.java` models exactly that, in miniature. It generates 1500 tiny classes (each with its own `heat` method) plus a `GenAll` registry class, batch-compiles them with `javac` **as a subprocess** — so the compiler's own considerable JIT footprint never enters *our* code cache — loads them, and calls all 1500 round-robin so **nothing ever goes cold**. After the flood, a fixed `probe` loop times how fast *fresh* code runs in whatever state the JVM is left in. Everything is bounded — the whole run finishes in about 15 seconds:

```java
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CacheFlooder {

    public interface Heat {
        long heat(long x);
    }

    public interface HeatFactory {
        Heat[] create();
    }

    // Every message flows through this ONE concatenation call site, and we
    // spin it at startup — after the code cache fills, the JVM has no room
    // left to link a fresh invokedynamic, so late string concatenation can
    // itself fail. Warming it early keeps the demo's tail end safe.
    static void report(String phase, long value) {
        System.out.println("phase=" + phase + " value=" + value);
    }

    public static void main(String[] args) throws Exception {
        report("startup", 0);
        // Warm reflection's shared machinery while the code cache is still
        // roomy — after it fills, even the JVM's internal adapter
        // allocations can fail, and phase 2 needs one reflective call.
        CacheFlooder.class.getDeclaredConstructor().newInstance();

        int classes = args.length > 0 ? Integer.parseInt(args[0]) : 1500;
        int rounds  = args.length > 1 ? Integer.parseInt(args[1]) : 300;

        // --- phase 1: generate `classes` tiny classes, one hot method each,
        //     plus a GenAll registry that instantiates them directly.
        //     javac runs as a SUBPROCESS, so its own considerable JIT
        //     footprint never touches our code cache. ---
        Path dir = Files.createTempDirectory("flooder");
        List<String> files = new ArrayList<>();
        StringBuilder registry = new StringBuilder(
                "public class GenAll implements CacheFlooder.HeatFactory {\n"
                + "    public CacheFlooder.Heat[] create() {\n"
                + "        return new CacheFlooder.Heat[] {\n");
        for (int i = 0; i < classes; i++) {
            String name = "Gen" + i;
            String source = "public class " + name + " implements CacheFlooder.Heat {\n"
                    + "    public long heat(long x) {\n"
                    + "        long r = x;\n"
                    + "        for (int j = 0; j < " + (40 + i % 60) + "; j++) {\n"
                    + "            r = (r * 31 + j) ^ (r >>> 5);\n"
                    + "        }\n"
                    + "        return r;\n"
                    + "    }\n"
                    + "}\n";
            Path file = dir.resolve(name + ".java");
            Files.writeString(file, source);
            files.add(file.toString());
            registry.append("            new ").append(name).append("(),\n");
        }
        registry.append("        };\n    }\n}\n");
        Path registryFile = dir.resolve("GenAll.java");
        Files.writeString(registryFile, registry.toString());
        files.add(registryFile.toString());

        List<String> command = new ArrayList<>(
                List.of("javac", "-cp", System.getProperty("java.class.path"), "-d", dir.toString()));
        command.addAll(files);
        if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("generation failed");
        }
        report("classes-generated", classes);

        // --- phase 2: load the registry, get all instances in one call ---
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                CacheFlooder.class.getClassLoader())) {
            HeatFactory factory = Class.forName("GenAll", true, loader)
                    .asSubclass(HeatFactory.class)
                    .getDeclaredConstructor().newInstance();
            Heat[] hot = factory.create();

            // --- phase 3: round-robin heating; nothing ever goes cold ---
            long sink = 0;
            long t0 = System.nanoTime();
            for (int round = 0; round < rounds; round++) {
                for (Heat h : hot) {
                    sink += h.heat(round);
                }
                if (round % 50 == 0) {
                    report("heating-round", round);
                }
            }
            report("heating-ms", (System.nanoTime() - t0) / 1_000_000);

            // --- phase 4: aftermath probe — how fast is fresh code NOW? ---
            long t1 = System.nanoTime();
            long acc = 0;
            for (int i = 0; i < 3_000_000; i++) {
                acc += probe(i);
            }
            report("aftermath-ms", (System.nanoTime() - t1) / 1_000_000);
            report("acc", acc);
            report("sink", sink);
        }
    }

    static long probe(int x) {
        long r = x;
        for (int i = 0; i < 40; i++) {
            r = (r * 31 + i) ^ (r >>> 5);
        }
        return r;
    }
}
```

```bash
javac CacheFlooder.java
```

**Control run** — the default 240 MB cache:

```bash
java -XX:+PrintCodeCache CacheFlooder
```

```
phase=startup value=0
phase=classes-generated value=1500
phase=heating-round value=0
phase=heating-round value=50
phase=heating-round value=100
phase=heating-round value=150
phase=heating-round value=200
phase=heating-round value=250
phase=heating-ms value=965
phase=aftermath-ms value=359
phase=acc value=6756679274046408408
phase=sink value=8093170222730768329
CodeHeap 'non-profiled nmethods': size=120032Kb used=166Kb max_used=166Kb free=119865Kb
 bounds [0x00007c1e07ec8000, 0x00007c1e08138000, 0x00007c1e0f400000]
CodeHeap 'profiled nmethods': size=120028Kb used=2347Kb max_used=2347Kb free=117681Kb
 bounds [0x00007c1e00400000, 0x00007c1e00670000, 0x00007c1e07937000]
CodeHeap 'non-nmethods': size=5700Kb used=1362Kb max_used=1413Kb free=4338Kb
 bounds [0x00007c1e07937000, 0x00007c1e07ba7000, 0x00007c1e07ec8000]
CodeCache: size=245760Kb, used=3875Kb, max_used=3926Kb, free=241884Kb
 total_blobs=2649, nmethods=2208, adapters=346, full_count=0
Compilation: enabled, stopped_count=0, restarted_count=0
```

*(Timings and `used` values vary.)* 2208 nmethods, 3.9 MB occupied — under 2% of the cache — and the aftermath probe runs in ~360 ms. Note where the mass sits: the **profiled** segment (2.3 MB) dwarfs the non-profiled one. Each `heat` method got 300 calls — enough for C1-with-profiling, not enough for C2's higher thresholds — so the segment occupancy *tells you which tier did the work*. The segments aren't just bookkeeping.

**The pin** — same program, with the *smallest* cache this JVM will boot: 2496 KB, the floor from the startup refusal above. At this size the live hot set — JVM baseline plus 1500 `heat` methods — is simply bigger than the room:

```bash
java -XX:ReservedCodeCacheSize=2496k -XX:+PrintCodeCache CacheFlooder
```

```
phase=startup value=0
phase=classes-generated value=1500
phase=heating-round value=0
phase=heating-round value=50
phase=heating-round value=100
phase=heating-round value=150
phase=heating-round value=200
[7.931s][warning][codecache] CodeCache is full. Compiler has been disabled.
[7.931s][warning][codecache] Try increasing the code cache size using -XX:ReservedCodeCacheSize=
OpenJDK 64-Bit Server VM warning: CodeCache is full. Compiler has been disabled.
OpenJDK 64-Bit Server VM warning: Try increasing the code cache size using -XX:ReservedCodeCacheSize=
CodeCache: size=2496Kb used=2495Kb max_used=2495Kb free=0Kb
 bounds [0x00007196759a7000, 0x0000719675c17000, 0x0000719675c17000]
 total_blobs=1893, nmethods=1452, adapters=346, full_count=1
Compilation: disabled (not enough contiguous free space left), stopped_count=1, restarted_count=0
phase=heating-round value=250
phase=heating-ms value=1199
phase=aftermath-ms value=6079
phase=acc value=6756679274046408408
phase=sink value=8093170222730768329
CodeCache: size=2496Kb used=2495Kb max_used=2495Kb free=0Kb
 bounds [0x00007196759a7000, 0x0000719675c17000, 0x0000719675c17000]
 total_blobs=1893, nmethods=1452, adapters=346, full_count=1
Compilation: disabled (not enough contiguous free space left), stopped_count=1, restarted_count=0
```

*(Exactly when the cache fills varies; in this run it went at 7.9 s, mid-heating, between rounds 200 and 250 — the sweeper held the line through generation and class loading, then the round-robin compilations overwhelmed it.)* Every beat of the incident from the concept section is here:

- **The warnings.** Two channels fire, once each, with no `-Xlog` flag needed — unified logging prints `[warning]`-level lines by default: the `[warning][codecache]` pair, and the tty-style `OpenJDK 64-Bit Server VM warning:` twins on stderr. Note the second line of each pair: `Try increasing the code cache size using -XX:ReservedCodeCacheSize=` — the JVM names its own remedy. Nothing repeats, nothing throws. (Add `-Xlog:codecache=info` and you additionally get the 25 sweep cycles — `Triggering threshold ... GC due to allocating ...` — that fought from the first second of the run and lost, plus an `[info][codecache] Code cache is full - disabling compilation` line.)
- **An immediate snapshot at the moment of failure.** The JVM prints a `CodeCache` summary *when it fills*, not just at exit: `used=2495Kb` of `size=2496Kb`, `free=0Kb`, `full_count=1`, and the verdict `Compilation: disabled (not enough contiguous free space left), stopped_count=1`. The exit-time snapshot tells the identical story. In production you'd collect the same facts live via `jcmd Compiler.codecache` — which is why section 4 called them out by name.
- **The program finishes — exit code 0.** Correctness untouched: `acc` *and* `sink` are byte-identical to the control run. Only the speed changed.
- **The cliff.** The aftermath probe went from 359 ms to **6079 ms** — ~17× slower, because `probe` got hot *after* the compiler was disabled and ran fully interpreted. The heating phase kept its compiled head start (965 → 1199 ms): rounds 0–200 had already been compiled when the cache gave out, and compiled code already installed keeps running. These are demo timings, not benchmark-grade numbers — lesson 22 earns the right to measure this properly — but the direction and scale are the point.

One nuance the snapshot hides: at this size the cache is too small to carve into three segments, so HotSpot runs a single combined heap — the printout has one `CodeCache` block and no per-segment `CodeHeap` lines. Segmentation is a luxury of having room.

And a sharper edge worth knowing exists: a full cache can escalate from "compiler off" to **real failures**. If the JVM needs space for one of its own non-negotiable blobs *after* the fill — an adapter, a method-handle intrinsic — it throws `OutOfMemoryError: Out of space in CodeCache for adapters` (yes: an `OutOfMemoryError` for a resource that isn't the heap, isn't Metaspace, and isn't covered by any flag you've met before today). `CacheFlooder`'s two odd-looking defenses — the single pre-warmed `report` concatenation and the reflection warmup at startup — exist precisely because an earlier shape of this demo died that way: it linked a string-concat `invokedynamic` only after the cache was full, and the JVM had nowhere to put the linkage machinery.

### 6. Optional aside: `-XX:+PrintAssembly` and the missing disassembler

You'll eventually want to *see* the generated machine code itself. The flag is `-XX:+PrintAssembly`, and it's a **diagnostic** flag — the tier [Lesson 15](../part-3-memory/15-escape-analysis.md) introduced with `-XX:+UnlockDiagnosticVMOptions`. Ask for it bare and the JVM refuses to start, naming its own requirement:

```bash
java -XX:+PrintAssembly Hello
```

```
Error: VM option 'PrintAssembly' is diagnostic and must be enabled via -XX:+UnlockDiagnosticVMOptions.
Error: The unlock option must precede 'PrintAssembly'.
Improperly specified VM option 'PrintAssembly'
Error: Could not create the Java Virtual Machine.
```

Unlocked, on a stock JDK, you get the *second* obstacle — HotSpot doesn't bundle a disassembler. It needs the `hsdis` library (buildable from the OpenJDK source tree, or findable prebuilt for your platform):

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly Hello 2>&1 | head -8
```

```
OpenJDK 64-Bit Server VM warning: PrintAssembly is enabled; turning on DebugNonSafepoints to gain additional output

============================= C1-compiled nmethod ==============================
----------------------------------- Assembly -----------------------------------
[0.052s][warning][os] Loading hsdis library failed

Compiled method (c1) 51    1       3       java.lang.Object::<init> (1 bytes)
 total in heap  [0x000079f9c4400008,0x000079f9c4400200] = 504
```

*(Timestamps, IDs and addresses vary.)* That `[warning][os] Loading hsdis library failed` is the honest state of a stock JDK: the flag works, the per-method headers print, but where the instruction mnemonics should be you get raw hex bytes instead — the machine code is real, just not disassembled. Nothing in this course requires `hsdis`; install it on a rainy day and every `head -8` above turns into readable assembly. The lesson works without it — you now know exactly what "without it" looks like.

---

## Try it yourself

1. Re-run section 2 with the `cold` phase at 1,000 iterations instead of 100. Predict, then check: does `CompileWatch::work` still stay out of the cold window? Which threshold did it cross, and what does that tell you about how small "cold" has to be on this machine?
2. Run `CompileWatch` with `-XX:+PrintCompilation -XX:+PrintCodeCache` together and match the exit snapshot's `nmethods` count to the `compilation.log` line count. Why is the snapshot's number *smaller* than the log's? (Hint: turnover — `made not entrant` nmethods can be swept before exit.)
3. During `CompileWatch`'s 30-second hold, run `jcmd <pid> Compiler.queue` and `jcmd <pid> Compiler.codelist | head`. What does the queue look like once the program is idle, and what extra detail does `codelist` give per nmethod compared to `Compiler.codecache`?
4. Re-run the 2496 KB pin *with* `-Xlog:codecache=info` and count the sweep cycles (`grep -c "Triggering threshold"`) before the fall. The code cache's own "GC" fought this run from its first second — what do the percentages in those lines tell you about when the pressure actually started?
5. Run the same 2496 KB pin with fewer classes: `java -XX:ReservedCodeCacheSize=2496k -XX:+PrintCodeCache CacheFlooder 400 300`. We ran this eight times and the cache never filled. Explain, in sweeper terms, why 400 hot generated classes survive a cache that 1500 overwhelm — what is the sweeper reclaiming to make room?

---

## Common mistakes

- **"A `CodeCache is full` warning means the JVM crashed or will."** It means the *compiler* stopped. The application runs correctly, interpreted, at a fraction of its speed, indefinitely — and the only evidence is one stderr line plus `full_count=1` and `Compilation: disabled` in a `Compiler.codecache` snapshot. The failure mode is a performance cliff, not an outage, which is exactly why it goes unnoticed.
- **"`-Xmx` bounds the JVM's code memory."** `-Xmx` caps the Java heap ([Lesson 12](../part-3-memory/12-runtime-data-areas.md)) and nothing else. The code cache is native memory with its own ceiling (`ReservedCodeCacheSize`), counted under NMT's `Code` category ([Lesson 17](../part-3-memory/17-off-heap-memory.md)). Container memory math that forgets it is how kernels get to OOM-kill "heap-sized" JVMs.
- **"The code cache only holds my application's compiled methods."** Section 2's hot phase was ~287 lines of `java.util.regex`, `java.util.Formatter` and lambda classes; section 3's near-empty `Hello` JVM already carried 300+ blobs of stubs and adapters. Your code is a minority tenant of the cache. Sizing or monitoring it from "how big is my app" alone misses the JDK's own footprint.
- **"Compilation is one-way: once compiled, always compiled."** The log disagrees in a single warm phase: tier 2 → OSR tier 4 → tier 4, with the earlier versions `made not entrant`. The cache has *turnover* — compilations replace each other, deoptimizations (lesson 20) invalidate them, the sweeper reclaims the dead. `nmethods` at any instant is survivors, not the total ever compiled.
- **"Raising `ReservedCodeCacheSize` is free headroom."** It's reserved native memory, committed as used — a bigger cache is real RSS your container limit feels. Size it from evidence (`used`/`max_used` under real load), not from superstition, and remember the floor: below `InitialCodeCacheSize` the JVM won't even boot.

---

## Check your understanding

**1. The `cold` phase ran 100 iterations and produced zero `CompileWatch::` lines in `-XX:+PrintCompilation`. Does that mean `work` and `phase` didn't execute? What does an absent line actually mean?**

<details>
<summary>Reveal answer</summary>

They executed — interpreted. `-XX:+PrintCompilation` logs *compilations*, and interpretation is not a compilation event. 100 calls crossed no invocation or back-edge threshold, so no compiler ever touched the methods. Reading this log, absence means "ran interpreted" (or didn't run); a line means "got compiled." This is also why you can't use the log to prove a method is unused.

</details>

**2. In the exit snapshot, `profiled nmethods` held 2.3 MB for `CacheFlooder` but only 258 KB for `CompileWatch`. What does a method's presence in the *profiled* segment tell you about its tier and its likely future?**

<details>
<summary>Reveal answer</summary>

Profiled nmethods are C1's tier 2/3 output — compiled code still carrying profiling instrumentation, on its way up the tiers. `CacheFlooder`'s `heat` methods got 300 calls each: enough for C1-with-profiling, not enough to trigger C2, so they piled up in the profiled segment. `CompileWatch`'s two hot methods got hundreds of thousands of calls, reached tier 4, and their compiled forms live in the *non-profiled* segment instead. Segment occupancy is a which-tier-did-the-work report: a profiled-heavy cache means "lots of methods partway up"; a non-profiled-heavy cache means "lots of methods at their final form."

</details>

**3. A colleague proposes monitoring code-cache health by watching heap usage, since "compiled code is just another kind of object." Why won't that work, and what should they watch instead?**

<details>
<summary>Reveal answer</summary>

The code cache is native memory, not heap — compiled nmethods are raw machine-code blobs in a region reserved at startup, invisible to the GC and to every heap metric. `-Xmx` doesn't bound it; heap dashboards don't show it. Watch the cache directly: `jcmd <pid> Compiler.codecache` for `used` vs `size` per segment, `full_count`, and `Compilation: enabled/disabled`; or NMT's `Code` category (Lesson 17) for the process-level number.

</details>

**4. The pinned `CacheFlooder` run printed `acc=6756679274046408408` — byte-identical to the 240 MB control run — yet took ~17× longer in the aftermath probe. Reconcile "identical result" with "the compiler was disabled."**

<details>
<summary>Reveal answer</summary>

Compilation changes *how* code executes, never *what* it computes — the same correctness contract lesson 15's escape analysis worked under. With the compiler disabled, `probe` ran interpreted: every bytecode executed, semantics intact, result identical — just far slower per iteration. The pair of runs is the whole story of this lesson in two numbers: same output, 17× time. "Compiler disabled" is a performance event with a correctness guarantee attached.

</details>

**5. You pinned a JVM to `-XX:ReservedCodeCacheSize=2m` and it refused to start, citing `InitialCodeCacheSize=2496K`. A teammate suggests working around the refusal with `-XX:InitialCodeCacheSize=1m` to get the cache smaller. Predict what that buys you, in sweeper and full-event terms.**

<details>
<summary>Reveal answer</summary>

Almost nothing. The *initial* size is only where the cache starts; it still grows on demand up to `ReservedCodeCacheSize`, so the ceiling — and the fill — are unchanged (in our run the fill arrived at the same point mid-heating either way). What does change: the cache begins life cramped, so the sweeper starts cycling within milliseconds of startup — a real `-Xlog:codecache=info` line from such a run shows a `Triggering threshold ... GC` at 0.031 s, before your code has done anything. The 2496 KB floor itself is non-negotiable because the JVM needs that room for its own stubs and adapters before a single Java method compiles; `Hello` alone already occupies ~1.2 MB of it.

</details>

---

## Recap

- Compiled machine code lives in the **code cache** — a fixed-size native-memory region, outside the heap, reserved at startup (`ReservedCodeCacheSize`, 240 MB by default on this machine). `-Xmx` doesn't touch it; NMT's `Code` category counts it.
- Since JDK 9 the cache is **segmented**: non-nmethods (JVM stubs and adapters), profiled nmethods (C1/tier 2–3, still instrumented), non-profiled nmethods (C2/tier 4, final form). Segment occupancy tells you which tier did the work.
- The cache has turnover and its own reclamation: methods are recompiled tier by tier, old versions go `made not entrant`, the **sweeper** frees cold and dead nmethods. `-Xlog:codecache=info` shows the sweep cycles.
- Four windows onto the same structure: `-XX:+PrintCompilation` (events, lesson 18), `-XX:+PrintCodeCache` (exit snapshot), `jcmd Compiler.codecache` (live snapshot — the production command), `-Xlog:codecache` (cache narration).
- A full cache is a **performance cliff, not a crash**: one `CodeCache is full. Compiler has been disabled.` warning, compilation stops permanently, the app runs interpreted — correct, and 10–30× slower. The evidence is `full_count` and `Compilation: disabled`; the monitoring is on you.

**Previous:** [Lesson 18 — Tiered compilation](18-tiered-compilation.md) · **Next:** [Lesson 20 — Inlining & deoptimization](20-inlining-and-deoptimization.md)
