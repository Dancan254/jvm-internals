# Lesson 02: Anatomy of a `.class` file

## What you'll learn

- The exact binary layout of a `.class` file, section by section
- The `CAFEBABE` magic number and the class-file version (major 69 = JDK 25)
- The constant pool: what it stores, and how bytecode refers to it (`CONSTANT_Methodref`, `CONSTANT_Fieldref`, `CONSTANT_String`, …)
- Access flags: what `ACC_PUBLIC`, `ACC_FINAL` and the odd one, `ACC_SUPER`, actually mean

---

## Why this matters

Every `.java` file you ever wrote ends up as one of these: a binary file the JVM loads, verifies and executes. Most days you never look at it. Then one day production throws `java.lang.UnsupportedClassVersionError: com/acme/Foo has been compiled by a more recent version of the Java Runtime (class file version 65.0)` — and that number, `65.0`, is a field in the first 8 bytes of the file. Or a native tool corrupts a jar and the JVM rejects it before your `main` ever runs, because the magic number is wrong. Or you read a bytecode-level bug report and it talks about "constant pool entry #23".

This lesson makes the `.class` file stop being opaque. Everything else in Part 1 — the operand stack (Lesson 03), the invocation opcodes (Lesson 04), `invokedynamic` (Lesson 05) — is written in the vocabulary you build here.

---

## The concept

### What a `.class` file is

`javac` translates Java source into the **class-file format**, a binary format specified byte-for-byte in the Java Virtual Machine Specification (JVMS §4). It is not tied to Java the language — Kotlin, Scala, Groovy and Clojure compilers all emit the same format — and it is not tied to a particular operating system or CPU. That is the whole point of the JVM: one binary format, every platform.

The layout is fixed, in this exact order:

```mermaid
flowchart LR
    A["magic<br/>4 bytes"] --> B["version<br/>4 bytes"]
    B --> C["constant pool"]
    C --> D["access flags<br/>2 bytes"]
    D --> E["this_class /<br/>super_class"]
    E --> F["interfaces"]
    F --> G["fields"]
    G --> H["methods"]
    H --> I["attributes"]
    style A fill:#f0196a,stroke:#f0196a,color:#fff
    style B fill:#f0196a,stroke:#f0196a,color:#fff
    style C fill:#12121f,stroke:#f0196a,color:#fff
    style D fill:#12121f,stroke:#f0196a,color:#fff
    style E fill:#12121f,stroke:#f0196a,color:#fff
    style F fill:#12121f,stroke:#f0196a,color:#fff
    style G fill:#12121f,stroke:#f0196a,color:#fff
    style H fill:#12121f,stroke:#f0196a,color:#fff
    style I fill:#12121f,stroke:#f0196a,color:#fff
```

No padding, no alignment, no optional reordering. The JVM reads it front to back, and the first thing it checks is the magic number.

### The magic number: `CAFEBABE`

Every `.class` file starts with the four bytes `0xCAFEBABE`. It identifies the file type, the same way a PNG starts with `0x89 'P' 'N' 'G'` or a zip (and therefore a jar) starts with `'P' 'K'`. If those four bytes are anything else, the JVM refuses to load the file, full stop. It is a pure magic number — it encodes no information, it is just a signature the JVM's authors picked in the 1990s. (`0xCAFEBABE` is a valid hex number that happens to spell something; the cafe theme fit the coffee brand.)

### The version: minor and major

The next four bytes are two unsigned 16-bit numbers: `minor_version` then `major_version`. The major version records which Java release the class was compiled for. JDK 25 emits **major version 69**.

The numbering is sequential from Java 1.1 (major 45), increasing by one per release:

| Major | Java release |
|---:|:---|
| 52 | 8 |
| 55 | 11 |
| 61 | 17 |
| 65 | 21 |
| 67 | 23 |
| 69 | 25 |

Shortcut for anything from Java 1.2 onwards: **major = release + 44** (25 + 44 = 69).

The rule that bites in production: **a JVM loads class files up to its own major version, and no higher.** A JDK 21 runtime (major 65) cannot load a class compiled for JDK 25 (major 69) — that is the `UnsupportedClassVersionError` above, and the `65.0` in the message is exactly this field. Newer JVMs happily load older class files, which is why a library compiled for Java 8 still runs everywhere.

