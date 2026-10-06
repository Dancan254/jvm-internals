# JVM Internals — Part 4 (Execution Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Part 4 of the course — lessons 18–22 (execution engine: interpreter, JIT tiers, inlining/deopt, OSR, JMH) — as a fully verified, reviewable increment on top of merged Parts 0–3.

**Architecture:** Same content-course architecture: Markdown lessons with inline Java 25 samples, all commands executed for real on the pinned JDK 25 (Zulu 25.28). Lesson 22 adds the course's third and final `labs/` dependency (JMH) — the only `labs/` change this part. Execution: swarm model (parallel writers, controller-owned git, parallel reviewers, wrap-up re-verification, final whole-branch review).

**Tech Stack:** JDK 25 (Zulu 25.28), `javac`/`java`/`jcmd`, Maven 3.9.10, JMH (`org.openjdk.jmh:jmh-core` + `jmh-generator-annprocess`) for lesson 22 only.

**Spec:** `docs/superpowers/specs/2026-10-06-jvm-internals-design.md` (section 3, Part 4 rows)

**Prior art to mirror:** `lessons/part-3-memory/15-escape-analysis.md` (JIT-adjacent topic with honest non-determinism handling — it forward-references lesson 22) and `lessons/part-2-classloading/10-classloader-leaks.md` (bounded-demo pattern).

## Global Constraints

- JDK 25, OpenJDK/HotSpot (Zulu 25.28) throughout; every output block from a real run on this machine.
- **JIT output is the most non-deterministic content in the course:** tier sequences, compile levels, timestamps, method IDs, inlining decisions, and deopt timing all vary run-to-run and build-to-build. Every such block is labeled varying, lessons teach *how to read* the output rather than promising exact sequences, and no lesson asserts "you will see exactly this."
- **Derive every claim from a run on the pinned JDK** (flag existence, log formats, marker meanings — verified empirically before being written).
- **Bounded demos:** hot loops have explicit iteration bounds; every run terminates in seconds-to-minutes. JMH runs are pinned small (`@Fork(1)`, reduced `@Warmup`/`@Measurement` iterations) so a full benchmark completes in about a minute, not an hour.
- Explicit `javac`-compiled classes for samples (compact-source plumbing pollutes compilation logs).
- Flags fully explained at true first use across the whole course — grep lessons 00–17 before explaining (`-Xlog:*` → 00, `-Xmx` → 12, `PrintFlagsFinal` → 12, `UnlockDiagnosticVMOptions` → 15, `--sun-misc-unsafe-memory-access=allow` → 13); referenced afterwards.
- Capture dir `~/jvm-internals-samples/`; no `/tmp` paths in shipped blocks.
- Skeleton + uniform `**Previous:** … · **Next:** …` footer (lesson 18's Previous → `../part-3-memory/17-off-heap-memory.md`; lesson 22's Next → lesson 23 plain text).
- Forward references to unwritten lessons 23–32 stay plain text. Concurrency cross-links (`blob/master/`) only where topics genuinely meet.
- `labs/` remains the only Maven module; lesson 22 is the only task touching it. The `${exec.mainClass}` property pattern from Part 3 stays coherent — JMH benchmarks run via the `-Dexec.mainClass` override (verified working).
- `-XX:+PrintAssembly` appears ONLY as a clearly-marked optional aside requiring `hsdis` (not bundled with the JDK) — the lesson must work fully without it.
- Commit messages `type(scope): description`, ≤72 chars.

## Review Focus

1. **Non-determinism honesty:** JIT log blocks labeled varying; lessons teach reading, never promise exact sequences (test: Task 6 re-runs every command and confirms the *claims about format* hold even when the numbers move).
2. **Flag existence on the pinned JDK:** every `-XX` diagnostic flag shipped must be run for real first (`-XX:+PrintCompilation`, `-Xlog:codecache`, `-XX:+PrintInlining` if used — several are diagnostic-gated; verify and show the real requirement) (test: Task 6 replays each).
3. **JMH on JDK 25:** the JMH version pinned must actually run on Zulu 25.28 with the annotation processor wired; the benchmark must complete in ~a minute with the pinned fork/iteration counts (test: Task 6 times the full run).
4. **labs/ regression:** after JMH lands, the ASM demo (06) and JOL tour (13) still build and run green via the established `-Dexec.mainClass` pattern; check whether JMH standalone runs trigger the `sun.misc.Unsafe` nag — if so, the `--sun-misc-unsafe-memory-access=allow` reference goes back to lesson 13 (its owner) (test: Task 6 runs all three demos).
5. **Promise delivery:** lesson 15 forward-references lesson 22 for benchmarking honesty; lesson 04's inlining teaser and lesson 15's EA-after-inlining point at Part 4 — lessons 18–20 must visibly deliver (test: Task 6 greps the promises against shipped content). Wrap-up also upgrades lesson 17's Next footer to a live link (carried plan defect from Part 3: wrap-up scope includes existing lessons' forward refs).

