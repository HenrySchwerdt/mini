# Benchmarks and reference-counting statistics (dogfooding D1)

Plan: `docs/plan-kotlin.md` section 10d. Goal of D1: measure first, then remove reference-counting (RC) work in the
backend without touching the programs.

## How to measure

* **Counters.** `minic build --stats prog.kt -o prog` compiles runtime and program with `-DMK_STATS`. At exit the program
  prints four `mk-stats:` lines to stderr: allocations, frees, live objects at exit and peak, bytes (total / live at exit /
  peak), `mk_retain` / `mk_release` calls split into non-atomic, atomic (`MK_FLAG_SHARED`) and no-ops (NULL, static,
  stack objects). Without the flag the runtime contains no counting code (`runtime/run_tests.sh` checks the object
  files). In a `--stats` build every retain/release is a call into the runtime so that the counters see all of them.
* **Benchmark driver.** `compiler/bench/run.sh [--reps N] [--configs "base opt"] [--only name] [--cflags "-O2"]`, or
  `compiler/bench/run.sh --reps 1 --only tree`. For every benchmark it makes the MIR once
  (`minic emit-mir`), runs the backend per configuration (`base` = `--no-rc-opt`, `opt` = RC elision), compiles the C twice
  (timing build, `-DMK_STATS` build) with clang `-O1` against libminirt (`-O2`), takes the best of N runs for the time and
  one run for the statistics, and checks that stdout (and the output file of the backend benchmarks) is identical in both
  configurations. Results: `compiler/build/bench/results.{tsv,md}`.
* **Benchmarks.** `compiler/bench/micro/*.kt`: `arraylist` (add / get / set / iteration of boxed Int and objects),
  `hashmap` (put / get / remove, Int and String keys), `stringbuilder`, `mapfilter` (map/filter/fold chains),
  `tree` (binary-tree building plus a linked list), `templates` (string templates and concatenation). Self-hosted
  backend: `minic-backend` (= `compiler/native-driver` + `compiler/backend` compiled natively by minic, in the
  configuration under test) turning a MIR file into C: `selfhost-small` = the MIR of `testdata/stdlib_collections.kt`
  (0.6 MB of C out), `selfhost-large` = the MIR of the backend itself plus its driver (the largest input
  we have). `selfhost-large-no-rcopt` is the same input with `--no-rc-opt` given to the running backend, i.e. without the
  RcOpt analysis inside it: the difference to `selfhost-large` is the cost of RcOpt itself.

## Results (4 cores, clang 18, `-O1`, best of 7)

`base` = baseline code generation (`--no-rc-opt`, the state before D1). `opt` = RcOpt (rules in `compiler/README.md`,
section "RC elision") **plus** inline retain/release fast paths in the generated C (`MKH_RETAIN` / `MKH_REL`: the common
non-atomic case is a few instructions, the runtime is only called for NULL-free unusual cases, a count reaching zero,
atomic or static objects). Counts are from the `--stats` build (logical operations = calls into the runtime there).

