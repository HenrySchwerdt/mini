# Plan: Mini becomes native Kotlin with a checkable memory model

Status: **draft for decision**. This document describes the conversion of Mini
(v0.3, its own Kotlin-inspired syntax) into a language that speaks **1:1 Kotlin grammar
and semantics**, ships the **Kotlin standard library**, can use **Kotlin libraries**,
talks to **C libraries** via `external`, uses **libuv** for threads/IO, and whose
distinguishing feature is an **allocator trait system** with which the compiler checks
*whether* and *where* allocating makes sense and *what was not freed*.

---

## 1. Guiding principles

1. **Every Mini program is a valid Kotlin program.** No custom syntax any more.
   Everything Mini-specific (allocators, ownership, C interop) is expressed with means
   Kotlin already has: annotations (including type annotations), context parameters
   (`context(alloc: Allocator)`), `external`, `expect`/`actual`, interfaces. This makes
   IntelliJ/kotlin-lsp, formatters and syntax highlighting work immediately, and the same
   code also compiles on the JVM (with a no-op allocator library).
2. **Files are named `.kt`.** The `.mini` extension is dropped.
3. **Compatibility before control, but control is available.** Foreign Kotlin code
   assumes a GC exists. It runs in `Managed` mode. Your own code can switch to `Strict`
   per file or module. Then every allocation **must** be statically assigned to an owner
   and released, otherwise it is a compile error.
4. **One analysis, two uses.** The same ownership and escape analysis that produces
   diagnostics in `Strict` mode eliminates reference-count operations in `Managed` mode
   and moves non-escaping objects to the stack.
5. **Conformance is measured, not claimed.** The target test suite is the
   `codegen/box` tests from the Kotlin repo (`compiler/testData/codegen/box`, every
   test returns `"OK"`). The pass rate is the central progress metric.

## 2. The key decision: build our own frontend or use K2?

This is the most important decision, because it changes the effort by orders of magnitude.

| | **A: Own frontend in C** (continuing mini0) | **B: Own frontend in Kotlin** (self-hosting) | **C: Kotlin K2 frontend + own backend** *(recommended)* |
|---|---|---|---|
| Parser | handwritten from the kotlin-spec grammar | handwritten | free (K2/FIR) |
| Type system | build it ourselves: overload resolution, inference, smart casts, variance, builder inference … | build it ourselves | free, exactly Kotlin |
| Using Kotlin libs | source code only, only what our frontend understands | same | source code **and** klibs (which contain IR) |
| Shared lowerings (coroutines, inline, data/enum classes, local classes) | all ourselves | all ourselves | mostly reusable from `backend.common` |
| Risk | years until real library compatibility | same | internal compiler APIs change between Kotlin versions, so pin the version |
| Compiler runs on | native | JVM, later native (compiling itself) | JVM |

**Recommendation: C.** The goal is "1:1 Kotlin, same std, use Kotlin libs". What is
unique about Mini is the memory model, not the parser. Kotlin/Native, Kotlin/Wasm
and Kotlin/JS are built exactly this way: shared frontend, own IR backend. We build
another backend ("Kotlin/Mini") that translates IR to C (later LLVM) and links our
C/libuv runtime.

The ownership diagnostics run on two tracks:
- **FIR checkers** (`FirAdditionalCheckersExtension`, stable plugin API) for errors that
  are visible in the source text. These therefore also show up in the IDE.
- **IR pass** after inlining for the flow-sensitive analysis (leaks, double free,
  escape). These errors show up at build time.

Phase 0 is a **spike** that validates variant C. If it fails, we fall back to B.
All parts from section 4 on (runtime, memory model, interop, std actuals) are the
same in both variants.

## 3. Architecture (variant C)

```
 .kt sources ─┐
 klibs (libs) ┼─► K2 frontend (FIR) ──► FIR checkers: mini.memory rules (IDE + CLI)
 mini-stdlib ─┘        │
                       ▼
                  Fir2Ir ──► Kotlin IR (IrModuleFragment)
                       │
                       ▼  shared lowerings from backend.common
                  Inline, coroutines→state machines, local classes, enums, data classes …
                       │
                       ▼  Mini lowerings
                  1. Escape analysis      → stack promotion
                  2. Allocator resolution → which allocator per allocation site
                  3. Ownership analysis   → Strict: diagnostics / Managed: RC elision (Perceus-style)
                  4. Exceptions           → explicit error return (Swift-error-ABI-style)
                  5. Generics             → monomorphize primitives, share reference types
                       │
                       ▼
                  C emitter (later: LLVM)  ──► clang ──► + libminirt (C, libuv) ──► binary
```

