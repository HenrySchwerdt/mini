# Native size optimization: bounded experiment

Implemented one verified improvement: optimized macOS `minic build`/`run` executables pass `-Wl,-dead_strip` to the linker, for both LLVM API and C backends. Debug (`-g`), `-O0`, other platforms, `mini build`, bootstrap, runtime ownership and compiler IR are unchanged. This branch is for review; it is not merged or included in the release.

## Reproducible baseline

Host: Apple M4, arm64, Darwin 25.2. Linker driver: LLVM 18 clang. Both variants use the same existing API-enabled compiler, standard-library mlib and runtime archive, at `-O2`. Only `scripts/minic.sh` differs. Samples: `examples/hello.kt` and Mandelbrot N=2500 (checksum `715849966`).

```sh
CC=/opt/homebrew/opt/llvm@18/bin/clang \
  /usr/bin/time -lp "$INSTALL/bin/minic" build --backend=llvm-api -O2 input.kt -o output
# Repeat with --backend=c; compare the original and modified staged installations.
```

Paired builds with explicit backend and identical sources:

| Program | Backend | Before (bytes) | After (bytes) | Reduction |
|---|---|---:|---:|---:|
| Hello | LLVM API | 82,232 | 54,792 | 33.4% |
| Hello | C | 82,224 | 54,784 | 33.4% |
| Mandelbrot | LLVM API | 82,328 | 54,600 | 33.7% |
| Mandelbrot | C | 82,272 | 54,592 | 33.6% |

All outputs matched. Small file metadata differences between runs do not affect the roughly 27 KB reduction. A separate linker-flag probe reduced Hello's executable `__text` from about 26.8 KB to 8.8 KB. No symbol stripping or unsafe floating-point changes were used.

## Timing and instruction counts

Three alternating Mandelbrot runs per variant: API baseline and optimized both 0.24/0.22/0.22 seconds; C baseline 0.19/0.19/0.20 and optimized 0.19/0.19/0.19. There is no meaningful measured runtime change.

macOS `time -lp` reported roughly 2.235 billion retired instructions per API Mandelbrot run and 2.234 billion after dead stripping; C was roughly 1.956 billion versus 1.955 billion. The differences are below 0.1%; no instruction-count improvement is claimed. These are macOS process counters, not a portable perf-stat measurement.

A paired Hello build took API 0.41→0.29 seconds and C 0.22→0.18 seconds wall time; user CPU time was approximately unchanged (API 0.14→0.15, C 0.15→0.16 seconds). Cache/startup noise dominates these small samples; no compile-time speedup is claimed.

Self-compilation front-end baseline: the current native compiler generated C for its own 91 compiler sources plus 62 stdlib sources in 2.68 seconds wall / 2.48 seconds user, reporting 43,611,878,088 retired instructions and about 700 MB peak RSS. This excludes C compilation/linking and is not a full bootstrap measurement. The linker-only change does not alter this invocation; no before/after self-compilation improvement is claimed. Exact command and raw measurements were retained under `/tmp/mini-size-selfhost-command.json` and `/tmp/mini-size-selfhost-time.txt`; paired artifacts are under `/tmp/mini-size-after`.

## Validation and limits

- Existing `scripts/test-minic-cli.sh` suite passed against the modified staged install.
- `scripts/test-minic-dead-strip.sh` passed eight API/C routing and output checks: optimized, debug, O0, and mocked Linux host.
- Shell syntax and `git diff --check` passed; independent code review found no blocker.
- Runtime/exception/RC implementation is untouched. No Linux size result or blanket Kotlin equivalence claim is made.

Candidate two was ELF function/data sections plus linker garbage collection. It was not implemented because this host cannot cheaply establish the corresponding Linux measurement. Further opportunities are explicit size-oriented LLVM pipelines and profiling self-compilation before changing RC, casts, temporaries or dispatch. Hot paths were not attributed by this experiment. Stop here rather than adding speculative changes.
