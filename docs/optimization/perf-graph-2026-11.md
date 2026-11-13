# graph: inlined `ArrayList.add`, a cache hint for list reads, big blocks from the heap (`claude/perf-graph`)

Goal: the benchmark `graph` (1 M `Node` objects, each with its own `ArrayList` of 4 edges; six breadth-first searches) was at about 1.9x Rust
(wall) with both backends. Method as in [hashmap-graph-2026-11.md](hashmap-graph-2026-11.md) and [graph-memory-layout-2026-11.md](graph-memory-layout-2026-11.md):
instructions are `valgrind --tool=callgrind` totals of the whole program (deterministic), wall times are interleaved runs of the binaries on a
shared, noisy machine. Both sides are built with the same toolchain flags (runtime `-O2`, programs `-O2`); "before" is main (`e83702f`), "after" this branch.

## What graph is bound by

Per breadth-first search (about 980 000 nodes, 3.9 M edges): 222 M instructions and 190 ms, i.e. IPC 0.4. The search is a chain of dependent cache
misses per node: the queue slot (sequential) -> the `Node` (random) -> its `ArrayList` (same 64-byte line as the node) -> the storage array (the next line) ->
the four neighbours (independent of each other). A Rust port has the same number of lines to touch but about half the instructions; mini ran 222
instructions per node, of which these were avoidable:

| cost | where | size |
|---|---|---|
| `ArrayList.add` is a real call, 28 instructions (5 register pushes, the old-slot release, the capacity test, the cold growth path expanded into it) | 11 M calls: `nodes.add`, `n.out.add`, `queue.add` | 0.30 G of 2.17 G |
| growth of the storage: one `refArrayNew` (zeroes the whole new array) plus a copy loop that moves slot by slot | 18 doublings of every queue | 0.15 G (done by the predecessor of this branch: `mk_ref_array_grow`, one `memcpy`) |
| the dependent miss chain of `queue[head]` | wall time, not instructions | about 25 percent of the user time |
| page faults of the big storage arrays: glibc `mmap`s every block of 128 KiB or more and `munmap`s it when it is freed, so every search faults its queue in again | 58 000 minor faults (Rust: 23 000) | 0.10 s system time of 1.3 s |
| `refArrayNew(4)` (the storage of every `ArrayList()`) goes through the generic size-class computation | 1 M calls, 69 instructions | 0.07 G |

## Changes

