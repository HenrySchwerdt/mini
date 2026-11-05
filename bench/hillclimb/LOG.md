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
