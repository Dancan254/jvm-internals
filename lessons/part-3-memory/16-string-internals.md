# Lesson 16: String internals

## What you'll learn

- What a `String` physically is on JDK 25: a 24-byte shell pointing at a `byte[]`, with a one-byte `coder` choosing between Latin-1 and UTF-16 storage (JEP 254)
- The string pool: one canonical instance per distinct content, held in a hash table **on the heap** — and `intern()`'s exact semantics
- What interning actually costs per string, and why interning unbounded input is a memory-shaped denial of service
- Why `+` in a loop is still quadratic even after JEP 280 — the memory view that completes [Lesson 05](../part-1-bytecode/05-invokedynamic.md)'s bytecode view

---

## Why this matters

Open a heap histogram of almost any real service — `jcmd <pid> GC.class_histogram`, the command from [Lesson 00](../part-0-the-machine/00-setup-and-toolchain.md) — and the top rows are nearly always the same two: `java.lang.String` and `[B` (byte arrays). Those two rows are one thing: a `String` *is* a byte array plus a small shell. JSON payloads, log lines, SQL, URLs, headers — enterprise Java moves text, and text is bytes on the heap. Understanding what a string costs is understanding the largest single line item in most heaps.

Two production stories recur around this. First: a team discovers `String.intern()` and uses it as a free deduplication cache for values read from requests — and the heap grows without bound, because the pool they are filling is global and every distinct attacker-supplied string stays until GC proves it dead. Second: someone builds a payload with `+=` in a loop, the young-generation collector starts sprinting, and latency charts go spiky — even though "concatenation is fast since JDK 9."

And then there is the folklore. Ask a room of senior engineers where the string pool lives and someone will say "PermGen." That has been wrong since JDK 7, and today you will prove it wrong with a histogram. Strings are also the place where three earlier lessons converge: the object headers from [Lesson 13](13-object-layout-jol.md), the compressed references from [Lesson 14](14-compressed-oops.md), and the indified concatenation from [Lesson 05](../part-1-bytecode/05-invokedynamic.md).

---

## The concept

### What a String is on JDK 25

Forget `char[]`. Since JDK 9 (JEP 254, *Compact Strings*), a `String` is four instance fields:

| Field | Type | Purpose |
|---|---|---|
| `value` | `byte[]` | the actual characters — one byte per char in Latin-1, two in UTF-16 |
| `coder` | `byte` | which encoding `value` holds: `0` = Latin-1, `1` = UTF-16 |
| `hash` | `int` | the cached `hashCode()` — computed once, kept |
| `hashIsZero` | `boolean` | distinguishes "hash not computed yet" from "hash computed and genuinely 0" |

The insight behind JEP 254 is that most strings in real applications fit in ISO-8859-1/Latin-1 — one byte per character. Storing those as UTF-16 `char`s wastes exactly half the array. So the JDK stores Latin-1 content one byte per char and pays the two-byte price only when a string actually contains a character outside Latin-1. The `coder` byte records which representation a given instance uses; every `String` method is written twice internally, once per coder.

Point [Lesson 13](13-object-layout-jol.md)'s JOL tour at a `String` and the layout is concrete:

```
java.lang.String object internals:
OFF  SZ      TYPE DESCRIPTION               VALUE
  0   8           (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4           (object header: class)    0x00176040
 12   4       int String.hash               0
 16   1      byte String.coder              0
 17   1   boolean String.hashIsZero         false
 18   2           (alignment/padding gap)   
 20   4    byte[] String.value              [104, 101, 108, 108, 111, 32, 106, 118, 109, 32, 105, 110, 116, 101, 114, 110, 97, 108, 115, 33]
Instance size: 24 bytes
Space losses: 2 bytes internal + 0 bytes external = 2 bytes total
```

*(Header values vary; the offsets and sizes do not.)* Read it with [Lesson 13](13-object-layout-jol.md)'s vocabulary: 8-byte mark word, 4-byte compressed klass word, then the fields. Note two things. First, the JVM **reordered the fields**: declared order is `value, coder, hash, hashIsZero`, but the layout is `hash` at offset 12, then `coder` and `hashIsZero` packed into two bytes, then the `value` reference at offset 20 — HotSpot packs fields by size to minimize padding, exactly the "field order matters" point from lesson 13, applied by the JVM itself. Second, `value` at offset 20 is 4 bytes, not 8 — a compressed oop, [Lesson 14](14-compressed-oops.md)'s subject, pointing at the `byte[]` elsewhere on the heap. The shell is **24 bytes**; the characters are a second object.

