# Benchmarks

These numbers come from `docs/benchmarks.md` in the repository (milestone D1, "dogfooding"): **measure first, then remove reference-counting work in the backend without touching the programs.** They compare the baseline code generation with the `RcOpt` pass. They were measured by the project, on a 4-core machine with clang 18 at `-O1`, best of 7 runs; they are not re-measured by this site's CI.

## How to measure

- **Counters.** `minic build --stats prog.kt -o prog` compiles runtime and program with `-DMK_STATS`. At exit the program prints four `mk-stats:` lines to stderr: allocations, frees, live objects at exit and peak, bytes (total, live, peak), and `retain`/`release` calls split into non-atomic, atomic (shared objects) and no-ops (null, static or stack objects). In a `--stats` build every retain and release is a runtime call so the counters see all of them.
- **Benchmark driver.** `compiler/bench/run.sh [--reps N] [--configs "base opt"] [--only name]`. For every benchmark it builds both configurations (`base` = `--no-rc-opt`, `opt` = RC elision), checks that the output is identical, and takes the best time of N runs. Results go to `compiler/build/bench/results.md`.
- **Benchmarks** (`compiler/bench/micro/*.kt`): `arraylist`, `hashmap`, `stringbuilder`, `mapfilter` (map/filter/fold chains), `tree` (binary-tree building), `templates` (string templates). Plus the **self-hosted backend** `minic-backend` turning a MIR file into C: `selfhost-small` and `selfhost-large` (the MIR of the backend itself, the largest input there is).

## Results

`base` is the baseline (`--no-rc-opt`). `opt` is RcOpt plus inline retain/release fast paths in the generated C. Counts are logical operations from the `--stats` build.

| benchmark | config | time (ms) | allocs | retains | releases |
|---|---|---:|---:|---:|---:|
| arraylist | base | 405 | 4 200 357 | 67 417 457 | 81 026 310 |
| arraylist | opt | **265** | 4 200 357 | 25 608 560 | 37 017 688 |
| hashmap | base | 102 | 1 285 094 | 11 218 441 | 19 014 613 |
| hashmap | opt | **72** | 1 285 094 | 5 511 361 | 12 618 633 |
| mapfilter | base | 247 | 1 937 308 | 36 133 678 | 43 114 204 |
| mapfilter | opt | **146** | 1 937 308 | 8 853 110 | 13 737 200 |
| stringbuilder | base | 92 | 1 000 062 | 12 400 021 | 14 400 104 |
| stringbuilder | opt | **60** | 1 000 062 | 4 200 000 | 5 200 062 |
| templates | base | 109 | 4 200 000 | 600 000 | 5 100 000 |
| templates | opt | 105 | 4 200 000 | 0 | 4 200 000 |
| tree | base | 100 | 2 172 852 | 8 277 846 | 18 128 592 |
| tree | opt | **61** | 2 172 852 | 0 | 3 745 746 |
| selfhost-small | base | 62 | 569 418 | 5 294 320 | 7 939 640 |
| selfhost-small | opt | **49** | 569 418 | 1 781 705 | 3 140 155 |
| selfhost-large | base | 749 | 5 290 607 | 54 561 332 | 83 114 527 |
| selfhost-large | opt | **603** | 5 290 607 | 17 290 458 | 30 511 031 |

## Reading the table

- **Time** drops from about 5 percent (`templates`, dominated by allocating the temporary strings) to 40 percent (`tree`, `mapfilter`, `arraylist`).
- **Retains** drop by 60 to 100 percent, **releases** by 40 to 80 percent.
- **Allocations and peak memory are identical by construction.** RcOpt moves and removes counting; it does not change what is allocated or when objects die.
- The native self-hosted backend gets 20 percent faster on its large input (749 to 603 ms) even though it now also runs the RcOpt analysis on its input. That analysis costs about 120 ms of the 603; compiling with `--no-rc-opt` is 25 percent cheaper in the backend for a program that then runs slower. A cheaper analysis is a candidate for later work.
- What remains counted is mostly *owned results* (`ArrayList.get`, `HashMap.get`, iterator `next` return +1 and the caller releases), boxing, and stores into collections. They would need borrowed return values and owned parameters for virtual slots, which are next steps.

## Where the counting came from

Found with the counters and by reading the generated C:

