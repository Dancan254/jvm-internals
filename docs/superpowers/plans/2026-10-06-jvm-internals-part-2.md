# JVM Internals — Part 2 (Classloading & Linking) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Part 2 of the course — lessons 07–11 (classloading & linking) — as a fully verified, reviewable increment on top of the merged Parts 0–1.

**Architecture:** Same content-course architecture as Parts 0–1: Markdown lessons with inline single-file Java 25 samples, all commands executed for real on JDK 25 with captured output. No new Maven modules or dependencies (custom classloaders are `java.base`). Execution uses the swarm model proven in Parts 0–1: parallel implementers write files without touching git; the controller commits per task; parallel reviewers; wrap-up task re-verifies everything.

**Tech Stack:** JDK 25 (OpenJDK/HotSpot, Zulu 25.28 installed), `javac`/`java`/`javap`/`jcmd`, no third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-10-06-jvm-internals-design.md` (section 3, Part 2 rows)

**Prior art to mirror:** merged Parts 0–1 on `main` — lesson skeleton, tone, quiz format, brand mermaid colors. Read `lessons/part-1-bytecode/05-invokedynamic.md` as the style reference.

## Global Constraints

- JDK 25, OpenJDK/HotSpot throughout; outputs captured from real runs on this machine's JDK only.
- Compact source files (`void main()`, `IO.println`) for general samples; explicit `javac`-compiled classes where the lesson dissects class structure or needs multiple cooperating classes (lessons 08–11 will mostly need explicit classes — classloading is invisible in compact-source plumbing).
- Every output block from a real run; non-deterministic output (classloader identity hashes, counts, timestamps) labeled as varying.
- Every lesson carries exact run commands incl. flags; flags fully explained at first use across the whole course (check lessons 00–06 before re-explaining `-verbose:class`, `-Xlog:*`, `-XX:*`), referenced afterwards.
- Lesson skeleton: What you'll learn / Why this matters / The concept / Hands-on / Try it yourself / Common mistakes / Check your understanding (click-to-reveal) / Recap.
- **Footer convention (new, from Parts 0–1 final review):** every lesson ends with a uniform `Previous · Next` footer linking the real neighbor files; `Next` on lesson 11 points to lesson 12 as plain text (unwritten).
- **Capture-directory convention (new):** commands whose output appears in lessons are run from `~/jvm-internals-samples/` (neutral path), never `/tmp/jvm-internals-task-N/`.
- **CDS ownership (new, pinned):** CDS content lives in lesson 07 — lessons 00 and 01 both promise "Lesson 07 looks inside it". Lesson 07 must deliver that promise.
- Cross-links to the concurrency repo (`https://github.com/Dancan254/concurreny-multithreading`, default branch `master` — deep links use `blob/master/`) only where topics genuinely meet.
- Commit messages: `type(scope): description`, ≤72-char subject (repo hook enforces, including merge commits).
- Forward references to unwritten lessons 12–32 stay plain text.

## Review Focus

1. **Metaspace-leak demo termination:** lesson 10's reload loop must fail fast and predictably — pin `-XX:MaxMetaspaceSize` (e.g. `-XX:MaxMetaspaceSize=64m`) so it ends with `OutOfMemoryError: Metaspace` in seconds, never hangs (test: verification run completes with the OOM, wall-clock bounded).
2. **Classloader demo state-sensitivity:** delegation caches and parent-first order make outputs depend on run order — every lesson 07–10 command must be verified from a clean directory in the order the lesson presents them (test: Task 6 re-run does exactly this).
3. **Build-sensitive counts:** `-verbose:class`/`-Xlog:class+load` class counts and CDS contents vary by JDK build — lessons label them varying and avoid asserting exact counts as fact (lesson 01 already burned us once: 2,518→2,535).
4. **JPMS command reproducibility:** lesson 11's module-path layouts (`--module-path`, `--module-source-path`) must be copy-paste reproducible from the lesson text alone — directory tree shown before commands (test: Task 6 replays from the tree alone).
5. **Promise delivery:** lesson 06 forward-references lesson 09 (custom `ClassLoader`, `defineClass`); lessons 00/01 promise CDS in lesson 07 — both must visibly deliver (test: Task 6 greps the promises against the shipped content).

---

### Task 1: Lesson 07 — The delegation model (+ CDS)

**Files:**
- Create: `lessons/part-2-classloading/07-the-delegation-model.md`

