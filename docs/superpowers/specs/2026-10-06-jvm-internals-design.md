# JVM Internals — Design Spec

**Date:** 2026-10-06
**Status:** Approved design, pending implementation plan
**Companion to:** `concurreny-multithreading` (same author, format, and brand: @your_javaguy)

---

## 1 · Concept & positioning

**Repo name:** `jvm-internals`
**Tagline:** *What the JVM actually does with your code.*

A mechanics-first course for **senior engineers** who already use Java well and want to
explain, predict, and debug what happens underneath: bytecode, classloading, memory
layout, JIT compilation, garbage collection, and observability.

This is the sequel to the concurrency course. Where `concurreny-multithreading` answers
"how do threads behave?", this repo answers "what is the JVM doing underneath all of
that?". Topics that meet the concurrency course (monitors, the Java Memory Model, thread
dumps) are explicitly cross-linked rather than re-taught.

**Pedagogy (inherited from the concurrency course):** break it on purpose → see the real
output → understand the mechanism. Mental models first, run-don't-read, every lesson
self-checking.

**Explicitly out of scope:** performance tuning as a discipline (heap sizing methodology,
GC tuning decision trees, production incident playbooks). Observability is covered as
*mechanics of the tools* (Part 6); a tuning-focused repo may follow later.

## 2 · Audience & success criteria

**Audience:** senior Java engineers. Assumed knowledge: everything in the concurrency
course (threads, JMM, `java.util.concurrent`, virtual threads), Maven basics, reading a
stack trace.

**Success criteria** — a reader who finishes the course can:

1. Read `javap -v` output fluently: constant pool, bytecode, invocation opcodes.
2. Explain the classloader delegation model and reproduce (and fix) a
   `ClassCastException` between "identical" classes.
3. Trigger and distinguish `StackOverflowError` and every `OutOfMemoryError` flavor,
   and say which runtime data area each one comes from.
4. Read object layouts with JOL, including the effects of compressed oops and field
   padding.
5. Read `-XX:+PrintCompilation` output: tiers, inlining, deoptimization, OSR.
6. Explain the generational hypothesis, TLABs, and how G1 and ZGC differ mechanically.
7. Explain safepoints and diagnose a time-to-safepoint stall.
8. Capture and read a JFR recording; drive `jcmd` for heap/class/thread diagnostics.
9. Write a minimal `java.lang.instrument` agent.

## 3 · Curriculum — 7 parts, 33 lessons (00–32)

### Part 0 · The machine (lessons 00–01)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 00 | Setup & toolchain | JDK 25 install check; `javap`, `jcmd`, GC-log flags; running single-file samples |
| 01 | JVM, JRE, JDK & the big picture | Spec vs HotSpot; the architecture map (classloader subsystem, runtime data areas, execution engine) every later lesson hangs off |

### Part 1 · Bytecode (lessons 02–06)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 02 | Anatomy of a `.class` file | Magic number, version, constant pool, access flags; `javap -v` |
| 03 | The operand stack | Stack machine vs register machine; reading `javap -c`; `aload`/`iload`/store family |
| 04 | The invocation opcodes | `invokevirtual` / `invokespecial` / `invokestatic` / `invokeinterface`; static vs dynamic dispatch demonstrated in bytecode |
| 05 | `invokedynamic` | Bootstrap methods; `LambdaMetafactory`; indified string concatenation (JEP 280) — why `+` got fast |
| 06 | Generating bytecode with ASM | A class emitted by hand with ASM, loaded and run |