1. every read of a reference field was copied into a retained temporary;
2. traversal variables (`p = p.next`) retained the next node and released the old one on every iteration;
3. constructor arguments such as `Tree(make(d - 1), make(d - 1))` created owned temporaries that the constructor retained and the caller released again;
4. `return x as T` of an owned temporary (every generic getter) retained and released;
5. releases of `NULL` were calls into the runtime;
6. string temporaries of templates: one retain and release per piece, unchanged by D1.

The rules that remove them are in [Reference counting and RcOpt](/internals/refcounting).

## Reproducing

```sh
./mini --version
bash compiler/bench/run.sh --reps 7           # about 8 minutes; --only tree for one benchmark
cat compiler/build/bench/results.md
./minic build prog.kt -o prog --stats         # counters for any program; --no-rc-opt for the baseline
```

Timing noise on a shared 4-core machine is about 5 percent; the counters are exact.

## Memory checker and annotations

None of these numbers needed `@Owned` annotations, `free()` calls or Strict mode. The conventions RcOpt infers (stored constructor parameters are owned, everything else borrowed, field reads are borrows while nothing overwrites a heap reference) are exactly what `@Owned` and `@Borrowed` state in Strict code, so the inference rules may become the default for unannotated parameters there.

## Cross-language baseline

The same eight programs (`binarytrees`, `nbody`, `fannkuch`, `spectralnorm`, `mandelbrot`, `wordcount`, `strbuild`, `json`) in C, Rust, Go, Kotlin/JVM, Kotlin/Native and Mini, run by `bench/run.sh`. Host: Intel Xeon @ 2.30 GHz VM, 4 cores, Linux 6.18; clang 18.1.3, rustc 1.97.0, Go 1.24.7, OpenJDK 21, Kotlin 2.1.20. Best of 5; the output of every run is checked. Mini is built with `minic build --cflag -O2`; `Mini-M` is Managed mode with the C backend, `Mini-M-ll` the LLVM backend. The Arena and Strict variants (only `binarytrees`) are in `docs/benchmarks.md`. This run used the runtime **before** the new allocator (see below). It is a VM: differences below about 5 percent are noise.

**Wall time** (seconds)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll |
|---|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 0.95 | 1.10 | 1.57 | 0.71 | 2.47 | 1.69 | 1.65 |
| nbody | 0.99 | 0.75 | 1.58 | 1.20 | 1.21 | 1.80 | 1.98 |
| fannkuch | 2.13 | 1.83 | 2.06 | 2.14 | 2.30 | 6.81 | 7.35 |
| spectralnorm | 1.01 | 1.00 | 1.00 | 1.78 | 1.04 | 1.99 | 2.01 |
| mandelbrot | 0.87 | 0.82 | 0.85 | 0.83 | 0.84 | 0.85 | 0.81 |
| wordcount | 0.53 | 0.91 | 1.27 | 3.29 | 3.91 | 4.39 | 4.37 |
| strbuild | 0.58 | 0.51 | 0.99 | 0.52 | 1.84 | 1.64 | 1.65 |
| json | 0.82 | 0.50 | 0.61 | 0.76 | 0.79 | 1.43 | 1.79 |

**Time relative to C** (1.00 = C at `-O2`; higher is slower)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll |
|---|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.00 | 1.16 | 1.65 | 0.75 | 2.61 | 1.78 | 1.75 |
| nbody | 1.00 | 0.76 | 1.59 | 1.21 | 1.22 | 1.81 | 2.00 |
| fannkuch | 1.00 | 0.86 | 0.96 | 1.00 | 1.08 | 3.19 | 3.44 |
| spectralnorm | 1.00 | 0.99 | 0.99 | 1.76 | 1.03 | 1.97 | 1.99 |
| mandelbrot | 1.00 | 0.95 | 0.97 | 0.96 | 0.97 | 0.98 | 0.92 |
| wordcount | 1.00 | 1.71 | 2.38 | 6.15 | 7.31 | 8.21 | 8.17 |
| strbuild | 1.00 | 0.88 | 1.71 | 0.90 | 3.19 | 2.84 | 2.86 |
| json | 1.00 | 0.61 | 0.74 | 0.93 | 0.96 | 1.74 | 2.17 |

**Peak RSS** (MiB)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll |
|---|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 34 | 34 | 46 | 501 | 141 | 130 | 130 |
| nbody | 2 | 2 | 2 | 42 | 3 | 2 | 2 |
| fannkuch | 2 | 2 | 2 | 42 | 3 | 2 | 2 |
| spectralnorm | 2 | 2 | 2 | 42 | 3 | 2 | 2 |
| mandelbrot | 2 | 2 | 2 | 40 | 3 | 2 | 2 |
| wordcount | 88 | 86 | 289 | 414 | 367 | 328 | 328 |
| strbuild | 104 | 104 | 447 | 397 | 611 | 205 | 205 |
| json | 103 | 75 | 168 | 479 | 364 | 173 | 173 |