---

### Task 1: Lesson 18 — Tiered compilation

**Files:**
- Create: `lessons/part-4-execution-engine/18-tiered-compilation.md`

**Interfaces:**
- Consumes: lesson 01's execution-engine box on the architecture map; lesson 15's EA (a C2 optimization — reference forward honestly).
- Produces: the interpreter/C1/C2/tier vocabulary and `PrintCompilation` reading skill consumed by lessons 19–22.

- [ ] **Step 1: Write and run the samples**

`HotMethod.java` — a small program with one clearly hot method (explicit bounded loop, e.g. 200k iterations calling a tiny method). Run with `-XX:+PrintCompilation` (true first use — full explanation) and capture: the method appearing, tier levels (1–4) in the log, the `%`/`s`/`b`/`n` markers explained from the real output. Controls: `-Xint` (interpreter only — same program visibly slower, no compilation lines); `-XX:-TieredCompilation` (first use) showing the two-mode world. Reading guide: one annotated real log excerpt, line by line.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): interpreter → C1 → C2, why three modes (startup vs peak), tiered thresholds as ergonomics not gospel, reading `-XX:+PrintCompilation` line by line.

- [ ] **Step 3: Verify every command verbatim** from clean `~/jvm-internals-samples/` dirs; every log block labeled varying.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 18, tiered compilation"` (after `git add`)

### Task 2: Lesson 19 — Watching the JIT work

**Files:**
- Create: `lessons/part-4-execution-engine/19-watching-the-jit.md`

**Interfaces:**
- Consumes: Task 1's log-reading skill.
- Produces: code-cache vocabulary referenced by lesson 21 (OSR) and Part 6 diagnostics.

- [ ] **Step 1: Write and run the samples**

`CompileWatch.java` — a bounded program with distinct cold/warm/hot phases, run with `-XX:+PrintCompilation` plus code-cache visibility (`-Xlog:codecache` — verify exact tag/behavior on the pinned JDK first; `jcmd <pid> Compiler.CodeHeap` or `VM.info` alternatives if the tag differs — ship what really runs). Show: compilation ramp during warm phase, code cache occupancy growing, the segmented code cache (non-nmethods/profiled/non-profiled) from real output. Optional aside: `-XX:+PrintAssembly` requiring `hsdis` — marked optional, one captured example of what the *absence* looks like (the real "Could not load hsdis" message), no fabricated assembly.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): watching a method go from interpreted to compiled live; the code cache and its segments; what happens when it fills (`-XX:ReservedCodeCacheSize` tiny-pin demo → "CodeCache is full" real message, bounded).

- [ ] **Step 3: Verify every command verbatim**; every block labeled varying.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 19, watching the JIT"` (after `git add`)

### Task 3: Lesson 20 — Inlining & deoptimization

**Files:**
- Create: `lessons/part-4-execution-engine/20-inlining-and-deoptimization.md`

**Interfaces:**
- Consumes: Task 1's tiers; lesson 04's vtable/dispatch model (monomorphic call sites); lesson 15's EA-after-inlining.
- Produces: the "speculative optimization + deopt safety net" mental model referenced by Part 5 safepoints (lesson 27).

- [ ] **Step 1: Write and run the samples**

`DeoptDemo.java` — an interface call site that is monomorphic during warmup (JIT inlines, visible in `-XX:+PrintCompilation` inlining annotations or `-XX:+PrintInlining` — verify the flag's real status/gating on the pinned JDK and ship what runs), then a second implementation is loaded/instantiated → capture the real `made not entrant` (and later `made zombie`) lines. A second demo: uncommon-trap deopt via a branch that never fires until it does. Both bounded; both labeled varying.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): inlining as the master optimization (enables EA from lesson 15, unrolling from lesson 21); speculation and the deopt contract; reading made-not-entrant/zombie; why deopt is correctness-preserving, not a failure.

- [ ] **Step 3: Verify every command verbatim.**

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 20, inlining and deoptimization"` (after `git add`)

### Task 4: Lesson 21 — OSR & loop optimizations

**Files:**
- Create: `lessons/part-4-execution-engine/21-osr-and-loop-optimizations.md`

**Interfaces:**
- Consumes: Task 1's log markers (`%` = OSR — teach it here if Task 1 didn't claim it; coordinate via brief), Task 3's inlining.
- Produces: nothing downstream depends on OSR mechanics; keep scope tight.

- [ ] **Step 1: Write and run the samples**

`LongLoop.java` — one long-running loop entered once (the OSR case: method never gets "hot" by call count but the loop does) with `-XX:+PrintCompilation` capturing the real `%`-marked OSR entries with the `!`/`b` markers decoded from actual output. Loop optimizations shown by effect: a bounded loop-invariant hoisting demo and an unrolling demo, observed via timing differences with `-XX:-LoopUnrolling`-style controls (verify flag existence/gating first — ship what runs) or via C2 log flags that really work on the pinned JDK.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): on-stack replacement — replacing a running frame's code mid-loop; why OSR exists (long loops in cold methods); loop unrolling and invariant hoisting as what C2 does to your loops.

