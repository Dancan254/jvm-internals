# JVM Internals — Part 3 (Memory) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Part 3 of the course — lessons 12–17 (memory) — as a fully verified, reviewable increment on top of merged Parts 0–2.

**Architecture:** Same content-course architecture: Markdown lessons with inline Java 25 samples, all commands executed for real on the pinned JDK 25 (Zulu 25.28). Lesson 13 adds the course's second `labs/` dependency (JOL) — the only `labs/` change this part. Execution: swarm model (parallel writers, controller-owned git, parallel reviewers, wrap-up re-verification, final whole-branch review).

**Tech Stack:** JDK 25 (Zulu 25.28), `javac`/`java`/`jcmd`, Maven 3.9.10, JOL (`org.openjdk.jol:jol-core`) for lesson 13 only.

**Spec:** `docs/superpowers/specs/2026-10-06-jvm-internals-design.md` (section 3, Part 3 rows)

**Prior art to mirror:** `lessons/part-2-classloading/10-classloader-leaks.md` (bounded-failure demo pattern) and `lessons/part-1-bytecode/05-invokedynamic.md` (style reference). Lesson 06 is the existing `labs/` citizen — lesson 13 must not break it.

## Global Constraints

- JDK 25, OpenJDK/HotSpot (Zulu 25.28) throughout; every output block from a real run on this machine; non-deterministic output (addresses, sizes, counts, timings, identity hashes) labeled varying.
- **Derive every number from a run on the pinned JDK, never from memory** (carried from Part 2 final review — object sizes, header layouts, heap thresholds, string sizes all verified empirically before being written).
- **Every failure demo is bounded:** pinned flags (`-Xmx`, `-XX:MaxDirectMemorySize`, thread counts with explicit joins/timeouts) so every OOM/SOF arrives in seconds. No demo may hang or stress the host (no real multi-GB allocations; the compressed-oops 32 GB story is told through flag diagnostics like `-XX:+PrintFlagsFinal`, never a real 32 GB heap).
- Explicit `javac`-compiled classes for all samples (memory layout is invisible in compact-source plumbing).
- Flags fully explained at true first use across the whole course — grep lessons 00–11 before explaining (`-XX:MaxMetaspaceSize` → 10, `-Xlog:gc` → 00, `-verbose:class` → 01); referenced afterwards.
- Capture dir `~/jvm-internals-samples/`; no `/tmp` paths in shipped blocks.
- Lesson skeleton + uniform `**Previous:** … · **Next:** …` footer (lesson 12's Previous → `../part-2-classloading/11-modules-and-classloading.md`; lesson 17's Next → lesson 18 plain text).
- Forward references to unwritten lessons 18–32 stay plain text. Concurrency cross-links (`blob/master/`) only where topics genuinely meet.
- `labs/` remains the only Maven module; lesson 13 is the only task that touches it.
- Commit messages `type(scope): description`, ≤72 chars.

## Review Focus

1. **Bounded failures:** every OOM/StackOverflow demo must terminate in seconds with the intended error flavor (test: Task 6 re-runs each with a wall-clock eye and confirms flavor + boundedness).
2. **No host stress:** nothing allocates more than a few hundred MB for real; 32 GB compressed-oops boundary shown via `-XX:+PrintFlagsFinal -Xmx…` diagnostics only (test: Task 6 confirms no command requests a huge live heap).
3. **labs/ regression:** lesson 13 adds JOL to `labs/pom.xml` — lesson 06's ASM demo must still build and run green afterward (test: Task 6 runs both demos).
4. **JOL on JDK 25:** JOL needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` (or similar) on modern JDKs — lesson 13 must show the working invocation verbatim, whatever it empirically takes (test: Task 6 replays it).
5. **Promise delivery:** lesson 10 forward-references lesson 12 ("a class is only collectible with its loader" → where classes live); lesson 01's architecture map names the runtime data areas as Part 3's job — lesson 12 must deliver both (test: Task 6 greps the promises against shipped content).

---

### Task 1: Lesson 12 — Runtime data areas

**Files:**
- Create: `lessons/part-3-memory/12-runtime-data-areas.md`

**Interfaces:**
- Consumes: lesson 01's architecture map; lesson 10's Metaspace content (reference, don't re-teach).
- Produces: the data-areas vocabulary (heap / stack / Metaspace / PC register / native stack) and the error-flavor → area mapping consumed by lessons 13–17.

- [ ] **Step 1: Write and run the samples**

`AreaHunt.java` — a small program printing diagnostics tying each area to something observable (thread count via `Thread`, `-Xlog:gc` heap regions, `jcmd VM.flags`). Bounded failure demos, each pinned: `StackBoom.java` (infinite recursion → `StackOverflowError`, plus `-Xss256k` variant showing the smaller stack dying sooner); `HeapBoom.java` (`-Xmx32m`, growing `List<byte[]>` → `OutOfMemoryError: Java heap space` in seconds). Metaspace OOM → reference lesson 10's demo; direct-buffer OOM → forward-reference lesson 17. Map every flavor to its area in a table.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): the five data areas, what's in each, per-thread vs shared; triggering `StackOverflowError` and each OOM flavor on purpose and saying which area each comes from (JVMS §2.5 as the anchor).

- [ ] **Step 3: Verify every command verbatim** from clean `~/jvm-internals-samples/` dirs, in lesson order; confirm bounded termination.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 12, runtime data areas"` (after `git add`)

