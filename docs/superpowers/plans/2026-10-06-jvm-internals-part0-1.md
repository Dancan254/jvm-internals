# JVM Internals — Part 0 & Part 1 (Bytecode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `jvm-internals` repo scaffolding and ship the first two parts of the course: Part 0 (the machine) and Part 1 (bytecode), lessons 00–06.

**Architecture:** A content course repo, mirroring `concurreny-multithreading`: lessons are Markdown files containing inline single-file Java 25 samples the reader types and runs; one Maven module `labs/` holds the only third-party-dependency code (ASM in this plan; JOL/JMH arrive in later plans). Every command in every lesson is executed on JDK 25 during authoring and the real output captured into the lesson.

**Tech Stack:** JDK 25 (OpenJDK, HotSpot), `javac`/`javap`/`jcmd`, Maven 3.9+, ASM 9.8+.

**Spec:** `docs/superpowers/specs/2026-10-06-jvm-internals-design.md`

## Global Constraints

- JDK 25, OpenJDK/HotSpot assumed throughout; Lesson 00 states this assumption explicitly.
- Compact source files (`void main()`, `IO.println`) for general samples — **except Part 1 bytecode lessons (02–06), which use explicitly declared classes compiled with `javac`**, because compact source files hide the very class declaration being dissected.
- Every output block in a lesson comes from a real run on JDK 25, never from memory; non-deterministic output (addresses, bootstrap method details, ordering) is labeled as varying.
- Every lesson carries its exact run command, including JVM flags; a flag is fully explained at first use, referenced afterwards.
- Every lesson follows the skeleton: What you'll learn / Why this matters / The concept / Hands-on / Try it yourself / Common mistakes / Check your understanding (click-to-reveal) / Recap.
- `labs/` is the only Maven module and the only place third-party dependencies exist.
- Commit messages must match `type(scope): description` — `feat(lessons): ...`, `feat(labs): ...`, `docs(course): ...` (a commit-msg hook in this repo enforces it).
- Cross-link the concurrency repo (`https://github.com/Dancan254/concurreny-multithreading`… confirm exact URL at Task 1) only where topics genuinely meet; do not re-teach its content.

## Review Focus