### The constant pool: the class's symbol table

The largest section is the **constant pool**, a table of every name the class refers to: its own name, its superclass, every field and method it touches, every string literal, every type descriptor. Bytecode never embeds names directly — it embeds **indexes into this table**. When you later see an instruction like `invokevirtual #21`, the `#21` is a constant-pool slot.

Each entry starts with a one-byte tag saying what kind of entry it is, followed by tag-specific data. The types you will see constantly in Part 1:

| Constant type | What it holds |
|---|---|
| `CONSTANT_Utf8` | A raw string of text: a name, a descriptor, a literal's characters. The leaf everything else points to. |
| `CONSTANT_Class` | A class or interface, as a pointer to a `CONSTANT_Utf8` holding its internal name (`java/lang/Object`). |
| `CONSTANT_String` | A string literal, as a pointer to a `CONSTANT_Utf8` holding the characters. |
| `CONSTANT_NameAndType` | A name + descriptor pair (`count:I`, `println:(Ljava/lang/String;)V`). Never referenced by bytecode directly — it is a building block. |
| `CONSTANT_Fieldref` | A field: a `CONSTANT_Class` + a `CONSTANT_NameAndType`. |
| `CONSTANT_Methodref` | A method on a class: a `CONSTANT_Class` + a `CONSTANT_NameAndType`. |
| `CONSTANT_InterfaceMethodref` | Same, for an interface method. |
| `CONSTANT_Integer` / `Float` / `Long` / `Double` | Numeric literals. |
| `CONSTANT_MethodHandle`, `CONSTANT_MethodType`, `CONSTANT_InvokeDynamic` | The machinery of `invokedynamic` — Lesson 05's topic. |

Two consequences worth internalizing now:

1. **The pool is a graph of indirections.** A `Methodref` doesn't contain the method's name — it contains two indexes, which lead to two more indexes, which finally lead to `Utf8` text. `javap` helpfully flattens this chain into a comment for you.
2. **Bytecode is compact because of this table.** `invokevirtual #21` is 3 bytes no matter how long the method's name is, and each distinct name is stored once, not once per call site.

(One spec quirk for completeness: `long` and `double` literals occupy *two* pool slots, which is why constant-pool indexes in the wild sometimes skip a number. You won't hit that in this lesson's tiny class.)

### Access flags

After the constant pool come two bytes of class-level **access flags**: a bit mask. The bits defined by the JVMS:

| Flag | Hex | Meaning |
|---|---|---|
| `ACC_PUBLIC` | `0x0001` | Declared `public` |
| `ACC_FINAL` | `0x0010` | Declared `final` — no subclasses |
| `ACC_SUPER` | `0x0020` | See below |
| `ACC_INTERFACE` | `0x0200` | This is an interface, not a class |
| `ACC_ABSTRACT` | `0x0400` | Declared `abstract` |
| `ACC_SYNTHETIC` | `0x1000` | Compiler-generated, not in source |
| `ACC_ANNOTATION` | `0x2000` | This is an annotation type |
| `ACC_ENUM` | `0x4000` | This is an `enum` |
| `ACC_MODULE` | `0x8000` | This is `module-info.class` |

`ACC_SUPER` is the historical curiosity. In Java 1.0, `invokespecial` had a quirky semantics for calling superclass methods; `ACC_SUPER` (added in 1.0.2) tells the JVM to use the corrected semantics. Every class compiled by any remotely modern `javac` sets it, so you will see it on every class file you ever dump — but the bit still gets checked on load, because the JVM must know which of the two 1990s semantics to apply. It is cargo from 1995 that the format still carries.

So `public class Greeting` gets flags `0x0021` = `ACC_PUBLIC | ACC_SUPER`.

Right after the flags come `this_class` and `super_class` (two constant-pool indexes naming this class and its direct superclass), and then the counts and tables for interfaces, fields, methods and attributes — all of which you will now see for real.

---

## Hands-on