The compiler is a Gradle project in Kotlin under `compiler/` with a pinned
Kotlin version. The runtime stays C17 under `runtime/` and is compiled along by `minic`.

## 4. The memory model

### 4.1 Allocator traits

Allocators are ordinary Kotlin interfaces in `mini.memory`. The compiler knows the
**capability traits** and derives from them which checks apply:

```kotlin
package mini.memory

interface Allocator {
    fun allocate(size: Long, align: Int): NativePtr?
}

/** Objects are freed individually → must-free analysis. */
interface Deallocating : Allocator {
    fun free(ptr: NativePtr)
}

/** Everything dies together at scope end → escape/lifetime analysis instead of a free obligation. */
interface Scoped : Allocator, AutoCloseable

/** Refcount (+ cycle collector). No obligations, default for foreign Kotlin code. */
interface Managed : Allocator

/** Marker: may be used by several threads. */
interface ThreadSafe
```

Provided implementations: `Heap : Deallocating, ThreadSafe` (malloc/free),
`Arena : Scoped`, `Pool<T> : Deallocating`, `Rc : Managed, ThreadSafe` (default),
`DebugAllocator`, which reports leaks at run time with the allocation site.

| Allocator trait | What the compiler checks |
|---|---|
| `Deallocating` | Every allocation is released exactly once on **every** path or handed to an `@Owned` slot. No use-after-free, no double free. |
| `Scoped` | No object from the arena escapes the scope (return, field of a longer-lived object, capture in an escaping lambda). |
| `Managed` | nothing, optimization only |
| `Scoped` (additionally) | An arena does **not** call `dispose` when it is released, so that it costs nothing per object. Therefore an arena object must not hold references to objects of other allocators (e.g. refcounted strings). The compiler reports this as an error. *(decided)* |
| without `ThreadSafe` | Objects from it must not be handed to another thread. |

### 4.2 Where to allocate? Placement per constructor call

Every `Foo(...)` is an allocation site. The compiler decides in this order:

1. **Does not escape** (escape analysis) → **stack**, no allocator needed. This holds in
   every mode and is the most important optimization.
2. A `context(alloc: …)` is in scope → that allocator.
3. The class fixes an allocator (`@AllocatedBy(Pool::class)`) → that one.
4. Otherwise the module default (`Rc`, or an error in `Strict` mode).

Kotlin context parameters (since 2.2) are exactly the right tool because they are
standard Kotlin:

```kotlin
import mini.memory.*

class Node(val value: Int, var next: Node? = null)

context(alloc: Arena)
fun buildList(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)   // → Arena
    return head
}

fun main() {
    Arena().use { arena ->
        context(arena) {
            val list = buildList(1000)
            println(list?.value)
        }
    }   // the arena is released; the compiler has proven that `list` does not escape
}
```

### 4.3 Ownership in Kotlin syntax

Annotations, also on types (valid Kotlin):

- `@Owned` on a return value, parameter or field: ownership is transferred.
- `@Borrowed` (default for parameters): only borrowed, must not be stored.
- `x.free()` / `alloc.free(x)` / `x.use { }` end ownership.
- Default inference: parameters are borrowed, return values owning, fields owning.
  Annotations are only needed at module boundaries and in special cases.

```kotlin
@file:MemoryMode(Strict)

class Buffer(val size: Int) : AutoCloseable { /* … */ }

context(alloc: Heap)
fun load(): @Owned Buffer {
    val buf = Buffer(4096)
    if (!fill(buf)) return Buffer(0)   // ← error
    return buf
}
```

```
error[M0102]: allocation from `Heap` (Deallocating) is never released
  --> src/load.kt:5:15   `val buf = Buffer(4096)` allocated here
  --> src/load.kt:6:24   path: early return without `buf.free()` / `buf.close()`
  help: call `buf.close()` before returning, or wrap in `buf.use { … }`
```

### 4.4 Does allocating here make sense? Lints

| Code | Message |
|---|---|
| M0201 (info) | heap allocation does not escape and was moved to the stack |
| M0202 (warn) | allocation in a loop in a `Scoped` arena: unbounded growth |
| M0203 (warn) | large object (> N KB) on the stack |
| M0204 (error, Strict) | allocation needs the `Managed` fallback, ownership not provable |
| M0205 (warn) | allocation in a `@NoAlloc` function or hot path |
| M0206 (error) | object from a non-`ThreadSafe` allocator leaves the thread |