| benchmark | config | time (ms) | allocs | peak bytes | retains | releases | of which no-op (retain+release) | static retain/release sites in the C |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| arraylist | base | 405 | 4 200 357 | 8 000 096 | 67 417 457 | 81 026 310 | 9 408 496 | 91 / 612 |
| arraylist | opt | **265** | 4 200 357 | 8 000 096 | 25 608 560 | 37 017 688 | 7 208 771 | 2 / 186 |
| hashmap | base | 102 | 1 285 094 | 8 000 184 | 11 218 441 | 19 014 613 | 12 146 952 | 310 / 1901 |
| hashmap | opt | **72** | 1 285 094 | 8 000 184 | 5 511 361 | 12 618 633 | 9 089 298 | 2 / 561 |
| mapfilter | base | 247 | 1 937 308 | 5 333 792 | 36 133 678 | 43 114 204 | 5 043 218 | 111 / 1098 |
| mapfilter | opt | **146** | 1 937 308 | 5 333 792 | 8 853 110 | 13 737 200 | 2 946 782 | 2 / 379 |
| stringbuilder | base | 92 | 1 000 062 | 1 077 908 | 12 400 021 | 14 400 104 | 5 000 021 | 24 / 88 |
| stringbuilder | opt | **60** | 1 000 062 | 1 077 908 | 4 200 000 | 5 200 062 | 0 | 2 / 17 |
| templates | base | 109 | 4 200 000 | 622 | 600 000 | 5 100 000 | 300 000 | 13 / 77 |
| templates | opt | 105 | 4 200 000 | 622 | 0 | 4 200 000 | 0 | 2 / 16 |
| tree | base | 100 | 2 172 852 | 6 291 408 | 8 277 846 | 18 128 592 | 12 396 606 | 22 / 86 |
| tree | opt | **61** | 2 172 852 | 6 291 408 | 0 | 3 745 746 | 1 572 894 | 2 / 16 |
| selfhost-small (`minic-backend`, 0.6 MB C out) | base | 62 | 569 418 | 5 698 648 | 5 294 320 | 7 939 640 | 2 630 757 | 7232 / 96391 |
| selfhost-small | opt | **49** | 569 418 | 5 698 646 | 1 781 705 | 3 140 155 | 1 107 517 | 5 / 26290 |
| selfhost-large (`minic-backend` on the backend's own MIR, 5.3 MB C out) | base | 749 | 5 290 607 | 62 335 790 | 54 561 332 | 83 114 527 | 28 481 646 | 7232 / 96391 |
| selfhost-large | opt | **603** | 5 290 607 | 62 335 788 | 17 290 458 | 30 511 031 | 10 737 526 | 5 / 26290 |
| selfhost-large-no-rcopt (without the RcOpt analysis inside) | base | 641 | 4 689 327 | 62 335 878 | 42 467 139 | 65 625 157 | 21 151 202 | |
| selfhost-large-no-rcopt | opt | 482 | 4 689 327 | 62 335 876 | 10 987 041 | 20 248 683 | 5 179 288 | |

Reading the table

* Time: 5 % (templates: allocation of the temporary strings dominates) to 40 % (`tree`, `mapfilter`, `arraylist`).
  Retains drop by 60 to 100 %, releases by 40 to 80 %. The allocation counts and peak memory are identical by
  construction: RcOpt moves and removes counting, it does not change what is allocated or when objects die (a move
  only shifts a release earlier, never later).
* The native backend gets 20 % faster on the large input (749 to 603 ms) although it now also runs the RcOpt
  analysis on its input: that analysis costs about 120 ms of the 603 (`selfhost-large` minus
  `selfhost-large-no-rcopt`, 603 - 482), i.e. the analysis is the price of removing the counting, and
  compiling with `--no-rc-opt` is 25 % cheaper in the backend for a program that runs slower. A cheaper analysis (fewer
  positions per function, dense per-function arrays instead of `HashMap<Int, ...>`) is a candidate for D2.
* `no-op` are calls with a NULL / static argument. They are still frequent: field releases in `dispose` of leaves,
  assignments of the first value to a local, string literals. The inline NULL test removes the call in the timing builds.
* What remains: the counted operations are mostly *owned results* (`ArrayList.get`, `HashMap.get`, iterator `next`
  return +1 and the caller releases), boxing, and stores into collections (`add`, `put`, `set`, which retain what they
  keep). They need borrowed return values and owned parameters for virtual slots (next steps).
* The time numbers combine the elision and the inline fast paths; the counters show the elision alone (in `--stats`
  builds both variants call the runtime for every operation).

### Intermediate numbers (borrowing and last-use moves only, before owned constructor parameters)

| benchmark | base ms | borrow+moves ms | final ms | retains base / borrow+moves / final (millions) |
|---|---:|---:|---:|---|
| arraylist | 414 | 291 | 265 | 67.4 / 27.6 / 25.6 |
| hashmap | 111 | 93 | 72 | 11.2 / 5.8 / 5.5 |
| mapfilter | 253 | 167 | 146 | 36.1 / 8.9 / 8.9 |
| stringbuilder | 92 | 70 | 60 | 12.4 / 4.2 / 4.2 |
| tree | 100 | 78 | 61 | 8.3 / 3.7 / 0 |

## Where the counting came from (baseline)

Hot spots found with the counters and by reading the generated C of the micro benchmarks:

1. every read of a reference field was copied into a retained temporary (`this.array`, `h.items`, `node.next`) and
   released at scope end: one retain/release pair per field access, also for `this` copies of receivers;
2. traversal variables (`p = p.next`, `e = e.next` in hash chains) retained the next node and released the old one in
   every iteration;
3. constructor arguments: `Tree(make(d - 1), make(d - 1))` created two owned temporaries, the constructor retained
   them, the caller released them again (two pairs per node);
4. `return x as T` of an owned temporary (every generic getter) retained the result and released the temporary;
5. releases of `NULL` (locals declared later than an early `return`, the old value of a first field store, fields of
   leaves in `dispose`) as calls into the runtime;
6. string temporaries of templates: one retain and release per piece (unchanged by D1: they are real owned results).

## Reproducing

```
./mini --version   # builds the native toolchain
bash compiler/bench/run.sh --reps 7           # about 8 minutes; --only tree for one benchmark
cat compiler/build/bench/results.md
./minic build prog.kt -o prog --stats         # counters for any program; --no-rc-opt for the baseline
```

Timing noise on a shared 4-core machine is about 5 %; the counters are exact. The `static` columns count
`mk_retain(` / `mk_release(` call sites in the C file of the program under test (inline macros are not counted).

## Memory-model checker and annotation experience (D1)

RcOpt works on the Managed code generation only. No `@Owned` annotations, `free()` calls or Strict mode were needed
for any of the numbers above, and no checker diagnostics changed (the Strict checker runs before code generation and is
untouched). The experience for `docs/memory-model.md`: the borrowed / owned-parameter conventions inferred here
(constructor parameters that are stored are owned, everything else borrowed, field reads are borrows while nothing
overwrites a heap reference) are exactly what `@Owned` / `@Borrowed` would state in Strict code, so the inference rules
can serve as the default for unannotated parameters there.

## D2: Strict code generation, calling conventions, arenas

Configurations: `base` = `--no-rc-opt --no-strict --no-conv`, `opt` = RcOpt (`--no-strict --no-conv`), `conv` = RcOpt plus
calling conventions (`--no-strict`), `strict` = default. Median of 7 runs; allocs, frees and peak are identical in every
configuration (the outputs are byte-identical to the Managed ones).

| benchmark | time ms base / opt / conv / strict | retains base -> strict | releases base -> strict |
|---|---|---|---|
| arena_managed | 139 / 78 / 78 / 76 | 13.76M -> 0 | 35.39M -> 5.90M |
| arena_heap | 138 / 81 / 78 / 82 | 13.76M -> 0 | 35.39M -> 1 |
| arena_arena | 142 / 80 / 82 / 72 | 13.76M -> 0 | 35.39M -> 60 |
| arraylist | 357 / 217 / 204 / 208 | 59.4M -> 11.2M | 73.0M -> 22.6M |
| hashmap | 108 / 75 / 74 / 75 | 11.9M -> 3.98M | 20.4M -> 11.6M |
| mapfilter | 228 / 139 / 143 / 139 | 32.9M -> 4.66M | 39.9M -> 9.72M |
| stringbuilder | 96 / 58 / 60 / 58 | 12.4M -> 4.2M | 14.4M -> 5.2M |
| templates | 116 / 109 / 110 / 113 | 0.6M -> 0 | 5.1M -> 4.2M |
| tree | 104 / 66 / 64 / 68 | 8.28M -> 0 | 18.1M -> 3.75M |
| selfhost-large | 1026 / 718 / 714 / 720 | 66.9M -> 11.2M | 101.3M -> 25.9M |

Reading: the three arena benchmarks run the same tree workload as Managed code, in a `Heap` and in an `Arena`. Strict
code with an allocator has no counting at all (0 retains, 1 release for `Heap`, 60 for `Arena` chunks). Wall time barely
moves between `opt` and `strict` on these workloads because RcOpt already removed the retains and the remaining releases
are cheap non-atomic decrements; the saving is in counted operations, not in allocator speed (the allocator calls
`malloc` in all cases). Conventions cut `arraylist` retains by half (borrowed `get`, owned `add`/`set`) and `mapfilter`
by a third; time differences of `conv` against `opt` are inside the 5 % noise. Self-host: instruction count (callgrind)
of RcOpt itself went from 1.70G to 1.25G with dense arrays (whole compile 5.64G to 5.22G).

Open: the Iterator.next group cannot be borrowed when boxing primitive iterators are reachable, the Map.get group is
dropped while `AbstractMutableMap.get` is reachable, and RcOpt precision depends on `@NoHeapEffects` assertions on the
collection `equals`/`hashCode`/`toString`.

## Allocator: size-class free lists and a real bump arena

D2 left a measurable fact: counted operations were mostly gone but wall time had not moved, because every object
still went through `malloc`/`free` and the arena zeroed each object separately. The allocator work targets exactly
that (`runtime/src/alloc*.c`, `docs/memory-model.md` 2.6):

* Rc and Heap objects up to 256 bytes (prefix included) come from thread-local size-class free lists; frees go to the
  freeing thread's list, whole batches of 256 blocks move to a central pool; larger blocks stay on `malloc`.
* The arena allocation is a pointer bump inlined into `mk_alloc_sized_site`, on `calloc`ed chunks with a high-water
  mark (no per-object memset in fresh memory), and closed arenas hand their chunks to a process-wide cache.
* `-DMK_PLAIN_MALLOC` (and any AddressSanitizer build, automatically) switches the new paths off.

Before = `origin/main` runtime, after = this branch, same generated C, `-O2`, best of 5, 4 cores. `opt` is the Managed
code generation, `strict` the default (Arena/Heap objects of the three `arena_*` programs; the other programs are
Managed in both columns). Outputs are byte-identical.

| benchmark | opt ms before -> after | strict ms before -> after |
|---|---|---|
| arena_managed (Rc tree) | 80 -> 42 (-48%) | 78 -> 41 (-47%) |
| arena_heap (`Heap`) | 75 -> 42 (-44%) | 77 -> 46 (-40%) |
| arena_arena (`Arena`) | 77 -> 42 (-45%) | 67 -> 40 (-40%) |
| tree | 64 -> 40 (-38%) | 67 -> 43 (-36%) |
| mapfilter | 143 -> 102 (-29%) | 138 -> 96 (-30%) |
| templates | 117 -> 93 (-21%) | 116 -> 93 (-20%) |
| stringbuilder | 62 -> 53 (-15%) | 60 -> 53 (-12%) |
| arraylist | 218 -> 199 (-9%) | 211 -> 194 (-8%) |
| hashmap | 67 -> 78 (+16%, noise: 71 -> 74 best of 15) | 76 -> 74 (-3%) |
| selfhost-small | 47 -> 40 (-15%) | 50 -> 39 (-22%) |
| selfhost-large | 1071 -> 736 (-31%) | 1112 -> 690 (-38%) |
| selfhost-large-no-rcopt | 1008 -> 677 (-33%) | 1104 -> 684 (-38%) |

Whole-compiler effect: `minic-native` compiling its own sources (`compiler/bootstrap.sh` stage 2/3 step, the same
`stage2.c` linked against the old and the new runtime, `-O1`, best of 5): 5.39 s -> 4.50 s (-16%). The bootstrap
fixpoint (C and `--backend=llvm`, stage2 == stage3) holds.

Reading: the three `arena_*` programs now cost the same, about 41 ms, wherever the nodes live: allocation is a few
instructions in all three (the 25 % of instructions the old path spent in `arena_allocate`, `mk_alloc_with` and `memset`
for `arena_arena` is down to the bump). `hashmap` and `arraylist` allocate mostly buffers above 256 bytes (still
`malloc`), so they barely move. Two details mattered more than the free lists themselves: handing batches between a thread
and the central pool in O(1) (the first version walked cold chains and made `hashmap` about 20 % slower than `malloc`), and
keeping freed arena chunks warm (an arena per round otherwise page-faults fresh memory every round: `arena_arena` was
10 % slower than before until the chunk cache).

Allocator counters (`MK_STATS`, new `mk-stats:` lines): `selfhost-large` serves 8 509 608 objects from size classes and
130 220 from `malloc`, carves 488 chunks (127 MB for a 107 MB peak), and needs 451 central-pool refills for
9 588 hand-offs; `arena_arena` makes 1 966 020 arena allocations, 600 of them on the slow path (new or cached chunk),
10 fresh chunks (4.2 MB) for 60 arenas.

## D2: annotation and diagnostic experience

Stdlib annotations needed: 10 `@ReturnsBorrowed`, 10 `@TakesOwnership` (collections), 27 `@NoHeapEffects`,
5 `@NativeSymbol` (allocator handles); user programs need none. Step 3 (Strict internals for the collections) was not
done: buffers are runtime `Any` objects (little counting to gain) and iterators/views hold counted references to the
map, so only `HashNode`/`HashMap` as `@AllocatedBy(Rc)` Strict classes would be a candidate.

## Cross-language baseline

The same eight programs written in C, Rust, Go, Kotlin/JVM, Kotlin/Native and Mini, so that Mini's speed and memory use can
be compared with other languages and so that regressions show up. The programs are in `bench/<name>/`, the runner is
`bench/run.py` (`bench/run.sh`), results are written to `bench/results/<date>-<host>.{json,md}`, and the CI workflow
`.github/workflows/bench.yml` runs the suite. Kotlin/JVM and Mini compile the very same `.kt` file (Mini Arena and Strict
have their own variants of `binarytrees`).

**Host and tools.** Intel Xeon @ 2.30 GHz VM, 4 cores, Linux 6.18; clang 18.1.3, rustc 1.97.0 (`-C opt-level=3
-C codegen-units=1`), Go 1.24.7, OpenJDK 21.0.12 (default flags), Kotlin 2.1.20 (`kotlinc`, `kotlinc-native -opt`). C uses
`clang -O2`. Mini is built with `minic build --cflag -O2` (C backend) and with `--backend=llvm`. Every number is the best of
5 runs; the output of every run is checked against `bench/<name>/expected.txt`. Mini columns: `M` = Managed, `A` = Arena,
`S` = Strict (only `binarytrees` has Arena and Strict variants), `-ll` = LLVM backend. Raw data:
`bench/results/2026-10-30-vm.json` and `.md` (the `.md` also has binary sizes, cold start and the `--stats` counters).

**Measured before the allocator change.** This run used the runtime at `dedfbcd`, before the thread-local size-class
allocator (see "Allocator: size-class free lists and a real bump arena"). The section "After the allocator" below
re-times the Mini builds with the new runtime. It is a VM, so differences below about 5 percent are noise.

### Wall time (seconds, best of 5)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll | Mini-A | Mini-A-ll | Mini-S | Mini-S-ll |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 0.95 | 1.10 | 1.57 | 0.71 | 2.47 | 1.69 | 1.65 | 1.67 | 1.62 | 1.91 | 1.77 |
| nbody | 0.99 | 0.75 | 1.58 | 1.20 | 1.21 | 1.80 | 1.98 | n/a | n/a | n/a | n/a |
| fannkuch | 2.13 | 1.83 | 2.06 | 2.14 | 2.30 | 6.81 | 7.35 | n/a | n/a | n/a | n/a |
| spectralnorm | 1.01 | 1.00 | 1.00 | 1.78 | 1.04 | 1.99 | 2.01 | n/a | n/a | n/a | n/a |
| mandelbrot | 0.87 | 0.82 | 0.85 | 0.83 | 0.84 | 0.85 | 0.81 | n/a | n/a | n/a | n/a |
| wordcount | 0.53 | 0.91 | 1.27 | 3.29 | 3.91 | 4.39 | 4.37 | n/a | n/a | n/a | n/a |
| strbuild | 0.58 | 0.51 | 0.99 | 0.52 | 1.84 | 1.64 | 1.65 | n/a | n/a | n/a | n/a |
| json | 0.82 | 0.50 | 0.61 | 0.76 | 0.79 | 1.43 | 1.79 | n/a | n/a | n/a | n/a |

### Time relative to C (1.00 = C at -O2; higher is slower)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll | Mini-A | Mini-A-ll | Mini-S | Mini-S-ll |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.00 | 1.16 | 1.65 | 0.75 | 2.61 | 1.78 | 1.75 | 1.76 | 1.71 | 2.01 | 1.87 |
| nbody | 1.00 | 0.76 | 1.59 | 1.21 | 1.22 | 1.81 | 2.00 | n/a | n/a | n/a | n/a |
| fannkuch | 1.00 | 0.86 | 0.96 | 1.00 | 1.08 | 3.19 | 3.44 | n/a | n/a | n/a | n/a |
| spectralnorm | 1.00 | 0.99 | 0.99 | 1.76 | 1.03 | 1.97 | 1.99 | n/a | n/a | n/a | n/a |
| mandelbrot | 1.00 | 0.95 | 0.97 | 0.96 | 0.97 | 0.98 | 0.92 | n/a | n/a | n/a | n/a |
| wordcount | 1.00 | 1.71 | 2.38 | 6.15 | 7.31 | 8.21 | 8.17 | n/a | n/a | n/a | n/a |
| strbuild | 1.00 | 0.88 | 1.71 | 0.90 | 3.19 | 2.84 | 2.86 | n/a | n/a | n/a | n/a |
| json | 1.00 | 0.61 | 0.74 | 0.93 | 0.96 | 1.74 | 2.17 | n/a | n/a | n/a | n/a |

### Peak RSS (MiB)

| benchmark | C | Rust | Go | JVM | K/N | Mini-M | Mini-M-ll | Mini-A | Mini-A-ll | Mini-S | Mini-S-ll |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 34 | 34 | 46 | 501 | 141 | 130 | 130 | 66 | 66 | 66 | 66 |
| nbody | 2 | 2 | 2 | 42 | 3 | 2 | 2 | n/a | n/a | n/a | n/a |
| fannkuch | 2 | 2 | 2 | 42 | 3 | 2 | 2 | n/a | n/a | n/a | n/a |
| spectralnorm | 2 | 2 | 2 | 42 | 3 | 2 | 2 | n/a | n/a | n/a | n/a |
| mandelbrot | 2 | 2 | 2 | 40 | 3 | 2 | 2 | n/a | n/a | n/a | n/a |
| wordcount | 88 | 86 | 289 | 414 | 367 | 328 | 328 | n/a | n/a | n/a | n/a |
| strbuild | 104 | 104 | 447 | 397 | 611 | 205 | 205 | n/a | n/a | n/a | n/a |
| json | 103 | 75 | 168 | 479 | 364 | 173 | 173 | n/a | n/a | n/a | n/a |

Cold start of `hello`: C 1.1 ms, Rust 1.2, Go 1.1, K/N 1.5, Mini 1.0, JVM 31.4 ms. Binary size (KiB, `binarytrees`): C 16,
Mini 86 to 93, K/N 481, Go 2178, Rust 4259 (JVM: 3 KiB of class files, without the JVM itself).

### Reproducing

```sh
./mini --version     # builds ./minic (native)
bench/run.sh --reps 5 --stats                     # all languages that are installed (the JVM and Native compiles are slow)
bench/run.sh --only json,wordcount --langs c,mini-managed --out /tmp/bench   # a subset
```

Tools are taken from `PATH` (`clang`, `rustc`, `go`, `java`, `kotlinc`, `kotlinc-native`) or from `CC`, `RUSTC`, `GO`, `JAVA`,
`KOTLINC`, `KOTLINC_NATIVE`. A tool that is missing is reported as "not measured". `--stats` adds a Mini Managed build with the
`MK_STATS` counters.

### Summary

* **On par with C: `mandelbrot`** (0.98x). Pure floating-point loops over locals compile to the same code.
* **About 1.7 to 2x C: `binarytrees` (1.8x), `nbody` (1.8x), `spectralnorm` (2.0x), `json` (1.7x).** Allocation- and
  object-heavy code, and code that goes through runtime accessors.
* **Far behind: `fannkuch` (3.2x), `strbuild` (2.8x), `wordcount` (8.2x).** All three are dominated by things that are
  out-of-line calls in the runtime (array access, number formatting, a generic `HashMap`).
* Against the other languages: Mini is faster than Kotlin/Native on `binarytrees` and `strbuild` only; Kotlin/Native is
  clearly faster on `fannkuch`, `spectralnorm`, `nbody`, `json` and `wordcount`. Go, Rust and the JVM beat Mini on
  `wordcount`, `json` and `binarytrees`.
* Memory: Mini's peak RSS is mostly below the JVM and Kotlin/Native (`binarytrees` 130 MiB against JVM 501, K/N 141;
  `strbuild` 205 against Go 447, JVM 397, C 104), but not always (`wordcount` 328 MiB against Go 289, C 88). Start-up
  (1.0 ms) and binary size (80 to 210 KiB) are small; only C is smaller.