A note on the convention before we start: Part 1 (Lessons 02–06) uses **explicitly declared classes compiled with `javac`**, not the compact source files (`void main()`, `java File.java`) the rest of the course uses. Compact source files hide the class declaration — and the class declaration is exactly what we are dissecting. We need a real `Greeting.class` on disk, produced by a `javac` we control, so we can open it up.

### 1. A tiny class, compiled the explicit way

`Greeting.java` — one constant string, one field, one method of our own, plus `main` so we can run it:

```java
public class Greeting {
    private static final String MESSAGE = "Hello, bytecode!";

    private int count = 0;

    public void greet() {
        count++;
        System.out.println(MESSAGE);
    }

    public static void main(String[] args) {
        new Greeting().greet();
    }
}
```

Compile and run:

```bash
javac Greeting.java
java Greeting
```

```
Hello, bytecode!
```

`javac Greeting.java` produces `Greeting.class` — 618 bytes on this machine. That file is the subject of the rest of the lesson.

### 2. The first bytes: magic and version

```bash
xxd Greeting.class | head -4
```

`xxd` dumps a file as hex, 16 bytes per line, with the printable characters on the right. We only need the first few lines:

```
00000000: cafe babe 0000 0045 0028 0a00 0200 0307  .......E.(......
00000010: 0004 0c00 0500 0601 0010 6a61 7661 2f6c  ..........java/l
00000020: 616e 672f 4f62 6a65 6374 0100 063c 696e  ang/Object...<in
00000030: 6974 3e01 0003 2829 5609 0008 0009 0700  it>...()V.......
```

Reading the first line against the layout diagram:

| Bytes | Hex | Meaning |
|---|---|---|
| 0–3 | `cafe babe` | Magic number — this is a `.class` file |
| 4–5 | `0000` | Minor version: 0 |
| 6–7 | `0045` | Major version: `0x45` = **69** = compiled for JDK 25 |
| 8–9 | `0028` | Constant-pool count: `0x28` = 40, meaning 39 entries (the count is one more than the last valid index — index 0 is reserved) |
| 10 | `0a` | First pool entry's tag: `0x0A` = 10 = `CONSTANT_Methodref` |

You can even see pool contents leaking into the printable column: `java/lang/Object`, `<init>`, `()V` — those are `Utf8` entries, stored as plain text inside the binary.

### 3. The full disassembly: `javap -v`

Lesson 00 introduced `javap`, the JDK's bundled class-file disassembler. Here we use its verbose mode: `javap -v Greeting` prints every section of the class file in readable form. Full output, captured from the run above (the file path, modification time and checksum in the header will differ on your machine):

