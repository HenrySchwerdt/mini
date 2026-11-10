# LLVM backend parity with the C backend, November 2026 (`claude/llvm-parity`)

Host: 4-core Xeon VM, shared with other jobs (wall noise +-5 percent), LLVM 18.1.3, `minic build -O2` with `--backend c` and
`--backend llvm-api` (same runtime archive, same programs). Wall = median of 7 runs, C and LLVM interleaved (order alternated per
repetition); Ir = `valgrind --tool=callgrind` total of the full-size run. Instruction counts are the reliable column: wall differences
on programs with equal Ir were checked in the disassembly (spectralnorm: the hot loops are the same instructions).

## Before (origin/main 282b384, API bootstrap compiler)

| benchmark | C s | LLVM s | wall LLVM/C | Ir C (G) | Ir LLVM (G) | Ir LLVM/C |
|---|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.37 | 1.62 | 1.18 | 13.55 | 13.82 | 1.020 |
| binarytrees.arena | 1.26 | 1.31 | 1.04 | 13.75 | 14.02 | 1.020 |
| binarytrees.strict | 1.50 | 1.62 | 1.07 | 16.22 | 16.49 | 1.017 |
| fannkuch | 2.75 | 2.88 | 1.05 | 20.71 | 20.05 | 0.969 |
| graph | 1.82 | 2.10 | 1.15 | 2.27 | 2.67 | **1.179** |
| json | 1.13 | 1.13 | 0.99 | 4.34 | 4.27 | 0.984 |
| mandelbrot | 1.08 | 0.98 | 0.91 | 6.81 | 6.73 | 0.989 |
| nbody | 1.08 | 1.12 | 1.03 | 9.00 | 8.95 | 0.994 |
| spectralnorm | 1.72 | 1.65 | 0.96 | 20.00 | 20.00 | 1.000 |
| strbuild | 0.79 | 0.85 | 1.09 | 9.86 | 10.03 | 1.018 |
| wordcount | 3.04 | 3.09 | 1.02 | 14.41 | 16.86 | **1.170** |

## After (merged with origin/main 145b481)

| benchmark | C s | LLVM s | wall LLVM/C | Ir C (G) | Ir LLVM (G) | Ir LLVM/C |
|---|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.686 | 1.681 | 0.997 | 13.57 | 13.23 | 0.975 |
| binarytrees.arena | 1.477 | 1.363 | 0.923 | 13.76 | 13.42 | 0.975 |
| binarytrees.strict | 1.580 | 1.469 | 0.930 | 16.17 | 15.83 | 0.979 |
| fannkuch | 2.718 | 2.707 | 0.996 | 20.71 | 20.05 | 0.969 |
| graph | 1.142 | 1.108 | 0.970 | 2.13 | 2.02 | 0.946 |
| json | 1.079 | 1.032 | 0.957 | 4.40 | 4.08 | 0.927 |
| mandelbrot | 1.095 | 1.074 | 0.981 | 6.81 | 6.73 | 0.989 |
| nbody | 0.957 | 0.902 | 0.943 | 9.00 | 8.95 | 0.994 |
| spectralnorm | 1.415 | 1.429 | 1.010 | 20.00 | 20.00 | 1.000 |
| strbuild | 0.784 | 0.775 | 0.988 | 10.23 | 10.22 | 0.999 |
| wordcount | 3.132 | 2.804 | 0.895 | 14.16 | 13.94 | 0.984 |

LLVM is at or below C in instructions on every program and within 1 percent (or faster) in wall time.

## What was missing (found with callgrind per function)

wordcount and graph showed out-of-line runtime calls that the C prelude inlines: `mk_sb_append_char` (12 percent of wordcount),
`mk_box_int`, `mk_is_instance` for `is Int`, `mk_checked_unbox_int`, and `mk_checked_not_null` (9 percent of graph). The checked casts
went through helper calls followed by a load of `mk_pending_exception` on every cast.

## Changes (compiler/backend/src/llvm/Lowering.kt, builders)

* `mkh_box_int` (always-inline helper): range test, acquire load of `mk_int_box_cache`, address of the shared box; else `mk_box_int`.
  New `IrBuilder.loadAcquirePtr` (text: `load atomic ... acquire`; API: `LLVMSetOrdering`).
* `mkh_sb_append_char` (always-inline helper): room and fitting character are two stores with TBAA tags on the builder fields; growth,
  widening and shared-buffer builders call `mk_sb_append_char`.
* `is String` / `is <box>`: `o != null && o->type == ti` inline.
* `NotNull`, checked casts (class, String, box, non-null `Any`) and `Unbox` of primitives: the test is the only code on the fast path,
  the failing path is a cold block that calls the runtime and leaves through the function's exit (as `arrayAccess` already did), so no
  pending-exception load follows. Classes that nothing extends also accept a direct superclass like the C emitter. Interface casts and
  array casts keep the old helper path.
* `x / c`, `x % c` with a literal `c` other than 0 and -1: plain `sdiv` / `srem`.

Already present before: inline string length / char_at, array accessors (including unchecked `mk_arrayu_*`), cached lengths, TBAA for
fields and arrays, cold branch weights on error paths. `nsw`/`nuw` flags are not emitted: the C backend uses unsigned wrapping
arithmetic too, and the instruction counts show no difference. noalias/nonnull attributes were not needed for parity.

## Validation

API bootstrap stage2 == stage3 (C, MIR, LLVM text, object), `repl-session.sh` ok; `e2e --suite llvm --suite llvm-leak --suite llvm-api`:
760 passed, 0 failed; C bootstrap stage2 == stage3, default e2e 987 passed (1 skipped), `mini test` 79 passed, strict-corpus ok=127 with no false results, check-purity and `ci-stage0.sh` ok.