### Part 2 · Classloading & linking (lessons 07–11)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 07 | The delegation model | Bootstrap / platform / application classloaders post-JDK 9; `-verbose:class` |
| 08 | Loading, linking, initialization | Verification, preparation, resolution; `<clinit>`; the exact initialization triggers, demonstrated |
| 09 | Custom classloaders | Loading a class from raw bytes; namespaces; the "same class" `ClassCastException` |
| 10 | Classloader leaks | A reload-in-a-loop demo that fills Metaspace; why the old class can't be collected |
| 11 | Modules & classloading | What JPMS changed (and didn't) about visibility and delegation |

### Part 3 · Memory (lessons 12–17)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 12 | Runtime data areas | Heap, stack, Metaspace, PC register, native stacks; triggering `StackOverflowError` and each `OutOfMemoryError` flavor on purpose |
| 13 | Object layout (JOL) | Mark word, klass word, fields, padding, alignment; `labs/` module |
| 14 | Compressed oops | OOP encoding; the 32 GB heap boundary; `+/-UseCompressedOops` experiments |
| 15 | Escape analysis & scalar replacement | Allocations that never reach the heap; proving EA with allocation-rate tests |
| 16 | String internals | Compact strings (JEP 254); the string pool; `intern()` semantics and pitfalls |
| 17 | Off-heap memory | Direct buffers; `-XX:NativeMemoryTracking`; why `java.lang.OutOfMemoryError: Direct buffer memory` happens |

### Part 4 · Execution engine (lessons 18–22)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 18 | Tiered compilation | Interpreter → C1 → C2; reading `-XX:+PrintCompilation` line by line |
| 19 | Watching the JIT work | Live compilation of a hot method; code cache; `-XX:+PrintAssembly` as optional aside (requires `hsdis`, not bundled) |
| 20 | Inlining & deoptimization | Forcing a live deopt (unstable branch / class loading); made-not-entrant, made-zombie |
| 21 | OSR & loop optimizations | On-stack replacement; loop unrolling; loop-invariant hoisting |
| 22 | Honest benchmarking with JMH | Warmup, dead-code elimination, constant folding; why naive `System.nanoTime` loops lie; `labs/` module |

### Part 5 · Garbage collection (lessons 23–27)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 23 | Reachability & references | GC roots; strong/soft/weak/phantom; `ReferenceQueue` |
| 24 | The generational hypothesis | TLABs; eden/survivor; promotion; watching it all in GC logs (`-Xlog:gc*`) |
| 25 | G1 mechanics | Regions; remembered sets; young/mixed collections; humongous objects |
| 26 | ZGC mechanics | Colored pointers; load barriers; sub-millisecond pauses; (Shenandoah mentioned, not demoed — not in all JDK builds) |
| 27 | Safepoints | What they are; global vs thread-local; the counted-loop time-to-safepoint trap |

### Part 6 · Observability & sharp edges (lessons 28–32)

| # | Lesson | Key outcomes |
|:-:|---|---|
| 28 | JFR | Recordings; event model; custom events; JFR streaming |
| 29 | `jcmd` deep dive | Heap dumps; `GC.class_histogram`; JSON thread dumps (→ links to concurrency lesson 24); VM flags |
| 30 | Instrumentation agents | A minimal `java.lang.instrument` agent that logs/transforms loaded classes; what JVMTI adds |
| 31 | `Unsafe` & VarHandle | The escape hatches; where the JDK itself uses them |
| 32 | Final exam | 30 questions: predict the output, read the `javap`, diagnose the log |

## 4 · Lesson format

Identical skeleton to the concurrency course:

```text
What you'll learn      3–4 concrete outcomes
Why this matters       the real problem, before any mechanism
The concept            explanation and diagrams
Hands-on               complete programs with real captured output
Try it yourself        exercises, no answers given
Common mistakes        the ones that bite in production
Check understanding    quiz with click-to-reveal answers
Recap                  the lesson in a few bullets
```

JVM-specific additions:

- Every lesson lists its **exact run command including JVM flags**.
- A flag is fully explained at first use and only referenced afterwards.
- Where output varies by JDK build, flags, or hardware (JIT logs, GC logs, addresses),
  the lesson says so explicitly next to the captured output.

## 5 · Samples & verification

- ~90% of samples are single-file Java 25 compact source files (`void main()`,
  `IO.println`), run as `java File.java` — zero setup, same promise as the concurrency
  repo.
- Exactly one Maven module, `labs/`, holds the three third-party dependencies used in
  the whole course: JOL (lesson 13), JMH (lesson 22), and ASM (lesson 06). Everything
  else uses `java.base` and JDK-shipped tools only — no other dependencies exist.
- **Verification rule:** every program is run on JDK 25 before its lesson ships; every
  output block comes from a real run, never from memory. Non-deterministic outputs are
  labeled as such.
- Demo constraint decisions:
  - ZGC is demoed, Shenandoah only mentioned (not in every JDK 25 build).
  - `-XX:+PrintAssembly` is an optional aside (requires `hsdis`, not bundled with the JDK).

## 6 · Repository layout

```text
jvm-internals/
├── lessons/                    ← the course (start here)
│   ├── README.md               ← course index
│   ├── part-0-the-machine/
│   ├── part-1-bytecode/
│   ├── part-2-classloading/
│   ├── part-3-memory/
│   ├── part-4-execution-engine/
│   ├── part-5-garbage-collection/
│   └── part-6-observability/
├── labs/                       ← Maven module: JOL + JMH (+ ASM for lesson 06)
├── docs/                       ← long-form deep dives (map-internals.md style), optional per part
└── README.md
```

README mirrors the concurrency repo's structure: hero badges (Java 25, lesson/sample
counts), "Why this course", mermaid path diagram, per-part lesson tables, run
instructions, lesson-skeleton diagram, sample-verification section, further reading
(JVMS, JEPs, *Inside the Java Virtual Machine*-class references), brand footer.

## 7 · Cross-linking with concurreny-multithreading

- README of each repo links to the other as companion/sequel.
- Lesson 29 links to concurrency lesson 24 (thread dumps, JFR).
- Memory-model topics (Part 3) reference concurrency lessons 07 (JMM) and 06
  (monitors) rather than re-teaching them.

## 8 · Build strategy

Built **part by part**: each part = its lessons + verified samples, shippable and
reviewable on its own. Suggested order: Part 0 → 1 → 2 → 3 → 4 → 5 → 6, with README
and `lessons/README.md` scaffolding written first so the course index exists from the
start (lessons marked "coming soon" until written). Detailed sequencing lives in the
implementation plan, not this spec.

## 9 · Open questions (resolved during planning, non-blocking)

1. Lesson 06 (ASM): confirmed to live in `labs/` since ASM is a third-party dependency.
   Alternative — defer ASM to `docs/` deep dive and keep lesson 06 to hand-reading a
   hex-level class file. Decide when planning Part 1.
2. Whether `docs/` deep dives are written per part or only where a topic outgrows its
   lesson. Default: only where needed.