```
Classfile /tmp/jvm-internals-task-4/Greeting.class
  Last modified Oct 6, 2026; size 618 bytes
  SHA-256 checksum 1540b6ad0e2280989ad609b4f69cce4ed4d19065af8ac15e8b1763987575eb52
  Compiled from "Greeting.java"
public class Greeting
  minor version: 0
  major version: 69
  flags: (0x0021) ACC_PUBLIC, ACC_SUPER
  this_class: #8                          // Greeting
  super_class: #2                         // java/lang/Object
  interfaces: 0, fields: 2, methods: 3, attributes: 1
Constant pool:
   #1 = Methodref          #2.#3          // java/lang/Object."<init>":()V
   #2 = Class              #4             // java/lang/Object
   #3 = NameAndType        #5:#6          // "<init>":()V
   #4 = Utf8               java/lang/Object
   #5 = Utf8               <init>
   #6 = Utf8               ()V
   #7 = Fieldref           #8.#9          // Greeting.count:I
   #8 = Class              #10            // Greeting
   #9 = NameAndType        #11:#12        // count:I
  #10 = Utf8               Greeting
  #11 = Utf8               count
  #12 = Utf8               I
  #13 = Fieldref           #14.#15        // java/lang/System.out:Ljava/io/PrintStream;
  #14 = Class              #16            // java/lang/System
  #15 = NameAndType        #17:#18        // out:Ljava/io/PrintStream;
  #16 = Utf8               java/lang/System
  #17 = Utf8               out
  #18 = Utf8               Ljava/io/PrintStream;
  #19 = String             #20            // Hello, bytecode!
  #20 = Utf8               Hello, bytecode!
  #21 = Methodref          #22.#23        // java/io/PrintStream.println:(Ljava/lang/String;)V
  #22 = Class              #24             // java/io/PrintStream
  #23 = NameAndType        #25:#26        // println:(Ljava/lang/String;)V
  #24 = Utf8               java/io/PrintStream
  #25 = Utf8               println
  #26 = Utf8               (Ljava/lang/String;)V
  #27 = Methodref          #8.#3          // Greeting."<init>":()V
  #28 = Methodref          #8.#29         // Greeting.greet:()V
  #29 = NameAndType        #30:#6         // greet:()V
  #30 = Utf8               greet
  #31 = Utf8               MESSAGE
  #32 = Utf8               Ljava/lang/String;
  #33 = Utf8               ConstantValue
  #34 = Utf8               Code
  #35 = Utf8               LineNumberTable
  #36 = Utf8               main
  #37 = Utf8               ([Ljava/lang/String;)V
  #38 = Utf8               SourceFile
  #39 = Utf8               Greeting.java
{
  public Greeting();
    descriptor: ()V
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=2, locals=1, args_size=1
         0: aload_0
         1: invokespecial #1                  // Method java/lang/Object."<init>":()V
         4: aload_0
         5: iconst_0
         6: putfield      #7                  // Field count:I
         9: return
      LineNumberTable:
        line 1: 0
        line 4: 4

  public void greet();
    descriptor: ()V
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=3, locals=1, args_size=1
         0: aload_0
         1: dup
         2: getfield      #7                  // Field count:I
         5: iconst_1
         6: iadd
         7: putfield      #7                  // Field count:I
        10: getstatic     #13                 // Field java/lang/System.out:Ljava/io/PrintStream;
        13: ldc           #19                 // String Hello, bytecode!
        15: invokevirtual #21                 // Method java/io/PrintStream.println:(Ljava/lang/String;)V
        18: return
      LineNumberTable:
        line 7: 0
        line 8: 10
        line 9: 18

  public static void main(java.lang.String[]);
    descriptor: ([Ljava/lang/String;)V
    flags: (0x0009) ACC_PUBLIC, ACC_STATIC
    Code:
      stack=2, locals=1, args_size=1
         0: new           #8                  // class Greeting
         3: dup
         4: invokespecial #27                 // Method "<init>":()V
         7: invokevirtual #28                 // Method greet:()V
        10: return
      LineNumberTable:
        line 12: 0
        line 13: 10
}
SourceFile: "Greeting.java"
```

That is the entire class file, decoded. Now walk it section by section.

**The header** — `minor version: 0`, `major version: 69`: the same bytes we read by hand in the hex dump (`0000 0045`). `flags: (0x0021) ACC_PUBLIC, ACC_SUPER`: the two-byte mask `0x0021`, decoded into its two set bits, exactly as the flags table above predicts for a `public` class.

**`this_class: #8` / `super_class: #2`** — two indexes into the constant pool. Follow them: `#8 = Class #10`, and `#10 = Utf8 Greeting`. The class names itself through the pool. Nothing is stored inline, ever.

**`interfaces: 0, fields: 2, methods: 3`** — counts of the tables that follow. Two fields (`MESSAGE`, `count`). *Three* methods, even though we wrote two: `javac` generated the no-arg constructor `public Greeting()` for us.

**The constant pool** — 39 entries, matching the `0x0028` count from the hex dump. Trace three of them to see the indirection pattern:

- **`#1 = Methodref #2.#3`** — the call to the `Object` constructor. `#2` is `Class #4`, and `#4 = Utf8 java/lang/Object`. `#3` is `NameAndType #5:#6`, where `#5 = Utf8 <init>` and `#6 = Utf8 ()V`. Four hops: Methodref → Class + NameAndType → Utf8 + Utf8. `javap` flattens it into the comment `// java/lang/Object."<init>":()V`. `<init>` is the JVM's name for a constructor — not a legal Java identifier, which guarantees it can never collide with a method you write.
- **`#19 = String #20`** — our string literal. The `String` entry just points at `#20 = Utf8 Hello, bytecode!`. When `greet()` executes `ldc #19` (load constant), this is the slot it loads.
- **`#28 = Methodref #8.#29`** — our own `greet()`. Note `#8` again: the `Class` entry for `Greeting` is *reused*. The pool stores each distinct item once, and every reference points at the same slot.