The `byte[]` has its own header — mark word, klass word, plus a 4-byte array length — 16 bytes before the first element, then the data, then alignment to an 8-byte boundary:

```
[B object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4        (object header: class)    0x00173070
 12   4        (array length)            20
 16  20   byte [B.<elements>             N/A
 36   4        (object alignment gap)    
Instance size: 40 bytes
```

So the full cost of a 20-character string like `"hello jvm internals!"`:

- **Latin-1 (coder 0):** 24-byte shell + `byte[20]` (16 + 20 = 36, aligned up to 40) = **64 bytes**
- **UTF-16 (coder 1):** 24-byte shell + `byte[40]` (16 + 40 = 56) = **80 bytes**

One character outside Latin-1 — a snowman, an emoji, a CJK character — and the *whole string* pays double for its array. There is no per-character mixing: the coder is per-string.

The `hash`/`hashIsZero` pair is why strings make good map keys: `hashCode()` runs once, and every later lookup reads the cached field. That caching is only safe because `value` never changes after construction — immutability is load-bearing here. It is also what makes the sharing you are about to see safe across threads without any synchronization; the concurrency course's lesson on [immutability and safe publication](https://github.com/Dancan254/concurreny-multithreading/blob/master/lessons/part-3-java-util-concurrent/16-immutability-threadlocal-scopedvalue.md) is the JMM side of that story.

### The string pool

Every string literal in your code — every `"..."` in a `.java` file, plus every compile-time constant expression — is **interned**: the runtime keeps exactly one canonical `String` instance per distinct content, and every reference to that literal resolves to that one instance. The structure behind it is the **String Table**: a hash table mapping content to a weakly-held canonical instance.

Two facts kill the folklore:

1. **The pool is on the heap, and has been since JDK 7.** In the PermGen era the pool lived in permanent generation, where interned strings were effectively uncollectable — that is the origin of every "interning leaks PermGen" war story. JDK 7 moved the pool to the regular heap; JDK 8 deleted PermGen entirely. Interned strings today are ordinary heap objects: when nothing references one, the GC reclaims it, and the table's weak entry is cleaned. You will watch this in a histogram below.
2. **The table itself is native bookkeeping pointing at heap objects.** The buckets and entries are off-heap structure (lesson 17 is about that world), but the `String` objects they point at — the *literals* — are heap allocations like any other.

`String.intern()` is the manual door into the pool, with two cases:

- **Content already pooled:** returns the existing canonical instance — *not* the object you called it on.
- **Content not yet pooled:** records **your object itself** as the canonical instance and returns it. Since JDK 7 there is no copy into a special area — the table stores a reference to the heap object you handed it.

That second case is the subtle one, and the hands-on demo nails it down with `==`.

### What interning costs

The pool is not free storage. Each distinct interned string costs:

- the `String` shell (24 bytes) and its `byte[]` — unavoidable, you had those anyway;
- a **16-byte table entry**;
- a share of the **bucket array**, which starts at 65,536 buckets (512 KB) and **grows** — concurrently, at runtime — when the average chain length gets too long. Bucket memory never shrinks back.

Intern a million distinct strings and you have added a million entries plus grown the bucket array, on top of the strings themselves. And here is the security shape: the pool is **global to the JVM**, keyed by content you may not control. If you intern strings derived from request data, an attacker who can vary an input field can mint unlimited distinct pool entries — heap growth plus permanent bucket-array growth plus longer collision chains slowing every intern and every literal resolution JVM-wide. The rule is short: **never `intern()` data you don't control.** If you need deduplication, use a bounded cache you own, with an eviction policy.

### Why `+` in a loop is still quadratic

[Lesson 05](../part-1-bytecode/05-invokedynamic.md) showed that since JDK 9, `javac` compiles `"a" + x` into an `invokedynamic` bootstrapped by `StringConcatFactory.makeConcatWithConstants` — sized exactly, no intermediate `StringBuilder`, fast *per call*. All true. But look at what one iteration of `s += "-payload"` must do **in memory**: strings are immutable, so producing the new `s` means allocating a fresh `byte[]` long enough for *everything so far* and copying the old content into it. Iteration *k* copies *k* chunks; over *N* iterations that is O(N²) bytes allocated and copied, and N−1 dead strings for the GC. JEP 280 made each step cheaper; it cannot change the arithmetic of the loop. The fix is still `StringBuilder` — one growable buffer, one final copy. The demo below measures both.

