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