* The LLVM backend is within about 10 percent of the C backend, except `json` (1.25x slower); the cause was not investigated.
* Caveat: the `json` row of the recorded run (Mini 1.43 s) is not reproducible on the old runtime. Re-building the same
  program against the old runtime gave 1.94 s to 1.99 s in three further runs (C 0.82 to 1.00 s). The recorded row is kept
  as measured; the re-measured value is in the table below.

### After the allocator

The runtime of PR #18 (`a809207`: thread-local size-class allocator for objects up to 256 bytes, faster arenas) was
compiled into the same programs with `minic build --cflag -O2 --runtime-src <rt>/runtime/src --runtime-include
<rt>/runtime/include` (C backend, Managed unless noted). "Re-measured" is the old runtime built and timed in the same session as the
new one; seconds, best of 5, same host. Output checked against `expected.txt`.

| benchmark | C | Mini-M (recorded run) | Mini-M, old runtime, re-measured | Mini-M, new runtime | new / C |
|---|---:|---:|---:|---:|---:|
| binarytrees | 0.95 | 1.69 | 1.68 | **1.18** | 1.24 |
| nbody | 0.99 | 1.80 | 1.76 | 1.74 | 1.76 |
| fannkuch | 2.13 | 6.81 | 6.84 | 7.07 | 3.32 |
| spectralnorm | 1.01 | 1.99 | 2.01 | 2.02 | 2.00 |
| mandelbrot | 0.87 | 0.85 | 0.85 | 0.85 | 0.98 |
| wordcount | 0.53 | 4.39 | 4.61 | **4.32** | 8.15 |
| strbuild | 0.58 | 1.64 | 1.63 | **1.50** | 2.59 |
| json | 0.82 | 1.43 | 1.94 | **1.43** | 1.74 |
| binarytrees, Arena | 0.95 | 1.67 | 1.66 | **1.20** | 1.26 |
| binarytrees, Strict | 0.95 | 1.91 | 1.81 | **1.27** | 1.34 |

