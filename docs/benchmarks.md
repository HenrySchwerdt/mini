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
  `gradle -p compiler bench` (`-Pbench.args="--reps 1 --only tree"`). For every benchmark it makes the MIR once
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
cd compiler && ./gradlew installDist && cd ..
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
