# Performance hillclimb log

Goal: Mini programs as fast as Rust, with peak RSS and binary size no larger than Rust, without trading away correctness or memory
safety. Branch `claude/perf-hillclimb`.

## Method

Harness: `bench/hillclimb/score.py` (build toolchains first: `scripts/build-mini.sh` -> `build/native`, `bench/hillclimb/build-api.sh`
-> `build/api`, which has the LLVM C API compiler). It builds each benchmark (`bench/<name>/<name>.{kt,rs}`: binarytrees nbody
fannkuch spectralnorm mandelbrot wordcount strbuild json graph, plus `bench/startup/hello` for size) with

* Rust: `rustc -C opt-level=3 -C codegen-units=1` (default target-cpu = x86-64 baseline)
* Mini C backend: `minic build -O2 --backend=c` (clang -O2, x86-64 baseline)
* Mini LLVM API backend: `minic build -O2 --backend=llvm-api` (x86-64 baseline)

Every run's stdout is compared with `expected.txt` (correctness gate). Speed = median CPU time (user+sys) of N runs, round-robin over
the three binaries, pinned to one CPU, one warm-up run (the box is shared with other agents, so wall time is noisy; CPU time is much
steadier). RSS = median ru_maxrss. Size = `strip`ped executable.

Score: `S = geomean(speed ratio mini/rust)` over (benchmark x backend), `R = geomean(RSS ratio)`, `Z = geomean(stripped size ratio)`
over (benchmarks + hello) x backend. `score = S^0.5 * R^0.25 * Z^0.25`; 1.0 is Rust parity on everything, lower is better.
Note Z is already about 0.23 (Mini executables are 5x smaller than Rust's, since Rust links its std statically), so Z has
little room, and the climb is dominated by S and R.

Keep rule: a change is kept only if the score improves beyond noise and no individual metric (per-benchmark speed / RSS / size, either
backend) regresses by more than 3%. Noise threshold: the CPU-time cv of one benchmark is 1-8% with 5 runs on this loaded box
(medians reproduce within about 3%); the geomean over 18 pairs reproduces within about 1.5%, so the score threshold is 1.5%
unless the effect is confined to the benchmark it targets, in which case a re-run of that benchmark must confirm it
(>5% and reproduced).

Validation before each push (see the task): scripts/test-e2e.sh (all suites), `mini test` in compiler, compiler/bootstrap.sh
(C and --backend=llvm-api), scripts/strict-corpus.sh --run, ASan/UBSan on the benchmarks when touching RC / allocation.

## Baseline (origin/main 821ef51), 5 runs, cpu 3

| bench | rust cpu s | C speed | C rss | C size | API speed | API rss | API size |
|---|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.39 | 1.10 | 2.87 | 0.20 | 1.08 | 2.87 | 0.20 |
| nbody | 0.91 | 3.12 | 1.00 | 0.20 | 1.33 | 1.00 | 0.21 |
| fannkuch | 2.25 | 1.55 | 1.00 | 0.20 | 1.15 | 1.00 | 0.20 |
| spectralnorm | 1.28 | 1.08 | 1.00 | 0.20 | 1.23 | 1.00 | 0.20 |
| mandelbrot | 1.00 | 1.02 | 1.00 | 0.19 | 1.11 | 1.00 | 0.19 |
| wordcount | 1.39 | 4.01 | 3.81 | 0.46 | 4.06 | 3.81 | 0.42 |
| strbuild | 0.63 | 1.77 | 1.97 | 0.21 | 1.82 | 1.97 | 0.21 |
| json | 0.75 | 2.02 | 1.67 | 0.33 | 1.81 | 1.67 | 0.32 |
| graph | 0.75 | 9.67 | 2.26 | 0.30 | 10.45 | 2.26 | 0.29 |

hello (stripped): C 63784 B, API 59688 B, Rust 342512 B. stdlib.mlib 5355061 B. Self-compile (frontend -> C, median of 3): 7.83 s;
LLVM-API self-compile (stdlib+compiler -> object, O1): 105 s.

**S = 1.997, R = 1.630, Z = 0.232, score = 1.1085** (baseline). Raw: `bench/hillclimb/baseline.json`.

Observations driving the first attempts:
* graph (10x), wordcount (4x), json, strbuild: every collection call is a vtable call (`ArrayList` is `open`), `for (x in list)`
  allocates an iterator and calls it through itables, retain/release are out-of-line runtime calls.
* nbody on the C backend is 3.1x vs 1.33x on the API backend: the C emitter declares libm functions through an `__asm__` label
  (`mkx_sqrt`), which hides the builtin from clang (no sqrtsd).
* RSS: every object carries a 16 byte allocator prefix plus a 16 byte header.

## Attempts

(appended below, newest last)

Noise note: the box runs 4 vCPUs shared with other agents (load average 6-10), so CPU-time medians of 3-5 runs move by 10-20%
between sessions (the Rust reference moves too: graph 0.75-1.25 s). Decisions on micro-optimizations therefore use
`bench/hillclimb/icount.sh` (callgrind instruction counts of scaled-down benchmarks: deterministic) plus A/B runs interleaved in one
session; the full score table is reported with the usual caveat. RSS and size are deterministic and exact.

### 1. C backend: libm calls as builtins (KEPT)
Hypothesis: `extern double mkx_sqrt(double) __asm__("sqrt")` hides the builtin from clang, so no `sqrtsd` is emitted. Change: `CEmitter`
declares `sqrt floor ceil trunc rint sin cos exp log fabs pow remainder nextafter` through `__builtin_<name>`.
nbody (C): 3.12x -> 1.23x Rust (3 runs, same session). Other benchmarks unchanged.

### 2. Whole-program devirtualization with rapid type analysis (KEPT) - `compiler/backend/src/Devirt.kt`
Every collection call was a vtable/itable call (ArrayList is `open`; iterators go through `mk_itable`, a linear search). After the
MIR of the program and its libraries is linked and pruned, a reachability-driven RTA (instantiated classes = `New`, `ObjectGet`, boxes
of inline classes, runtime classes; vtable slots 0..3 and `Function0`/`Comparable` entries are called by the runtime and kept
reachable) turns a VCall/ICall with one possible target into a direct Call; strict code, `Any` slots and signature mismatches are left
alone. Not applied to mlib builds (open world), wasm libraries or the REPL. Callgrind graph (100k nodes): 434M -> 303M instructions;
graph 9.7x -> 5.1x (C, 3 runs). A/B on wordcount (alternating, same session): 6.0 s vs 6.9 s with `--no-devirt`. c suite 105/105.

### 3. Rejected: LTO of the runtime into the program; -O3 / larger inline threshold; `static` linkage for generated C
Measured on generated C of wordcount and graph with clang: `-flto` of runtime + program 6.0 -> 5.5 s and 6.1 -> 5.0 s (10-15%), needs
lld/gold and a matching clang on the user's machine; `-O3` and `-mllvm -inline-threshold=1000` no change; making all generated
functions `static` no change (clang already inlines within the TU). Not adopted: too small / too fragile for the cost.

### 4. Small Rc/Heap objects without the 16-byte allocator prefix (KEPT) - runtime/src/alloc.c, alloc_small.h, minirt.h
The block of every object carried `[owner | size]` in front of the header. Now small (<= 256 B) blocks of the Rc and Heap allocators
have no prefix: the object is the block; flags carry the owner (MK_FLAG_HEAP) and the size class (bits 8..15); the free-list link
lives in the dead object's type word (refcount/flags stay intact for the double-free diagnosis). Other allocators and large objects
keep the prefix (MK_FLAG_PREFIXED). RSS: binarytrees 98 -> 67 MB, graph 193 -> 147 MB, json 124 -> 93 MB; binarytrees 1.01x -> 0.84x
Rust. Runtime tests updated (flags no longer 0; byte accounting by size class); runtime/run_tests.sh (ASan+UBSan, TSan) passes.

### 5. Inline reference-array accessors (KEPT) - CEmitter prelude `mkh_ra_*`, `Lowering.refArrayAccess`
`ArrayList`/`HashMap` storage accessors were runtime calls. The layout of `MkRefArray` is now `MkArray`'s, and get/get_b/set/set_o/length
are inline in both backends (bad index and NULL still take the runtime's panic). Callgrind graph 300M -> 274M.

### 6. Box cache, direct `mk_alloc`, cast and `is` fast paths, string helpers (KEPT)
* `mk_box_int` (-128..1023), `mk_box_char` (0..255) and `mk_box_boolean` return shared immortal boxes: wordcount 3.70x -> 2.87x.
* `mk_alloc`/`mk_alloc_sized` take the small-block path directly (two calls less).
* `mkh_cast_nn`/`mkh_cc` (C prelude and LLVM prelude) test `o->type == target` inline; `mk_is_instance` walks only the superclass
  chain for a class target (interfaces have `instance_size == 0`) and the C backend inlines that chain; `mk_checked_not_null` -> `mkh_nn`;
  `mk_string_char_at`/`mk_string_length` inline in the C prelude. json callgrind: 166M -> 131M instructions.
* `mk_string_from_int/long` and `StringBuilder.append(Int)` write the digits in place (two digits per division); string templates
  `a + b + c ...` call `mk_string_concat3..6` (one allocation instead of one per `+`). strbuild callgrind 430M -> 347M.

### 7. Early release of last-use locals (KEPT) - RcOpt `RcFunctionPlan.early`, both emitters
Temporaries were released at the end of their scope (`check(bottomUp(stretch))` kept a 33 MB tree alive until the end of `main`). The
RcOpt liveness pass now records, for a simple statement that is the last use of an owned, never-reassigned local declared in the same
statement list (not a parameter, not borrowed, not moved/taken by that statement, not the last statement of the list), an early
release after it. Disabled in Strict functions and in functions with tracked locals (a first version broke strict_auto: a tracked alias
of an owned local is not a use of it). binarytrees RSS 67 -> 34 MB (Rust: 34 MB).

### 8. ArrayList: iterator over the storage, growth by move (KEPT) - stdlib/kotlin/collections/ArrayList.kt, `mk_ref_array_move`
`for (x in list)` used the generic list iterator (index checks, interface calls). `ArrayList.iterator()` is now `ArrayListIterator`
(reads the storage directly); `ensureCapacity` moves the slots (no retain/release per element). graph callgrind 251M -> 216M.

### 9. Linux size: function sections, --gc-sections, no unwind tables, RTA pruning of unreachable methods (KEPT)
`scripts/minic.sh` (optimized builds, Linux): `-ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables`
for the generated C, `-Wl,--gc-sections` for both backends (the macOS `-dead_strip` of the earlier campaign had no Linux counterpart);
the runtime archive is built the same way (build-mini.sh, package-release.sh; `MINIC_UNWIND_TABLES=1` keeps unwind tables for stack
walkers). `Devirt.prune` drops every function the program cannot reach and abstracts their vtable/itable entries (MirLink's pruning keeps
all methods of a live class). Stripped sizes (C): hello 63.8K -> 22.8K, binarytrees 67.9K -> 35.1K, wordcount 166K -> 68K, json 117K -> 60K,
graph 105K -> 43K (Rust: 336-349K). Not adopted: `-z noseparate-code` (-4K) and `-z norelro` (-1K) weaken the default hardening.


### 10. C emitter: array accesses leave the function inline; libm intrinsics in the LLVM lowering; StringBuilder narrow buffer (KEPT)
* `mk_array_get/set_*` in C are one statement expression with the bounds test and the exit path (when the exit path is short): no
  pending-exception load after each access. fannkuch (C) 1.50x -> 1.34x. `-fno-math-errno` for generated C; `sqrt floor ceil trunc
  rint fabs sin cos exp log pow` become `llvm.*.f64` intrinsics in the LLVM lowering.
* `StringBuilder` buffer is one byte per character until a character >= 256 is appended (runtime v1s.c; toString still makes UTF-16):
  strbuild RSS 1.98x -> 0.99x, wordcount 3.82x -> 2.87x, strbuild 1.43x -> 1.21x.
* Rejected: `MINI_OPT=-O3` for both backends (no change within noise).

## Final (5 runs, cpu 3; `bench/hillclimb/final.json`)
S = 1.395 (baseline 1.997), R = 1.225 (1.630), Z = 0.1025 (0.232), **score 0.703 (baseline 1.1085, -37%)**. Per backend: C S=1.40,
API S=1.39. Table in the task report. Self-compile guard 14.2 s is for a -O0-built dev compiler (baseline 7.8 s was a -O1 build): re-measure with
`scripts/build-mini.sh` before concluding anything about compile time.
Validated at this state: runtime/run_tests.sh, test-e2e (default suites + llvm + llvm-leak), llvm-api suite, strict-corpus --run,
`mini test`, compiler/bootstrap.sh C and --backend=llvm-api (stage2 == stage3). Not run: the wasm toolchain test.

## Next
Compact (Latin-1) strings (wordcount/json RSS), HashMap node layout (before/after links only in LinkedHashMap), a cache for `mk_itable`,
runtime bitcode inlining for the LLVM backend (`mk_alloc`, `mk_sb_append_char`), per-function sections in LLVM objects.
Devirt.kt and RcOpt `early` overlap with the `claude/mir-opt` agent's scope (CHA devirtualization, RC elision): merge carefully.

# Round 3 (`claude/perf-2`, base `claude/mir-opt`)

Measuring: `bench/hillclimb/score.py` (5 runs, pinned cpu 3) plus wall-clock phase timers and valgrind callgrind / cachegrind
(`bench/hillclimb/icount.sh`). The runtime library of these builds is `-O1` (build-mini.sh default); a `-O2` runtime (as in the
release) was tried: wordcount 2.77 -> 2.77 s, json 1.00 -> 0.99 s, graph 2.0 -> 2.0 s (no change, not pursued).
Note: the 0.9.0 stage0 (CI) cannot resolve a nested class constructor (`Inliner.Stats()`): `Inline.kt` has a top-level `InlinerStats`.

## Where the time goes (before)

* graph (C 3.2x): 6 BFS x 0.23 s plus 0.35 s to build; the BFS is memory bound (1M `Node`s of 32 B interleaved with a 32 B `ArrayList`
  and a 64 B storage array: 128 MB where Rust's index based version touches a 32 MB `Vec<Node>`). Removing the retain/release of the
  loop variables (hand edited C, no RC on `m`, `n`, the list) took the run from 2.1 s to 1.7 s: the counts are *writes* to cache lines
  that the loop only reads (`m.dist < 0` is false for 3 of 4 edges), so they add misses, dirty lines and candidate buffering for the
  cycle collector. One collection of the live graph costs 0.55 s (2.9M objects, mark and restore, about 100 ns per object and phase:
  random pointer chasing), the candidate buffer + slot table 50-70 MB of the RSS.
* wordcount (C 2.4x): 112 ns per word in the counting loop = about 1000 instructions: `HashMap.put` 113, string hash 72 (twice per
  word: `get` then `put`), `findNode` 55, release 63, alloc 47, equals 60 (+memcmp 36), `substring` 33, sb append 137 per word. RSS: the
  text is built in a one byte per character buffer (85 MB) and then copied into a UTF-16 string (170 MB): 246 MB peak.
* json (C 1.4x): allocation / free of the tree (12 % alloc, 18 % `mk_release`), the walk is 90 ns per node (cache misses).

## Attempts

### 1. RC elision across `ArrayList.add` (KEPT) - `@NoHeapEffects` on `ArrayList.add` / `ensureCapacity`, `Inliner` skips such functions
RcOpt borrows a loop variable / element read only while no event in its window can overwrite a heap reference it depends on;
`queue.add(m)` (a slot store plus the `array` field store of `ensureCapacity`) counted as such an event, so `m`, `n` and `n.out` of the
BFS were retained and released. `add` only fills the first free slot and `ensureCapacity` moves every element to the new storage, so no
reference that an element read depends on is dropped: both are `@kotlin.internal.NoHeapEffects` now (the storage array object itself is
only read inside `ArrayList`, never held across a call). The MIR inliner does not expand `noHeapEffects` functions (the promise is about
the call as a whole). graph: callgrind 283M -> 181M instructions (-36 %), C run 2.2 -> 1.95 s, RSS 225 -> 198 MB.

### 2. Cycle collector: 8 byte slot table entries (KEPT) - runtime/src/cycles.c
The table that finds the root buffer slot of a dying candidate stored 8 + 4 bytes per entry at a load of at most 1/2. Now one 64 bit
word (low 32 bits of the address, index + 1; a hit is confirmed against `roots[index]`) at a load of at most 3/4. graph RSS 198 -> 188 MB,
no change in speed.

### 3. Rejected: huge pages for big heaps; per-class allocation spans; prefetching in the collector; one collector stack
* `madvise(MADV_HUGEPAGE)` on 2 MiB aligned chunks after 8 MB of heap: the kernel here hands out no huge pages (AnonHugePages 0), graph unchanged.
* per-size-class bump spans (objects of a class contiguous instead of interleaved in allocation order): graph +10 % (the Node, its list and
  its array are adjacent in the interleaved layout, one DRAM miss serves the three).
* prefetching the children in `ref_array_trace`: collection 522 -> 540 ms (no change); a single stack for mark and restore: RSS -3 MB only.

### 4. Latin-1 strings (KEPT) - MK_FLAG_LATIN1, runtime string functions, both emitters
A `String` is now either UTF-16 or one byte per character (`MK_FLAG_LATIN1` in the object header, the bytes start where the UTF-16
units did). Everything that creates strings picks the narrow form when it can (`StringBuilder` narrow buffer, literals whose characters are
all < 256 in both emitters, `substring`, `+` / templates of narrow parts, number formatting, UTF-8 decoding of ASCII/Latin-1 text); all
readers go through `mk_string_at` / `mk_string_to_utf8` / `mk_string_copy_chars` (equality and hash are representation independent: a
narrow and a wide string with the same characters are equal). The inline `charAt` of the C prelude and of the LLVM lowering test the
flag. Runtime tests updated (+ a mixed representation test).
`StringBuilder.toString()` of a big narrow buffer (>= 64K characters) no longer copies: the block of the buffer (it has room for the
allocator prefix and the string header in front of the characters) is shrunk to the text and becomes the string; the builder shares it
(`backing`, one reference) and copies the characters before it is changed (append, set, shorten).
wordcount RSS 252 -> 93 MB (2.87x -> 1.1x Rust), json 96 -> 87 MB, strbuild unchanged (105 MB); speed flat (wordcount 2.8 -> 2.7 s).

### 5. Comparison head (50fbbcb) vs origin/main (8526f22), same session (5 runs, cpu 3; `r3-head.json`, `r3-main.json`)
|            | S | R | Z | score |
|---|---|---|---|---|
| main 8526f22 | 1.475 | 1.281 | 0.1254 | 0.769 |
| perf-2 50fbbcb | 1.427 | 1.113 | 0.1254 | 0.730 |

Per benchmark (C speed x Rust / RSS x Rust), main -> perf-2: wordcount 2.50 -> 2.58 / 2.86 -> 1.06, json 1.41 -> 1.30 / 1.25 -> 1.14, graph 3.88 -> 3.27 / 2.56 -> 2.15,
strbuild 1.29 -> 1.31, binarytrees 0.90 -> 0.87, others within noise (cv 3-10 %, Rust's own time moves +-10 % between runs). Stripped sizes are identical
in both trees (hello 27024 B). So the perf-2 commits are a net gain; the apparent S/Z rise against `final.json` (1.395 / 0.1025) is not from them:
* Z: hello grew 22.8K -> 27.0K when the cycle collector (mk_cycles_collect 2 KiB, slot table, ...) arrived from the memory-safety branch; every program links it.
  Idea (not done): keep the collector out of programs without a candidate class (needs mk_release / mk_cycles_candidate to be weak or the
  compiler to pick a "no cycles" runtime flavour).
* S: Rust's own cpu time differed by up to 20 % between the sessions (binarytrees 1.66 s then, 1.37 s now), so ratios of different sessions are not
  comparable; same-session A/B above is. spectralnorm C 1.01 -> 1.37: absolute C time 1.26 -> 1.71 s. The generated C is the same shape as before; everything is
  inlined into main and clang's loop/vectorisation choices decide. Rewriting the step-1 loops in the canonical `i++ < n` form (hand edited C) gave 1.50 -> 1.40..1.52 s:
  noise, not adopted. API build 1.47 s (LLVM's loop form is canonical already).
* wasm toolchain (scripts/build-wasm-toolchain.sh incl. test-programs, mlib inheritance, minic.wasm tests): passes on 50fbbcb.

### 6. Cycle collector: no root-count trigger after a collection that freed nothing (KEPT) - runtime/src/cycles.c
graph ran two collections: one at 32 MB (nothing to free: `visited=1`) and one at 262144 roots (the `roots >= threshold` trigger, threshold x4 backoff) when
the 1M node graph was complete: 2.9M objects visited, 0.5 s of 2.1 s, nothing freed. Now a useless collection backs off by 8 (was 4) and, while backed off,
only the allocation volume trigger (>= 3 x live bytes x backoff, at least 256 MB) starts a collection; the roots trigger applies again after the first collection that frees something.
graph 3.27x -> 2.45x (C), 3.23x -> 2.31x (API), RSS 2.15x -> 2.00x; json, binarytrees, wordcount unchanged (wordcount 2.58 -> 2.30 was noise: no collection runs there beyond the first).
Cost: a program that starts to create cyclic garbage after a useless collection can grow to 256 MB before the next one (stress-rss/stress-leak suites pass).

Fix (CI): EmitterTest.stringLiteralsAreUtf16AndDeduplicated asserted the UTF-16-only literal; it now checks both representations
(MK_STRING_LITERAL8 for all-below-256 strings, MK_STRING_LITERAL otherwise) including deduplication of each and a mixed module. The emitter was correct.
Full `mini test -p backend -p frontend -p native-driver -p mini-tool`: exit 0 (103 + 33 + 2 + 79 tests). check-purity's "backend: unbound variable"
is on origin/main too (scripts/check-purity.sh is identical there; `declare -A` does not exist in the macOS bash 3.2).
