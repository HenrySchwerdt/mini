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

## Layout (claude/layout): field reordering, subclass tail-padding fill

Tool: `minic build x.kt -Xlayout-stats` prints per class size, padding and field offsets, new layout next to the old one
(declaration order, padded superclass embedded). Shared function: `compiler/backend/src/Layout.kt` (`ObjectLayouts`), used by the
C emitter (flat structs in offset order plus `_Static_assert` on sizeof and every own offset) and the LLVM backends.

Rules: header first; runtime-defined exception classes (`MkThrowable` prefix) stay a fixed prefix (embedded as `base`); own fields
placed after the superclass's unpadded data end, largest alignment then size first, gaps filled by the largest field that fits.
Deterministic in the class chain alone, so mlibs and wasm objects agree with their users.

Measured (instance size, old -> new):
* bench programs: no change at all (graph 272/272 B summed over classes, binarytrees 56/56, json 464/464, wordcount 760/760, strbuild 56/56).
* the compiler itself (851 classes): sum of sizes 38416 -> 37960 B (-1.2%), padding 1885 -> 1429 B; with the allocator's 16 B
  granules (alloc_small.h) 26 classes drop a granule (41552 -> 41072 B summed). Examples: Ir2Mir 400->368, mini.lower.IrSimpleFunction
  200->176, mini.backend.memory.AVal 88->72, MFun 176->152, mini.frontend.sema.VarSym 112->96, MirStmt.ForRange 88->80, FnState 80->64.
* Boolean bit packing (estimate, 247 Boolean fields): only 48 B more over all classes and 3 more classes dropping a granule: not done.
* self-compile peak RSS (frontend -> C, 3 runs each): base 549032/549388/549252 KiB, head 549588/549628/549404 KiB: no measurable change.

Verdict: the realistic gain is within noise (most padding is the unavoidable 8 B tail round-up, and the allocator rounds to 16 B anyway).
Recommendation: merge only if the risk is judged acceptable; `-Xlayout-stats` is the durable part.

Full validation (head = main e434374 + #70/#71 merged): all test-e2e.sh suites (c, llvm, leak, llvm-leak, memcheck, memrun, strict,
strict-leak, strict-llvm-leak, stress-leak, stress-llvm-leak, stress-rss, multifile, mlib, llvm-api), runtime/run_tests.sh, C bootstrap
and llvm-api bootstrap (stage2 == stage3), `mini test` (79 tests), strict-corpus --run, ci-stage0 (0.9.0 stage0), the wasm toolchain
with the mlib inheritance test (wasm32 layout checked: 12-byte header, `Int` placed in the gap before the 8-aligned `Long`). Fixes: the
C asserts are skipped unless pointers are 64-bit; `-Xlayout-stats` used 8-byte pointers for `--target=wasm32` without `--emit=wasm-lib`.
# Round 4 (`claude/perf-3`, base origin/main 8c22f6c)

Same-session A/B (5 runs, cpu 3, `score.py --no-guard`): head = this branch, base = the same compiler/stdlib with the origin/main runtime library
(`libminirt.a` swapped, so only the runtime differs).

|  | S | R | Z | score |
|---|---|---|---|---|
| base (origin/main runtime) | 1.4105 | 1.1048 | 0.1258 | 0.7251 |
| head | 1.3829 | 1.1043 | 0.1116 | **0.6968** (-3.9 %) |