Also notice entries like `#33 = Utf8 ConstantValue`, `#34 = Utf8 Code`, `#35 = Utf8 LineNumberTable`: even the *names of attributes* used later in the file are pooled.

**The methods** — each method gets its own `descriptor` (the JVM type signature: `()V` means "no arguments, returns void"; `I` is `int`, `Ljava/lang/String;` is a `String`), its own `flags` (`main` is `0x0009` = `ACC_PUBLIC | ACC_STATIC` = `0x0001 | 0x0008`), and a `Code` attribute holding the actual bytecode. Do not worry about reading the instructions yet — `aload_0`, `putfield`, the operand stack they operate on, and the three different `invoke*` opcodes you can already see are Lessons 03 and 04. For now, just register the pattern: every instruction that touches a name carries a pool index (`putfield #7`, `ldc #19`, `invokevirtual #21`), and `javap` resolves it in the trailing comment.

The `LineNumberTable` maps bytecode offsets back to source lines — that is how a stack trace knows your crash was on line 8.

**The trailing attribute** — `SourceFile: "Greeting.java"` records which file the class was compiled from. Also stack-trace fuel.

### 4. The private fields: `javap -p -v`

Did you notice the fields never appeared? `javap` shows only public and package-private members by default, and both of ours are `private`. Add `-p` (show **all** classes and members, including private ones; flags compose, so `-p -v` is "everything, verbosely"):

```bash
javap -p -v Greeting
```

The fields section now appears:

```
  private static final java.lang.String MESSAGE;
    descriptor: Ljava/lang/String;
    flags: (0x001a) ACC_PRIVATE, ACC_STATIC, ACC_FINAL
    ConstantValue: String Hello, bytecode!

  private int count;
    descriptor: I
    flags: (0x0002) ACC_PRIVATE
```

Two things to see here. `MESSAGE`'s flags are `0x001a` = `0x0002 | 0x0008 | 0x0010` = `ACC_PRIVATE | ACC_STATIC | ACC_FINAL` — field flags use the same bit-mask idea as the class flags. And because `MESSAGE` is a compile-time constant, `javac` stored its value directly in the field's **`ConstantValue` attribute** — a pointer back to pool entry `#19`. The field never gets "assigned" anywhere; the value is simply *there*. That is why the pool needed an entry named `ConstantValue` (`#33`).

---

## Try it yourself

1. Recompile for an older release with `javac --release 21 Greeting.java`, then run `xxd Greeting.class | head -1` and `javap -v Greeting | grep "major version"`. What major version do you get, and does it match the version table above? Try running the resulting class on a JDK 21 if you have one — and the JDK 25 build on JDK 21, to see `UnsupportedClassVersionError` with your own eyes.
2. Add a second string constant, `private static final String FAREWELL = "Goodbye!";`. Recompile and find the two new pool entries (`String` and `Utf8`). Then add a `long` constant and watch the pool indexes skip a number.
3. Make `Greeting` `final` and have it implement `java.io.Serializable`. What changes in the class `flags` and in the `interfaces` count?
4. Run `javap -v java.lang.Object` (you can pass any class on the classpath, not just local files). How big is its constant pool compared to ours? Try `javap -v java.lang.String | grep -c "= Utf8"` for a sense of scale.
5. Delete `Greeting.class`, then compile with `javac -g:none Greeting.java`. Dump again with `javap -p -v`. Which pieces of the output disappeared, and what would a stack trace from this class look like now?

---

## Common mistakes

