# Compiler and runtime optimization campaign

Status: in progress; changes are isolated on `codex/optimization-campaign`, with no automatic merge to main.

## Targets and comparison rules

Starting commit: `8ce0e5c61ddf7fe2992777b9e6b5c753070e555e`.
The generated-program target is Mini runtime / optimized hand-written C runtime <= 1.2 for representative complex programs. This is a stretch target, not an achieved result or universal guarantee. Use equivalent algorithms, workloads, output, floating-point semantics and target architecture; record optimization flags. Compare both Mini LLVM API and C backends. Unsafe fast math is excluded. There is no uniquely perfect C implementation: record the reference and tuning performed.

Other stretch goals: 20% fewer retired self-compilation instructions and additional binary/package size reductions beyond PR45. Preserve Kotlin semantics, ownership, exceptions, C, WASM, ORC and deterministic self-hosting.

## Hardware and method

Apple M4, arm64, 10 cores, macOS 26.2 (25C56), Apple clang 17.0.0. CPU-heavy builds and measurements have a single-agent lease. Five measured self-compilation runs follow a warmup; report medians and variation, not only fastest runs. Fixed 153-input workload emits both C and MIR. See `bench/results/campaign-baseline.json` for the initial installed-compiler baseline and `bench/results/campaign-core-pilot.json` for paired candidate measurements.

The initial installed compiler baseline and rebuilt C-backend pilot use different compiler builds. Their instruction totals must not be compared as an optimization effect.

## Worker ownership

Luna workers inspect compiler frontend/lowering (A), LLVM backend (B), package/link size (C), runtime/memory (D, pending), and benchmark infrastructure (E). Lead approves architectural and ownership changes, reviews and integrates commits individually. User approved continuing with the current Sol lead.

## First wave

### Compiler core

Avoid allocating an empty substitution HashMap for classes with no type parameters in `TypeOps.substOf`. No global cache or lazy-resolution changes. General review clear; frontend suite 32/32. Baseline/candidate emitted C and MIR are byte-identical.

Paired five-run pilot uses identically built C-backend compiler executables and a fixed source list (SHA256 `31ab0eab3e22a41a534abf7be6f2ca9d203922ad6ca53dd9bc5d8529989c836b`).

| Metric | Baseline | Candidate | Difference |
|---|---:|---:|---:|
| Self-compile retired instructions, median | 44,658,569,433 | 44,535,558,464 | -0.28% |
| Wall time, median | 2.537 s | 2.533 s | within noise |
| Peak RSS, median | 684,949,504 B | 684,212,224 B | -0.11% |

Raw wall ranges: baseline 2.487–2.644 s, candidate 2.503–2.572 s. No wall-time speedup claim. Initial overlapped pilot was discarded before the paired run.

### Binary size (experiment; functional validation pending)

On a staged copy of the macOS release compiler, `strip -x` retains external exports while removing local symbols. Compiler: 60,849,928 -> 47,100,464 B (-22.60%); compressed archive: 20,761,241 -> 18,831,722 B (-9.29%); installed files: 65,591,728 -> 51,842,264 B. External nm names and dyld export-trie names match exactly. Package smoke and reviewed packaging implementation remain pending. This removes local symbolication metadata; source/debug builds must retain it. No Linux gain is claimed.

### LLVM backend

Rejected a Mandelbrot devirtualization change based only on pre-optimization IR: the optimized hot loop already has no indirect calls or exception checks. Per-dispatch-group throw analysis remains an unapproved proposal requiring a demonstrated hot-path benefit and conservative open-world handling.

### Runtime and benchmark infrastructure

Runtime investigation and fair hand-C/API comparisons pending. Benchmark runner compatibility repair is under review; unavailable RC/allocation statistics must be explicitly reported.

## Validation still required

Integrated remeasurement; stage2/stage3 deterministic bootstrap; backend behavioral comparisons; runtime sanitizers; C/API/WASM/ORC and release package smoke. Current results are preliminary, not full campaign validation.

## Next measured opportunities

Profile HashMap/string hashing and iterator allocations during self-compilation. Inspect optimized code in complex workloads before changing MIR/LLVM. Consider allocation-free supertype traversal only after proving lazy-resolution order and semantic equivalence. Do not weaken exception checks or remove standard-library source fallback.
