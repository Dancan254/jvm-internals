<div align="center">

# JVM Internals

### What the JVM actually does with your code

**33 lessons · a quiz in every lesson · one final exam**

[![Java](https://img.shields.io/badge/Java-25_LTS-f0196a?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![Lessons](https://img.shields.io/badge/lessons-18_of_33-f0196a?style=for-the-badge&logo=bookstack&logoColor=white)](lessons/README.md)
[![Sequel](https://img.shields.io/badge/sequel_to-concurreny--multithreading-12121f?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Dancan254/concurreny-multithreading)

[**Start the course →**](lessons/README.md) &nbsp;·&nbsp; [Course index](lessons/README.md) &nbsp;·&nbsp; [The concurrency course ←](https://github.com/Dancan254/concurreny-multithreading)

</div>

---

## Why this course

Most JVM material starts at "tune the heap" and never explains what a heap *is*. This course is the sequel to [Java Concurrency & Multithreading](https://github.com/Dancan254/concurreny-multithreading): that course answered *how do threads behave?* — this one answers *what is the JVM doing underneath all of that?*

You **read real `javap` output**, **break things on purpose** (trigger every `OutOfMemoryError` flavor, force a live JIT deoptimization, leak a classloader until Metaspace fills), and only then learn the mechanism that explains what you just saw. By the time you reach JFR and instrumentation agents, you know exactly what each tool is looking at.

```text
Exception in thread "main" java.lang.OutOfMemoryError: Metaspace
        ← Lesson 10. By the end of Part 2 you know whose memory that was,
          why the old classes couldn't be collected, and how to see it coming.
```

<table>
<tr>
<td width="33%" valign="top">

### Mental models first
Every concept starts with **why it matters**, then a diagram, then the mechanism. You learn to ask the one question that matters: *which part of the JVM owns this behavior?*

</td>
<td width="33%" valign="top">

### Run, don't read
Almost every sample is **one self-contained file**. No IDE, no project setup: `java File.java` — or, when we're dissecting bytecode, `javac` + `javap -v` — and you see it for yourself.

</td>
<td width="33%" valign="top">

### Check yourself
Every lesson ends with exercises, the **common mistakes that bite in production**, and a quiz with hidden answers. A 30-question exam closes the course.

</td>
</tr>
</table>

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

<details open>
<summary><b>Part 0 · The machine</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 00 | [Setup & toolchain](lessons/part-0-the-machine/00-setup-and-toolchain.md) | JDK 25 install check; `javap`, `jcmd`, GC-log flags; running single-file samples |
| 01 | [JVM, JRE, JDK & the big picture](lessons/part-0-the-machine/01-jvm-jre-jdk-big-picture.md) | Spec vs HotSpot; the architecture map every later lesson hangs off |

</details>

<details open>
<summary><b>Part 1 · Bytecode</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 02 | [Anatomy of a `.class` file](lessons/part-1-bytecode/02-anatomy-of-a-class-file.md) | Magic number, version, constant pool, access flags; `javap -v` |
| 03 | [The operand stack](lessons/part-1-bytecode/03-the-operand-stack.md) | Stack machine vs register machine; the `aload`/`iload`/store family |
| 04 | [The invocation opcodes](lessons/part-1-bytecode/04-invocation-opcodes.md) | `invokevirtual` / `invokespecial` / `invokestatic` / `invokeinterface`; static vs dynamic dispatch in bytecode |
| 05 | [`invokedynamic`](lessons/part-1-bytecode/05-invokedynamic.md) | Bootstrap methods, `LambdaMetafactory`, and why `+` got fast (JEP 280) |
| 06 | [Generating bytecode with ASM](lessons/part-1-bytecode/06-generating-bytecode-with-asm.md) | A class emitted by hand with ASM, loaded and run |

</details>

<details open>
<summary><b>Part 2 · Classloading & linking</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 07 | [The delegation model](lessons/part-2-classloading/07-the-delegation-model.md) | Bootstrap / platform / application classloaders post-JDK 9; `-verbose:class` |
| 08 | [Loading, linking, initialization](lessons/part-2-classloading/08-loading-linking-initialization.md) | Verification, preparation, resolution; `<clinit>`; the exact initialization triggers |
| 09 | [Custom classloaders](lessons/part-2-classloading/09-custom-classloaders.md) | Loading a class from raw bytes; namespaces; the "same class" `ClassCastException` |
| 10 | [Classloader leaks](lessons/part-2-classloading/10-classloader-leaks.md) | A reload-in-a-loop demo that fills Metaspace |
| 11 | [Modules & classloading](lessons/part-2-classloading/11-modules-and-classloading.md) | What JPMS changed (and didn't) about visibility and delegation |

</details>

<details open>
<summary><b>Part 3 · Memory</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 12 | [Runtime data areas](lessons/part-3-memory/12-runtime-data-areas.md) | Heap, stack, Metaspace, PC register; every `OutOfMemoryError` flavor triggered on purpose |
| 13 | [Object layout (JOL)](lessons/part-3-memory/13-object-layout-jol.md) | Mark word, klass word, fields, padding, alignment |
| 14 | [Compressed oops](lessons/part-3-memory/14-compressed-oops.md) | OOP encoding; the 32 GB heap boundary |
| 15 | [Escape analysis & scalar replacement](lessons/part-3-memory/15-escape-analysis.md) | Allocations that never reach the heap |
| 16 | [String internals](lessons/part-3-memory/16-string-internals.md) | Compact strings (JEP 254); the string pool; `intern()` |
| 17 | [Off-heap memory](lessons/part-3-memory/17-off-heap-memory.md) | Direct buffers; `-XX:NativeMemoryTracking` |

</details>

<details>
<summary><b>Part 4 · Execution engine</b> — coming soon</summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 18 | Tiered compilation — *coming soon* | Interpreter → C1 → C2; reading `-XX:+PrintCompilation` line by line |
| 19 | Watching the JIT work — *coming soon* | Live compilation of a hot method; the code cache |
| 20 | Inlining & deoptimization — *coming soon* | Forcing a live deopt; made-not-entrant, made-zombie |
| 21 | OSR & loop optimizations — *coming soon* | On-stack replacement; unrolling; loop-invariant hoisting |
| 22 | Honest benchmarking with JMH — *coming soon* | Warmup, dead-code elimination; why naive `nanoTime` loops lie |

</details>

<details>
<summary><b>Part 5 · Garbage collection</b> — coming soon</summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 23 | Reachability & references — *coming soon* | GC roots; strong/soft/weak/phantom; `ReferenceQueue` |
| 24 | The generational hypothesis — *coming soon* | TLABs; eden/survivor; promotion, watched in GC logs |
| 25 | G1 mechanics — *coming soon* | Regions; remembered sets; humongous objects |
| 26 | ZGC mechanics — *coming soon* | Colored pointers; load barriers; sub-millisecond pauses |
| 27 | Safepoints — *coming soon* | Global vs thread-local; the counted-loop time-to-safepoint trap |

</details>

<details>
<summary><b>Part 6 · Observability & sharp edges</b> — coming soon</summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 28 | JFR — *coming soon* | Recordings; the event model; custom events; streaming |
| 29 | `jcmd` deep dive — *coming soon* | Heap dumps; class histograms; JSON thread dumps; VM flags |
| 30 | Instrumentation agents — *coming soon* | A minimal `java.lang.instrument` agent |
| 31 | `Unsafe` & VarHandle — *coming soon* | The escape hatches; where the JDK itself uses them |
| 32 | Final exam — *coming soon* | 30 questions: predict the output, read the `javap`, diagnose the log |

</details>

---

## Run any sample in one command

All you need is **JDK 25**.

```bash
java -version   # openjdk version "25" ...
java Demo.java  # that's it
```

Almost every sample uses Java 25's compact source files: no `class` declaration, no `public static`, and the `java.base` imports come for free.

```java
void main() {
    IO.println("Hello from the JVM");
}
```

> [!NOTE]
> **Two exceptions.** Part 1 (lessons 02–06) dissects the `.class` file itself, so those samples are **explicitly declared classes** compiled with `javac` — compact source files would hide the very class declaration under the microscope. And lessons 06, 13 and 22 use the third-party libraries ASM, JOL and JMH, which live in the single Maven module `labs/`.

Every lesson prints its **exact run command, JVM flags included**. A flag is fully explained the first time it appears and only referenced afterwards.

---

## How every lesson is built

```text
┌───────────────────────┐
│  What you'll learn    │  3–4 concrete outcomes
│  Why this matters     │  the real problem, before any mechanism
│  The concept          │  explanation and diagrams
│  Hands-on             │  complete programs with real output
│  Try it yourself      │  exercises, no answers given
│  Common mistakes      │  the ones that bite in production
│  Check understanding  │  quiz with click-to-reveal answers
│  Recap                │  the lesson in a few bullets
└───────────────────────┘
```

A taste of the quizzes:

> **Two classloaders each load `com.example.User` from the same bytes. Is a `User` from one assignable to a `User` from the other?**
> <details>
> <summary>Reveal answer</summary>
>
> No. A class's runtime identity is *(fully qualified name, defining classloader)*. Same name, different loaders, different classes — assigning across them throws `ClassCastException` even though the bytecode is byte-for-byte identical. Lesson 09 reproduces this on purpose.
>
> </details>

---

## Every sample is verified

Every program in the course was run on JDK 25 (OpenJDK/HotSpot), and every output block in the lessons comes from a real run, not from memory. Where output changes from run to run (addresses, JIT and GC logs, bootstrap-method details), the lesson says so next to the captured output.

| Tool | Used for | Where |
|---|---|---|
| `javap -v` / `javap -c` | Disassembling `.class` files | Part 1 |
| `-Xlog:gc*` | Reading GC logs | Parts 3, 5 |
| `-XX:+PrintCompilation` | Watching tiered compilation | Part 4 |
| JOL | Object memory layout | Lesson 13 (`labs/`) |
| JMH | Honest microbenchmarks | Lesson 22 (`labs/`) |
| ASM | Generating bytecode | Lesson 06 (`labs/`) |
| JFR · `jcmd` | Observability | Part 6 |

---

## Repository layout

```text
jvm-internals/
├── lessons/                       ← the course (start here)
│   ├── README.md                  ← course index
│   ├── part-0-the-machine/
│   ├── part-1-bytecode/
│   ├── part-2-classloading/
│   ├── part-3-memory/
│   ├── part-4-execution-engine/
│   ├── part-5-garbage-collection/
│   └── part-6-observability/
├── labs/                          ← the only Maven module: JOL + JMH + ASM
└── docs/                          ← long-form deep dives, where a topic outgrows its lesson
```

---

## Further reading

- **[The Java Virtual Machine Specification (Java SE 25)](https://docs.oracle.com/javase/specs/jvms/se25/html/index.html)** — the ground truth. Chapter 4 (the class file format), Chapter 5 (loading, linking, initializing) and Chapter 6 (the instruction set) map directly onto Parts 1–2.
- **Bill Venners**, *Inside the Java Virtual Machine*. Dated in places, still the clearest walkthrough of the runtime data areas and the classloading subsystem.
- JEPs: [254 · Compact strings](https://openjdk.org/jeps/254) · [280 · Indify string concatenation](https://openjdk.org/jeps/280) · [439 · Generational ZGC](https://openjdk.org/jeps/439) · [512 · Compact source files](https://openjdk.org/jeps/512)
- The companion course: **[Java Concurrency & Multithreading](https://github.com/Dancan254/concurreny-multithreading)** — threads, the Java Memory Model, and `java.util.concurrent`. Where topics meet (monitors, the JMM, thread dumps), this course links there instead of re-teaching.

---

<div align="center">

**Made by [@your_javaguy](https://www.youtube.com/@your_javaguy)**

If this helped you finally read a `javap` dump, give the repo a star.
[**Start with Lesson 00 →**](lessons/part-0-the-machine/00-setup-and-toolchain.md)

</div>