The allocator helps exactly where objects are allocated and freed in large numbers: `binarytrees` -30 percent in all three
memory modes, `json` -26 percent against the re-measured baseline. `wordcount` (-6 percent) and `strbuild` (-8 percent)
allocate 40 and 42 million objects too, but their time is not in the allocator (see below). `nbody`, `fannkuch`,
`spectralnorm` and `mandelbrot` do not allocate in their hot loops and do not change.

## Quick wins

Three of the fixes ranked at the end of "Where the time goes", implemented one commit each. Mini Managed with the C backend
(`Mini-M`) and the LLVM backend (`Mini-M-ll`), `minic build --cflag -O2`, seconds, best of 5, same host as above (the C
column is the recorded C time from the baseline). "Before" is `origin/main` (runtime with the allocator), measured in the
same session. It is a VM: differences below about 5 percent are noise (`binarytrees` moved between 1.11 s and 1.22 s across
runs without any change to what it executes).

1. **Inline array accessors.** `mk_array_get_*`, `mk_array_set_*` and `mk_array_length` are `static inline` in `minirt.h`
   (unsigned compare for both bounds, `__builtin_expect`, the failing path is the out-of-line `mk_raise_index`, which leaves
   the pending exception exactly as before). The LLVM lowering (one implementation behind both the text and the in-process
   builder) emits the same sequence as IR: length load, `icmp uge`, cold call of `mk_raise_index`, GEP and load or store, a phi
   for the result.
