# Mini vs Rust, November 2026 (`claude/perf-6`)

Host: 4-core Xeon 2.1 GHz VM, Linux 6.18, clang (C backend, `minic build -O2 --backend c`), `rustc 1.97 -C opt-level=3 -C codegen-units=1`
(the Rust ports in `bench/*/*.rs`, unchanged). The machine is noisy (+-5 percent between runs); wall times below are the median of 7
runs of the same binaries, taken back to back. Instruction counts are `valgrind --tool=callgrind` totals of the full-size runs
(`perf` is not installed here). The LLVM API backend is not in this table: this container has no LLVM-API release build to time with.

## Baseline (origin/main, `bench/run.py --reps 5 --langs rust,mini-managed`, best of 5)

| benchmark | Rust s | Mini s | Mini / Rust |
|---|---:|---:|---:|
| binarytrees | 1.39 | 1.22 | 0.88 |
| nbody | 0.79 | 1.06 | 1.34 |
| fannkuch | 2.05 | 2.87 | 1.40 |
| spectralnorm | 1.13 | 1.43 | 1.27 |
| mandelbrot | 0.96 | 0.83 | 0.86 |
| wordcount | 1.10 | 2.69 | 2.45 |
| strbuild | 0.60 | 0.73 | 1.22 |
| json | 0.71 | 0.88 | 1.24 |

Binary size (the runner's numbers, KiB; Rust is not stripped by default, `strip` gives about 336-349 KiB):
Mini is 18-74 KiB (binarytrees 38, nbody 38, fannkuch 30, spectralnorm 26, mandelbrot 18, wordcount 74, strbuild 38, json 70), Rust 4259-4281.
Mini is 5-18x smaller than stripped Rust on every benchmark. Peak RSS equals Rust's within 15 percent (json 86 vs 75 MiB, wordcount 91 vs 86).

## Before / after, the four benchmarks with a gap (median of 7, same session)

| benchmark | Rust s | Mini before s | Mini after s | after / Rust | Rust Ir (G) | Mini Ir before (G) | Mini Ir after (G) |
|---|---:|---:|---:|---:|---:|---:|---:|
| fannkuch | 2.27 | 2.98 | 2.35 | 1.04 | 9.81 | 31.37 | 22.60 |
| spectralnorm | 1.29 | 1.70 | 1.42 | 1.10 | 12.50 | 19.00 | 18.50 |
| nbody | 0.94 | 1.17 | 1.24 (noise, same C) | 1.31 | 9.03 | 16.48 | 16.48 |
| wordcount | 1.25 | 2.72 | 2.82 (noise, same C) | 2.26 | 9.51 | 17.41 | 17.41 |

Binary sizes are unchanged (fannkuch 31 008 bytes both, spectralnorm 26 912, nbody 39 312, wordcount 76 176).
Mini / Rust wall time went from 1.31 to 1.04 (fannkuch) and from 1.32 to 1.10 (spectralnorm).

## Root causes found

* **fannkuch, spectralnorm (array loops).** The generated C was already free of reference counting and boxing in the hot loops; the cost was
  the bounds tests. (1) `MkArray.length` and the `Int` elements of an `IntArray` are both `int32_t`, so the C compiler must assume that
  every element store may change the length: each `a[i]` in a loop that writes any `IntArray` reloaded the length and could not
  merge or hoist the compare. (2) The test itself stayed in the inner loop although `for (j in 0 until N) ... v[j]` can be proved in
  bounds once, before the loop (Rust does exactly that: one `len >= N` compare in the function prologue, then a branch-free loop).
  Removing every test from the generated spectralnorm C (an upper bound) gave -20 percent wall time, which is the gap to Rust.
* **nbody.** Objects on the heap (`Array<Body>`, field loads through pointers) against Rust's `[Body; 5]` on the stack; no cheap
  compiler fix (it needs escape analysis of arrays of objects). The bodies array length is now cached, which is neutral.
* **wordcount (2.3x, not fixed).** The loop is dominated by library work that Rust's port avoids by construction: `substring`
  allocates a string per word (Rust keys the map with `&str` slices of the text), `counts[w] = c + 1` is a lookup plus a `put` with a freshly
  boxed `Int` (Rust does `*entry += 1` in place), and `StringBuilder.append(Char)` is an out-of-line call per character (11 percent of
  instructions). `HashMap` already caches the last looked-up key. Closing this needs unboxed `HashMap<K, Int>` specialisation or
  a `getOrPut`/`merge` fast path in the stdlib; both are larger than this branch.

## Optimizations implemented (compiler/backend/src/opt, docs/mir-opt.md)

* **array-len** (`-Xno-array-len`): a primitive- or object-array local that is never reassigned and is indexed inside a loop reads its
  length once after its declaration; the accessors take it as an extra last argument (`mk_array_get_int(a, i, len)`). The unsigned
  bounds compare is unchanged. C and LLVM emitters take the extra argument; `mk_raise_index` still reports the real length.
  On its own (before range-check) fannkuch went from 2.96 s to 2.44 s.
* **range-check** (`-Xno-range-check`): loop versioning. An innermost ascending `for` loop (at most about 220 MIR nodes) with accesses
  `a[i]`, `a[i + x]`, `a[x + i]`, `a[i - x]`, `a[x - i]` (`x` a constant or a local the loop does not touch) on cached-length primitive arrays
  is emitted twice behind one test before the loop (exact, in Long arithmetic: empty range, or lowest index >= 0 and highest index < len,
  for every distinct index form). The copy uses unchecked `mk_arrayu_*` accessors; when the test fails the original loop runs, so a bad
  index still raises `ArrayIndexOutOfBoundsException` at the same iteration after the same side effects. Memory safety is unchanged: no
  access is unchecked unless the test proved it in bounds. `compiler/testdata/array_range_check.kt` covers every form, empty and
  inclusive ranges, negative offsets and failing loops with side effects.
* **constant divisors in C**: `x / 2` and `x % 26` with a literal divisor other than 0 and -1 are emitted as plain C division (no
  `mkh_div_int` call: clang kept an `idiv` in one peeled spectralnorm loop).

## Validation

* `compiler/bootstrap.sh` with the 0.9.0 release as stage0 (C backend): stage2 == stage3, native memory check OK.
  `--backend=llvm-api`: stage2 == stage3 (C, MIR, LLVM module text and object identical).
* `mini test` (compiler/): 79 passed. `scripts/test-e2e.sh` default: 939 passed, 0 failed, 1 skipped; `--suite llvm --suite llvm-leak
  --suite llvm-api`: 736 passed. `scripts/strict-corpus.sh --run`: ok=111, no false positives or negatives, no wrong code.
  `scripts/check-purity.sh` ok. The runtime is unchanged.