---

## Hands-on

All samples compile with `javac` and run on the course JDK. Work in `~/jvm-internals-samples/lesson-16/`.

### 1. The fields and the coder

`StringAnatomy.java` — reflection to see the four fields, then read `coder` and the real `value` length for a Latin-1 string and a UTF-16 string of the same logical length:

```java
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class StringAnatomy {

    public static void main(String[] args) throws Exception {
        System.out.println("Instance fields of java.lang.String:");
        for (Field f : String.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                System.out.printf("  %-8s %s%n", f.getType().getSimpleName(), f.getName());
            }
        }

        Field value = String.class.getDeclaredField("value");
        value.setAccessible(true);
        Field coder = String.class.getDeclaredField("coder");
        coder.setAccessible(true);

        // Same logical length: 20 characters each
        String latin1 = "hello jvm internals!";
        String utf16  = "hello jvm internals☃"; // ends in a snowman

        System.out.println();
        report(latin1, value, coder);
        report(utf16, value, coder);
    }

    static void report(String s, Field value, Field coder) throws Exception {
        byte[] bytes = (byte[]) value.get(s);
        System.out.printf("\"%s\"%n", s);
        System.out.printf("  length()      = %d%n", s.length());
        System.out.printf("  coder         = %d%n", coder.get(s));
        System.out.printf("  value.length  = %d bytes%n", bytes.length);
    }
}
```

Compile and run it plainly first:

```bash
javac StringAnatomy.java
java StringAnatomy
```

```
Instance fields of java.lang.String:
  byte[]   value
  byte     coder
  int      hash
  boolean  hashIsZero
Exception in thread "main" java.lang.reflect.InaccessibleObjectException: Unable to make field private final byte[] java.lang.String.value accessible: module java.base does not "opens java.lang" to unnamed module @7ad041f3
	at java.base/java.lang.reflect.AccessibleObject.throwInaccessibleObjectException(AccessibleObject.java:353)
	at StringAnatomy.main(StringAnatomy.java:15)
```

*(The hex id varies.)* Strong encapsulation, exactly as [Lesson 11](../part-2-classloading/11-modules-and-classloading.md) taught it: `java.base` does not open `java.lang` for deep reflection. This is a diagnostic program, a legitimate bridge — so open the package the way lesson 11 showed:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED StringAnatomy
```

```
Instance fields of java.lang.String:
  byte[]   value
  byte     coder
  int      hash
  boolean  hashIsZero

"hello jvm internals!"
  length()      = 20
  coder         = 0
  value.length  = 20 bytes
"hello jvm internals☃"
  length()      = 20
  coder         = 1
  value.length  = 40 bytes
```

Same `length()`, same logical string — but the snowman flipped the whole instance to coder 1 and doubled the array from 20 to 40 bytes. That is JEP 254 read straight out of the object.

### 2. The price per string, measured

Layout math is a model; now measure the real heap. `StringCost.java` allocates two million distinct 19-character strings, holds them, and divides the heap growth by the count:

```java
public class StringCost {

    static final int N = 2_000_000;