**Reading it honestly.** Mini is on par with C on `mandelbrot`; about 1.7 to 2x C on `binarytrees`, `nbody`, `spectralnorm` and `json`; and far behind on `fannkuch` (3.2x), `strbuild` (2.8x) and `wordcount` (8x). Kotlin/Native is faster than Mini on most of them; Mini is ahead on `binarytrees` and `strbuild`. Start-up is 1.0 ms and the binaries are 80 to 210 KiB.

**After the allocator.** With the thread-local size-class allocator of PR #18 compiled into the same programs (Mini Managed, C backend, same host, best of 5):

| benchmark | C | Mini, old runtime | Mini, new runtime |
|---|---:|---:|---:|
| binarytrees | 0.95 | 1.68 | **1.18** |
| nbody | 0.99 | 1.76 | 1.74 |
| fannkuch | 2.13 | 6.84 | 7.07 |
| spectralnorm | 1.01 | 2.01 | 2.02 |
| mandelbrot | 0.87 | 0.85 | 0.85 |
| wordcount | 0.53 | 4.61 | 4.32 |
| strbuild | 0.58 | 1.63 | 1.50 |
| json | 0.82 | 1.94 | **1.43** |

The allocator helps where objects are allocated and freed in large numbers (`binarytrees` -30 percent, `json` -26 percent) and changes nothing elsewhere.

**Quick wins.** Inline array accessors (C and LLVM), borrowed array element reads in the reference-counting optimizer, and an `snprintf`-free integer to string conversion, Mini Managed, same host, best of 5, before and after:

| benchmark | C | Mini-M before | Mini-M after | Mini-M-ll before | Mini-M-ll after |
|---|---:|---:|---:|---:|---:|
| binarytrees | 0.95 | 1.15 | 1.22 | 1.13 | 1.16 |
| nbody | 0.99 | 1.72 | **1.03** | 1.88 | 1.25 |
| fannkuch | 2.13 | 7.06 | **2.68** | 6.85 | 3.43 |
| spectralnorm | 1.01 | 2.00 | **1.26** | 2.00 | 2.01 |
| mandelbrot | 0.87 | 0.90 | 0.88 | 0.84 | 0.80 |
| wordcount | 0.53 | 4.10 | 3.42 | 3.98 | 3.63 |
| strbuild | 0.58 | 1.46 | **0.81** | 1.49 | 0.82 |
| json | 0.82 | 1.36 | 1.37 | 1.25 | 1.33 |

The LLVM backend did not gain on `spectralnorm` (cause not found). The table below the profiles describes the state before these changes.

**Where the time goes** (profiles with `valgrind --tool=callgrind` on the current runtime; the full analysis is in `docs/benchmarks.md`):

- `fannkuch`: 62 percent of the instructions are in the out-of-line array accessors `mk_array_get_int` and `mk_array_set_int`. With the accessors inlined (bounds check kept) it takes 2.65 s instead of 7.2 s.
- `nbody`: every array element read retains and releases the element (500 M retains in 25 M steps). Inlining the accessor gives 1.20 s, and borrowing the element without reference counting 0.99 s, equal to C.
- `strbuild`: 63 percent of the instructions are `snprintf` inside `mk_string_from_int`. A hand-written integer conversion gives 0.93 s instead of 1.48 s.
- `wordcount`: the generic `HashMap` does two lookups per word (`get` and `put`), hashes the string every time, goes through virtual `hashCode`/`equals`, casts and out-of-line node accessors, and boxes every count. About 1,300 instructions per word.
- `json`: out-of-line `String.get` per character (8.5 percent), `is` checks that walk the type hierarchy (6.3 percent), allocation and release.
- `binarytrees`: allocation, zeroing and recursive release are the whole program; 137 M of 205 M releases are no-ops on `null`.

Common causes are that the runtime is a separate translation unit without LTO (so no accessor is inlined) and that array element reads are not borrowed by the reference-counting optimizer. Both are fixable in the backend; `-flto` alone already helps `nbody` by 24 percent and `json` by 12 percent.

To reproduce: `./mini --version && bench/run.sh --reps 5 --stats` (results in `bench/results/`).
