# Compiler and runtime optimization campaign

This report describes the completed first phase only. The user subsequently extended the campaign; see [continued runtime and escape analysis](escape-analysis.md) for later source changes, measurements and outstanding validation. Statements below about retained changes and completion apply to that earlier snapshot.

Completed two investigation/implementation waves on `codex/optimization-campaign`. No merge to main and no release publication. Starting commit: `8ce0e5c61ddf7fe2992777b9e6b5c753070e555e`.

## Outcome

Retained a small, measured frontend allocation reduction and a large macOS toolchain-size reduction. Repaired the existing benchmark runner for the current CLI and truthful build provenance. Rejected both runtime traversal and compiler O2-default experiments because they did not establish an overall performance win. No LLVM, runtime ABI, ownership or floating-point semantic changes remain.

The 20% self-compilation instruction-reduction stretch goal was not reached. The generated-program goal, Mini runtime <=1.2x well-optimized equivalent C, was met on N-Body and Fannkuch but remains unmet on JSON. No universal performance claim is made.

## Hardware, toolchain and method

Apple M4, arm64, 10 cores, macOS 26.2 (25C56); Apple clang 17.0.0; LLVM 18.1.8. CPU-heavy builds and timing runs had an exclusive lease, avoiding concurrent benchmarks. Luna workers owned independent compiler-core, LLVM, size, runtime and benchmark scopes; the lead approved semantics/architecture decisions and integrated reviewed commits individually.

Self-compilation measurements use 91 compiler and 62 standard-library Kotlin inputs from clean 8ce sources, emitting C and MIR. They measure compiler execution including serialization, **not a final clang compile/link or complete LLVM object-generation pipeline**. Warmups precede five alternating pairs or rotating triads. Input hashes, output hashes, commands, all samples, retired instructions, cycles and RSS are retained in JSON. The C/MIR outputs match the initial baseline hashes exactly.

Application runs use one warmup and five output-checked samples. C reference variants are O2, O3 and O3 `-mcpu=native`, with `-fno-fast-math -ffp-contract=off`; API uses precise FP and its current generic CPU target. Ratios use the fastest correct C variant's median. This is an explicit tuned reference, not proof of a uniquely perfect C implementation. PR45 dead stripping is applied consistently to baseline and candidate app links and is not counted again.

## Final comparison

Primary source-change control: **both compiler binaries built locally with LLVM API O1, identical runtime objects and static link recipe**. This avoids differences between a CI-built published runtime and the current local runtime.

| Metric | Baseline | Retained optimized result | Difference |
|---|---:|---:|---:|
| Self-compile retired instructions, median | 47,223,885,034 | 47,118,649,919 | -0.223% |
| Self-compile wall time, median | 2.690 s | 2.640 s | overlapping ranges; no meaningful speedup established |
| Self-compile peak RSS, median | 697,434,112 B | 697,663,488 B | effectively flat (+0.033%) |
| Packaged compiler binary | 60,849,928 B | 47,100,464 B | -22.60% |
| Installed regular files | 65,592,105 B | 51,842,641 B | -20.96% |
| Compressed toolchain archive | 20,770,837 B | 18,831,916 B | -9.33% |
| Hello World object | 872 B | 872 B | byte-identical |
| Hello World executable* | 54,280 B | 54,352 B | +72 B; build-environment comparison |
| Representative JSON object | 66,752 B | 66,752 B | byte-identical |
| Representative JSON executable* | 102,360 B | 102,424 B | +64 B; build-environment comparison |

Compiler/install/archive figures come from the **same integrated installation tree**, using identical tar flags and substituting only the compiler before/after stripping. Original stage3 remains untouched. They isolate the symbol-stripping effect.

\* Application objects are byte-identical. The linked executable comparisons use published versus locally built runtime archives; the small byte differences are not attributable frontend optimization gains or regressions. No generated-program binary-size reduction was demonstrated. Source and bootstrap compiler artifacts retain local symbols; local symbolication detail is reduced only in the packaged compiler.

The isolated C-built core pilot independently measured 44.659B -> 44.536B instructions (-0.28%) and -0.11% RSS; wall time was flat. Do not compare its absolute totals with the differently built API compilers. The initial published-package versus local-package API comparison observed -1.41% instructions, but includes runtime/build-environment differences; it is not the source-change claim. The same-local-toolchain control above is the primary attributable result.

## Generated-program runtime

These measurements describe existing code-generation quality. The retained frontend shortcut produced byte-identical application objects, so this campaign makes no generated-code speedup claim.

| Program | Fastest correct C median | Integrated API O3 median | Mini / C | <=1.2 goal |
|---|---:|---:|---:|---|
| nbody | 0.7387 s | 0.8271 s | 1.120x | yes |
| fannkuch | 1.9871 s | 1.7013 s | 0.856x | yes |
| json | 0.3334 s | 0.5036 s | 1.511x | no |

N-Body and Fannkuch use matched algorithms, iteration counts and outputs, with array/object representation differences recorded. JSON uses byte strings/custom buffers in C versus UTF-16, substrings and collections in Mini; its remaining end-to-end gap stays visible. Wordcount is excluded from strict ratios because its C hash-table algorithm differs materially.