    public static void main(String[] args) throws Exception {
        boolean utf16 = args.length > 0 && args[0].equals("utf16");

        System.gc();
        Thread.sleep(300);
        System.gc();
        long before = usedHeap();

        String[] keep = new String[N];
        for (int i = 0; i < N; i++) {
            // Built at runtime: 19 characters, never a compile-time constant
            String base = "abcdefghij-" + String.format("%08d", i);
            keep[i] = utf16 ? base.substring(0, 18) + "☃" : base;
        }

        System.gc();
        Thread.sleep(300);
        System.gc();
        long after = usedHeap();

        System.out.printf("%,d live strings, mode = %s%n", N, utf16 ? "UTF-16" : "Latin-1");
        System.out.printf("heap growth: %.1f MB%n", (after - before) / 1_048_576.0);
        System.out.printf("cost per slot (String + byte[] + array slot): %.1f bytes%n",
                (after - before) / (double) N);

        // keep the array observable until here, or the JIT lets the GC
        // collect everything before we measure (dead-code liveness)
        System.out.println("sample: " + keep[N - 1]);
    }

    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
```

The strings are built at runtime (`String.format`, `substring`) so nothing is a compile-time constant and nothing lands in the pool — we are measuring plain heap strings. The final `println` is load-bearing: without a use of `keep` after the measurement point, the JIT's liveness analysis treats the array as dead and the GC collects all two million strings before we measure. (That is [Lesson 15](15-escape-analysis.md)'s world — the JIT is always watching what is actually reachable.) Compile and run all three variants:

```bash
javac StringCost.java
java -Xmx512m StringCost
```

```
2,000,000 live strings, mode = Latin-1
heap growth: 130.5 MB
cost per slot (String + byte[] + array slot): 68.4 bytes
sample: abcdefghij-01999999
```

```bash
java -Xmx512m StringCost utf16
```

```
2,000,000 live strings, mode = UTF-16
heap growth: 161.1 MB
cost per slot (String + byte[] + array slot): 84.5 bytes
sample: abcdefghij-0199999☃
```

```bash
java -Xmx512m -XX:-CompactStrings StringCost
```

```
2,000,000 live strings, mode = Latin-1
heap growth: 161.4 MB
cost per slot (String + byte[] + array slot): 84.6 bytes
sample: abcdefghij-01999999
```

*(Heap totals vary by a fraction; the per-slot costs land within a byte of these every run.)* `-Xmx` you know from [Lesson 12](12-runtime-data-areas.md). The third run introduces `-XX:-CompactStrings`, first use in this course: a product flag that **switches JEP 254 off**, forcing every string to UTF-16 regardless of content. It exists mainly for debugging and for the pathological case where an application is entirely non-Latin-1 and the coder check is pure overhead. Look at what it did to the Latin-1 run: 68.4 became 84.6 bytes — identical, within noise, to the UTF-16 run's 84.5. The flag doesn't approximate the effect of UTF-16; it *is* the effect.

Now check the model against reality. A 19-char Latin-1 string: 24-byte shell + `byte[19]` (16 + 19 = 35, aligned to 40) + 4 bytes for the array slot = **68 bytes predicted, 68.4 measured**. UTF-16: 24 + `byte[38]` (16 + 38 = 54, aligned to 56) + 4 = **84 predicted, 84.5 measured**. The header math from lessons 13–14 prices two million objects to within one percent. (You will also meet the flip side of compact strings in Part 5: G1 can deduplicate identical `byte[]` payloads behind distinct `String` shells — but that is GC territory.)

### 3. `intern()` semantics, in six booleans

`InternDemo.java`:

```java
public class InternDemo {