2. **Borrowed array element reads.** An `mk_array_get_obj` that defines a local is analysed like a field read: the local is
   a borrower of the array slot and is validated against reference-array stores, calls that may do one, and the array root
   being reassigned or dropped between the read and the last use. Where it holds, both backends use the non-retaining read
   (`mk_array_get_obj_b`) and the release disappears. `compiler/testdata/rcopt_arrayread.kt` covers the hazards (slot
   overwritten, overwritten in a callee, array dropped or reassigned) under ASan.
3. **Fast int to string.** `mk_itoa` replaces `snprintf` in `mk_string_from_int`, `mk_string_from_long` and
   `println(Int/Long)`; `mk_sb_append_int/long` write the digits into the builder buffer, and `StringBuilder.append(Int/Long)`
   uses them (no temporary string).

| benchmark | C | Mini-M before | Mini-M after | Mini-M-ll before | Mini-M-ll after |
|---|---:|---:|---:|---:|---:|
| binarytrees | 0.95 | 1.15 | 1.22 | 1.13 | 1.15 |
| nbody | 0.99 | 1.72 | **1.03** | 1.88 | **0.94** |
| fannkuch | 2.13 | 7.06 | **2.68** | 6.85 | **2.03** |
| spectralnorm | 1.01 | 2.00 | **1.26** | 2.00 | **1.29** |
| mandelbrot | 0.87 | 0.90 | 0.88 | 0.84 | 0.81 |
| wordcount | 0.53 | 4.10 | 3.42 | 3.98 | 4.28 |
| strbuild | 0.58 | 1.46 | **0.81** | 1.49 | 0.83 |
| json | 0.82 | 1.36 | 1.37 | 1.25 | 1.36 |