Application sample ranges, Mini C/O2 comparisons, RSS and sizes are in the result JSON. Final API compilation/link observations were approximately 0.120/0.028 s (N-Body), 0.110/0.036 s (Fannkuch), and 0.262/0.033 s (JSON). These are single build observations, not robust compile-time before/after evidence. Retain/release and allocation counters were unavailable in the repaired runner and are explicitly marked; no counts were invented.

## Retained changes by scope

**Compiler core:** `TypeOps.substOf` returns an empty immutable map for classes without type parameters, avoiding an unnecessary HashMap allocation. Callers consume the map read-only or copy it; no global caching, lazy-resolution ordering or type semantics changed.

**Binary size:** `scripts/package-release.sh` applies `strip -x` only to the staged macOS arm64 compiler copy. `scripts/test-package-release-symbols.sh` checks external symbols, dyld export names, local-symbol reduction and size; failures abort packaging. Both external-name sets match exactly: 36,692 names, SHA256 `fb27b4187c3a715bb74803acbf954c071aa9863703e8b1c504a1c6b3af754894`. Local symbols fall from 132,542 to one. No Linux setting or stdlib-source fallback was removed.

**Benchmark infrastructure:** current `-O2 --backend=c|llvm-api` commands, MINIC override, backend/flag sidecars, unknown provenance for old untracked artifacts and honest unavailable-counter reporting. Five focused tests passed.

**LLVM and runtime:** no source changes retained. Existing canonical LLVM pipelines and memory ownership remain intact.

## Rejected experiments and decisions

1. **Superclass-first runtime type check:** 268/2,147 top-of-stack samples pointed to `type_is`. The fast path preserved behavior and passed focused ASan/UBSan checks, but duplicated work on interface/negative checks. Five pairs showed +2.59% instructions and +1.26% paired median wall time. All code was removed. See `typecheck-experiment.md` and its raw JSON.
2. **Compiler built with LLVM O2 instead of bootstrap O1:** identical sources/runtime objects/link flags, five rotated triads. O2 retired 0.514% fewer instructions than integrated O1, but median wall time was 2.07% higher with overlapping ranges. Stripped binary saved only 33,056 B (0.07%); build observations were 35.33 s versus about 33.3 s. The small instruction/size gains do not establish a compelling net win; bootstrap remains O1. C/MIR/LLVM IR outputs matched, and no configuration change remains.
3. **Mandelbrot devirtualization/check removal based on pre-optimization IR:** rejected because optimized assembly already had no such calls/checks in its hot loop.
4. **New LLVM passes, host tuning, global type caches, symbol-export removal or layout changes:** no demonstrated need/safe measured benefit. The canonical O2/O3 pipeline is already present. Native tuning remains an optional future design, not an implicit portability change.

## Validation

Retained source validation at `aaa8758` passed; later commits add reports/results only. Raw summary: `bench/results/campaign-validation.json`. Ignored logs and unpublished local package `0.7.0-campaign` live under `build/campaign-validation/`.

- C bootstrap: stage2/stage3 C and MIR byte equality, plus native memory-check probes.
- Module tests: backend 85, frontend 32, native-driver 2; 119 passed.
- C E2E: 329 passed, 0 failed, one expected thread-related skip across C, sanitizer/leak, ownership diagnostics/execution, multifile and mlib suites. macOS has ASan/UBSan here, not LeakSanitizer.
- LLVM 18 API bootstrap: stage2/stage3 C, MIR, LLVM IR and object equality; API-built plain compiler C/MIR matched the C chain.
- LLVM API E2E: 74/74 passed. REPL session and ORC session API smoke passed; its unresolved-symbol diagnostic is an intentional rollback case.
- Actual integrated macOS package: strip/export invariants, default LLVM API, explicit C fallback and installed REPL smoke passed. Dynamic dependencies are system-only, with no external LLVM/Homebrew library. Duplicate `-lm` and Homebrew libunwind re-export link warnings did not introduce external package dependencies.
- General source review and Python review cleared retained changes; benchmark tests and diff checks passed.

Linux, a fresh browser/WASM bundle and the complete runtime/thread unit matrix were not rerun. No runtime or LLVM source changes remain, but these are explicit validation limits. No per-example WASM program assets were added.

## Persistent evidence and next opportunities

- `bench/results/campaign-baseline.json`: initial installed-compiler baseline/profile provenance.
- `bench/results/campaign-core-pilot.json`: matched C-built compiler source-change pairs.
- `bench/results/campaign-runtime-baseline.json`: tuned hand-C, Mini C and API O2/O3 application comparisons.
- `bench/results/campaign-integrated.json`: final application output/object checks, package observation, isolated local API pairs, O1/O2 triads and build manifests.
- `bench/results/campaign-typecheck-experiment.json`: rejected runtime experiment.
- `bench/results/campaign-validation.json`: integrated correctness/package summary.

Future work should profile JSON's string/index/allocation paths and iterator/interface-dispatch overhead, then propose targeted changes with equivalent-semantics C comparisons. Compiler-known concrete-class checks may avoid interface traversal, but require a separate architecture/ABI audit. Allocation-free supertype traversal needs proof of lazy-resolution order. Linux section/GC and architecture-specific static LLVM footprint changes require actual target-platform measurements. The 20% and broad 1.2x goals remain open; this campaign stops after the two measured waves rather than adding speculative optimizations.