1. **Implicit-class trap:** compact source files compile to a synthetic class; running `javap` on them shows generated plumbing, not teaching material → Task 4 step pins explicit `class` declarations + `javac` for all Part 1 samples (test: every `javap` command in lessons 02–06 targets a `javac`-compiled `.class`).
2. **ASM/JDK 25 mismatch:** ASM older than 9.8 cannot read/write class-file version 69 → Task 8 pins `org.ow2.asm:asm:9.8` and its verification run proves the generated class loads (test: `mvn -q compile exec:java` exits 0 and prints the generated class's greeting).
3. **Bootstrap-method output drift:** `javap -v` on lambdas shows `BootstrapMethods` details that vary by JDK build → Task 7 labels that output as version-dependent (test: lesson text contains an explicit "your output may differ" note next to the block).
4. **Vendor flag drift:** `-XX` and `-Xlog` flags are HotSpot-specific; a reader on GraalVM/OpenJ9 sees different output → Task 2 (Lesson 00) states the OpenJDK/HotSpot assumption as a prerequisite (test: lesson 00 contains the assumption line).
5. **Reader on JDK 25.0.x vs author's build:** minor javap/output differences → Lesson 00 has the reader capture their own `java -version` and lessons say outputs come from a real JDK 25 run (test: lesson 00 includes the `java -version` exercise).

---

### Task 1: Repo scaffolding

**Files:**
- Create: `.gitignore`
- Create: `README.md`
- Create: `lessons/README.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `lessons/README.md` course index listing all 33 lessons (00–06 as live links after Tasks 2–8, 07–32 marked "coming soon"); README structure Task 9 updates.

- [ ] **Step 1: Write `.gitignore`**

Contents: `target/`, `*.class`, `.idea/`, `.vscode/`, `*.iml`, `.DS_Store`.

- [ ] **Step 2: Write root `README.md`**

Mirror the concurrency repo's README structure, adapted: centered hero (`# JVM Internals`, subtitle *"What the JVM actually does with your code"*), badges (Java 25 LTS, lessons, "sequel to the concurrency course"), "Why this course" (3-column table: mental models first / run, don't read / check yourself), mermaid path diagram P0→P6 in the brand colors (`#12121f` fill, `#f0196a` stroke), per-part `<details>` lesson tables (00–06 filled, 07–32 "coming soon"), "Run any sample in one command" section, lesson-skeleton diagram, sample-verification section, repository layout, further reading (JVMS, relevant JEPs), `@your_javaguy` brand footer.

- [ ] **Step 3: Write `lessons/README.md` course index**

Mirror the concurrency repo's `lessons/README.md`: course intro, "How to use this course" (the 8-section skeleton), "Every sample is one file" (with the Part 1 exception noted: lessons 02–06 use `javac`-compiled explicit classes), prerequisites (senior Java; the concurrency course or equivalent), full lesson list 00–32 grouped by part with 07–32 marked coming soon.

- [ ] **Step 4: Verify and commit**

Run: `ls README.md lessons/README.md .gitignore` — all three exist.
Run: `grep -c "coming soon" lessons/README.md` — expect 26 occurrences (lessons 07–32).

```bash
git add .gitignore README.md lessons/README.md
git commit -m "docs(course): add repo scaffolding and course index"
```

### Task 2: Lesson 00 — Setup & toolchain

**Files:**
- Create: `lessons/part-0-the-machine/00-setup-and-toolchain.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the OpenJDK/HotSpot assumption and flag-explanation convention all later lessons reference.

- [ ] **Step 1: Run the verification commands on JDK 25**

Run and capture real output: `java -version`; a compact-source `HelloJvm.java` run via `java HelloJvm.java`; `javap -version`; `jcmd -l` (lists the running JVM); `jcmd <pid> VM.version`.

- [ ] **Step 2: Write the lesson**

Outcomes (from spec): JDK 25 install check; `javap`/`jcmd`/GC-log flags; running single-file samples. Include: the OpenJDK/HotSpot assumption line; the `java -version` exercise (reader captures their own version); what each tool is for, in one paragraph each, with the captured outputs; the convention "flags explained at first use, referenced after".

- [ ] **Step 3: Verify every command verbatim**

Re-run each command exactly as written in the lesson from a clean shell; confirm outputs match what the lesson shows (modulo labeled variable parts).

- [ ] **Step 4: Commit**

```bash
git add lessons/part-0-the-machine/00-setup-and-toolchain.md
git commit -m "feat(lessons): add lesson 00, setup and toolchain"
```

### Task 3: Lesson 01 — JVM, JRE, JDK & the big picture

**Files:**
- Create: `lessons/part-0-the-machine/01-jvm-jre-jdk-big-picture.md`

**Interfaces:**
- Consumes: Task 2's toolchain conventions.
- Produces: the architecture-map diagram (classloader subsystem / runtime data areas / execution engine) that lessons 02–31 reference as "the map from lesson 01".

- [ ] **Step 1: Run the demo commands**

Run and capture: `java -XshowSettings:properties -version 2>&1 | head -30` (shows the JVM surfacing its own configuration); `java -verbose:class` on the HelloJvm sample from lesson 00, first ~15 lines (classloading teaser — "where do these come from? Part 2 answers").

- [ ] **Step 2: Write the lesson**

Outcomes: JVM vs JRE vs JDK; spec vs HotSpot (name one other implementation); the architecture map (ASCII or mermaid diagram) with one sentence per subsystem and a pointer to the part that covers it.

- [ ] **Step 3: Verify every command verbatim** (as Task 2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add lessons/part-0-the-machine/01-jvm-jre-jdk-big-picture.md
git commit -m "feat(lessons): add lesson 01, the big picture"
```

### Task 4: Lesson 02 — Anatomy of a `.class` file

**Files:**
- Create: `lessons/part-1-bytecode/02-anatomy-of-a-class-file.md`

**Interfaces:**
- Consumes: lesson 00's `javap` intro.
- Produces: the `javac` + explicit-class convention used by lessons 03–06; constant-pool vocabulary (`CONSTANT_Methodref`, `CONSTANT_String`, …) lessons 03–05 build on.

- [ ] **Step 1: Write and run the sample**

`Greeting.java` — a tiny explicitly declared class (one constant string, one field, one method). Compile with `javac Greeting.java`; run and capture: `xxd Greeting.class | head -4` (CAFEBABE magic + minor/major version 69); `javap -v Greeting` full output.

- [ ] **Step 2: Write the lesson**

Outcomes: magic number, class-file version (69 = JDK 25), constant pool walkthrough using the captured `javap -v` output, access flags. Hands-on: annotate the captured output section by section.

- [ ] **Step 3: Verify every command verbatim** (as Task 2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add lessons/part-1-bytecode/02-anatomy-of-a-class-file.md
git commit -m "feat(lessons): add lesson 02, class file anatomy"
```

### Task 5: Lesson 03 — The operand stack

**Files:**
- Create: `lessons/part-1-bytecode/03-the-operand-stack.md`

**Interfaces:**
- Consumes: Task 4's `javac`/explicit-class convention and constant-pool vocabulary.
- Produces: stack-machine mental model (`iload`/`istore`/`iadd`/…) used in lessons 04–05.

- [ ] **Step 1: Write and run the sample**

`StackMath.java` — a method computing `(a + b) * c - 1` from parameters and a local. `javac` it; capture `javap -c StackMath`. Predict-then-verify exercise: reader predicts max stack depth, then checks against `javap -v`'s `stack=` value.

- [ ] **Step 2: Write the lesson**

Outcomes: stack machine vs register machine; load/store/op families; why the class file declares `max_stack`/`max_locals`. Annotate the captured bytecode line by line.

- [ ] **Step 3: Verify every command verbatim** (as Task 2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add lessons/part-1-bytecode/03-the-operand-stack.md
git commit -m "feat(lessons): add lesson 03, the operand stack"
```

### Task 6: Lesson 04 — The invocation opcodes

**Files:**
- Create: `lessons/part-1-bytecode/04-invocation-opcodes.md`

**Interfaces:**
- Consumes: Task 5's stack-machine model.
- Produces: dispatch vocabulary (static vs dynamic) that lesson 05's `invokedynamic` contrasts against.

- [ ] **Step 1: Write and run the sample**

`Dispatch.java` — one class hierarchy (interface + impl + subclass) exercising all four: an `invokestatic` call, `invokespecial` (constructor + `super.` call), `invokevirtual` (overridden method), `invokeinterface`. Capture `javap -c Dispatch` with each opcode highlighted in the lesson.

- [ ] **Step 2: Write the lesson**

Outcomes: the four opcodes and when javac emits each; why `invokevirtual` needs the receiver's runtime class (vtable in one paragraph, no C++). Quiz includes "which opcode does this call compile to?" items.

- [ ] **Step 3: Verify every command verbatim** (as Task 2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add lessons/part-1-bytecode/04-invocation-opcodes.md
git commit -m "feat(lessons): add lesson 04, invocation opcodes"
```

### Task 7: Lesson 05 — `invokedynamic`

**Files:**
- Create: `lessons/part-1-bytecode/05-invokedynamic.md`

**Interfaces:**
- Consumes: Task 6's four-opcode model.
- Produces: bootstrap-method concept referenced by the labs/ASM lesson and later lambda/memory lessons.

- [ ] **Step 1: Write and run the samples**

`LambdaLinkage.java` — a lambda passed to a method; `StringConcat.java` — `"a" + x + "b"` in a loop. `javac` both; capture `javap -v` showing the `invokedynamic` call sites and the two bootstrap methods (`LambdaMetafactory.metafactory`, `StringConcatFactory.makeConcatWithConstants`). Label the bootstrap sections "details may differ across JDK 25 builds".

- [ ] **Step 2: Write the lesson**

Outcomes: what problem `invokedynamic` solves (defer linkage to runtime); bootstrap methods; why lambdas are cheap and string `+` got fast after JDK 9 (JEP 280). Compare with what lessons 04's opcodes would have required.

- [ ] **Step 3: Verify every command verbatim** (as Task 2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add lessons/part-1-bytecode/05-invokedynamic.md
git commit -m "feat(lessons): add lesson 05, invokedynamic"
```

### Task 8: `labs/` Maven module + Lesson 06 — Generating bytecode with ASM

**Files:**
- Create: `labs/pom.xml`
- Create: `labs/src/main/java/org/javaguy/labs/asm/GeneratedGreeter.java`
- Create: `lessons/part-1-bytecode/06-generating-bytecode-with-asm.md`

**Interfaces:**
- Consumes: Task 5's stack model, Task 7's bootstrap concept.
- Produces: `labs/pom.xml` — the parent POM later plans add JOL (lesson 13) and JMH (lesson 22) dependencies to; package convention `org.javaguy.labs.<topic>`.

- [ ] **Step 1: Create `labs/pom.xml`**

`groupId` `org.javaguy`, `artifactId` `labs`, Java 25 (`maven.compiler.release` 25), single dependency `org.ow2.asm:asm:9.8`, `exec-maven-plugin` with `mainClass` `org.javaguy.labs.asm.GeneratedGreeter`.

- [ ] **Step 2: Write and run the ASM sample**

`GeneratedGreeter.java`: uses `ClassWriter` to emit a class `Greet` with a `public static void main`-equivalent method printing a fixed string; writes `Greet.class` to disk, then loads and invokes it via `MethodHandles.lookup().defineClass(bytes)`. Expected console output: the greeting printed by the generated class.

- [ ] **Step 3: Verify the build end-to-end**

Run: `cd labs && mvn -q compile exec:java`
Expected: exit 0, the greeting line in stdout, `Greet.class` written.

- [ ] **Step 4: Write the lesson**

Outcomes: emitting a class with ASM; connecting each `visitXxx` call to the class-file sections from lesson 02 and bytecode from lessons 03–04; loading generated bytes (`MethodHandles.Lookup#defineClass`). The lesson walks the reader through running it in `labs/` — the one place Maven appears in Parts 0–1.

- [ ] **Step 5: Commit**

```bash
git add labs/ lessons/part-1-bytecode/06-generating-bytecode-with-asm.md
git commit -m "feat(labs): add labs module with ASM bytecode-generation demo and lesson 06"
```

### Task 9: Wrap-up — index links, badges, full re-verification

**Files:**
- Modify: `lessons/README.md` (flip lessons 00–06 from "coming soon" to live links)
- Modify: `README.md` (lessons badge count 7, per-part tables already filled from Task 1)

**Interfaces:**
- Consumes: all previous tasks' lesson files and index structure.
- Produces: a self-consistent, fully linked Parts 0–1 course increment.

- [ ] **Step 1: Re-run every sample command in lessons 00–06 verbatim** from a clean shell; fix any drift found.

- [ ] **Step 2: Update links and badges** so every written lesson is linked and counts are accurate.

- [ ] **Step 3: Verify links**

Run: `for f in lessons/part-*/*.md; do test -f "$f" || echo "MISSING: $f"; done` and confirm every `](...)` target in `lessons/README.md` resolves.

- [ ] **Step 4: Commit and push**

```bash
git add lessons/README.md README.md
git commit -m "docs(course): link lessons 00-06 in course index"
git push
```

---

## Self-Review Notes

- **Spec coverage:** Part 0 (§3, lessons 00–01) → Tasks 2–3; Part 1 (lessons 02–06) → Tasks 4–8; lesson format (§4) → Global Constraints + every lesson task; samples & verification (§5) → verification steps in every task; repo layout (§6) → Tasks 1, 8; open question #1 (ASM placement) → resolved: ASM in `labs/` (Task 8). Parts 2–6 and README-remaining items are deferred to their own plans per the spec's build strategy.
- **Open question #2 (docs/ deep dives):** none needed for Parts 0–1; revisit when planning Part 3.
