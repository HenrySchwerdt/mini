# Mini

**Kotlin, compiled to native code, with manual memory management the compiler can check.**

[![Build and test](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml/badge.svg)](https://github.com/HenrySchwerdt/mini/actions/workflows/build.yml)
[![Documentation](https://img.shields.io/badge/docs-henryschwerdt.github.io%2Fmini-6d4aff.svg)](https://henryschwerdt.github.io/mini/)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Mini is **Kotlin**: same grammar, same semantics, same standard library. The compiler `minic` does not
target the JVM. It translates Kotlin through an in-process LLVM API into **native binaries** by default (the C emitter remains available with `--backend=c`) and adds its own
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
* **Self-hosting.** `minic` compiles its own backend to a native binary; release compilers statically link LLVM 18 and retain the C emitter for bootstrap and fallback builds.
* **Checked against real Kotlin.** Expected outputs of the test programs come from Kotlin on the JVM.
  The runtime tests run under ASan, UBSan and TSan.

## How it works

```text
                  ┌─► K2 frontend (oracle) ─────────┐
  Kotlin source ──┤                                 ├─► MIR ─► RcOpt ─► LLVM C API ─► object + system linker ─► binary
                  └─► own frontend (Mini) ─────────┘                 └─► C emitter (--backend=c)
                         lexer, parser, type checker
```

`MIR` is Mini's mid-level IR. `RcOpt` removes reference-counting work. The LLVM C API builds native objects in process; the C emitter can write plain C17 that links against `libminirt`, a small C runtime (object model, refcounting, strings, allocators).
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
| **Full bootstrap, stage 2 == stage 3 (B3)** | **Done.** `compiler/bootstrap.sh` checks the C, LLVM IR, and LLVM API object builds. |
| Project tool `mini` (`mini new/build/run/test`, `mini.toml`, path and git dependencies) | **Done, and native**: `mini` is Kotlin compiled by Mini itself and calls `minic-native`; no JVM, no Gradle. It ships in the release archives. See [docs/mini-tool.md](docs/mini-tool.md). Dropping Gradle from the rest of the repository (tests, K2 oracle) is next. |
| Reflection (static metadata, ownership-aware) and a compiler-plugin API in the toolchain; JSON (`mini-serialization`) and a web framework with compile-time DI and one arena per request (`mini-web`) as separate libraries | Planned after B3. |
| LLVM API compiler, statically linked LLVM, API default, C fallback, and ORC REPL | **Done.** Release compilers include LLVM 18 with no LLVM runtime dependency; building programs still uses the platform C linker. |
| Embedded lld, statically bundled libuv, `.kts` scripts | Planned. |
| Coroutines and async IO on libuv, runtime written in Kotlin, Windows | Later. |

Platforms: Linux and macOS (CI). Windows follows later. The full plan is in
[docs/plan-kotlin.md](docs/plan-kotlin.md).

## Quickstart

### Install a release

Prebuilt `mini` (project tool) and `minic` (compiler) for Linux (x64, arm64) and macOS (arm64), no JDK, Gradle or LLVM install; a C linker (clang or gcc) is still needed to build programs:

```sh
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
export PATH="$HOME/.mini/bin:$PATH"
mini new hello && cd hello && mini run
```

For a single file there is the bare compiler: `minic build hello.kt -o hello && ./hello`.

The script installs the latest [release](https://github.com/HenrySchwerdt/mini/releases) into `~/.mini` after checking its
SHA256 (`MINI_VERSION=0.4.0` picks a version, `MINI_HOME` the directory). A release also carries a portable C bootstrap
seed (`mini-<version>-bootstrap-c.tar.gz`): `./build.sh` in it builds a native compiler with `cc` alone. Details in
[compiler/README.md](compiler/README.md#releases).

### From a source checkout

Requirements: clang (or gcc). No JDK and no Gradle: the first `./mini` builds the native compiler and the tool
(`scripts/build-mini.sh`, about a minute) starting from a native compiler it finds or downloads (the latest release).
libuv is only needed for the runtime tests.

```sh
./mini new hello && cd hello && ../mini run
```

The bare compiler wrapper `./minic` (`./minic run examples/hello.kt`, `./minic check examples/memory_leak.kt`) is native too (the
driver script `scripts/minic.sh` over `build/native/bin/minic-native`). The K2 cross-checks (official Kotlin compiler on the JVM)
are a separate optional tool, [tools/k2-oracle](tools/k2-oracle/README.md).

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

Without `PATH` use `./mini new hello && cd hello && ../mini run`. `mini` is a native binary (about 3 MB, starts in a few milliseconds); in a checkout it lives in `build/native/bin/mini`. It also has `build --release`, `add`, `remove`,
`update`, `tree`, `clean`, `doctor` and shell completions; dependencies are paths or git repositories pinned in
`mini.lock`. Full reference: [docs/mini-tool.md](docs/mini-tool.md).

### Self-hosting in one minute

`minic` compiles itself (frontend, lowering, backend: pure Kotlin) to a native binary, and that binary compiles itself again to
the identical C:

```sh
compiler/bootstrap.sh          # stage0 (a native compiler) -> stage1 -> stage2 -> stage3; stage2 and stage3 are byte-identical
```

## Repository layout

| Directory | Contents |
|---|---|
| `compiler/` | the compiler and the `mini` tool as flat Mini projects (`mini.toml`, `src/`, `tests/`): own frontend, lowering, MIR, C17/LLVM backends, ownership checker. [README](compiler/README.md) |
| `tools/k2-oracle/` | optional cross-checks against the official Kotlin compiler on the JVM (the only Gradle build; nightly CI). [README](tools/k2-oracle/README.md) |
| `runtime/` | libminirt (C17): object model, refcounting, strings, allocators. [README](runtime/README.md) |
| `stdlib/` | Mini stdlib as Kotlin source (ported from the Kotlin stdlib, Apache-2.0) |
| `stdlib-test/` | stdlib tests that run on the JVM against the real Kotlin stdlib (run by `tools/k2-oracle`) |
| `examples/` | example programs |
| `website/` | the [documentation site](https://henryschwerdt.github.io/mini/) (VitePress), its checked code samples and generators |
| `docs/` | [plan and roadmap](docs/plan-kotlin.md), [memory model](docs/memory-model.md), [stdlib](docs/stdlib.md), [benchmarks](docs/benchmarks.md) |

## Testing

```sh
runtime/run_tests.sh                 # runtime under ASan/UBSan/TSan (set LIBUV_ROOT if needed)
scripts/check-purity.sh              # the compiler packages use no JVM API
(cd compiler && ../mini test)        # unit tests of backend, frontend, native-driver, mini-tool (mini-tool is slow: real builds)
scripts/test-e2e.sh                  # compiler/testdata: C backend, ASan/LSan/UBSan leak check, memory-model corpus (--suite llvm ...)
compiler/bootstrap.sh                # stage2 == stage3 (also --backend=llvm and llvm-api)
```

Expected outputs of `compiler/testdata` come from real Kotlin on the JVM. CI runs these suites on Linux and macOS per pull
request without a JDK; the K2 cross-checks (`tools/k2-oracle`) run nightly. See [docs/no-gradle.md](docs/no-gradle.md).

## History

The earlier C bootstrap compiler `mini0`, with its own syntax, has been removed. Its last state is
merge commit `391270a` on `main`.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The stdlib contains code ported from the
Kotlin standard library (Apache-2.0, JetBrains and contributors), see [stdlib/NOTICE](stdlib/NOTICE).
