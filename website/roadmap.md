# Roadmap and status

This page separates what exists today from what is planned. Anything under "Planned" is a design in `docs/plan-kotlin.md`, not something you can use. Sources: the project README and the plan.

## Done

| Area | State |
|---|---|
| **v1: self-hosted native backend** | **Done.** `minic` compiles its own backend; the native `minic-backend` produces byte-identical C for every test program (`SelfHostTest`). |
| **Own parser (B2-1)** | **Done.** 0 mismatches against K2's PSI tree on the repo sources, about 230 tricky snippets, 30k random expressions, 20k random declarations and about 12k mutated repo files. |
| **Own type checker (B2-2)** | **Done.** 99.99 % agreement with K2 FIR (86658 of 86668 positions). Builds natively with the same result as on the JVM. |
| **RC elision (D1)** | **Done.** `arraylist` 405 to 265 ms, `tree` retains 8.3M to 0, self-hosted backend 749 to 603 ms. See [Benchmarks](/benchmarks). |
| **Memory model checker** | Implemented: diagnostics M0101 to M0115, M0202, M0204 to M0206 ([reference](/reference/diagnostics/)). M0201 and M0203 are specified but not implemented. |
| **Language and stdlib** | classes, `sealed`/`data`/`enum`/`object`, interfaces, generics, lambdas, `inline`/`reified`, exceptions, extensions, collections. See [Mini and Kotlin/JVM](/guide/mini-vs-kotlin). |
| **Platforms** | Linux and macOS (CI). |

## In progress

| Area | State |
|---|---|
| **Own frontend to MIR without K2 (B2-3)** | In progress. On the working branch all 47 test programs already lower to MIR identical to the K2 path, also from a natively built, K2-free `minic-native`. |
| **Strict codegen and stdlib on the memory model (D2)** | In progress. Strict programs are checked, but programs that use allocator objects, context parameters or `mini.memory` do not build natively yet (`minic build` reports `unsupported construct: value class 'NativePtr'` while compiling `mini.memory`). The planned lowering uses `mk_alloc_with`, `mk_free` and `mk_arena_*` from the runtime. |

## Next

| Area | State |
|---|---|
| **Full bootstrap, stage 2 == stage 3 (B3)** | Next. `minic` compiles itself without a JVM. |

## Planned

<p class="status-planned">Everything in this section does not exist yet.</p>

| Area | Plan |
|---|---|
| **Project tool `mini`** | `mini new`, `mini build`, `mini run`, `mini test`, `mini add`, a `mini.toml` manifest with a lockfile, libraries as source packages. First as commands of the current `minic`, after B3 rewritten in Mini as a native binary. Planned right after B2-3 (plan section 10f). |
| **Repository without Gradle** | After B3 the compiler is built by `mini`, with a C bootstrap seed (the generated C of `minic` is published with every release, so building from scratch needs only a C compiler). Gradle would survive only in optional K2 oracle checks. |
| **Reflection** | R2 after B3: static, read-only metadata for `KClass.members`, `memberProperties`, `KProperty.get/set`, `KFunction.call`, only for classes that are reflected on, with ownership annotations stored in the metadata. Today only `Foo::class` and `simpleName`/`qualifiedName` exist (R1). |
| **Compiler plugins, JSON, web framework** | R3 plugin API; `kotlinx.serialization`-style JSON (`@Serializable`, `Json.encodeToString`) with serializers generated at compile time; a Spring-like web framework with compile-time DI and routing and **one arena per request**. Needs coroutines and async IO first. |
| **Coroutines and async IO** | `suspend` lowering, a dispatcher on libuv, `mini.io` with timers, files, TCP/UDP, DNS. Not started; `suspend` is a compile error today. |
| **C interop** | `external fun` with `@CName`, `@CInclude`/`@CLink`, a `kotlinx.cinterop`-compatible API, a bindgen tool and C export. Not started; `external` is used only inside the stdlib, for runtime intrinsics. |
| **LLVM backend** | Replaces C emission later; would allow real exception unwinding. |
| **Windows** | Linux and macOS first. The generated C also uses GNU statement expressions, so MSVC is out. |
| **Runtime written in Kotlin** | Rewrite libminirt piece by piece in Strict mode with `@NoAlloc`, on a small `mini.unsafe` layer (raw pointers, atomics, `external` libc). |
| **Cycle collector and weak references** | Reference cycles leak today. |
| **Escape analysis and stack placement** | Specified (M0201, M0203) but not implemented; the checker treats escape only syntactically. |
| **Collections of owned values** | `List<@Owned T>` and arrays of owned elements; today they are rejected with M0110. |
| **Transitive `@NoAlloc` and function summaries**, **`M0207` and `minic mem-report`** (show where reference counting remains) | Designs in the plan and the specification. |
| **Dogfooding the memory model** | An `Arena` per compiler phase; stdlib internals (`ArrayList`, `HashMap`, `StringBuilder` buffers) converted to Strict. |
| **Kotlin libraries** | Source of Kotlin Multiplatform libraries (`commonMain`), later klib import and a Gradle plugin. JVM-only `.jar` libraries are deliberately not supported. |

## Plan phases

The plan numbers phases P0 to P8 (spike, core backend, runtime and stdlib, memory model, interop, concurrency and IO, libraries, LLVM and performance, self-hosting). The status above is the current view; the original phase table is in `docs/plan-kotlin.md`, section 10.
