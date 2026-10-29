# Mini

**Kotlin, compiled to native code, with manual memory management the compiler can check.**

[![Build and test](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml/badge.svg)](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml)

Mini is **Kotlin**: same grammar, same semantics, same standard library. The compiler `minic` does not
target the JVM. It translates Kotlin through C into **native binaries** and adds its own
**memory model** on top. Write ordinary Kotlin with reference counting by default, or opt a file into
**Strict** mode and let the compiler prove that every allocation has an owner and is released exactly once.

Every Mini program is valid Kotlin. Mini-specific things (allocators, `@Owned`, `x.free()`) are a plain
Kotlin library that also compiles on the JVM, so IDEs, formatters and syntax highlighting work unchanged.

## Leaks are compile errors

```kotlin
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() = println("close $size")
}

fun fill(b: Buffer): Boolean = b.size > 100

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) return null // leaks `buf`
    return buf
}
```

```text
$ ./minic check examples/memory_leak.kt
error[M0102]: allocation from `Heap` is never released
  --> examples/memory_leak.kt:14:15   allocated here; path: early return
   14 |     val buf = Buffer(n)
      |               ^
  help: call `buf.free()` on every path (or `buf.use { ... }` for AutoCloseable), or move it to an owner
```

The same analysis finds double frees, use-after-free and arena escapes. The full diagnostic catalog
(`M01xx` errors, `M02xx` lints) is in [docs/memory-model.md](docs/memory-model.md).

## Features

* **Kotlin, 1:1.** Classes, `sealed`/`data`/`enum`/`object`, interfaces, generics, lambdas and closures,
  `inline`/`reified`, exceptions, extensions, `vararg`, ranges, collections and large parts of the stdlib
  all compile natively.
* **Three memory tiers, chosen per file.**
  * **Managed** (default): reference counting. Normal Kotlin code runs unchanged.
  * **Arena / Heap / Pool**: allocators as Kotlin context parameters (`context(arena) { ... }`).
  * **Strict** (`@file:MemoryMode(Mode.Strict)`): no RC fallback. Every allocation needs a proven owner.
* **Allocator traits.** `Deallocating`, `Scoped`, `Managed` and `ThreadSafe` are ordinary Kotlin interfaces.
  The compiler derives which checks apply from the traits an allocator implements.
* **Diagnostics in the style of rustc.** Stable error codes (`M0102`, ...), primary and secondary
  source locations, and a `help:` line.
* **Less reference counting.** The `RcOpt` pass (borrow inference, last-use moves, owned constructor
  parameters) removes most retain/release pairs from generated code.
* **The stdlib is Kotlin source.** `stdlib/` is ported from the Kotlin stdlib (Apache-2.0) and compiled
  together with your program. No JDK, no stdlib JAR. See [docs/stdlib.md](docs/stdlib.md).
* **Self-hosting.** `minic` compiles its own backend to a native binary that emits byte-identical C.
* **Checked against real Kotlin.** Expected outputs of the test programs come from Kotlin on the JVM.
  The runtime tests run under ASan, UBSan and TSan.

## How it works

```text
                  ┌─► K2 frontend (today) ──────────┐
  Kotlin source ──┤                                 ├─► MIR ─► RcOpt ─► C17 ─► clang + libminirt ─► binary
                  └─► own frontend (B2, in progress)┘
                         lexer, parser, type checker
```

`MIR` is Mini's mid-level IR. `RcOpt` removes reference-counting work, and the C emitter writes plain C17
that links against `libminirt`, a small C runtime (object model, refcounting, strings, allocators).
In Strict mode a separate checker runs before code generation.

## Status

| Area | State |
|---|---|
| **v1: self-hosted native backend** | **Done.** `minic` compiles its own backend; the native `minic-backend` produces byte-identical C for every test program (`SelfHostTest`). |
| **Own parser (B2-1)** | **Done.** 0 mismatches against K2's PSI tree on the repo sources, ~230 tricky snippets, 30k random expressions, 20k random declarations and ~12k mutated repo files. |
| **Own type checker (B2-2)** | **Done.** 99.99 % agreement with K2 FIR (86658 of 86668 positions). Builds natively with the same result as on the JVM. |
| **RC elision (D1)** | **Done.** `arraylist` 405 to 265 ms, `tree` retains 8.3M to 0, self-hosted backend 749 to 603 ms. See [docs/benchmarks.md](docs/benchmarks.md). |
| **Own frontend to MIR without K2 (B2-3)** | In progress. On the working branch all 47 test programs already lower to MIR identical to the K2 path, also from a natively built, K2-free `minic-native`. |
| **Strict codegen and stdlib on the memory model (D2)** | In progress. |
| **Full bootstrap, stage 2 == stage 3 (B3)** | Next. `minic` compiles itself without a JVM. |
| Coroutines and async IO on libuv, LLVM backend, runtime written in Kotlin, Windows | Later. |

Platforms: Linux and macOS (CI). Windows follows later. The full plan is in
[docs/plan-kotlin.md](docs/plan-kotlin.md).

## Quickstart

Requirements: JDK 21, clang (or gcc). libuv is only needed for the runtime tests.

```sh
./minic build examples/hello.kt -o build/hello && ./build/hello
./minic build examples/shapes.kt -o build/shapes && ./build/shapes
./minic check examples/memory_leak.kt
```

`./minic` builds the compiler on first use (Gradle) and then runs it.

### Self-hosting in one minute

`minic` compiles its own backend (pure Kotlin) to a native binary and uses it to generate C:

```sh
# minic builds its own backend natively (~20 s)
./minic build $(find compiler/native-driver/src/main/kotlin compiler/backend/src/main/kotlin -name '*.kt') -o build/minic-backend
./minic emit-mir examples/shapes.kt -o build/shapes.mir   # frontend (K2, still on the JVM)
build/minic-backend build/shapes.mir -o build/shapes.c     # backend, native
clang -std=c17 -O2 -Iruntime/include build/shapes.c runtime/src/*.c -lm -o build/shapes && build/shapes
```

## Repository layout

| Directory | Contents |
|---|---|
| `compiler/` | `minic`: K2 frontend, Kotlin IR, MIR, C17, clang, plus the ownership checker and the own frontend. [README](compiler/README.md) |
| `runtime/` | libminirt (C17): object model, refcounting, strings, allocators. [README](runtime/README.md) |
| `stdlib/` | Mini stdlib as Kotlin source (ported from the Kotlin stdlib, Apache-2.0) |
| `stdlib-test/` | stdlib tests that run on the JVM against the real Kotlin stdlib |
| `examples/` | example programs |
| `docs/` | [plan and roadmap](docs/plan-kotlin.md), [memory model](docs/memory-model.md), [stdlib](docs/stdlib.md), [benchmarks](docs/benchmarks.md) |

## Testing

```sh
runtime/run_tests.sh                 # runtime under ASan/UBSan/TSan (set LIBUV_ROOT if needed)
(cd compiler && ./gradlew test)      # compiler: end-to-end, leak check, memory-model corpus, stdlib
```

Expected outputs always come from real Kotlin on the JVM. CI runs both suites on Linux and macOS.

## History

The earlier C bootstrap compiler `mini0`, with its own syntax, has been removed. Its last state is
merge commit `391270a` on `main`.