### Task 2: Lesson 13 — Object layout with JOL (+ labs/ JOL dependency)

**Files:**
- Modify: `labs/pom.xml` (add `org.openjdk.jol:jol-core` — latest 0.x that runs on JDK 25, pin what verification proves; keep ASM + exec plugin intact)
- Create: `labs/src/main/java/org/javaguy/labs/jol/LayoutTour.java`
- Create: `lessons/part-3-memory/13-object-layout-jol.md`

**Interfaces:**
- Consumes: lesson 12's heap concept; `labs/` conventions from lesson 06 (package `org.javaguy.labs.<topic>`, exec plugin).
- Produces: header vocabulary (mark word, klass word, alignment/padding) consumed by lesson 14 (compressed oops) and lesson 16 (string layout).

- [ ] **Step 1: Add JOL to `labs/pom.xml` and write `LayoutTour.java`**

Prints `ClassLayout.parseInstance(...)` for: an empty object, an object with an `int` + a `boolean` (padding visible), an `int[]` (array header + length), and a `String`. If JOL needs `--add-opens` on JDK 25, wire it via exec plugin config or documented command — whatever a real run proves.

- [ ] **Step 2: Verify the build**

`cd labs && mvn -q compile exec:java` runs the JOL tour green AND the ASM demo from lesson 06 still works (run both). Capture real layouts.

- [ ] **Step 3: Write the lesson**

Outcomes (spec): mark word, klass word, fields, padding, alignment; reading JOL output fluently; why field order matters for size. Walk the captured layouts line by line.

- [ ] **Step 4: Commit**

`git commit -m "feat(labs): add JOL layout tour and lesson 13"` (after `git add`)

### Task 3: Lesson 14 — Compressed oops

**Files:**
- Create: `lessons/part-3-memory/14-compressed-oops.md`

**Interfaces:**
- Consumes: Task 2's klass-word/header vocabulary (JOL shows compressed vs not).
- Produces: pointer-encoding mental model referenced by GC lessons (Part 5).

- [ ] **Step 1: Write and run the samples**