- **"It compiled, so it runs anywhere Java runs."** A class file runs only on a JVM with major version ≥ the class's own. Compiled on 25, deployed on a 21 container: `UnsupportedClassVersionError` at startup. The fix is `javac --release N` (or Maven's `<release>`), not just "any JDK on the build machine".
- **Reading the version number as decimal in the hex dump.** `0045` in the file is hexadecimal: `0x45` = 69. A class file showing `0037` is not "version 37", it's 55 = Java 11.
- **Assuming the pool contains one entry per use.** It contains one entry per *distinct* item. Ten calls to the same method share one `Methodref` slot.
- **Trusting `javap`'s default output as complete.** Without `-p`, private members are invisible. If you're auditing a class (yours or a dependency's), always `-p -v`.
- **Thinking `.class` files are encrypted or obfuscated.** As you just saw, names, strings and structure sit in plain text. Anyone with `javap` — bundled with every JDK — can read your API surface. Obfuscation is a separate, deliberate step.

---

## Check your understanding

**1. A jar refuses to load with an error about a bad magic number. What does that tell you, and what's the first thing you'd check?**

<details>
<summary>Reveal answer</summary>

The first four bytes of some `.class` file in the jar are not `0xCAFEBABE`, which means the file is not a class file at all — the jar is corrupt, truncated (a failed download is the classic cause), or something that isn't a `.class` got packaged with that extension. Check the jar's integrity first (`unzip -t`, or re-download / rebuild it).

</details>

**2. A dependency's class file starts with bytes `cafe babe 0000 0041`. Which Java release was it compiled for, and will a JDK 17 runtime load it?**

<details>
<summary>Reveal answer</summary>

`0x0041` = 65, and 65 − 44 = 21, so it was compiled for Java 21. A JDK 17 runtime loads class files up to major version 61, so it will refuse with `UnsupportedClassVersionError` (that's the "class file version 65.0" message). You'd need to run on JDK 21+, or get a build of the dependency targeting 17.

</details>

**3. Why does the bytecode instruction `putfield #7` contain a number instead of the field's name?**

<details>
<summary>Reveal answer</summary>

`#7` is an index into the constant pool, where the field's class, name and descriptor are stored exactly once (as a `Fieldref` → `Class` + `NameAndType` → `Utf8` chain). Every instruction that refers to that field reuses the same two-byte index, so bytecode stays compact and each distinct name is stored a single time in the file.

</details>

**4. `javap -v` on a class shows `flags: (0x0621) ACC_PUBLIC, ACC_SUPER, ACC_INTERFACE, ACC_ABSTRACT`. What kind of type is this, and why are two of those flags always seen together?**

<details>
<summary>Reveal answer</summary>

It's an interface. `ACC_INTERFACE` (`0x0200`) marks it, and every interface is implicitly `abstract` — it cannot be instantiated — so `ACC_ABSTRACT` (`0x0400`) is always set alongside it. (`0x0621` = `0x0001 | 0x0020 | 0x0200 | 0x0400`.)

</details>

**5. You wrote two methods in `Greeting.java`, but `javap` reports `methods: 3`. Where did the third come from, and what is it called in the constant pool?**

<details>
<summary>Reveal answer</summary>

`javac` generated the no-argument constructor because we declared none. In the class file a constructor is a method named `<init>` — a name that is not a valid Java identifier, so it can never clash with anything you write. (Instance initializers and field initializers like `count = 0` get folded into it too, which is why you saw `putfield #7` inside `Greeting()`.)

</details>

---

## Recap

- A `.class` file is a strictly ordered binary format: magic, version, constant pool, flags, this/super, interfaces, fields, methods, attributes.
- `0xCAFEBABE` identifies the file type; major version **69** means compiled for JDK 25 (major = release + 44). A JVM loads class files up to its own version, never newer — that's `UnsupportedClassVersionError`.
- The constant pool is the class's symbol table: every name lives there once, and bytecode refers to it by index. `Methodref`/`Fieldref` = `Class` + `NameAndType` → `Utf8` leaves.
- Access flags are a bit mask; `ACC_SUPER` is a 1995 compatibility flag every modern class sets.
- `javap -v` decodes the whole file; add `-p` or private members stay hidden.
- Constructors are methods named `<init>`, generated by `javac` when you don't write one.

**Next: Lesson 03 — The operand stack**, where we stop looking at the file's plumbing and start reading the instructions themselves: `aload_0`, `dup`, `iadd`, and what `stack=3, locals=1` really mean.
