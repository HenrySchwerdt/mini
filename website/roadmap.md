# Roadmap and status

This page separates what exists today from what is planned. Anything under "Planned" is a design in `docs/plan-kotlin.md`, not something you can use. Sources: the project README and the plan.

## Done

| Area | State |
|---|---|
| **Self-hosting (B3)** | **Done.** The native `minic-native` compiles itself without a JVM or K2; stage 2 and stage 3 are byte-identical, through the C backend and both LLVM backends. CI bootstraps from the latest release. |
| **Own frontend (B2)** | **Done** and the default: own parser (0 mismatches against K2's PSI tree on large fuzzed corpora), own type checker (99.99 % agreement with K2 FIR) and lowering to MIR without K2. |
| **Releases** | Prebuilt archives for linux-x64, linux-arm64 and macos-arm64, plus a C bootstrap seed. See [Install](/install). |
| **Memory model checker** | Diagnostics M0101 to M0115, M0202, M0204 to M0207 ([reference](/reference/diagnostics/)), natively (`minic-native --check`) and in the browser. M0201 and M0203 are specified but not implemented. |
| **C interop** | `external` with `@CName`, `CPointer`/`*Var`, `memScoped`, `@CStruct`, `staticCFunction`, `@file:CInclude`/`@file:CLink`, `@Owned(free=)`. See [C interop](/guide/c-interop). |
| **LLVM backend** | MIR to LLVM IR, as text or in memory through the LLVM C API bound in Mini; `minic-native --run` runs programs with the ORC JIT. |
| **Playground and notebook in the browser** | The compiler built for WebAssembly runs in your browser ([Playground and notebook](/guide/playground)), and every example in the documentation is a live cell ([Learn Mini](/learn/)). |
| **Matrix operator `@`** | `a @ b` calls an `operator fun matmul`; no type implements it by default. See [Mini and Kotlin/JVM](/guide/mini-vs-kotlin). |
| **Performance, first round** | RC elision, a size-class allocator and faster arenas, inline array accessors, borrowed array reads, fast integer formatting. Several benchmarks are within 1.0 to 1.3x of C. See [Benchmarks](/benchmarks). |
| **Language and stdlib** | classes, `sealed`/`data`/`enum`/`object`, interfaces, generics, lambdas, `inline`/`reified`, exceptions, extensions, collections. See [Mini and Kotlin/JVM](/guide/mini-vs-kotlin). |
| **Platforms** | Linux and macOS (CI). |

## In progress

| Area | State |
|---|---|
| **Native `mini` project tool** | The `mini` tool (`mini new/build/run/test`, `mini.toml`) is being rebuilt as a native binary compiled by Mini, so that it ships in the release archives and needs no JVM. |
| **Repository without Gradle** | After the native `mini`, the test suite moves to `mini test`; the K2 cross-checks move to a separate, optional tool. |
| **LLVM performance** | Bringing the LLVM backends to at least the speed of the C backend, then making LLVM the default. |
| **Strict codegen and stdlib on the memory model (D2)** | Strict programs are checked everywhere; programs that use allocator objects and `mini.memory` do not build everywhere yet, so the memory chapters of the tutorial use Check only. |

## Planned

<p class="status-planned">Everything in this section does not exist yet.</p>

| Area | Plan |
|---|---|
| **Dependencies and packages** | Dependencies in `mini.toml` by git URL or path with a lockfile, later precompiled `.mlib` packages, a build cache and a registry. |
| **Reflection** | Static, read-only metadata for `KClass.members`, `memberProperties`, `KProperty.get/set`, `KFunction.call`, only for classes that are reflected on, with ownership annotations stored in the metadata. Today only `Foo::class` and `simpleName`/`qualifiedName` exist. |
| **Compiler plugins** | A plugin API that generates code at compile time (serializers, DI wiring, route tables). |
| **Libraries outside the stdlib** | `mini-serialization` (JSON in the style of kotlinx.serialization), `mini-web` (a web framework with compile-time DI and routing and one arena per request), tensor libraries on the `@` operator. Separate packages, not part of the stdlib. |
| **Coroutines and async IO** | `suspend` lowering, a dispatcher on libuv, `mini.io` with timers, files, TCP/UDP, DNS. `suspend` is a compile error today. |
| **LLVM by default** | Embedded linker, static libuv, `.kts` scripts and a REPL on the JIT, real exception unwinding. |
| **Performance, second round** | A faster `HashMap` and cached string hashes (wordcount), runtime memory layout, link-time optimization, arenas inside the compiler. |
| **Windows** | Linux and macOS first. |
| **Runtime written in Kotlin** | Rewrite libminirt piece by piece in Strict mode, and replace libuv step by step. |
| **Cycle collector and weak references** | Reference cycles leak today. |
| **Escape analysis and stack placement** | Specified (M0201, M0203) but not implemented. |
| **Collections of owned values** | `List<@Owned T>` and arrays of owned elements; today they are rejected with M0110. |
| **Kotlin libraries** | Source of Kotlin Multiplatform libraries (`commonMain`). JVM-only `.jar` libraries are deliberately not supported. |

## Plan phases

The plan numbers phases P0 to P8 (spike, core backend, runtime and stdlib, memory model, interop, concurrency and IO, libraries, LLVM and performance, self-hosting). The status above is the current view; the original phase table is in `docs/plan-kotlin.md`, section 10.