- [ ] **Step 3: Verify every command verbatim**; all blocks labeled varying.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 21, OSR and loop optimizations"` (after `git add`)

### Task 5: Lesson 22 — Honest benchmarking with JMH (+ labs/ JMH dependency)

**Files:**
- Modify: `labs/pom.xml` (add `org.openjdk.jmh:jmh-core` and `jmh-generator-annprocess` — latest 1.x that runs on JDK 25, pin what verification proves; keep ASM, JOL, exec-plugin property pattern intact)
- Create: `labs/src/main/java/org/javaguy/labs/jmh/BenchmarkLies.java`
- Create: `lessons/part-4-execution-engine/22-honest-benchmarking-jmh.md`

**Interfaces:**
- Consumes: lesson 15's EA/DCE (the benchmark-killers), lesson 18's warmup reality; `labs/` conventions (package `org.javaguy.labs.<topic>`, `-Dexec.mainClass` pattern).
- Produces: the JMH vocabulary (@Benchmark/@State/@BenchmarkMode/warmup) referenced by Part 5 GC benchmarks if any.

- [ ] **Step 1: Add JMH to `labs/pom.xml` and write `BenchmarkLies.java`**

One class containing the three classic traps as separate @Benchmark methods: dead-code elimination (result unused → suspiciously fast), constant folding (JIT precomputes), and the honest variant consuming results via `Blackhole`. Pinned small: `@Fork(1)`, 2–3 warmup + 3 measurement iterations of 1s — full run about a minute. Verify: JMH runs on Zulu 25.28 via `-Dexec.mainClass` (document the exact working invocation); check whether it triggers the `sun.misc.Unsafe` nag standalone and reference lesson 13's flag if so.

- [ ] **Step 2: Verify the build and the run**

`cd labs && mvn -q compile` green; the JMH run completes in about a minute with real scores captured; ASM (06) and JOL (13) demos still green.

- [ ] **Step 3: Write the lesson**

Outcomes (spec): why naive `System.nanoTime` loops lie (warmup, DCE, constant folding — each demonstrated with its real score vs the honest variant); JMH's annotations and what they buy; reading JMH output (score, error, units); the "benchmarks measure the benchmark" humility — delivers lesson 15's promise.

- [ ] **Step 4: Commit**

`git commit -m "feat(labs): add JMH benchmark traps and lesson 22"` (after `git add`)

### Task 6: Wrap-up — index, forward-ref upgrades, labs regression, full re-verification

**Files:**
- Modify: `lessons/README.md` (lessons 18–22 → live links)
- Modify: `README.md` (badge `lessons-23_of_33`, Part 4 table filled)
- Modify: `lessons/part-3-memory/17-off-heap-memory.md` (footer Next → live link to lesson 18 — carried plan-defect fix: wrap-up scope includes existing lessons' forward references)
- Modify: the five new lesson files (any fixups from verification)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a coherent 23-lesson increment ready for final review and merge.

- [ ] **Step 1: Re-run every reader-facing command in lessons 18–22 verbatim** from clean `~/jvm-internals-samples/` dirs, in lesson order; confirm format claims hold even where numbers move; fix drift with real recaptured output; time the JMH run.

- [ ] **Step 2: labs/ regression** — `mvn -q compile` clean; ASM (06), JOL (13), JMH (22) all green via the documented invocations.

- [ ] **Step 3: Promise check** — lesson 15 → 22 delivered; lesson 04/15 → Part 4 delivered; lesson 17 footer upgraded.

- [ ] **Step 4: Index and badge** — 18–22 live in `lessons/README.md`; README badge `lessons-23_of_33`; Part 4 table filled.

- [ ] **Step 5: Link verification** — every relative `](...)` target in all course markdown resolves.

- [ ] **Step 6: Commit** (no push — controller publishes)

`git commit -m "docs(course): link lessons 18-22, part 4 wrap-up"` (after `git add`)

---

## Self-Review Notes

- **Spec coverage:** Part 4 rows (spec §3, lessons 18–22) → Tasks 1–5; PrintAssembly as optional aside → Task 2 (spec §5 constraint honored); Shenandoah-not-demoed constraint irrelevant here; labs constraint → Task 5 is the only labs change.
- **Carried recommendations:** wrap-up includes existing-lesson forward-ref upgrades (Task 6, file scope + Step 3); Unsafe-nag ownership re-check for JMH (Task 5 Step 1 + Review Focus 4).
- **Dependencies:** JMH is the third and final dependency per spec §5. No further `labs/` growth after this part.
- **Execution method:** swarm (established): parallel implementers Tasks 1–5 (disjoint files; Task 5 is the sole labs/ toucher), controller commits, parallel reviewers, Task 6, final review, merge.
