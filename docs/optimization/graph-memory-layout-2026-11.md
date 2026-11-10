# graph: huge-page chunks and cache-line aligned blocks (`claude/perf-10`)

Method as in [hashmap-graph-2026-11.md](hashmap-graph-2026-11.md) (C backend, `-O2`, callgrind instruction totals, interleaved wall runs; the VM is
shared and noisy, +-10 percent between repeated runs, so only same-session interleaved comparisons count).

## Finding

graph is not instruction bound. Per phase (min of 7): build + teardown of the 1M node graph 0.34 s (Rust 0.07 s), one BFS 0.17 s (Rust 0.11 s).
One BFS is about 220 M instructions at IPC 0.6, and cachegrind (8 MB LL) shows about 8 M last-level data misses per BFS, close to the 6 M a Rust
port needs. So the lever is the cost of a miss: the 168 MB heap is walked in random order with 4 KiB pages (TLB misses), and 32 byte objects at a
16 byte alignment straddle two cache lines in a quarter of the cases.

## Changes (runtime/src/alloc_small.c, alloc_small.h)

* Bump chunks grow to 2 MiB (was 256 KiB). A 2 MiB chunk is `mmap`ed 2 MiB aligned and `madvise(MADV_HUGEPAGE)`d (Linux; other systems keep
  `malloc`). With THP in `madvise` mode (the common default) the object heap of a big graph is a few dozen TLB entries instead of tens of thousands.
* Blocks whose size is a power of two up to 64 bytes (32, 64; 96 gets 32) are carved at that alignment, so a 32 byte object never straddles two
  lines and a 64 byte array is exactly one line. The 16..48 bytes the alignment skips become one free block of that size class (nothing is wasted).
  The inline path of `mk_small_alloc` keeps the common case (bump pointer already aligned) at a compare; the rest is `mk_small_carve`.

## Result (graph, wall, interleaved; same session)

| variant | min s | vs before |
|---|---:|---:|
| before (main 31ec842) | 1.69 | |
| alignment only | 1.50 | -11% |
| huge pages only | 1.30 | -23% |
| both | 1.32 | -22% |

Later runs on the busier machine with the toolchain-built runtime (-O1): 1.66 -> 1.47 and 2.01 -> 1.70 (-12..-15%). Instructions: graph 2.118 -> 2.132 G,
binarytrees 13.35 -> 13.57 G, json +2%, wordcount +1.4% (the alignment test). RSS +1% (graph 175 -> 177 MB). Other benchmarks are within noise.
Rust port for reference: 0.65 s (graph is now about 2.0x Rust, was 2.4x).

## Measured, not adopted

* Skipping candidate recording altogether (an unsafe upper bound, the collector never sees the objects): graph -7% wall. The bookkeeping is
  `slots_put` + `cycles_candidate`, about 137 instructions and two cache misses per first release of an object (1M nodes when the first BFS queue
  is dropped), plus `slots_take` at teardown. A real redesign (one hash set instead of roots array + slot table, or an index inside the object)
  can win at most a few percent and touches the collector's invariants; the graph genuinely is cyclic (`Node -> ArrayList -> RefArray -> Node`), so
  no acyclicity analysis can remove it.
* Teardown at exit: freeing the graph costs about 0.1 s of 1.5 s; skipping it is only possible without leak checking and was not pursued.
* Header 16 -> 8 bytes: would shrink a graph node group from 128 to about 96 bytes (-25% heap), worth maybe 5-10% on graph, but the type word
  (8) + refcount (4) + flags (4, size class, colour, ARMED/BUFFERED) is used by both emitters, the wasm32 layout asserts, `slots` tags and the
  collector; not contained enough for this branch.
* RC elision for `queue.add(m)` / queue drop: each element costs one retain at insertion and one random-access release at the drop (about 1M of the
  8M misses per BFS). Removing it needs proving that the owning list outlives the queue and is not mutated, a heap-content property RcOpt does not track.
  The hot BFS loops already read fields and elements without retain/release (borrowed reads, see the generated C).

## Validation

`runtime/run_tests.sh` (ASan/UBSan/TSan, -Werror -Wextra) passes; `scripts/test-e2e.sh` default 961 passed (incl. stress-leak, stress-rss); `--suite llvm --suite llvm-leak` 514 passed;
`strict-corpus.sh --run` ok=113; `check-purity.sh` ok. Compiler sources are unchanged (runtime only): bootstrap, `ci-stage0.sh` and the wasm toolchain job were not re-run.