* **`@kotlin.internal.AlwaysInline`** (stdlib `Conventions.kt`; `MirFunction.alwaysInline`; the C prelude's `MKH_ALWAYS_INLINE` = `__attribute__((always_inline))`,
  the LLVM backend's `alwaysinline` function attribute; MIR text format keeps version 2, the new flag follows `noHeapEffects` and older MIR still reads).
  `ArrayList.add` has `@NoHeapEffects` (growth moves the elements, appending fills a NULL slot), which is what keeps RcOpt from retaining the elements
  that a loop holds across `queue.add(m)`, and what makes the MIR inliner refuse the function (it would show RcOpt the slot store: measured, graph 2.49 G instead of
  2.02 G, a retain and a release per node and per edge). The request goes to the C compiler / LLVM instead, which expand the already
  analysed code. The growth path stays a call (`ensureCapacity`). Off-switch: `-Xno-always-inline`. Only `ArrayList.add` carries it.
* **`refArrayPrefetch`** (`mk_ref_array_prefetch`; stdlib `Intrinsics.kt`, runtime `v1s.c`, the C prelude's `mkh_ra_prefetch`, `llvm.prefetch` in the LLVM lowering,
  nothing on wasm; a pure runtime function for `Effects`/`NullGuard`). `ArrayList.get(i)` asks for the object in slot `i + 8` and for the 64 bytes behind it.
  A work queue read front to back reaches nodes that are scattered over the heap; the object and what its constructor allocated next (its list, its
  storage array: the `Node`, `ArrayList` and `RefArray` of one node are consecutive blocks) are then in the cache when the search gets to them, and the
  three dependent misses per node become one. Distances 4 to 48 give the same result (user time -20 to -28 percent on graph); the hint in *every*
  reference-array read (iterators included) costs 12 percent more instructions and gains less, `ArrayList.get` alone is +7 instructions per call. The hint
  has no effect on a result; a null array, an index out of range and an empty slot do nothing. Off-switch: `-Xno-prefetch`.
* **Big blocks from the heap** (runtime `v1s.c`, `mk_ref_array_grow`; glibc only): the first time a list grows to half a million slots (4 MiB of storage) the runtime sets
  `mallopt(M_MMAP_THRESHOLD, 32 MiB)` and `M_TOP_PAD` 32 MiB. The storage arrays of a growing list (up to 8 MiB here) then come from the heap and a freed one is reused
  by the next growth instead of being unmapped: 58 000 -> 18 000 minor faults, system time 0.11 s -> 0.05 s on graph (peak RSS +12 MB, the heap keeps its padding). Blocks above
  32 MiB are mapped as before. It was first done in `mk_runtime_init` for every program; wordcount (a hash table whose big arrays are `calloc`ed) got 3 percent slower
  from it (repeated interleaved runs, both directions of the A/B), so it is tied to the list growth now and a program without such a list keeps glibc's defaults.
* **`mk_alloc_sized_armed`** has the size class, alignment and zeroing of the 56-byte block (a list's default storage of 4 slots) as compile-time constants (-24 instructions
  per list).
* Carried over from the predecessor commit: `mk_ref_array_grow` (growth as one out-of-line call with one `memcpy`; the old storage is emptied, not walked).

## Result

Instructions (callgrind, whole program, G); every other benchmark program is unchanged to within 20 instructions (the same generated code, the runtime is not touched on
their paths):

| benchmark | C before | C after | LLVM before | LLVM after |
|---|---:|---:|---:|---:|
| graph | 2.169 | 1.905 (-12.2%) | 2.025 | 1.886 (-6.8%) |
| json | 3.536 | 3.442 (-2.6%) | 3.920 | 3.851 (-1.8%) |
| binarytrees | 11.315 | 11.315 | 13.025 | 13.025 |
| fannkuch | 13.687 | 13.687 | 17.206 | 17.206 |
| mandelbrot | 6.758 | 6.758 | 6.729 | 6.729 |
| nbody | 8.875 | 8.875 | 8.875 | 8.875 |
| spectralnorm | 12.503 | 12.503 | 17.502 | 17.502 |
| strbuild | 5.354 | 5.354 | 5.426 | 5.426 |
| wordcount | 8.965 | 8.965 | 9.051 | 9.051 |

Rust, graph: 0.947 G. (The LLVM backend of this branch has no inline allocation yet, which is why its instruction count falls less; its wall time falls as much.)

Wall time, `bench/walltime.sh -n 7` (median of 7 pinned runs, interleaved; seconds; the machine is shared and loaded, ratios move by 10 percent between sessions):

`bench/walltime.sh -n 7` before / after (two sessions, the machine was loaded in both; the Rust column is the yardstick, so compare the ratios):

| benchmark | C before | C after | LLVM before | LLVM after | Rust before / after | C / Rust before -> after | LLVM / Rust before -> after |
|---|---:|---:|---:|---:|---:|---:|---:|
| graph | 1.893 | 0.917 | 1.797 | 0.993 | 0.853 / 0.676 | 2.22 -> **1.36** | 2.11 -> **1.47** |
| json | 1.514 | 0.960 | 1.655 | 0.992 | 1.279 / 0.796 | 1.18 -> 1.21 | 1.29 -> 1.25 |
| wordcount | 2.089 | 1.593 | 2.099 | 1.529 | 1.769 / 1.290 | 1.18 -> 1.23 | 1.19 -> 1.19 |
| strbuild | 0.684 | 0.577 | 0.628 | 0.535 | 1.155 / 0.957 | 0.59 -> 0.60 | 0.54 -> 0.56 |
| binarytrees | 1.592 | 1.838 | 1.689 | 1.884 | 1.956 / 2.264 | 0.81 -> 0.81 | 0.86 -> 0.83 |

(The earlier session of the same script, with a different load, gave graph 1.93 s -> 0.94 s C and 1.62 s -> 0.89 s LLVM, Rust 0.79 / 0.60.) Because the ratios of json and wordcount
move by the noise of the sessions, the binaries of both toolchains were also run in one interleaved loop, pinned to one core, median of 11, CPU seconds (user + system):

| benchmark | C before -> after | LLVM before -> after |
|---|---:|---:|
| graph | 1.308 -> 0.897 (-31%) | 1.196 -> 0.897 (-25%) |
| json | 0.944 -> 0.897 (-5%) | 0.996 -> 0.968 (-3%) |
| wordcount | 1.396 -> 1.411 (+1%, noise) | 1.421 -> 1.410 |
| strbuild | 0.440 -> 0.442 | 0.430 -> 0.416 |
| binarytrees | 1.322 -> 1.307 | 1.315 -> 1.353 (noise: identical binary) |

graph is at 1.4 to 1.5 times Rust (was 2.1 to 2.2 times); Rust's own CPU time on graph is 0.5 to 0.6 s.

Binary size (`.text` of the linked benchmark programs, bytes; file sizes differ by 8 bytes or less except where a 4 KiB page boundary is crossed: graph LLVM +4104):

| | before | after |
|---|---:|---:|
| graph, C / LLVM | 57 406 / 56 062 | 58 776 (+1 370) / 57 288 (+1 226) |
| json, C / LLVM | 66 886 / 64 678 | 68 128 (+1 242) / 66 096 (+1 418) |
| wordcount, C / LLVM | 73 350 / 72 182 | 73 494 (+144) / 72 326 (+144) |
| binarytrees, fannkuch, mandelbrot, nbody, spectralnorm, strbuild | | unchanged |
| hello world (file) | 14 824 | 14 824 |
| the compiler built by itself (`-O1`, file) | 9 697 864 | 9 891 424 (+2.0%: 1 600 `ArrayList.add` call sites expanded, the 93 hints) |

Peak RSS of graph: 173 MB -> 185 MB (the heap padding of the tuning).


## Measured and not kept

* `ArrayList.add` inlined by the MIR inliner (remove `@NoHeapEffects`): graph 2.49 G, +23 percent (retains of `n` and `m` in the search loop).
* The growth path of `add` as a `noinline` cold function with the length stores moved before the slot store: -0.5 percent (the five pushes stay).
* Prefetch in every reference-array read: graph +227 M instructions (+12.6 percent), 17 percent less user time instead of 26.
* A second prefetch level (the storage array of the node `n + 8` through `n.out`, reading the already cached node): the same time as prefetching the line behind the object.
* Prefetching only the object (no second line): user time -8 percent instead of -26.
* `slots_put` / `cycles_candidate` (the candidate buffer of the cycle collector: 130 instructions and a cache miss for each of the 1 M nodes that the first dropped queue
  releases once): untouched. The graph is cyclic (`Node -> ArrayList -> RefArray -> Node`), so every node is a candidate; a cheaper buffer needs an index inside the object
  (the header has no free 21 bits).

## What is left

Rust: 0.95 G instructions, 0.5 to 0.6 s CPU. After this branch graph runs 1.9 G instructions and 0.7 to 0.8 s CPU. The rest is structural: three objects (128 bytes) per node
instead of one 32-byte struct plus a 32-byte edge vector (build: 0.55 G); the candidate buffering of the cycle collector (0.13 G, see above); `memset` of the grown storage
(0.15 G: `calloc` of a heap block zeroes all of it and the copy then overwrites half of it); the retain per `queue.add` and the random release when the queue is dropped
(one miss per element); and the generic code of `ArrayList` and its iterator over a hand-written loop (about 17 instructions per edge in the search loop: modCount, length,
array, bounds, type test; the generated C is already what the C compiler makes of it without knowing that the list is not touched by the loop body).

## Validation

* `scripts/check-purity.sh` ok; the seed check (the 0.10 C seed compiling compiler and stdlib): SEED OK.
* `runtime/run_tests.sh` (ASan / UBSan, TSan for the threads test, `-Werror -Wextra`), including new checks for `mk_ref_array_grow` and `mk_ref_array_prefetch`: all pass.
* `compiler/bootstrap.sh` (C): stage2 == stage3, native memory check OK; `--backend=llvm-api`: stage2 == stage3 (C, MIR, LLVM module text, object file);
  `scripts/ci-stage0.sh` with the 0.10.0 release as stage0: OK.
* `scripts/test-e2e.sh --suite`: c 303, leak 324, memcheck 123, llvm 303, llvm-leak 324, strict 158 passed (new test `compiler/testdata/arraylist_add_inline`:
  growth inside loops, element aliases across the `add`, a failing iteration that adds, reads around the prefetch distance; same output with
  `-Xno-always-inline` and `-Xno-prefetch`, checked by hand).
* `mini test -p backend -p frontend -p native-driver`: 104 + 37 + 2 passed.
* wasm32: `scripts/wasm/build-runtime.sh` (`-Wall -Wextra -Werror`) and `scripts/wasm/test-programs.sh` over the collection tests (`arraylist_add_inline`,
  `stdlib_collections*`, `linked_list`, `list_addall_self`, `alloc_inline`, `rc_borrow_traps`): 9 ok.
* `website/scripts/gen-internals.sh`: the MIR listing gets the new flag (`counter.mir.txt`).