    public static void main(String[] args) {
        String a = "jvm-internals";
        String b = "jvm-internals";
        String c = new String("jvm-internals");
        String d = c.intern();
        String built = new StringBuilder("jvm-").append("internals").toString();
        String e = built.intern();

        System.out.println("literal == literal            : " + (a == b));
        System.out.println("new String == literal         : " + (c == a));
        System.out.println("new String.intern() == literal: " + (d == a));
        System.out.println("built.intern() == literal     : " + (e == a));
        System.out.println("built.intern() == built       : " + (e == built));

        // A string nobody has ever interned: what does the pool store?
        String fresh = new StringBuilder("probe-").append(System.nanoTime()).toString();
        String pooled = fresh.intern();
        System.out.println("fresh.intern() == fresh       : " + (pooled == fresh));
    }
}
```

```bash
javac InternDemo.java
java InternDemo
```

```
literal == literal            : true
new String == literal         : false
new String.intern() == literal: true
built.intern() == literal     : true
built.intern() == built       : false
fresh.intern() == fresh       : true
```

Every line is one of the two pool rules. Both literals resolve to the single canonical instance (`true`). `new String(...)` explicitly allocates a *second* object on the heap — the one case where `new` for strings still exists is forced copies — so it is not the pooled one (`false`). Interning that second object returns the canonical instance (`true`). The runtime-built string interns to the same canonical instance (`true`), and that instance is the *literal's* object, not `built` — content was already pooled, so the pool kept its incumbent and `built` stayed a separate object (`false`).

The last line is the post-JDK-7 rule, and the one that surprises people raised on PermGen lore: `probe-<nanotime>` has never been pooled, so `intern()` stores **the object you passed** as the canonical instance and returns it — `pooled == fresh` is `true`. No copy into a special area. The table holds a reference to your ordinary heap object.

### 4. The pool under load

Now stress the structure. `InternCost.java` interns a million distinct strings and keeps them all reachable:

```java
public class InternCost {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        String[] keep = new String[n];
        for (int i = 0; i < n; i++) {
            keep[i] = ("interned-payload-" + i).intern();
        }
        System.out.printf("interned %,d distinct strings%n", n);
        System.out.println("sample: " + keep[n - 1]);
    }
}
```

Run it with two instruments. The first is a new `-Xlog` tag — the unified-logging framework from [Lesson 00](../part-0-the-machine/00-setup-and-toolchain.md), pointed at the String Table subsystem. The tag is `stringtable`, and at the default `info` level it stays silent; the interesting events are one level down, so we add the `=debug` level selector:

```bash
javac InternCost.java
java -Xmx256m -Xlog:stringtable=debug InternCost 2>&1 | grep stringtable
```

```
[1.687s][debug][stringtable] Concurrent work triggered, live factor: 11.4389 dead factor: 1.52588e-05
[2.541s][debug][stringtable] Grown to size:131072
```

*(Timestamps vary.)* The table noticed its chains growing ("live factor" ≈ average entries per bucket), did concurrent rehash work, and **grew from 65,536 to 131,072 buckets mid-run**. The string pool is a live structure that resizes itself while your application runs.

The second instrument is a diagnostic dump of the table at exit. `-XX:+PrintStringTableStatistics` — first use in this course, so the full explanation: it prints the String Table's (and its siblings') occupancy statistics when the JVM exits. It is a *diagnostic* flag, so it requires the diagnostic gate you first met in [Lesson 15](15-escape-analysis.md), `-XX:+UnlockDiagnosticVMOptions`:

```bash
java -Xmx256m -XX:+UnlockDiagnosticVMOptions -XX:+PrintStringTableStatistics InternCost
```

```
interned 1,000,000 distinct strings
sample: interned-payload-999999
StringTable statistics:
Number of buckets       :    131072 =   1048576 bytes, each 8
Number of entries       :   1000182 =  16002912 bytes, each 16
Number of literals      :   1000182 =  64019056 bytes, avg  64.000
Total footprint         :           =  81070544 bytes
Average bucket size     :     7.631
Variance of bucket size :    27.065
Std. dev. of bucket size:     5.202
Maximum bucket size     :        24
```

*(The SymbolTable and shared-table sections between the program output and this block are omitted; entry counts include the JVM's own literals and vary slightly between runs.)* Read it line by line: 131,072 buckets (1 MB of bucket array, 8 bytes each); 1,000,182 entries at 16 bytes each — your million plus ~182 literals the JDK itself interned; the literals (the actual `String` objects) averaging 64 bytes each — matching the anatomy math for a 20-character string. **Total footprint: about 81 MB for a million short strings.** Average chain length 7.6 after the growth, worst bucket 24.

Now the sizing knob. `-XX:StringTableSize=<n>` — first use — sets the table's **initial** bucket count (default 65,536, confirmable with `-XX:+PrintFlagsFinal -version | grep StringTableSize`). The table still grows under load, so this is about starting big enough to avoid repeated growth work and long early chains. What does *starting too small* cost? Pin it at 1,024 and compare:

```bash
time java -Xmx256m InternCost
```

```
interned 1,000,000 distinct strings
sample: interned-payload-999999

real	0m3.663s
```

```bash
time java -Xmx256m -XX:StringTableSize=1024 InternCost
```

```
interned 1,000,000 distinct strings
sample: interned-payload-999999