### 4.4a Making refcounting visible (planned)

After escape analysis and RC elision, the compiler can optionally report every place where
refcounting **still** remains: `M0207 (warn)`, with the reason (object is shared, escapes
into a field of unknown lifetime, a cycle is possible …) and a concrete suggestion (`Arena` around
the block, `@Owned` return, `x.free()`). Controllable per file or module
(`@file:MemoryMode(Mode.Guided)` or the compiler flag `-Wrc`). In addition there is `minic mem-report`:
an overview per function of what ends up on the stack, in an arena, manual, or RC.
Refcounting is not automatically worse (shared graphs, caches). That is why it is
a warning, not an error.

### 4.5 Modes

- `@file:MemoryMode(Managed)`: **default**, for all foreign Kotlin libs. Refcounting
  with elision. The refcounting is non-atomic as long as an object stays on its thread,
  and becomes atomic when shared between threads ("biased RC"). A cycle collector is
  added in a later phase.
- `@file:MemoryMode(Strict)`: no RC fallback, every allocation needs a proven
  owner.
- Mixed: Strict code may call Managed code. Managed objects are "borrowed, lifetime external"
  in Strict code. Conversely, Strict objects must not be stored in Managed fields
  without transferring ownership.

## 5. Kotlin semantics that need runtime work

