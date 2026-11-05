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

## Attempts
(see the table at the end, filled as the work goes)