real	0m11.091s
```

*(Timings vary; the ratio is the signal.)* Three times slower — growth churn plus long collision chains on every single intern. Re-run the small-table variant with the statistics flags to see the chains directly:

```bash
java -Xmx256m -XX:StringTableSize=1024 -XX:+UnlockDiagnosticVMOptions -XX:+PrintStringTableStatistics InternCost 2>&1 | sed -n '/^StringTable statistics:/,/Maximum/p'
```

```
Average bucket size     :    30.523
Maximum bucket size     :        87
```

Every lookup walks those chains. This is the performance half of the DoS story from the concept section: an attacker cannot choose your `-XX:StringTableSize`, but forcing *distinct-content* interns has the same effect as a tiny table — more entries, more growth, longer chains, slower interning for everyone sharing the JVM. (You normally touch `StringTableSize` only in the opposite direction: a service that legitimately interns tens of millions of stable keys starts the table large to skip the growth phase.)

### 5. Proof the pool is heap

Final nail for the PermGen folklore: watch interned strings appear in a *heap* histogram of a live process. `InternSleep.java` interns half a million and waits:

```java
public class InternSleep {
    public static void main(String[] args) throws Exception {
        String[] keep = new String[500_000];
        for (int i = 0; i < keep.length; i++) {
            keep[i] = ("interned-payload-" + i).intern();
        }
        System.out.println("interned 500,000; sleeping 60s");
        System.out.println(keep[42]);
        Thread.sleep(60_000);
    }
}
```

```bash
javac InternSleep.java
java -Xmx256m InternSleep &
jcmd -l | grep InternSleep
```

```
166051 InternSleep
```

*(PID varies.)* Lesson 00's `jcmd` command list included `VM.stringtable` — first live use in the course; it prints the same table statistics as the exit-time flag, but for a running JVM:

```bash
jcmd 166051 VM.stringtable | sed -n '/^StringTable statistics:/,/Average/p'
```

```
StringTable statistics:
Number of buckets       :     65536 =    524288 bytes, each 8
Number of entries       :    500006 =   8000096 bytes, each 16
Number of literals      :    500006 =  32000416 bytes, avg  64.000
Total footprint         :           =  40524800 bytes
Average bucket size     :     7.629
```

And the heap histogram — the tool that only counts *heap* objects:

```bash
jcmd 166051 GC.class_histogram | grep -E "java\.lang\.String|\[B" | head -4
```

```
   1:        508610       20385472  [B (java.base@25)
   2:        508532       12204768  java.lang.String (java.base@25)
   3:            10        2000272  [Ljava.lang.String; (java.base@25)
 222:             1             24  java.lang.StringBuilder (java.base@25)
```

*(Row numbers and exact counts vary.)* Half a million interned strings, sitting in rows 1 and 2 of a **heap** histogram — `String` shells and their `byte[]` payloads, indistinguishable from any other objects, collectable by the same GC as everything else. PermGen is not in this picture because PermGen no longer exists. Clean up:

```bash
kill 166051
```

This histogram pair is also your first diagnostic reflex in production: when `String` and `[B]` dominate a heap dump in similar counts, you are looking at string payload, and the question becomes which code path is minting and retaining them — a cache, an intern call, or a `+=` loop.

### 6. The quadratic loop, measured

`ConcatCost.java` — same final string built two ways:

```java
public class ConcatCost {

    static final int N = 30_000;

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        String s = "";
        for (int i = 0; i < N; i++) {
            s += "-payload";
        }
        long t1 = System.nanoTime();
        System.out.printf("+= loop: %,6d ms, final length %,d%n",
                (t1 - t0) / 1_000_000, s.length());

        t0 = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) {
            sb.append("-payload");
        }
        s = sb.toString();
        t1 = System.nanoTime();
        System.out.printf("builder: %,6d ms, final length %,d%n",
                (t1 - t0) / 1_000_000, s.length());
    }
}
```

First, look at the loop through [Lesson 05](../part-1-bytecode/05-invokedynamic.md)'s eyes — `javap` shows one indified concat per iteration, exactly as that lesson promised:

```bash
javac ConcatCost.java
javap -c ConcatCost | grep -A9 "iload         4"
```

```
        10: iload         4
        12: sipush        30000
        15: if_icmpge     31
        18: aload_3
        19: invokedynamic #17,  0             // InvokeDynamic #0:makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;
        24: astore_3
        25: iinc          4, 1
        28: goto          10