| Kotlin feature | Implementation |
|---|---|
| `Any`, `equals`/`hashCode`/`toString`, `is`/`as` | object header (TypeInfo pointer + RC/flags), vtables and interface tables |
| `T?` / `null` | null pointer for reference types, tag+value for primitives (replaces Mini's `Optional`) |
| `kotlin.Result`, `when` | replace Mini's `Result`/`match` 1:1 |
| Exceptions | error-return ABI in the C backend (flag after every throwing call, `@NoThrow` inference removes checks). Real unwinding in the LLVM backend later. `finally` and `use` also call `free` on the error path. The ownership analysis knows these paths. |
| Generics (erased in Kotlin) | reference types share code (one pointer layout), primitives are monomorphized. `reified` works via inlining. |
| Lambdas/closures | the closure object goes through normal allocation placement, non-escaping lambdas live on the stack |
| `String`, `StringBuilder` | UTF-16 like Kotlin (compatibility for `Char`/indices), from the std with `actual`s |
| Coroutines/`suspend` | state-machine lowering from `backend.common`. The dispatcher runs on the libuv loop. `Dispatchers.IO`/`Default` use the libuv thread pool (`uv_queue_work`). |
| Async IO (`mini.io`) | `suspend` APIs over libuv (timers, files, TCP/UDP, DNS, processes): the callback resumes the continuation, no thread blocks. API shape modelled on kotlinx-io/ktor-io so that Ktor & co. can run on it later. Memory: coroutine frames go through the normal allocator resolution. An `Arena` can run per `coroutineScope` (e.g. one arena per HTTP request, everything gone at scope end). |
| `object`/companion, `lateinit`, `lazy` | runtime init guards, thread-safe via atomics |

## 6. Standard library

- **Source:** the `common` sources of the official Kotlin stdlib (`libraries/stdlib/src`,
  Apache-2.0) plus **our `actual`s** in `stdlib-mini/`. Kotlin/Native solved the same
  problem. Its `actual`s (`kotlin-native/runtime/src/main/kotlin`, also
  Apache-2.0) serve as a template. We replace their `@SymbolName`/C++ runtime calls
  with our C runtime.
- **Shortcut, check early:** feed the finished stdlib klib of Kotlin/Native directly through
  our backend and rebuild only its runtime symbols in C.
- **Mini extensions** in their own packages: `mini.memory`, `mini.concurrent` (Thread,
  Mutex, Condition on libuv), `mini.io` (files, TCP, UDP, DNS on libuv),
  `mini.interop`.
- **kotlinx.coroutines** as the first big foreign lib: port its native source set to our
  runtime, with a libuv dispatcher.

## 7. C interop with `extern`

Everything in Kotlin syntax and **compatible with the `kotlinx.cinterop` API of Kotlin/Native**,
so that Kotlin/Native code using cinterop works with us:

```kotlin
@file:CInclude("uv.h")
@file:CLink("uv")

import kotlinx.cinterop.*

@CName("uv_version_string")
external fun uvVersionString(): CPointer<ByteVar>?

@CStruct("uv_loop_t")
external class UvLoop : CStructVar

fun main() {
    println(uvVersionString()?.toKString())
    memScoped {                       // = Scoped allocator, same analysis as Arena
        val n = alloc<IntVar>()
        n.value = 42
    }
}
```

- `external fun` + `@CName` become a direct C call without a wrapper.
- `CPointer<T>`, `CValue<T>`, `memScoped`, `alloc<T>()`, `staticCFunction` (callbacks)
  as in Kotlin/Native.
- **`mini-bindgen`**: reads C headers (libclang) and generates the `external` declarations
  as `.kt`, like `cinterop` with `.def` files.
- Export in the other direction: `@CName("my_fn") fun …` generates C ABI symbols and a
  generated header so that C can call Mini code.
- Ownership across the boundary: pointers from C are "externally managed". `@Owned` on an
  `external fun` transfers the free obligation, e.g. `@Owned(free = "uv_loop_close")`.

## 8. Using Kotlin libs: levels

1. **Source of KMP libs** (`commonMain`): compiles directly. For their `expect`s
   we write a "mini" set of `actual`s, or take the `nativeMain` variant
   if it only uses cinterop/posix.
2. **klibs** (Kotlin/Native libraries contain serialized IR): with variant C
   our backend can deserialize and compile them. That makes many published KMP libs work
   without source. Limit: libs that use Kotlin/Native-internal
   intrinsics need matching runtime symbols.
3. **JVM-only libs** (`.jar`): deliberately **not supported**, because they need the JDK.

Gradle integration: a Gradle plugin with its own KMP target `mini()`, so that one can
simply add a target in existing KMP projects (later phase).

## 9. What happens to the existing code

| Existing | Future |
|---|---|
| `bootstrap/` (mini0, C), old `std/*.mini`, `examples/*.mini`, docs v0.2/v0.3 | **removed** (last state: merge commit `391270a` on `main`) |
| `runtime/` | is now libminirt (C17); the libuv binding of the old compiler served as a template |
| CI, xmake | xmake removed; CI = runtime suite (`runtime/run_tests.sh`) + Gradle tests on Linux/macOS |
| Docs v0.2/v0.3 | archive. New reference: "Kotlin + Mini memory model" |

## 10. Phases and acceptance criteria

**Priority (current):** Linux and macOS first. Windows runs in CI but does not
block. Manual memory management is the core feature. That is why the
basics of `mini.memory` (`Heap`, `Arena`, `free`/`use`, `@file:MemoryMode(Strict)` and
the first leak and double-free diagnostics) already come in P1, in parallel with classes. P3 then
extends the analysis (escape analysis, lints, thread rules).

| Phase | Content | Done when … |
|---|---|---|
| **P0 Spike** (key decision) | Gradle project with pinned Kotlin version, K2 → IR → minimal C emitter | `fun main() { println(1 + 2) }` from `.kt` runs natively. Decision C or B is made. |
| **P1 Core backend** | classes, inheritance, interfaces, `when`, nullability, strings, primitives, arrays, lambdas. Box-test runner. | runner runs in CI, pass rate is reported |
| **P2 Runtime + std** | object header, RC (`Rc`), exceptions, stdlib `common` + `actual`s, collections | `println(listOf(1,2,3).map { it * 2 })`, ≥ 30 % box tests |
| **P3 Memory model** | `mini.memory`, escape analysis, allocator resolution, Strict ownership analysis, FIR checkers, lints M01xx/M02xx, DebugAllocator | negative tests for every diagnostic, all ported examples run leak-free under ASan |
| **P4 Interop** | `external`/`@CName`, cinterop API, `mini-bindgen`, C export | libuv bindings are **generated** instead of handwritten |
| **P5 Concurrency/IO** | `mini.concurrent`, `mini.io` on libuv, coroutines lowering, kotlinx.coroutines port | echo server with coroutines, TSan clean |
| **P6 Libs** | klib import, Gradle plugin/KMP target | a real KMP lib (e.g. kotlinx-datetime or okio) runs unchanged |
| **P7** | LLVM backend, cycle collector, performance | box tests ≥ 80 %, benchmarks against Kotlin/Native |
| **P8 Self-hosting** | see section 10a | the Mini compiler builds itself, stage 2 == stage 3 |

### 10b. v1 and bootstrap: concrete milestones

**v1 is done when `minic` compiles its own backend** (`compiler/backend`, pure
Kotlin, rule from 10a) and the native result produces the same C output as the JVM backend
for all test programs. This is a hard, automatically checkable yardstick. It forces
exactly the features that real Kotlin code needs:

| Area | Needed for v1 |
|---|---|
| Language | classes, `sealed`/`data`/`enum`/`object`, interfaces, generics (classes + functions), lambdas and closures, extension functions, `when`, nullability, exceptions (`error()`, `require`, `try/catch/finally`), default and named args, `vararg`, properties with getters, `lazy` |
| stdlib | `String`/`StringBuilder`, `List`/`MutableList`/`Map`/`Set` (+ `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`), `map`/`filter`/`joinToString`/`forEach`/`associate`…, `Pair`, ranges, `buildString`, `println` |
| Memory | Managed (RC) everywhere. The Strict check runs as a separate track in parallel. |

**Status:** v1/B1 is reached. `SelfHostTest` builds `native-driver` and the backend natively as
`minic-backend`. For all test programs its C is byte-identical to the JVM output.

**Status B2:** B2-1 (lexer, parser, AST) and B2-2 (name resolution, type checking, inference, smart casts in
`compiler/frontend`, package `mini.frontend.sema`) are reached; K2 (FIR) is the oracle and agrees on the stdlib, compiler sources and test programs in
99.99 % of positions, and built natively it gives the same result as on the JVM. See `compiler/README.md`. Open: Ir/MIR generation from the own frontend (B2-3).

**Bootstrap stages after that:**
1. **B1 (= v1):** backend self-hosted. The native `minic-backend` reads serialized MIR
   (written to a file by the JVM frontend) and produces C. This makes half the compiler run natively.
2. **B2:** own frontend in `common` Kotlin (lexer → parser → resolver → MIR), K2 as
   oracle. Growth file by file: every test file must yield the same MIR as the K2 path.
3. **B3:** fully native: `minic` compiles itself without a JVM, stage 2 == stage 3.

The stdlib path for v1: send the Kotlin stdlib sources (`common`, Apache-2.0) **together
with the program** as source text through K2, with `-Xallow-kotlin-package` and our own `actual`s.
That way `inline` functions like `map`/`filter` also have IR bodies that our backend
can compile. The JVM stdlib JAR only provides bytecode, no IR.

### 10d. Dogfooding: making stdlib and compiler fast with our own memory model (after v1)

As soon as the compiler runs natively, it is our biggest test program. Procedure:
1. **Measure before optimizing:** runtime counters (allocations, frees, retain/release,
   peak memory) as a `--debug-memory` build, plus a benchmark suite: native `minic` vs. JVM `minic`
   on large inputs (e.g. its own backend), plus micro-benchmarks for the stdlib
   (`ArrayList`, `HashMap`, `StringBuilder`, `map`/`filter`).
2. **RC elision in the compiler** (Perceus-style: borrow inference for parameters, move of the last
   use, reuse). Experience says this brings the biggest gain without touching the code.
3. **Run `minic mem-report` / `M0207`** (section 4.4a) over the stdlib and compiler:
   where does refcounting remain, and why?
4. **Targeted conversion:**
   - stdlib internals (buffers of `ArrayList`/`StringBuilder`/`HashMap`) to Strict with
     `@Owned` fields. The public API stays Kotlin-compatible.
   - In the compiler, an `Arena` per phase (frontend, Ir2Mir, Emit).
5. **Evaluate:** time, memory and number of RC operations before/after. And above all: how many
   annotations and `free()`s were needed, and which diagnostics got in the way? These experiences
   flow back into `docs/memory-model.md` (ergonomics, inference of `@Owned`).

**Status D1 (steps 1 and 2):** counters (`minic build --stats`, `-DMK_STATS`), benchmarks (`compiler/bench`) and the
RC elision `RcOpt` (borrow inference, move of the last use, constructor parameters with ownership) are implemented;
before/after numbers in `docs/benchmarks.md`, rules in `compiler/README.md` (section "RC elision").

### 10c. Runtime in Kotlin (after v1)

Goal: **no handwritten C runtime any more.** libminirt is rewritten piece by piece in Mini
Kotlin. Only libc and libuv remain as `external` libraries; later libc can be replaced via
syscalls (freestanding).

What the compiler needs for this is a small low-level layer `mini.unsafe`, modelled on
`kotlinx.cinterop`:
- raw pointers (`NativePtr`/`CPointer<T>`), pointer arithmetic, `load`/`store`, `sizeOf<T>()`,
  `reinterpret`;
- atomics (`AtomicInt`, CAS, fences), mapped directly to C11/LLVM atomics;
- `external` C functions (`malloc`, `free`, `write`, `uv_*`) and `@CName` export
  (e.g. for `main`).

The chicken-and-egg problem: the runtime itself must not allocate via refcounting. That is why
it is written in **`@file:MemoryMode(Mode.Strict)` + `@NoAlloc`**. The runtime is thus the
hardest real-world test of our own memory model.

Order, each with the existing C tests as acceptance (same ABI from `minirt.h`):
number and string formatting → UTF-8/16 → allocators (Heap, Arena, Pool, Debug) →
object model and refcounting → console/entry point. After that `runtime/src/*.c` is gone.
For the bootstrap this is a win: compiler, stdlib and runtime are then all Kotlin.

### 10a. Self-hosting

Goal: the compiler is compiled by itself and runs as a native binary without a JVM.
Our own code is made faster with the memory model along the way: `Arena` per
compiler phase and `Strict` in the hot paths.

**Design rule from now on:** the backend (`compiler/backend`, everything after Fir2Ir) and the
Mini analyses are **pure `common` Kotlin**: no `java.*`, no `kotlin.jvm.*`, no
JVM reflection. JVM contact sits in exactly one thin module `compiler/jvm-bridge`
(call K2, read files, start clang). This lets the backend compile itself early
(milestone "backend self-hosted").

The frontend is the hard part. K2 depends on Java code (intellij-core, the lexer
generated by JFlex, `java.util`). Two ways:

1. **Own frontend in `common` Kotlin**, with K2 as **oracle**: for every file of the
   test suite and for the compiler itself, our frontend must produce the same IR as K2
   (differential testing). This is much easier than "frontend without a reference" (way A
   from section 2), because every deviation is noticed automatically.
2. **Automatically convert the Java parts of K2 to Kotlin** (J2K from IntelliJ, or
   the command-line J2K from the Kotlin repo) and then compile the real K2 frontend with
   our backend. Steps:
   1. **Inventory:** which Java classes does K2 actually reach in LightTree mode (without PSI editor
      features)? Measure the call graph starting at `FirSession`/the LightTree parser. The goal is
      the smallest possible intellij-core subset.
   2. **J2K + rework:** converted code rarely compiles right away. Nullability,
      static members, generics wildcards and raw types need manual work. The lexer
      is regenerated from the `.flex` grammar (JFlex can also emit other output, otherwise
      we write our own generator) instead of converting generated Java code.
   3. **`java.*` shim:** whatever remains (`java.util` collections, `ConcurrentHashMap`,
      `ThreadLocal`, `WeakReference`/`SoftReference`, `ServiceLoader`, file IO, simple
      reflection) gets its own `mini.jdk` library in Kotlin on libuv. Only what
      the inventory demands, not the whole JDK.
   4. **Adapt the memory model:** a compiler is the ideal case for arenas. Almost everything
      (FIR trees, IR, symbol tables) lives for exactly one compilation or one phase
      and has many cycles (parent pointers). So every phase gets an `Arena` via
      `context(arena)`. Only what lives across phases is copied out explicitly.
      This removes the cycle problems of refcounting, and memory is released per phase
      in one go. `WeakReference`/`SoftReference` caches become phase caches.
   5. **Maintenance problem:** every Kotlin version jump brings new Java code. So
      the conversion runs as a reproducible pipeline (script + patch series), not as a
      one-time fork.

   Assessment: way 2 delivers 100 % K2 semantics but costs permanent maintenance. Way 1 is more
   work at the start but independent afterwards. The decision is made after the inventory
   (step 1). That delivers the hard number: how many lines of Java.

Bootstrap chain: JVM compiler (K2 + our backend) → compiles our frontend and
backend → `minic` stage 1 (native) → stage 2 → stage 3 and comparison stage 2 == stage 3.

### 10e. Reflection, serialization and frameworks (after B3)

Goal: JSON and a Spring-Boot-like web framework work in Mini without giving up the memory
model. Principle: **pay only for what is used**, and prefer work at compile time over work at
run time. Mini compiles the whole program at once, so the compiler knows which classes are
reflected on.

Three levels:

| Level | What | Cost |
|---|---|---|
| **R1 (exists)** | `::class`, `simpleName`, `qualifiedName`, `is`/`as`, `isInstance`, `cast` | nothing extra: the TypeInfo pointer in the object header |
| **R2: runtime reflection** | `KClass.members`, `memberProperties`, `constructors`, `KProperty.get/set`, `KFunction.call/callBy`, annotations with runtime retention, `KType` of members | static, read-only tables in the binary, only for classes that are reflected on (whole-program detection) or marked `@Reflectable` |
| **R3: compile-time metaprogramming** | compiler plugins that generate code from annotations, like kotlinx.serialization: `@Serializable` serializers, DI wiring, route tables | no run-time cost, generated code is checked like hand-written code |

**Memory model (R2).**
- Metadata objects (`KClass`, `KProperty`, `KFunction`, annotation instances) are **static**:
  allocator `Static`, never freed, always borrowed, no refcounting (the runtime already treats
  static objects as RC no-ops).
- Each member's ownership annotations (`@Owned` result, borrowed/owned parameters) are
  stored in the metadata. Hence `prop.get(obj)` returns a **borrowed** value, `ctor.call(...)`
  returns an **owned** value that the Strict checker tracks like any other allocation.
- Where ownership is only known at run time (`callBy` with arbitrary arguments, reflected
  setters on owned fields) the call is a **Managed boundary**: allowed in Managed code,
  a diagnostic in Strict code (same rule as §4.5 / memory-model §6.2). Reflection cannot
  silently bypass the checks.
- Size: a few hundred bytes of read-only data per reflected class (names, member tables with
  getter/function pointers, signatures). It costs binary size, not heap.

**JSON (first user of R3).** A port of the kotlinx.serialization API (`@Serializable`,
`Json.encodeToString`, `Json.decodeFromString<T>`, `@SerialName`, polymorphism via sealed
classes) with the serializer generation as a Mini compiler plugin. Decoding allocates through
the caller's allocator context, so `context(arena) { Json.decodeFromString<Request>(body) }`
puts the whole object graph into the arena.

**Web framework.** Spring-Boot-style ergonomics with the Micronaut/Quarkus approach: DI and
routing are resolved **at compile time** (R3), not by scanning the classpath at startup.
- `@Component`/`@Inject`/`@Controller`/`@Get("/path")` generate a wiring graph and a route table.
- Server on `mini.io` (libuv TCP, coroutines, §5).
- **One arena per request:** handler, decoded body, intermediate objects and the response
  live in the request's arena and are released at once when the request ends. That is the
  memory model's showcase: no GC pauses, almost no refcounting, and the checker reports
  anything that escapes the request (M0105).

**Not supported:** loading classes at run time, `java.lang.reflect.Proxy`, generating types at
run time. Like Kotlin/Native, the closed world is the price of native code. Proxies for DI
are generated at compile time instead.

Order: R2 core (`members`, `get/set`, `call`) after B3 → R3 plugin API → serialization → the
web framework (needs coroutines and async IO on libuv first).

### 10f. Project tool `mini`

Users never see Gradle. `mini` is the project tool (like `cargo`), and `minic` remains the bare
compiler underneath (like `rustc`).
- `mini new app`, `mini new mylib --lib`, `mini build`, `mini run`, `mini test`,
  `mini add <dep> --git <url> | --path <dir>`.
- `mini.toml` manifest (name, version, kind, dependencies, memory mode) plus a lockfile.
- Libraries are **source packages**, compiled together with the program like the stdlib.
  Maven KMP libraries (source jars / klibs, §8) come later through the same mechanism.
- v0 as commands of the current `minic`. After B3 it is rewritten in Mini, as a native
  binary with instant startup (dogfooding: file IO and process spawning on libuv).
- Release: prebuilt `mini`/`minic` binaries for Linux and macOS on GitHub Releases.

**The repository itself drops Gradle after B3.** The compiler is then just another Mini
project built by `mini`:
- A workspace `mini.toml` at the root with members `compiler/frontend`, `compiler/lower`,
  `compiler/backend`, `compiler/driver`, `stdlib`, `runtime` (until §10c removes the C runtime)
  and `website` samples. Each member has its own `mini.toml`.
- **Bootstrap seed** instead of a JVM: building from scratch needs only a C compiler. Every
  release also publishes the generated C of `minic` (`bootstrap/minic.c`, like Zig's C seed).
  `clang bootstrap/minic.c runtime/*.c` gives stage 0, which builds stage 1 from source. CI
  then checks stage 2 == stage 3 on every push.
- Tests run with `mini test` (own test runner: `@Test` functions found by our frontend,
  expected-output files as today).
- The K2 oracles (`jvm-bridge`, `frontend-oracle`, `memory-spec`) are the only JVM parts left.
  They become an optional `tools/k2-oracle` check that compares our frontend with K2 on
  demand (Kotlin version upgrades), not part of the normal build. Gradle then lives only
  there, and can go entirely once the oracles are no longer needed.
- Migration order: (1) `mini` v0 can build multi-module workspaces, (2) build the compiler
  with `mini` in CI next to Gradle and compare the outputs, (3) switch CI and the README,
  (4) delete `compiler/gradle*`, `settings.gradle.kts`, `build.gradle.kts`.

### 10g. Toolchain without clang: LLVM as the primary backend, static libuv, JIT and scripts (after B3)

Decision: after the full self-host (B3) Mini moves **fully to the LLVM backend**. The C backend stays
only as the portable bootstrap seed (`bootstrap/minic.c`, §10f) and as a fallback for platforms
without LLVM; new optimizations and features target LLVM first.

- **LLVM in-process through its C API**, bound with Mini's own C interop (§7): `external` functions
  with `@CName("LLVMBuildAdd")` etc. Handles that LLVM disposes (`LLVMDispose*`) are `@Owned`, so the
  binding is the big real-world test of §7 and of the memory model. No textual `.ll` files and no
  `llc`/`clang` process.
- **lld embedded** (through a small C shim around its library entry point) for linking: `minic` is a
  single self-contained binary (LLVM + lld inside, like Zig). LLVM is not built by us: the release CI
  links the official prebuilt LLVM static libraries per platform. Expected size 60-100 MB.
- **What LLVM gives us beyond C:** real exception unwinding instead of the error-return ABI (§5),
  precise debug info (DWARF with Kotlin source lines, lldb/gdb step through `.kt` files), LTO across
  stdlib and program, intrinsics for atomics and overflow checks, cross-compilation to every LLVM
  target (`mini build --target linux-arm64`).
- **libuv statically bundled:** each release ships a prebuilt `libuv.a` per target (MIT licence) and
  `minic` links it automatically. Users never install libuv. Later a prebuilt musl for fully static
  Linux binaries. (Bundling libuv does not need B3 and can come earlier with the C backend.)
- **JIT and Kotlin scripts:** LLVM ORC JIT through the C API.
  - `mini run script.main.kts`, shebang scripts (`#!/usr/bin/env mini`), `mini repl`.
  - Pipeline: own frontend → MIR → LLVM IR → ORC, executed in memory without object files or linking.
  - The stdlib is compiled once into a cached object/bitcode that the JIT links against; scripts are
    cached by content hash. Target: a new script starts in about 50-150 ms, a cached one in a few ms.
  - Script features: top-level statements, `args`, `@file:DependsOn` mapped to `mini` dependencies,
    Managed memory by default (allocators still available).
  - The REPL adds one small module per input to the ORC session.
- **Together with §10c** (runtime in Kotlin) Mini programs then contain no C at all; only libc and libuv
  remain, both as prebuilt libraries.

Order after B3: C interop complete (§7: pointers, structs, callbacks) → LLVM backend reaching parity
with the C backend (same test suite, byte-identical program output) → switch the default to LLVM,
embedded lld, single binary → ORC JIT, `.kts`, REPL → cross-compilation and static musl binaries.

## 11. Working method: manager and Sonnet agents

- **Manager** (this agent): architecture, interfaces between packages,
  write work packages with clear acceptance tests, review diffs, run tests
  themselves, merge, keep the plan up to date. The manager writes no feature code.
- **Workers**: agents on **Sonnet 5.5**, one per work package, each in its **own
  Git worktree**. Packages that do not block each other run in parallel.
- Every package contains: goal, affected directories, interface (signatures/headers),
  acceptance tests, "do not touch" list. A package is done only after a green local
  suite and manager review.
- Parallelizable from P1:
  1. Runtime (C: object header, RC, strings, exception support)
  2. IR→C emitter
  3. Box-test runner and CI
  4. stdlib `actual`s
  5. `mini.memory` API and diagnostic test cases (written before the analysis: test-first)
  6. cinterop/bindgen

## 12. Risks and open questions

- **Internal compiler API (variant C):** IR and Fir2Ir APIs are not stable. Countermeasures:
  pin the Kotlin version, upgrades as their own work package, keep the contact surface small.
- **Ownership inference vs. real Kotlin code:** Strict will never work for arbitrary foreign code.
  This is intentional; that is what `Managed` is for.
- **Exceptions in the C backend** cost one branch per call. Accepted until the
  LLVM backend.
- **UTF-16 strings** are more memory-hungry, but necessary for 1:1 semantics.
- **License:** the Kotlin stdlib and the K/N runtime are Apache-2.0. Taken-over files keep
  their header, and a NOTICE file is added.
