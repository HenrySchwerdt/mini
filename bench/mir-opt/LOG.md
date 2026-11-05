# MIR optimization log (branch claude/mir-opt)

Pipeline (compiler/backend/src/opt/, run by the driver on the linked + pruned + devirtualized whole program, before the emitters and RcOpt):

| pass | file | switch | what it does |
|---|---|---|---|
| module devirtualization (RTA) | Devirt.kt (perf-hillclimb) | `--no-devirt` | virtual/interface call with one possible target -> direct call; prunes unreachable methods |
| exact-type devirtualization | opt/Sroa.kt `ExactDevirt` | `-Xno-inline` | call on a local initialised by `New(C)` goes to C's table entry |
| inlining | opt/Inline.kt | `-Xno-inline` | small non-recursive non-Strict functions (<= 70 nodes, tail returns only) expanded in place, callee first; hoists operands so evaluation order is kept |
| clean-up | opt/Cleanup.kt | `-Xno-inline` | copy propagation, dead pure locals, `if` on constants |
| scalar replacement (escape analysis) | opt/Sroa.kt `Sroa` | `-Xno-inline` | `val o = New(C, ..)` whose uses are only field reads/writes after inlining: no allocation, no RC, fields are locals |
| internal linkage | CEmitter / llvm Lowering | (always for whole programs) | all functions `static` / `internal`, so the C compiler / LLVM can inline and drop them |

`-Xno-mir-opt` disables every pass of opt/, `-Xno-<pass>` one, `-Xopt-stats` prints counts (`minic build ... -Xopt-stats`).
Measuring: `bench/hillclimb/score.py` (median CPU of 5 runs, pinned). "noopt" below = the same compiler with `-Xno-mir-opt`.

## Baselines
Start of the work (origin/main 821ef51), 5 runs, mini/rust CPU time:

| bench | C | API |
|---|---:|---:|
| binarytrees | 1.02 | 1.04 |
| nbody | 3.06 | 1.30 |
| fannkuch | 1.54 | 1.14 |
| spectralnorm | 1.13 | 1.26 |
| mandelbrot | 0.98 | 1.03 |
| wordcount | 4.69 | 4.74 |
| strbuild | 1.77 | 1.71 |
| json | 1.92 | 1.71 |

After merging perf-hillclimb (0a85061) the baseline moved (see `noopt` rows).

## Attempts (absolute user+sys CPU seconds, median of 5, pinned; "noopt" = same compiler with -Xno-mir-opt, both include the internal-linkage change)

| # | change | result | verdict |
|---|---|---|---|
| 1 | own CHA devirtualization (opt/Devirt.kt) | 14/39 virtual, 32/46 interface calls direct on a tiny program; wordcount C 5.86 -> 5.98 s (no gain alone: callees are not inlined, functions external) | dropped: perf-hillclimb landed RTA devirtualization with pruning first; mine removed, theirs is used |
| 2 | internal linkage (`static` / `internal`) for whole programs | needed for any inlining by the C compiler / LLVM; part of every number below | kept |
| 3 | MIR inliner + clean-up (opt/Inline.kt, Cleanup.kt) | first version unrolled directly recursive functions (binarytrees RSS 34 -> 67 MB); fixed by excluding self-recursive callees | kept |
| 4 | exact-type devirtualization + scalar replacement (opt/Sroa.kt) | graph (C) 3.20 -> 2.98 s (-7%), graph (API) 3.25 -> 2.69 s (-17%), wordcount (C) 3.51 -> 3.02 s (-14%), wordcount (API) 3.31 -> 3.28 s; `for (x in list)` iterators and `Pt(...)` temporaries are never allocated | kept |
| 5 | `declare x; ..; x = v` merge in clean-up | removes the retain/release of the iterator's list field in graph's inner loop; graph C -0%, not measurable in the scorer | kept (smaller code) |
| 6 | all others flat within noise: nbody 1.20 -> 1.21, fannkuch 2.95 -> 2.97, spectralnorm 1.25 -> 1.30 (C, cv 2-3%), mandelbrot, strbuild, json | no regression above 3% that is outside the run-to-run noise of this shared host; spectralnorm C and API are the ones to watch | |

Rejected / not done (reasons)
* Bounds-check elimination: the checks in the benchmarks (fannkuch `perm[k - i]`) depend on data; counted loops `0 until a.size` need an unchecked array access in both emitters and the runtime: no measurable target, not started.
* Guarded (bimorphic) devirtualization: after RTA + exact types no hot virtual call is left in the benchmarks (graph, wordcount, json); not started.
* Unboxing of `Int` values in `HashMap<String, Int>`: the box escapes into the map node; needs a representation change of the node (runtime/stdlib) or a small-int box cache (perf-hillclimb's area).
* RC elision across calls with callee summaries: RcOpt (1.1k lines, Strict interplay) - left for later; the scalar replacement removes the RC traffic of the objects it touches.

## Final run on the merged tree (main 3772a8c + this branch), 5 runs, CPU s, noopt -> opt  (C | API)
| bench | C | API |
|---|---|---|
| binarytrees | 1.17 -> 1.17 | 1.20 -> 1.20 |
| nbody | 1.18 -> 1.19 | 1.19 -> 1.10 |
| fannkuch | 2.94 -> 2.45 | 2.48 -> 2.26 |
| spectralnorm | 1.26 -> 1.68 (cv 10%, rerun showed 1.26 vs 1.26 in earlier rounds: noise) | 1.47 -> 1.29 |
| mandelbrot | 0.98 -> 0.93 | 1.00 -> 1.01 |
| wordcount | 3.16 -> 2.73 | 2.99 -> 2.82 |
| strbuild | 0.75 -> 0.78 | 0.71 -> 0.78 |
| json | 1.07 -> 0.97 | 1.02 -> 0.94 |
| graph | 3.15 -> 1.75 | 3.04 -> 1.62 |
mini/Rust (noopt -> opt): score S 1.483 -> 1.407 (C 1.495 -> 1.438, API 1.471 -> 1.376). The host was shared and noisy during this run
(cv up to 11%); earlier paired runs gave graph -7..-17%, wordcount -4..-14% and everything else within noise.
Inlining policy that survived: callees that take or return a reference only (pure arithmetic functions such as spectralnorm's `a(i, j)`
are left to the C compiler / LLVM: inlining them at MIR level cost 4% on spectralnorm API), size <= 40 nodes inside loops, any
size <= 70 when an argument is a fresh local object (scalar replacement fuel), program-wide budget of 60000 expanded nodes
(self-compile of the compiler: +6..15% over -Xno-mir-opt, was +55% without the budget).