```

The recipe takes the whole current `s` as its dynamic input and produces a brand-new, longer `s`. Fast machinery — one strategy-optimized call — but the output of iteration *k* is *k+1* chunks long, and the old string is garbage the moment the new one exists. Now run it under a small heap with GC logging (`-Xmx` from lesson 12, `-Xlog:gc` from lesson 00):

```bash
java -Xmx64m ConcatCost
```

```
+= loop:  2,429 ms, final length 240,000
builder:     10 ms, final length 240,000
```

```bash
java -Xmx64m -Xlog:gc ConcatCost 2>&1 | grep -c "Pause Young"
```

```
101
```

```bash
java -Xmx64m -Xlog:gc ConcatCost 2>&1 | grep "Pause Young" | head -3
```

```
[0.122s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 35M->1M(64M) 2.882ms
[0.134s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 30M->1M(64M) 1.653ms
[0.161s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 37M->1M(64M) 1.762ms
```

*(Timings, counts and sizes vary — the shape does not.)* Roughly **240× slower, 101 young collections**, and look at the GC lines: each pause reclaims ~35 MB of dying strings back to a 1 MB live set. The live data is tiny; the *churn* is enormous — about 3.6 billion bytes allocated and copied across the loop for a 240 KB result. The `StringBuilder` version allocates one buffer, grows it a handful of times, and finishes before the first GC would even trigger. JEP 280 optimized the concat; it cannot optimize away immutability. That is the whole answer to lesson 05's parting question about `s = s + x` in a loop.

---

## Try it yourself

1. Predict before running: change `StringCost` to build **8-character** strings (`"abcdefgh"` variants). Price it with the header math — shell 24 + `byte[8]` (16 + 8, already aligned) + slot 4 — then run it. How close is the measured per-slot cost? Repeat with 24-character strings and watch the array cross an alignment boundary.
2. Constant folding: add `String f = "jvm-" + "internals";` to `InternDemo` and predict `f == a`. Verify with `javap -c` — what does the bytecode actually load? (Lesson 05's recipe machinery is *not* involved here; find out why.)
3. Take the `keep` array out of `InternCost` so interned strings are dropped immediately, and re-run with `-XX:+UnlockDiagnosticVMOptions -XX:+PrintStringTableStatistics`. Compare the entry count with the kept run. What does the difference prove about how the table holds its literals?
4. Size the table *up*: run `InternCost` with `-XX:StringTableSize=2000000` and compare wall-clock time and `Average bucket size` with the default run. Where does the extra bucket memory show up in the statistics — and does it ever come back?
5. Lesson 05 mentioned the `java.lang.invoke.stringConcat` system property for choosing a strategy. Run `ConcatCost` with `-Djava.lang.invoke.stringConcat=BC_SB` and then `MH_INLINE_SIZED`. Does the strategy choice change the `+=` loop's O(N²) shape, or only its constant factor? Explain from what gets allocated each iteration.

---

## Common mistakes

- **"The string pool lives in PermGen / Metaspace."** Java 6 folklore. The pool moved to the heap in JDK 7; PermGen itself was deleted in JDK 8. Interned strings are ordinary heap objects — section 5 showed them in a heap histogram — and they are collected when unreachable, because the table holds them *weakly*. What is native is the table's buckets-and-entries bookkeeping, not the strings.
- **"Unreferenced interned strings stay forever."** Consequence of the same folklore. Table entries are weak references; a GC that finds an interned string unreachable reclaims it and cleans the entry (Try it yourself #3 shows the entry count drop). What does *not* shrink is the grown bucket array.
- **"`intern()` is a free dedup cache."** Every distinct interned value costs the string, a 16-byte entry and bucket share, forever while reachable — and the pool is global. Interning request-derived data lets any client mint permanent distinct entries: a memory-shaped DoS. Deduplicate with a bounded cache you own and can evict from.
- **"`new String(\"x\")` is harmless."** It allocates a second object for content that is already pooled — section 3's `false` line. The only legitimate uses are deliberate copies (rare since substring stopped sharing arrays, also in JDK 7's era); in ordinary code it is pure waste and a code-review flag.
- **"`==` works for these two strings, so `==` is fine."** It "works" only when both sides happen to be the same pooled instance — literals and compile-time constants. One `new String`, one runtime-built value, or one string deserialized from the wire and the comparison silently breaks. Value comparison is `.equals()`, always.
- **"Concatenation is fast since JDK 9, so `+=` in a loop is fine now."** JEP 280 made each *individual* concat optimal; the loop still allocates and copies O(N²) bytes because strings are immutable. Section 6 measured 240× and 101 young GCs. Loop → `StringBuilder`, exactly as before JDK 9.
- **"A `String` is a `char[]` with extras."** Not since JDK 9. It is a `byte[]` plus a coder; code (and mental models, and interview answers) that assume two bytes per char are a decade out of date.

---

## Check your understanding

**1. Price a 30-character Latin-1 string on this JDK, and the same string with one character replaced by an emoji. Show the arithmetic.**

<details>
<summary>Reveal answer</summary>

Latin-1: 24-byte `String` shell + `byte[30]` (16-byte array header incl. length + 30 data bytes = 46, aligned up to 48) = **72 bytes**. With one non-Latin-1 character the whole array flips to UTF-16: `byte[60]` (16 + 60 = 76, aligned to 80), total 24 + 80 = **104 bytes**. One character outside Latin-1 costs the whole string 32 extra bytes here — the coder is per-string, not per-character.

</details>

**2. `String s = new StringBuilder("ab").append("cd").toString(); String t = s.intern();` Assuming `"abcd"` has never been pooled in this JVM, what is `t == s`, and why does the answer surprise people with pre-JDK-7 mental models?**

<details>
<summary>Reveal answer</summary>

`true`. When the content is not yet pooled, `intern()` records the caller's own object as the canonical instance and returns it — the table stores a reference to that ordinary heap object. Pre-JDK-7, the pool lived in PermGen and `intern()` *copied* the string into it, returning a different object, so `t == s` was `false`. The old behavior is why people still believe interning "moves" strings somewhere special; nothing moves today.

</details>

**3. In the `-XX:StringTableSize=1024` run, interning a million strings took ~3× longer and the statistics showed `Average bucket size: 30.5` versus `7.6` by default. What exactly is slower, and why does the table growing to the same final size not erase the difference?**

<details>
<summary>Reveal answer</summary>

Slower are the hash-chain walks: every `intern()` hashes the content and walks its bucket chain comparing content, and 30-entry chains cost roughly 4× the comparisons of 7-entry chains — paid on every one of the million interns, plus repeated concurrent growth work on the way up. Growth is triggered by load, not by a target size: the small table spends the whole run overloaded, growing late and in steps, whereas the default-sized run sits at healthy chain lengths for most of its inserts. Final size is not what matters; the chain lengths experienced *during* the run are.

</details>

**4. `InternCost` keeps every interned string in a static-reachable array and grows the pool by ~81 MB; the no-`keep` variant interns the same million strings and ends with far fewer table entries. Reconcile "the pool is global" with "interned strings get collected."**

<details>
<summary>Reveal answer</summary>

The *table* is global and lives as long as the JVM; the *entries* are weak references. A pooled string is reclaimed as soon as nothing outside the table references it — the pool alone does not keep a string alive. In the kept run the application itself pins all million strings via the array, so GC can reclaim none of them and the table holds a million live entries. In the no-keep run each string becomes unreachable immediately after its iteration, GCs reclaim them, and the dead entries are cleaned from the table. Globality is about *ownership of the structure*, not about immortality of the contents — which is also why the DoS risk requires the attacker to make *you* retain (or continuously re-mint) the strings.

</details>

**5. Lesson 05 proved `makeConcatWithConstants` builds each concatenation with exact sizing and no intermediate `StringBuilder`. If every iteration is individually optimal, why does `s += chunk` in a loop still allocate gigabytes?**

<details>
<summary>Reveal answer</summary>

Because immutability forces each iteration's output to be a *new* string containing *all* previous content. Exact sizing means iteration *k* allocates exactly `k` chunks' worth of bytes and copies them — optimal for that step, but the steps sum: 1 + 2 + … + N chunks is O(N²) bytes allocated, and N−1 intermediate strings become garbage. The optimization removed waste *inside* one concatenation; it cannot remove the re-copying that concatenating-into-an-accumulator logically requires. `StringBuilder` sidesteps it by mutating one buffer and copying once at the end.

</details>

---

## Recap

- A JDK 25 `String` is a **24-byte shell** — mark word, compressed klass word, cached `hash`, `hashIsZero`, a `coder` byte, and a compressed reference — pointing at a **`byte[]`**, not a `char[]`. JEP 254 stores Latin-1 content one byte per char (`coder 0`) and flips the whole string to two-byte UTF-16 (`coder 1`) otherwise; measured cost: 68 vs 84 bytes per 19-char string, and `-XX:-CompactStrings` erases the difference.
- The **string pool** is a hash table of weak references **on the heap** (since JDK 7 — PermGen is folklore). Literals resolve to one canonical instance; `intern()` returns the incumbent if pooled, and *your object itself* if not.
- Interning costs ~16 bytes of table entry plus bucket share on top of every string: a million short strings ≈ **81 MB**, visible in `-Xlog:stringtable=debug` growth events and `-XX:+PrintStringTableStatistics`. The pool is global — never intern input you don't control — and `-XX:StringTableSize` sets the starting bucket count, with long chains costing real time when it is wrong.
- JEP 280 made each `+` optimal **per call**; it cannot repeal immutability, so `+=` in a loop remains O(N²) allocations — 240× slower and 101 young GCs against `StringBuilder` in the demo. The bytecode view (lesson 05) and the memory view (this lesson) are the same story told twice.
- When `String` and `[B` top a heap histogram together — and they will — you now know what you are looking at, what each instance costs, and which code paths mint them. Part 5's GC lessons pick up from that histogram.

**Previous:** [Lesson 15 — Escape analysis & scalar replacement](15-escape-analysis.md) · **Next:** [Lesson 17 — Off-heap memory & NMT](17-off-heap-memory.md)