**LLVM backend follow-up.** The "Mini-M-ll after" column above is after these fixes to the LLVM lowering (text and in-process
paths share them; the in-process path, `--opt=O2`, measured within 3 percent of the text path on all eight). The quick wins had
left it behind the C backend (`spectralnorm` 2.01 vs 1.26, `fannkuch` 3.43 vs 2.68, `nbody` 1.25 vs 1.03), for three reasons
visible in the optimized IR and the assembly:

1. **TLS model.** The emitted IR declared `mk_pending_exception` as a plain `thread_local` global. Without module flags
   clang assumes PIC and uses the general-dynamic model: every pending-exception test (after every array access) called
   `__tls_get_addr`. It is now `thread_local(initialexec)` (`LLVMSetThreadLocalMode` in the API builder), one `%fs` relative
   load, as in the C backend's executable. This alone took `spectralnorm` from 2.01 s to 1.2 s.
2. **No TBAA.** The C backend's struct accesses carry type-based alias tags, the IR did not, so every store to a `Double`
   field or array element forced reloads of the others (28 instead of 16 double loads in `nbody`'s `advance`, the array
   length reloaded after each element store). Loads and stores of fields (tag per declaring class and field), array lengths,
   array elements (tag per element kind) and the pending slot now carry flat TBAA tags (`IrBuilder.loadTagged/storeTagged`);
   the object header (reference counts) stays untagged and so aliases everything. `nbody` 1.25 s to 1.05 s.
3. **Pending test after inline array accesses.** The failing bounds path merged back into the fast path, which then tested
   `mk_pending_exception` on every access (a load the optimizer could neither hoist nor remove). The failing path now leaves
   the function (or goes to the handler) itself after `mk_raise_index`, as the pending test would have, and the fast path has
   no test. `fannkuch` 3.06 s to 2.1 s, now faster than the C backend, which still has this test.

Per step (C backend, same session): `fannkuch` 7.06 s to 2.65 s after step 1; `nbody` 1.72 s to 1.48 s after step 1 and to
1.01 s after step 2; `strbuild` 1.46 s to 0.88 s (0.81 s in the final run) after step 3. The measured experiments above
predicted 2.65 s, 0.99 s and 0.93 s.

Micro benchmarks (`compiler/bench/run.sh --configs opt`, ms, best of 3): `stringbuilder` 53 to 23, `templates` 97 to 71, `hashmap`
88 to 72, `tree` 45 to 38, `arraylist` 215 to 201, `mapfilter` 103 to 103; the self-hosted backend on the large MIR
(`selfhost-large`) 742 to 787 (about 6 percent slower, within the run-to-run spread seen on this host; not investigated).

What did not move, and why. `spectralnorm` with the LLVM backend stays at 2.0 s while the C backend dropped to 1.26 s: the
emitted loop has the inline accessor, but the optimized IR of the LLVM build keeps something the C build does not lose; the
cause was not found. The LLVM `fannkuch` and `nbody` are 25 percent above the C backend for the same reason (not investigated).
`wordcount`, `json` and `binarytrees` are dominated by the causes listed above, not by these three fixes.

Step 4 of the plan, the inline `NULL` test before `mk_release`, was already in place: with RcOpt the generated code uses
`MKH_REL` (C) and `mkh_rel` (LLVM), which test for `NULL` and do the non-atomic decrement inline, including in the
`dispose` glue. The 137 M "no-op releases" of `binarytrees` come from the `--stats` build, which routes every release
through the runtime on purpose so that the counters see it.

## Where the time goes

