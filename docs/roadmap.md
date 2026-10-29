# From bootstrap to a usable language

The current implementation is an executable prototype with a tested class/module/
generic/annotation core, native Optional/Result and a first allocator-backed list std. A
finished language requires behavioral stability, a coherent std, tools and a
verified platform matrix, not just more syntax. No percentage or time estimate is
meaningful before the intended release scope is fixed.

## Current evidence

- The language checker and native CLI integration tests run locally on macOS arm64.
- Native programs exercise inheritance/interface dispatch, generic specialization,
  packages/imports, annotations, returned/nested closures, typed callbacks,
  nested Optional/Result patterns, control flow and
  explicit allocation/freeing. Two libuv workers exercise synchronized shared state,
  join/close behavior and typed lifecycle errors.
- The public compiler can emit checked C17, native executables, or LLVM IR through Clang.
- The frontend/driver build and tests also run with the optional LLVM C library disabled.
- A Linux/macOS/Windows CI matrix is checked in; Linux/Windows have not been run here.
- The language, syntax, object, lambda, optional-memory and threading suites have
  166 cases. Sanitizer checks use AddressSanitizer and UndefinedBehaviorSanitizer;
  the native threading example also runs under ThreadSanitizer locally. This is regression evidence, not proof of
  complete correctness or a measured coverage claim.

## Next release milestones

1. **Stabilize semantics and diagnostics.** Expand negative tests, malformed-source
   tests and cross-module cases; define constant ranges, pointer constness,
   aggregate initialization and resource copying/cleanup precisely. Object roots,
   typed allocation factories and recursive heap-residency checks are implemented;
   ownership/move/drop and allocator-pairing enforcement remain open.
   [The Kotlin-inspired syntax core](syntax-direction.md) is implemented;
   retain Mini's class-only methods and exhaustive pattern-matching `match`.
2. **Concrete backend representation and direct LLVM.** Extract backend-neutral
   concrete operations from the current lowering. Implement type layouts,
   expressions, control flow, calls, generic instances and virtual dispatch in the
   direct LLVM backend, then verify object/link behavior against the same native
   integration tests. Keep the C emitter as a comparison/debugging backend.
3. **A useful std.** Follow [the std conventions](../std/README.md): Optional for
   absence and Result for failures. Add allocator-backed strings/collections,
   iteration, byte buffers and files with documented ownership. The first ArrayList
   subset is implemented and tested, including allocation failure and bounds errors. Higher-order
   Kotlin-style APIs can now use function types and initial lambdas; generic instance
   methods, broader captures and resource cleanup rules remain open. The first
   libuv Thread/Mutex subset is implemented; task transfer/lifetimes, data-race
   safety, atomics and structured concurrency remain open.
4. **Portable tooling.** Run and fix the OS matrix, define dependency metadata and
   versioning, package/build tooling, and implement Mini diagnostics/navigation
   rather than treating clangd support for the C bootstrap as a Mini language server.
5. **Self-hosting.** Write a compiler in Mini only after the language, source I/O,
   allocation, diagnostics and ABI are dependable. Bootstrap it with mini0 and
   compare successive compiler stages on the same suite.

## Removing the C dependencies

There are three separate dependencies:

| Layer | Current state | Replacement |
| --- | --- | --- |
| Compiler implementation | C17 bootstrap | A self-hosted Mini compiler, later |
| Generated program implementation | C17 intermediate compiled by Clang/LLVM | Direct LLVM/object emission |
| Runtime services / startup | libc allocation, stdio, libuv threading, OS wrappers and C entry/link setup | Small target adapters plus Mini allocator/I/O/startup support |

Direct LLVM emission removes the generated C translation step. It does **not**
automatically remove `malloc`, `free`, `printf`, libc startup or linker dependencies.
Removing the standalone `runtime/` C files alone also does not remove them: the
current working emitter generates its own tiny libc-based helper functions.

A practical migration is:

1. Define target-neutral runtime contracts (page allocation, byte I/O, process exit).
2. Implement target adapters: Linux syscalls, Windows APIs, and supported macOS
   system interfaces. Keep platform-specific assumptions outside the typechecker.
3. Implement the heap allocator and formatting/buffer logic in Mini on those contracts.
4. Teach the direct backend/driver the corresponding runtime symbols and target
   entry/link rules. Keep one platform green before expanding to another.
5. Add binary inspection tests for forbidden runtime symbols and tests for
   allocation failure, resizing, I/O errors and cleanup.

A portable hosted program still uses operating-system interfaces and may need the
platform's system libraries. A fully freestanding executable with no libc/startup
at all is a separate, target-specific goal. Keeping a C-compatible ABI for
interoperability does not require implementing the language runtime in C.