hello stripped 27024 -> 18720 B (C and LLVM-API), wordcount/json/graph sizes -4 KB each (0.21 -> 0.21, 0.20 -> 0.19, 0.17 -> 0.17 of Rust: the
programs that use a candidate-capable class still link the collector). Speed and RSS differences are noise (Rust's own times moved +-8 % between the two runs).

### 1. Link the cycle collector, the arena allocator and the deferred-release queue only where they are used (KEPT) - runtime/src/{object,alloc,alloc_arena,cycles}.c, internal.h
Object files of a static library are pulled in by symbol, but `--gc-sections` (function sections, see 9 of round 2) removes everything that live code does
not reach; the cycle collector was reached from `mk_release` / `mk_alloc` of every program (+4 KB in hello world).
* `MkCyclesHooks mk_cycles_hooks` (object.c): `candidate`, `forget`, `state`, `at_exit`, `release_armed`, `drain`. `mk_release`, `free_dead`, the entry's
  `mk_cycles_at_exit` and `mk_release_drain` call through it; the trampoline `mk_cycles_candidate` stays for generated code.
* `mk_cycles_install()` (cycles.c) fills the table. It is called by `mk_cycles_ensure()` from the only places that make candidates: `mk_alloc_armed`
  (the compiler's allocation entry of a cyclic class), `mk_ref_array_new`, `array_new` of object arrays and `mk_cycles_collect`. The generic paths
  (`mk_alloc`, `mk_alloc_sized`, `mk_alloc_sized_site`) arm a traced type only when the table is installed (`MK_ARMABLE`). Corner: a traced class that is
  allocated only through the generic `mk_alloc` of a program with no armed class and no array of references is not armed (not produced by the compiler).
* The release path of ARMED / BUFFERED objects (deep-chain queue `pending_*`, `release_buffered`) moved to `mk_release_armed` / `mk_release_drain_armed`,
  reachable only through the table; `mk_threads_running` moved to sys.c.
* Arena: `alloc.c` reaches the arena only through `mk_arena_marker` / `mk_arena_zeroed_hook`, set by `mk_arena_create` (one indirect call per arena object).
Result: hello 27024 -> 18720 B (the text segment dropped below the page boundaries too). Programs with candidate classes are unchanged (one load + compare in
`mk_alloc_armed`). runtime/tests/test_cycles.c calls `mk_cycles_install()` first.

### Not done in this round (candidates, measured/analysed only)
* wordcount (callgrind, 600K words): 970 instr/word; get+put hash and compare the key twice (hash 76, equals 56, findNode 55, put 113), box alloc/release 40:
  a string hash slot in the header (4 B, both emitters and the LLVM lowering hard-code the layout: `Lowering.kt` literal and `stringCharAt`), a fused
  get/put, a String fast path in `mk_equals` are each worth 3-5 %.
* graph: 128 B per node (Node 32 + ArrayList 32 + 4-slot array 64: the array is 24 B header + 32 B = 56 -> 64 class); the collector is not the RSS (one collection, 1 root).

## Round 4b (`claude/perf-4`, base `claude/perf-3`)

### 2. HashMap remembers the node of the last String key found (KEPT) - stdlib/kotlin/collections/HashMap.kt
`counts[w]` then `counts[w] = c + 1` hashed and compared the key twice. `findNode` stores `lastKey` / `lastNode` (strong references, only for
`String` keys: immutable, so identity means contents) and answers `key === lastKey` without hashing; `put` with `key === lastKey` updates the node's value
directly. Both are cleared where a node leaves the table (`removeNode`, `clear`; iterator removal goes through `removeNode`). Test: testdata/hashmap_lastkey.kt.
wordcount callgrind (600K words) 594.96M -> 554.64M (-6.8 %); json, graph, binarytrees unchanged (no String-keyed lookups / no maps).
Wall clock (full size): 2.69 s vs 2.80 s in a first run, 2.74 vs 2.77 s in the A/B below (within the noise of the box, cv 4-13 %).
Not worth doing after this (measured, callgrind per word):
* a cached string hash: every word is a fresh substring, so a header slot would be filled and read once; the hash is now computed once per word (41 instr).
* a String fast path in `mk_equals` / replacing memcmp by a loop for short strings: about 15 instr of 925 per word (< 1.5 %), below what the box can show.
* a small-Int box cache: exists already (`BOX_CACHE` -128..1023 in classes.c).
* `mk_sb_append_char` (17 instr per char) is already a tight function; the call is the cost.

### 3. The uncaught-exception report no longer links `mk_to_string` (KEPT) - runtime/src/{v1l,classes}.c
`mk_report_uncaught` called `mk_to_string`, which reaches every number formatter (`format_fp` 950 B, `mk_string_from_double/long/...`), `mk_any_to_string`
and `mk_hash_code`: all of it was linked into hello world. It now calls the Throwable's `toString` slot directly (`mk_throwable_virtual_to_string`).
hello 18720 -> 14576 B (text 4.4 KB; the rest is ELF/page overhead), -46 % since the start of the round.

### 4. Analysed, not done
* RC ownership transfer / borrowed loop variables: the counting loop of wordcount retains/releases `c` once per word (about 10 instr, 1 %): `put` may
  drop the node's old value, so the borrow cannot be proven without knowing `put`; graph's BFS already has no RC traffic on loop variables (round 3, 1).
* itable cache: `mk_itable` is 0.6 % of wordcount, < 1 % of json/graph. LLVM bitcode inlining: the API backend is already level with the C backend
  (S_c 1.394 vs S_api 1.400 in the same session), so there is little to win.
* graph (2.4-2.8x): 128 B per node and, in the scaled callgrind run, the collector (visit_mark/restore, ref_array_trace, slots_put: 30 % of the
  instructions at 100K nodes; one collection at full size). A tuning of the first collection threshold is the next thing to look at.

### Same-session A/B (5 runs, cpu 3, `--no-guard`; base = perf-3 stdlib + runtime, same compiler; `build/r4b-*.json` not committed)
|  | S | R | Z | score |
|---|---|---|---|---|
| base (perf-3) | 1.3678 | 1.1043 | 0.1116 | 0.6930 |
| head (perf-4) | 1.3971 | 1.1045 | 0.1041 | 0.6884 (-0.7 %) |

S moved +2 % but that is the box: Rust's own time moved by up to 7 % between the two runs (graph 0.66 vs 0.70 s, binarytrees 1.34 vs 1.42) and the programs
this round did not touch (nbody, fannkuch, mandelbrot, spectralnorm, binarytrees) show the whole S difference; the only speed change of the round is
wordcount, -6.8 % instructions. Z is deterministic: hello 18720 -> 14576 B and every program -4 KB or more (0.105 C, 0.103 API). Against the start of
round 4 (origin/main runtime, `r4-base`: score 0.7251) the branch is at 0.6884.
