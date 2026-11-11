# HashMap node layout and the graph benchmark (`claude/perf-9`)

Same host and method as [wordcount-gap-2026-11.md](wordcount-gap-2026-11.md): C backend, `-O2`, instructions = `valgrind --tool=callgrind`
totals of the full-size runs (deterministic), wall = median of interleaved runs of the two binaries. This VM is shared and noisy: two copies of
one and the same binary differ by up to 5 percent in wall time here (nbody, mandelbrot), so the instruction counts are the measure that
decides whether a change regressed anything.

## Changes

* **`HashNode` / `LinkedNode`** (stdlib/kotlin/collections/HashMap.kt). The plain `HashMap` node was 64 bytes (16 header, hash, key, value, `next`,
  and the two insertion-order links `before` / `after` that only a `LinkedHashMap` uses). It is now `open class HashNode` (48 bytes) and
  `LinkedHashMap` allocates `LinkedNode : HashNode` with the two extra links; `head` / `tail` are typed `LinkedNode`, the casts happen only in
  the linked paths (`unlink`, the linked iterator). The slot read of the lookup is unchanged. The node members are `final override` so the
  accessors stay direct calls. `mutableMapOf()`, `linkedMapOf`, `LinkedHashSet` keep the insertion order, a plain `HashMap` keeps the JVM
  bucket order (iteration order is part of what the existing tests print, so open addressing was not an option).
* **`lastNode` is a non-owning field** (`@mini.memory.Borrowed`). The one-entry cache (`map[k]` then `map[k] = v` hashes and compares once) kept
  a strong reference to the node. The table owns the node, and the cache is cleared in `removeNode` and `clear`, which are the only places a
  node leaves the table, so the retain and the release (and the cycle-candidate test of the release) are not needed. The key stays a strong
  reference (its identity must keep meaning its contents).
* **`RefArray` dispose** (runtime/src/v1s.c) decrements the reference of an item that stays alive inline (the decrement `mk_release` would do:
  not static / stack / shared, not an unbuffered cycle candidate, count above one, no threads running), without the call. Dropping a list of
  objects that are also held elsewhere (the queue of a graph search, the node list of a graph) is a loop of such releases.
* **Big blocks from `calloc`** (runtime/src/alloc.c): an object block of 128 KiB or more (the growth of a big `ArrayList`) is `calloc`ed instead of
  `malloc` + `memset`, libc maps fresh pages that are already zero.

## Result (instructions, callgrind, G)

| benchmark | Rust | before | after | change |
|---|---:|---:|---:|---:|
| wordcount | 9.51 | 14.410 | 13.919 | -3.4% |
| graph | 0.947 | 2.268 | 2.118 | -6.6% |
| json | 3.10 | 4.337 | 4.346 | +0.2% (the checked cast in the linked iteration) |
| strbuild | 9.86 | 9.859 | 9.859 | 0 |
| binarytrees | 19.64 | 13.551 | 13.551 | 0 |
| nbody | 9.03 | 9.000 | 9.000 | 0 |
| fannkuch | 9.81 | 20.705 | 20.705 | 0 |
| spectralnorm | 12.50 | 20.003 | 20.003 | 0 |
| mandelbrot | 6.70 | 6.810 | 6.810 | 0 |

Wall, median of 7 interleaved runs (s), same session:

| benchmark | Rust | before | after | after / Rust |
|---|---:|---:|---:|---:|
| wordcount | 1.34 | 2.82 | 2.53 | 1.88 (-10.4%) |
| graph | 0.75 | 1.81 | 1.75 | 2.33 (-3.3%) |

The other programs run byte-identical generated C (nbody, mandelbrot, fannkuch, spectralnorm, binarytrees, strbuild) or differ by a
few instructions (json), and their wall times differ by the noise of the machine only.

wordcount is memory bound, not instruction bound: the node shrank from 64 to 48 bytes (the 50 000 nodes are 2.4 MB instead of 3.2 MB, the
working set of table, nodes, keys and boxes moves into the 8 MB L2 here), which is worth about 8 percent wall for 3 percent of the instructions.

## graph: where the time goes

The wall gap to Rust (2.2x) is the same as the instruction gap (2.4x), IPC is alike (0.7), so for graph it is instructions:

* Phases (C backend): build 0.36 s, six BFS runs 1.17 s, teardown of the 1M node graph about 0.4 s.
* Rust has no per-object bookkeeping: 1M `Node` structs in one `Vec`, edges are `usize` in a 32-byte `Vec` each. Mini allocates a `Node`
  (32 bytes), an `ArrayList` (32) and its `RefArray` (64) per node, every `queue.add(m)` retains, every release of a node that stays alive
  hits the cycle-candidate path (a `Node` can be in a cycle: `Node -> ArrayList -> RefArray -> Node`), whose first execution per object
  buffers it (`cycles.c: slots_put`, about 90 instructions and a cache miss).
* Of the 2.27 G: main's loops 1.0 G, `ArrayList.add` 0.36 G (11 M calls), the node teardown 0.47 G (release, dispose, candidate buffering),
  the six queue drops 0.37 G (63 instructions per element before the inline decrement, about 30 after), allocation 0.35 G (35 instructions per
  object, 3 M objects), zeroing of the big growth arrays 0.13 G (gone with `calloc`).
* Tried and dropped: a separate bump chunk per size class (objects of one class contiguous): graph 18 percent slower (the node, its list and its
  array are allocated together and read together); inlining the capacity test of `ArrayList.add`: +1 percent instructions (the call to
  `ensureCapacity` was not the cost).
* What is left is structural: the object header (16 bytes), reference counting, cycle candidates for cyclic class graphs. Closing it
  would need unboxed element storage for lists of objects with a single owner, or an acyclicity analysis (`Node.out` is only ever filled with
  `Node`s from `nodes`, but proving that is whole-program).

## Lookup without a temporary key (done later)

Per word the temporary `substring` was about 95 instructions to build, 12 to retain as `lastKey`, 12 for the parameter release in `put`,
about 45 to free: roughly 19 percent of the instructions of wordcount. It is not made any more: see
[perf-11-2026-11.md](perf-11-2026-11.md) (`substr-key`, a MIR pass, `HashMap.findNodeRange` / `putRange` / `putRangeAt`).

## Validation

* `compiler/bootstrap.sh` (C): stage2 == stage3, native memory check OK; `--backend=llvm-api`: stage2 == stage3 (C, MIR, LLVM module text and object).
* `scripts/ci-stage0.sh` with the 0.10.0 release as stage0: stage2 == stage3, native memory check OK.
* `mini test` (compiler/): 79 passed. `scripts/test-e2e.sh` default: 961 passed, 0 failed, 1 skipped; `--suite llvm --suite llvm-leak`: 514 passed.
  `scripts/strict-corpus.sh --run`: ok=113, no false positives or negatives, no wrong code. `runtime/run_tests.sh` (ASan/UBSan, TSan, `-Werror -Wextra`):
  all tests passed. `scripts/check-purity.sh` ok.
* Kotlin conformance (`tools/conformance`, the 135 box tests that mention a hash collection or `MutableMap` / `MutableSet`): identical to the
  toolchain without the change, test by test (32 pass, 101 not applicable, 2 compile errors that were there before).