Profiles of the Mini Managed builds against the runtime of `a809207` (so the analysis is current after the allocator).
Method: `minic build ... --cflag -O2 --cflag -g`, programs with reduced sizes (for example `WORDS = 1000000`, `N = 9`),
`valgrind --tool=callgrind` (instruction counts, `callgrind_annotate`), the generated C from `--emit-c`, and the counters of
a `--stats` build. Where a fix could be tried without touching the compiler, it was tried on a scratch copy of the generated C
or of the runtime and re-timed at full size (best of 5); these experiments are not committed. They tell how much a fix is
worth; the real fix has to be done in the backend or the runtime. Instruction counts are reported as "Ir".

### Common cause 1: the runtime is a separate translation unit

`minic` compiles the generated C and all `runtime/src/*.c` files in one `clang` command but as separate translation units
without LTO. Array element access (`mk_array_get_int`, `mk_array_set_int`, `mk_array_get_obj`, `mk_array_get_double`),
`mk_string_char_at`, `mk_sb_append_char` and the `HashMap` helpers are therefore real calls, and after each call that can
raise the generated code tests the thread-local `mk_pending_exception`. An array access that is one `mov` in C is a call, a
range check, a load and a TLS test. The C backend already inlines the retain/release fast paths (`MKH_RETAIN` and `MKH_REL`),
but not these accessors. `-flto` alone is not enough: clang did not inline the accessors into the large `main` functions
(`fannkuch` 7.03 s to 7.30 s) although it helped others (below).

### Common cause 2: retain/release around array and field reads that are borrows

`val a = bodies[i]` calls `mk_array_get_obj`, which retains the element, and the generated code releases it again at the end of
the scope (`MKH_REL(v_tmp_3)`). The array is alive during the whole loop, so the pair is not needed (RcOpt borrows field reads
already; it does not borrow array element reads).

### fannkuch (3.3x C)

Evidence: callgrind at `N = 9`: 697 M Ir, of which `mk_array_get_int` 42.6 percent and `mk_array_set_int` 19.1 percent, the
remaining 38.2 percent in `main` itself. 62 percent of the instructions are spent in out-of-line accessors. `--stats` shows
0 allocations in the loop and 0 retains: it is only array access. The generated code for `perm[i] = perm[k - i]` is two
calls and two TLS tests.

Experiment: the generated C with `mk_array_get_int` and `mk_array_set_int` replaced by `static inline` functions with the
same bounds check and `mk_raise_index` on the cold path: **7.2 s to 2.65 s**; without the check 2.35 s (C: 2.13 s).

Fix: define the primitive array accessors as `static inline` functions in `minirt.h` (the `MkArray` layout is already
public), with `__builtin_expect` on the range check; the pending-exception test after the access can stay (the experiment kept it
and it is cheap once the call is gone). Expected: fannkuch
to about 1.25x C (2.7 s), spectralnorm and the Int/Double loops of every program similarly. Second step: hoist the
length load and drop the range check when the loop bound is the array length (`for (i in 0 until n)` with `n <= size`),
expected another 10 percent.

### nbody (1.76x C)

Evidence: callgrind at 500 k steps: 821 M Ir, `kt_advance` 58 percent self, `mk_array_get_obj` 23 percent self,
`mk_retain` 18 percent self (every inner-loop iteration reads one body through `mk_array_get_obj`, which calls `mk_retain`). The `--stats` build reports
500 M retains and 500 M releases for 25 M steps, all on borrowed array elements. `sqrt` is not a problem: the binary contains
`sqrtsd` and no call to `sqrt` (the `external fun sqrt` shim is inlined by clang).

Experiments (full size): inline `mk_array_get_obj` with the check and the inline retain: **1.76 s to 1.20 s**; additionally
no retain and no release for the elements (borrowed): **0.99 s**, equal to C. `-flto` alone: 1.85 s to 1.40 s.

Fix: (1) inline `mk_array_get_obj` as above; (2) in RcOpt treat an element read from an array that is not written
in the scope as a borrow (like a field read): the retain and the release disappear. Expected: 1.76x to 1.0x.

### binarytrees (1.24x C after the allocator, 1.78x before)

Evidence: after the allocator, callgrind at depth 14: `mk_release` 19.8 percent self, `mk_alloc_sized_site` 19.5 percent
plus its inline part 7.2 percent, `mk_free_object_memory` 5.7 percent plus 7.1 percent, `memset` 5.7 percent (zeroing in
`mk_alloc`), `Node.dispose` 8.9 percent. The `--stats` build (old runtime) counts 68.3 M allocations, 205 M releases of which
137 M are no-ops (`NULL` children and the like) and 0 retains: construction and recursive destruction are the whole program. 
`-flto`: 1.15 s to 1.08 s.

Fix: (1) skip the `memset` for objects whose constructor writes every field (the emitter knows this), expected 3 to 5 percent;
(2) emit an inline `NULL` test before the call to `mk_release` for the `left` and `right` children in `dispose` (137 M of the
205 M releases do nothing), expected 5 percent; (3) inline the free-list pop of the small allocator into the generated code.
Together expected 1.24x to about 1.1x.

### strbuild (2.6x C after the allocator)

Evidence: callgrind at 1/20 size: `mk_string_from_int` is **62.7 percent inclusive**, `snprintf` 50.3 percent and
`__printf_buffer`/`_itoa_word` among the top self entries; `StringBuilder.append(Int)` first formats the number into a new
heap string (one allocation, one release) and then copies it. `--stats`: 42 M allocations for 18 M loop iterations, 26.4 M retains.

Experiment: hand-written decimal conversion in `mk_string_from_int` (4 lines, scratch runtime copy): **1.48 s to 0.93 s**
(C: 0.58 s).

