# Mini

**Kotlin, compiled to native code, with manual memory management the compiler can check.**

[![Build and test](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml/badge.svg)](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml)
[![Documentation](https://img.shields.io/badge/docs-henryschwerdt.github.io%2Fmini-6d4aff.svg)](https://henryschwerdt.github.io/mini/)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Mini is **Kotlin**: same grammar, same semantics, same standard library. The compiler `minic` does not
target the JVM. It translates Kotlin through C into **native binaries** and adds its own
**memory model** on top. Write ordinary Kotlin with reference counting by default, or opt a file into
**Strict** mode and let the compiler prove that every allocation has an owner and is released exactly once.

**Documentation: https://henryschwerdt.github.io/mini/** (guide, language tour, memory guide, diagnostics reference, how the compiler works).

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
| **Own frontend to MIR without K2 (B2-3)** | **Done.** `--frontend=mini` gives MIR and C byte-identical to the K2 path for all 47 test programs. The natively built `minic-native` (no JVM, no K2) compiles the whole compiler in about 5 s. |
| **Strict codegen and stdlib on the memory model (D2)** | In progress. |
| **Full bootstrap, stage 2 == stage 3 (B3)** | **Done.** `compiler/bootstrap.sh`: stage1 builds stage2, stage2 builds stage3, and the C and MIR of stage 2 and stage 3 are byte-identical. Checked in CI. |
| Project tool `mini` (`mini new/build/run/test`, `mini.toml`, path and git dependencies; JVM-hosted v0) | **v0 done**, see [docs/mini-tool.md](docs/mini-tool.md). A native `mini` and a repository without Gradle are next. |
| Reflection (static metadata, ownership-aware) and a compiler-plugin API in the toolchain; JSON (`mini-serialization`) and a web framework with compile-time DI and one arena per request (`mini-web`) as separate libraries | Planned after B3. |
| LLVM as the primary backend (in-process via its C API, embedded lld, single binary, no clang), statically bundled libuv, `.kts` scripts and a REPL on the LLVM ORC JIT | Planned after B3. |
| Coroutines and async IO on libuv, runtime written in Kotlin, Windows | Later. |

Platforms: Linux and macOS (CI). Windows follows later. The full plan is in
[docs/plan-kotlin.md](docs/plan-kotlin.md).

## Quickstart

### Install a release

Prebuilt `minic` for Linux (x64, arm64) and macOS (arm64), no JDK needed, only a C compiler (clang or gcc):

```sh
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
export PATH="$HOME/.mini/bin:$PATH"
minic build hello.kt -o hello && ./hello
```

The script installs the latest [release](https://github.com/HenrySchwerdt/mini/releases) into `~/.mini` after checking its
SHA256 (`MINI_VERSION=0.4.0` picks a version, `MINI_HOME` the directory). A release also carries a portable C bootstrap
seed (`mini-<version>-bootstrap-c.tar.gz`): `./build.sh` in it builds a native compiler with `cc` alone. Details in
[compiler/README.md](compiler/README.md#releases).

### From a source checkout

Requirements: JDK 21, clang (or gcc). libuv is only needed for the runtime tests.

```sh
./minic build examples/hello.kt -o build/hello && ./build/hello
./minic build examples/shapes.kt -o build/shapes && ./build/shapes
./minic check examples/memory_leak.kt
```

`./minic` builds the compiler on first use (Gradle) and then runs it.

### Create a project

`mini` is the project tool (like `cargo`; `minic` is the bare compiler underneath, like `rustc`). Put the repository
root on your `PATH` once, then:

```sh
export PATH="$PATH:$PWD"            # in the repository root; add it to your shell profile
mini new hello && cd hello
mini run                            # build and run
mini test                           # run the @Test functions in tests/
mini new geometry --lib             # a library; use it with: mini add geometry --path ../geometry
mini check                          # type-check and run the memory checker, no binary
mini explain M0102                  # explain a diagnostic
```

Without `PATH` use `./mini new hello && cd hello && ../mini run`. `mini` also has `build --release`, `add`, `remove`,
`update`, `tree`, `clean`, `doctor` and shell completions; dependencies are paths or git repositories pinned in
`mini.lock`. Full reference: [docs/mini-tool.md](docs/mini-tool.md).

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
| `website/` | the [documentation site](https://henryschwerdt.github.io/mini/) (VitePress), its checked code samples and generators |
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

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The stdlib contains code ported from the
Kotlin standard library (Apache-2.0, JetBrains and contributors), see [stdlib/NOTICE](stdlib/NOTICE).
