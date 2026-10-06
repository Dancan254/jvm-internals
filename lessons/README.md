# JVM Internals: The Course

This course has 33 lessons. It starts with "what does the JVM do between `java Demo.java` and the output?" and ends with you reading `javap` dumps fluently, forcing live JIT deoptimizations, filling Metaspace with a classloader leak on purpose, and driving JFR and `jcmd` like they're part of your editor.

This is the sequel to [Java Concurrency & Multithreading](https://github.com/Dancan254/concurreny-multithreading). That course answered *how do threads behave?* — this one answers *what is the JVM doing underneath all of that?* Where the two genuinely meet (monitors, the Java Memory Model, thread dumps), we link back instead of re-teaching.

The method is the same as the concurrency course: **break it on purpose → see the real output → understand the mechanism**. You trigger every `OutOfMemoryError` flavor, reproduce a `ClassCastException` between two "identical" classes, and watch a hot method climb the compilation tiers — and only then do you learn the mechanism that explains what you saw.

---

## How to use this course

Each lesson follows the same shape:

- **What you'll learn**: three or four things you'll walk away with
- **Why this matters**: the problem the concept solves, explained before the mechanism
- **The concept**: explanation and diagrams
- **Hands-on**: complete programs you type in and run yourself
- **Try it yourself**: exercises, with no answers given
- **Common mistakes**: the ones that actually bite people
- **Check your understanding**: a short quiz with reveal-on-click answers
- **Recap**

Work through the lessons in order. Each one builds on the last, and the quizzes assume you ran the code.

Every lesson prints its **exact run command, JVM flags included**. A flag is fully explained the first time it appears and only referenced afterwards.

### Every sample is one file

Almost every program in this course is a single, self-contained Java 25 file that you run directly:

```bash
java Demo.java
```

You don't need Maven, a project or an IDE. [Lesson 00](part-0-the-machine/00-setup-and-toolchain.md) shows you how.

**Two exceptions:**

- **Part 1 (lessons 02–06)** dissects the `.class` file itself, so those samples are **explicitly declared classes** compiled with `javac` and read with `javap -v` — compact source files would hide the very class declaration being dissected.
- **Lessons 06, 13 and 22** use the third-party libraries ASM, JOL and JMH. They live in the single Maven module [`labs/`](../labs/pom.xml) — the only place in the course where a dependency exists.

### Prerequisites

This is a course for **senior Java engineers**. You should be comfortable with everything in the [concurrency course](https://github.com/Dancan254/concurreny-multithreading) — threads, the Java Memory Model, `java.util.concurrent`, virtual threads — or have the equivalent experience. You should also know Maven basics and how to read a stack trace. You don't need any prior JVM internals knowledge.

---

## The path

```mermaid
flowchart LR
    P0["Part 0<br/>The machine"] --> P1["Part 1<br/>Bytecode"]
    P1 --> P2["Part 2<br/>Classloading & linking"]
    P2 --> P3["Part 3<br/>Memory"]
    P3 --> P4["Part 4<br/>Execution engine"]
    P4 --> P5["Part 5<br/>Garbage collection"]
    P5 --> P6["Part 6<br/>Observability & sharp edges"]

    style P0 fill:#12121f,stroke:#f0196a,color:#fff
    style P1 fill:#12121f,stroke:#f0196a,color:#fff
    style P2 fill:#12121f,stroke:#f0196a,color:#fff
    style P3 fill:#12121f,stroke:#f0196a,color:#fff
    style P4 fill:#12121f,stroke:#f0196a,color:#fff
    style P5 fill:#12121f,stroke:#f0196a,color:#fff
    style P6 fill:#f0196a,stroke:#f0196a,color:#fff
```

---

## Part 0: The machine

| # | Lesson |
|---|---|
| 00 | [Setup & toolchain](part-0-the-machine/00-setup-and-toolchain.md): JDK 25, `javap`, `jcmd`, GC-log flags |
| 01 | [JVM, JRE, JDK & the big picture](part-0-the-machine/01-jvm-jre-jdk-big-picture.md): spec vs HotSpot, the architecture map |

## Part 1: Bytecode

| # | Lesson |
|---|---|
| 02 | [Anatomy of a `.class` file](part-1-bytecode/02-anatomy-of-a-class-file.md): magic number, version, constant pool, `javap -v` |
| 03 | [The operand stack](part-1-bytecode/03-the-operand-stack.md): the stack machine, the load/store families |
| 04 | [The invocation opcodes](part-1-bytecode/04-invocation-opcodes.md): `invokevirtual`, `invokespecial`, `invokestatic`, `invokeinterface` |
| 05 | [`invokedynamic`](part-1-bytecode/05-invokedynamic.md): bootstrap methods, `LambdaMetafactory`, indified string concat |
| 06 | [Generating bytecode with ASM](part-1-bytecode/06-generating-bytecode-with-asm.md): emit a class by hand, load it, run it |

## Part 2: Classloading & linking

| # | Lesson | Status |
|---|---|:-:|
| 07 | The delegation model: bootstrap / platform / application classloaders | *coming soon* |
| 08 | Loading, linking, initialization: verification, resolution, `<clinit>` | *coming soon* |
| 09 | Custom classloaders: namespaces and the "same class" `ClassCastException` | *coming soon* |
| 10 | Classloader leaks: filling Metaspace on purpose | *coming soon* |
| 11 | Modules & classloading: what JPMS changed | *coming soon* |

## Part 3: Memory

| # | Lesson | Status |
|---|---|:-:|
| 12 | Runtime data areas: heap, stack, Metaspace, and every `OutOfMemoryError` flavor | *coming soon* |
| 13 | Object layout (JOL): mark word, klass word, padding, alignment | *coming soon* |
| 14 | Compressed oops: OOP encoding and the 32 GB boundary | *coming soon* |
| 15 | Escape analysis & scalar replacement: allocations that never reach the heap | *coming soon* |
| 16 | String internals: compact strings, the string pool, `intern()` | *coming soon* |
| 17 | Off-heap memory: direct buffers and `-XX:NativeMemoryTracking` | *coming soon* |

## Part 4: Execution engine

| # | Lesson | Status |
|---|---|:-:|
| 18 | Tiered compilation: interpreter → C1 → C2, read line by line | *coming soon* |
| 19 | Watching the JIT work: hot methods, the code cache | *coming soon* |
| 20 | Inlining & deoptimization: forcing a live deopt | *coming soon* |
| 21 | OSR & loop optimizations: on-stack replacement, unrolling, hoisting | *coming soon* |
| 22 | Honest benchmarking with JMH: why naive `nanoTime` loops lie | *coming soon* |

## Part 5: Garbage collection

| # | Lesson | Status |
|---|---|:-:|
| 23 | Reachability & references: GC roots, strong/soft/weak/phantom | *coming soon* |
| 24 | The generational hypothesis: TLABs, eden/survivor, promotion | *coming soon* |
| 25 | G1 mechanics: regions, remembered sets, humongous objects | *coming soon* |
| 26 | ZGC mechanics: colored pointers, load barriers, sub-millisecond pauses | *coming soon* |
| 27 | Safepoints: and the counted-loop time-to-safepoint trap | *coming soon* |

## Part 6: Observability & sharp edges

| # | Lesson | Status |
|---|---|:-:|
| 28 | JFR: recordings, the event model, custom events, streaming | *coming soon* |
| 29 | `jcmd` deep dive: heap dumps, class histograms, JSON thread dumps, VM flags | *coming soon* |
| 30 | Instrumentation agents: a minimal `java.lang.instrument` agent | *coming soon* |
| 31 | `Unsafe` & VarHandle: the escape hatches | *coming soon* |
| 32 | Final exam: 30 questions — predict the output, read the `javap`, diagnose the log | *coming soon* |

---

## Versions

| | Version |
|---|---|
| Java | 25 (LTS), OpenJDK/HotSpot |
| Build tool | none for the samples; Maven only for `labs/` |

Features and flags used in this course, and where each one stands in Java 25:

| Feature | Status in Java 25 |
|---|---|
| Compact source files and instance `main` (`void main()`) | Final (JEP 512) |
| `java.lang.IO` (`IO.println`) | Final (JEP 512) |
| Indified string concatenation | Final since Java 9 (JEP 280) |
| Compact strings | Final since Java 9 (JEP 254) |
| Generational ZGC | Final (JEP 439) |

---

## Reference

The ground truth is [The Java Virtual Machine Specification](https://docs.oracle.com/javase/specs/jvms/se25/html/index.html) — Chapter 4 (class file format), Chapter 5 (loading, linking, initializing) and Chapter 6 (the instruction set) map directly onto Parts 1–2. For a book-length walkthrough, Bill Venners, *Inside the Java Virtual Machine*, covers the runtime data areas and classloading subsystem with the same mechanics-first spirit.

---

Ready? **[Start with Lesson 00](part-0-the-machine/00-setup-and-toolchain.md)**