Fix: (1) replace `snprintf` by an inline integer-to-digits loop in `mk_string_from_int` and `mk_string_from_long`
(and in `console.c` for `println(Int)`); (2) `mk_sb_append_int` writes the digits straight into the builder with no temporary
string; (3) string templates `"item-" + i + ":" + (i * 7)` should become one allocation of the final size
(`mk_string_concat_n`) instead of a chain of three concatenations. Expected: 1.50 s to about 0.75 s for (1) and (2) together
(1.3x C), a further 10 percent for (3).

### wordcount (8x C)

Evidence: the generation of the text takes only 0.5 s of 4.3 s (measured separately). The rest is 20 M iterations of
`indexOf`, `substring`, `counts[w]` and `counts[w] = c + 1`, at about 1,300 instructions per word. Callgrind inclusive:
`HashMap.put` 28.5 percent, `HashMap.get` 16.7 percent, `findNode` 14.8 percent (it runs twice per word, once for the `get`
and once for the `put`), `substring` 10.3 percent. Per call inside `findNode` and `put`: `mk_hash_code` (virtual dispatch,
then `mk_string_hash`, which hashes the whole word again every time, 5.2 percent self), `mk_equals` (3.2 percent, via
`mk_itable`, 1.8 percent), `mk_checked_cast` (1.6 percent), `HashNode.get-key`/`get-value`/`set-value` as separate calls
(1.8 percent each), `mk_ref_array_get_b` for the bucket and `mk_release` (4.7 percent). `--stats` (old runtime): 40 M
allocations, i.e. two objects per word (the substring and the boxed `Int` value that `counts[w] = c + 1` allocates), 208 M
retains and 289 M releases. The program is instruction-bound, not memory-bound (about 1,600 Ir per word including the generation, which at 4.3 s implies an IPC of about 3).
Peak RSS is 328 MiB against 88 MiB for C because the text is UTF-16 (2 bytes per character, 171 MB for 85.6 M characters).

Fix, in order of benefit: (1) a one-lookup read-modify-write on `HashMap` (`merge`, `getOrPut`, or `compute` in the stdlib; the
compiler can also fuse `m[k] = (m[k] ?: 0) + 1` into it): halves the lookups, expected -25 to -30 percent; (2) cache the
hash in `MkString` (one `int` next to the length, computed on first use): removes the second hash and the hashing of
every key during `resize`, expected -5 percent; (3) make the `HashNode` field accessors and `mk_ref_array_get_b` inline
(common cause 1) and avoid `mk_checked_cast` for the key (the key type is known after monomorphisation), expected -10 to
-15 percent; (4) a small-integer cache or an in-place update of the boxed value when the box is unshared (refcount 1),
expected -10 percent; (5) a specialised `substring` that allocates the final string in one step (it is 165 Ir per call
today). All five together: expected 4.3 s to about 2 s, which is still about 4x C but level with the JVM (3.3 s) and K/N
(3.9 s).

### json (1.74x C after the allocator, 2.2x before)

Evidence: callgrind at `TOP = 6000`: `mk_string_char_at` 8.5 percent self (one out-of-line call per character, with the
null and range check), `mk_release` 7.6 percent, `type_is` 6.3 percent (`is` and `when (x) { is ... }` on the value
classes walk the supertype chain and the interface lists recursively on every test), `mk_alloc_sized_site` 6.1 percent
plus 2.5 percent inline, `parseString` and `parseValue` 4.9 and 5.4 percent self. `--stats` (old runtime): 11.8 M allocations,
47 M releases of which 28 M are no-ops, 7.5 M retains. `-flto`: 1.36 s to 1.20 s.

Fix: (1) inline `mk_string_char_at` (common cause 1), expected -5 percent; (2) `is` for a final class (or a class
without subclasses in the program, known at link time) as a single pointer comparison, and a one-entry cache or a
precomputed display of ancestors for open classes, expected -5 to -8 percent; (3) the `NULL`-test before `mk_release`
as for binarytrees; (4) build with `-flto`, which gave -12 percent here and -24 percent on `nbody`. Expected overall
1.43 s to about 1.1 s (1.3x C).

### spectralnorm (2.0x C, not profiled in detail)

The inner loop reads `DoubleArray` elements through `mk_array_get_double`, the same out-of-line accessor as `fannkuch`; the
instruction count was not profiled, but the fix of common cause 1 is expected to bring it to 1.2x C or better.

### Summary of the proposed fixes, ranked by expected gain

| fix | affects | measured or expected |
|---|---|---|
| Inline primitive array accessors with a cold-path range check | fannkuch, spectralnorm, json, wordcount | fannkuch 7.2 s to 2.65 s (measured) |
| Borrow array element reads (no retain/release) and inline `mk_array_get_obj` | nbody, tree walkers | nbody 1.76 s to 0.99 s (measured) |
| `mk_string_from_int` without `snprintf`, `append(Int)` without temporary | strbuild | 1.48 s to 0.93 s (measured), about 0.75 s with the builder path |
| One-lookup `HashMap` update, cached string hash, inline node accessors | wordcount | 4.3 s to about 2 s (estimate) |
| Skip `memset` for fully initialised objects, `NULL` test before `mk_release` | binarytrees, json | 5 to 10 percent (estimate) |
| `-flto` in `minic build` (or a unity build of runtime and program) | nbody, json, binarytrees | nbody -24 %, json -12 %, binarytrees -6 % (measured); no effect on fannkuch and wordcount |
| Cheaper `is` checks | json | 5 to 8 percent (estimate) |
| `minic build` defaults to `-O1` (the benchmarks pass `-O2`) | every user program | not measured here |