Diagnostics-driven, no big heaps: `java -XX:+PrintFlagsFinal -version | grep -i compressed` at `-Xmx` values below/above the boundary (showing the JVM flipping `UseCompressedOops`/`UseCompressedClassPointers` ergonomically — capture at small heap and at `-Xmx40g` *flags-only, never a live 40 GB heap*); a JOL-based comparison (reuse lesson 13's tour with `-XX:-UseCompressedOops`, moderate heap) showing header/field size differences; object-size math from the captured layouts.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): what an oop is; 35-bit-into-32-bit encoding (shift by alignment); the ~32 GB boundary and why it's not exactly 32; compressed class pointers vs compressed oops; when disabling makes sense.

- [ ] **Step 3: Verify every command verbatim** — flags-only for the boundary, moderate heaps for live demos.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 14, compressed oops"` (after `git add`)

### Task 4: Lesson 15 — Escape analysis & scalar replacement

**Files:**
- Create: `lessons/part-3-memory/15-escape-analysis.md`

**Interfaces:**
- Consumes: lesson 12's stack-vs-heap model; lesson 04's inlining teaser (EA works after inlining — forward point to Part 4).
- Produces: "allocation ≠ heap" mental model referenced by JMH lesson 22 (dead-code/EA interplay in benchmarks).

- [ ] **Step 1: Write and run the samples**

`EscapeBench.java` — a hot loop allocating a small object whose fields feed a result (EA-eligible) vs a variant where the object escapes (stored to a static/returned). Compare: allocation rate via `-Xlog:gc` (young collections near-silent in the EA case), and `-XX:-DoEscapeAnalysis` control run showing the difference. Optionally `-XX:+PrintEscapeAnalysis`/`-XX:+PrintEliminateAllocations` (diagnostic flags — verify they exist on the pinned JDK before including; they're `-XX:+UnlockDiagnosticVMOptions` gated).

- [ ] **Step 2: Write the lesson**

Outcomes (spec): escape states (no/method/global); scalar replacement — fields promoted to registers/locals, allocation vanishes; why this is an optimization *after* inlining; lock elision mention; why "objects always live on the heap" died in the 2000s.

- [ ] **Step 3: Verify every command verbatim**; JIT-adjacent outputs labeled varying; confirm the diagnostic flags exist on Zulu 25.28 before shipping them.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 15, escape analysis"` (after `git add`)

### Task 5: Lesson 16 — String internals

**Files:**
- Create: `lessons/part-3-memory/16-string-internals.md`

**Interfaces:**
- Consumes: Task 2's JOL layouts (String's real size); lesson 05's `invokedynamic` concat (the bytecode view — this lesson is the memory view).
- Produces: string-pool/intern vocabulary referenced by GC lessons.

- [ ] **Step 1: Write and run the samples**

`StringAnatomy.java` — JOL (or size math from lesson 13/14 knowledge) showing a `String`'s layout: `byte[] value` + `coder` field (compact strings, JEP 254) — Latin-1 vs UTF-16 sizes of the same logical string; interning demos: literal vs `new String` vs `intern()` identity (`==`) outcomes captured; the string pool as a heap structure (post-JDK-7 — kill the permgen folklore); `-Xlog:stringtable` if available on the pinned JDK (verify before shipping).

- [ ] **Step 2: Write the lesson**

Outcomes (spec): compact strings and the `coder` field; the string pool and `intern()` semantics and pitfalls (memory cost, DoS-shaped inputs); why `+` in a loop is still bad even with JEP 280 (memory view meets lesson 05's bytecode view).

- [ ] **Step 3: Verify every command verbatim.**

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 16, string internals"` (after `git add`)

### Task 6: Lesson 17 — Off-heap memory & NMT

**Files:**
- Create: `lessons/part-3-memory/17-off-heap-memory.md`

**Interfaces:**
- Consumes: lesson 12's data-areas map (off-heap completes it).
- Produces: NMT vocabulary referenced by observability lessons (Part 6).

- [ ] **Step 1: Write and run the samples**

`DirectBoom.java` — `ByteBuffer.allocateDirect` in a pinned loop with `-XX:MaxDirectMemorySize=32m` → `OutOfMemoryError: Direct buffer memory` in seconds (bounded); NMT walkthrough: run with `-XX:NativeMemoryTracking=summary` (first use, full explanation), capture `jcmd <pid> VM.native_memory summary` output, then `summary.diff` (or baseline/diff) showing where the direct buffers land; heap-vs-direct comparison showing the heap staying flat while native grows.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): what direct buffers are and why they exist (I/O without copying); `MaxDirectMemorySize` default and the OOM flavor; NMT categories and reading a summary; cleaner/phantom-reference freeing (why the OOM is GC-pressure-sensitive — forward point to Part 5).

- [ ] **Step 3: Verify every command verbatim**; bounded; addresses/sizes labeled varying.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 17, off-heap memory"` (after `git add`)

### Task 7: Wrap-up — index, footers, labs regression, full re-verification

**Files:**
- Modify: `lessons/README.md` (lessons 12–17 → live links)
- Modify: `README.md` (badge `lessons-18_of_33`, Part 3 table filled)
- Modify: the six new lesson files (any fixups from verification)

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: a coherent 18-lesson increment ready for final review and merge.

- [ ] **Step 1: Re-run every reader-facing command in lessons 12–17 verbatim** from clean `~/jvm-internals-samples/` dirs, in lesson order; confirm every failure demo's flavor + boundedness; fix drift with real recaptured output.

- [ ] **Step 2: labs/ regression** — `mvn -q compile` clean; run BOTH the ASM demo (lesson 06) and the JOL tour (lesson 13) green.

- [ ] **Step 3: Promise check** — lesson 10 → 12 reference resolves; lesson 01's map delivered by 12; lesson 06's labs conventions kept.

- [ ] **Step 4: Index and badge** — 12–17 live in `lessons/README.md`; README badge `lessons-18_of_33`; Part 3 table filled.

- [ ] **Step 5: Link verification** — every relative `](...)` target in all course markdown resolves.

- [ ] **Step 6: Commit** (no push — controller publishes)

`git commit -m "docs(course): link lessons 12-17, part 3 wrap-up"` (after `git add`)

---

## Self-Review Notes

- **Spec coverage:** Part 3 rows (spec §3, lessons 12–17) → Tasks 1–6, outcomes per lesson; lesson format/verification (§4–5) → constraints + per-task Step 3 + Task 7; labs constraint (§5) → Task 2 is the only labs change.
- **Part 2 final-review recommendation carried:** all numbers from pinned-JDK runs (Global Constraints, Review Focus).
- **Dependencies:** JOL is the only new dependency (spec §5 names it for lesson 13). JMH untouched (lesson 22's plan).
- **Execution method:** swarm (established): parallel implementers Tasks 1–6 (disjoint files; Task 2 is the sole labs/ toucher), controller commits, parallel reviewers, Task 7, final review, merge.