**Interfaces:**
- Consumes: lesson 01's `-verbose:class` teaser; lesson 00/01's CDS promises.
- Produces: classloader vocabulary consumed by lessons 08–11 — bootstrap/platform/application loaders, parent-first delegation, *defining* vs *initiating* loader, class namespace (`(name, defining loader)` identity).

- [ ] **Step 1: Write and run the samples**

`WhoLoadsWhat.java` — prints `String.class.getClassLoader()`, its own class loader, and the platform loader via `ClassLoader.getPlatformClassLoader()`, walking the parent chain. Run with `-verbose:class` and capture: who loads the app class (`app`), who loads `java.base` classes (`bootstrap`), and the JDK 9+ three-loader reality (no more extension classloader). CDS: `jcmd <pid> VM.cds` (or `-Xlog:cds` at startup) showing the shared archive; `-Xshare:off` comparison showing the class count/startup difference. All counts labeled varying.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): bootstrap/platform/application classloaders post-JDK 9; `-verbose:class` reading; parent-first delegation and why it exists (security + consistency); the CDS archive delivered as promised by lessons 00/01.

- [ ] **Step 3: Verify every command verbatim** from a clean `~/jvm-internals-samples/` dir, in lesson order.

- [ ] **Step 4: Commit**

`git add lessons/part-2-classloading/07-the-delegation-model.md && git commit -m "feat(lessons): add lesson 07, the delegation model"`

### Task 2: Lesson 08 — Loading, linking, initialization

**Files:**
- Create: `lessons/part-2-classloading/08-loading-linking-initialization.md`

**Interfaces:**
- Consumes: Task 1's loader vocabulary; lesson 02's class-file anatomy (verification reads the same structures).
- Produces: the exact initialization-trigger list and `<clinit>` semantics referenced by later GC/JIT lessons.

- [ ] **Step 1: Write and run the samples**

`LazyInit.java` — a class with a `<clinit>` that prints, demonstrating: accessing a `static final` constant does NOT trigger initialization (constant inlined at compile time — show with `javap -c` on the caller), first active use does (new instance, static method, static field, reflection via `Class.forName` vs lazy `ClassLoader.loadClass` / `.class` literal). Run variants and capture the ordering differences. Linking demo: a deliberately corrupted class file (flip a byte) → `VerifyError` at link time, captured.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): the three phases and what each does; verification/preparation/resolution; the six active-use triggers (JLS §12.4.1) demonstrated, not just listed; `<clinit>` vs `<init>`; why constants don't trigger.

- [ ] **Step 3: Verify every command verbatim** (clean dir, lesson order).

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 08, loading linking initialization"` (after `git add`)

### Task 3: Lesson 09 — Custom classloaders

**Files:**
- Create: `lessons/part-2-classloading/09-custom-classloaders.md`

**Interfaces:**
- Consumes: Task 1's namespace/identity model; lesson 06's `defineClass` forward-reference (deliver it).
- Produces: custom-`ClassLoader` mechanics (`findClass`, `defineClass`, parent vs child-first) consumed by Task 4's leak demo.

- [ ] **Step 1: Write and run the samples**

Two cooperating explicit classes: `Greeter.java` (compiled separately, its `.class` read as bytes) and `ByteLoader.java` — a `ClassLoader` subclass that loads `Greeter` from the byte array via `defineClass`, instantiates it reflectively, and invokes a method. Then the identity demo: load `Greeter` through TWO different custom-loader instances and through the app loader; `instanceof` across the namespaces → the "same class" `ClassCastException` (or `false instanceof`), captured with the loaders' identity hashes labeled varying. Show `child != parent` delegation by overriding `loadClass` (child-first) and observing the split.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): loading a class from raw bytes; namespaces and class identity `(name, defining loader)`; the classic "same class" `ClassCastException`; when you'd write one (plugins, isolation, hot-reload) with real-world anchors (app servers, agents — forward point to lesson 30).

- [ ] **Step 3: Verify every command verbatim** (clean dir, lesson order).

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 09, custom classloaders"` (after `git add`)

### Task 4: Lesson 10 — Classloader leaks

**Files:**
- Create: `lessons/part-2-classloading/10-classloader-leaks.md`

**Interfaces:**
- Consumes: Task 3's custom-loader mechanics; Task 1's namespace model.
- Produces: the "a class is only collectible with its loader" rule referenced by Metaspace content in lesson 12.

- [ ] **Step 1: Write and run the samples**

`LeakLoop.java` — a loop that repeatedly creates a NEW custom loader instance (Task 9 mechanics), loads a fresh class copy through it, and discards the reference — with one deliberate pin (e.g. a `ThreadLocal` or a static reference held from the app-loader world) so the old loaders can't be collected. Run with `-XX:MaxMetaspaceSize=64m` (explained at first use) and capture the bounded failure: `OutOfMemoryError: Metaspace`. Then the fixed variant (unpinned) running to completion with `-Xlog:class+unload` showing unload events. Must terminate in seconds, never hang.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): why a class can't be collected while its loader lives; the reload-in-a-loop leak; classic production anchors (redeploy leaks, `ThreadLocal` pins); diagnosis via `-Xlog:class+load,class+unload` and `jcmd GC.class_histogram`.

- [ ] **Step 3: Verify every command verbatim** — including confirming the OOM is the Metaspace flavor and the run is time-bounded.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 10, classloader leaks"` (after `git add`)

### Task 5: Lesson 11 — Modules & classloading

**Files:**
- Create: `lessons/part-2-classloading/11-modules-and-classloading.md`

**Interfaces:**
- Consumes: Task 1's three-loader model.
- Produces: nothing downstream in Parts 3–6 depends on JPMS mechanics; keep scope tight.

- [ ] **Step 1: Write and run the samples**

A two-module layout (`module-info.java` in each): `app` requires `utils`; show the directory tree, then `javac --module-source-path` / `java --module-path` commands verbatim. Capture: a denied access (non-exported package → `IllegalAccessError` at runtime / compile error), and `-verbose:class` (or `--show-module-resolution`) output showing module-aware loading. All from a clean tree shown in the lesson.

- [ ] **Step 2: Write the lesson**

Outcomes (spec): what JPMS changed (readability, exports, encapsulation of JDK internals) and what it did NOT change (the three loaders still exist; delegation still parent-first); why `IllegalAccessError` on JDK internals became the modern `ClassCastException`-era confusion.

- [ ] **Step 3: Verify every command verbatim** from the printed tree alone, in a clean dir.

- [ ] **Step 4: Commit**

`git commit -m "feat(lessons): add lesson 11, modules and classloading"` (after `git add`)

### Task 6: Wrap-up — index, footers, full re-verification

**Files:**
- Modify: `lessons/README.md` (lessons 07–11 → live links)
- Modify: `README.md` (badge `lessons-12_of_33`, Part 2 table filled)
- Modify: `lessons/part-0-the-machine/*.md`, `lessons/part-1-bytecode/*.md` (retrofit uniform `Previous · Next` footers onto lessons 00–06)
- Modify: the five new lesson files (footer conformity)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a coherent 12-lesson course increment ready for final review and merge.

- [ ] **Step 1: Re-run every reader-facing command in lessons 07–11 verbatim** from clean `~/jvm-internals-samples/` dirs, in lesson order; confirm lesson 10's OOM flavor and bounded runtime; fix any drift with real recaptured output.

- [ ] **Step 2: Promise delivery check** — grep lessons 00/01's CDS promise and lesson 06's custom-loader forward-reference against the shipped 07/09 content; confirm both deliver.

- [ ] **Step 3: Footers** — uniform `Previous · Next` on all twelve lessons (07+ targets plain text where unwritten).

- [ ] **Step 4: Index and badge** — 07–11 live in `lessons/README.md`; README badge `lessons-12_of_33`; Part 2 table filled.

- [ ] **Step 5: Link verification** — every relative `](...)` target in all course markdown resolves.

- [ ] **Step 6: Commit** (no push — controller publishes)

`git commit -m "docs(course): link lessons 07-11, uniform footers, part 2 wrap-up"` (after `git add`)

---

## Self-Review Notes

- **Spec coverage:** Part 2 rows (spec §3, lessons 07–11) → Tasks 1–5, outcomes copied per lesson; lesson format (§4) → constraints; verification (§5) → per-task Step 3 + Task 6; build strategy (§8) → this plan is the Part 2 increment.
- **Final-review recommendations carried:** CDS pinned to 07 (Task 1 + Review Focus 5), uniform footers (constraint + Task 6 Step 3, including retrofit), neutral capture dir (constraint).
- **No new dependencies:** lessons 07–11 need nothing beyond `java.base` + JDK tools — `labs/` untouched.
- **Execution method:** swarm (established in Parts 0–1): parallel implementers Tasks 1–5 (disjoint files), controller commits, parallel reviewers, then Task 6, then final whole-branch review.
