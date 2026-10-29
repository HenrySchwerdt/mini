# Benchmarks

These numbers come from `docs/benchmarks.md` in the repository (milestone D1, "dogfooding"): **measure first, then remove reference-counting work in the backend without touching the programs.** They compare the baseline code generation with the `RcOpt` pass. They were measured by the project, on a 4-core machine with clang 18 at `-O1`, best of 7 runs; they are not re-measured by this site's CI.

## How to measure

- **Counters.** `minic build --stats prog.kt -o prog` compiles runtime and program with `-DMK_STATS`. At exit the program prints four `mk-stats:` lines to stderr: allocations, frees, live objects at exit and peak, bytes (total, live, peak), and `retain`/`release` calls split into non-atomic, atomic (shared objects) and no-ops (null, static or stack objects). In a `--stats` build every retain and release is a runtime call so the counters see all of them.
- **Benchmark driver.** `compiler/bench/run.sh [--reps N] [--configs "base opt"] [--only name]`, or `gradle -p compiler bench`. For every benchmark it builds both configurations (`base` = `--no-rc-opt`, `opt` = RC elision), checks that the output is identical, and takes the best time of N runs. Results go to `compiler/build/bench/results.md`.
- **Benchmarks** (`compiler/bench/micro/*.kt`): `arraylist`, `hashmap`, `stringbuilder`, `mapfilter` (map/filter/fold chains), `tree` (binary-tree building), `templates` (string templates). Plus the **self-hosted backend** `minic-backend` turning a MIR file into C: `selfhost-small` and `selfhost-large` (the MIR of the backend itself, the largest input there is).

## Results

`base` is the baseline (`--no-rc-opt`). `opt` is RcOpt plus inline retain/release fast paths in the generated C. Counts are logical operations from the `--stats` build.

| benchmark | config | time (ms) | allocs | retains | releases |
|---|---|---:|---:|---:|---:|
| arraylist | base | 405 | 4 200 357 | 67 417 457 | 81 026 310 |
| arraylist | opt | **265** | 4 200 357 | 25 608 560 | 37 017 688 |
| hashmap | base | 102 | 1 285 094 | 11 218 441 | 19 014 613 |
| hashmap | opt | **72** | 1 285 094 | 5 511 361 | 12 618 633 |
| mapfilter | base | 247 | 1 937 308 | 36 133 678 | 43 114 204 |
| mapfilter | opt | **146** | 1 937 308 | 8 853 110 | 13 737 200 |
| stringbuilder | base | 92 | 1 000 062 | 12 400 021 | 14 400 104 |
| stringbuilder | opt | **60** | 1 000 062 | 4 200 000 | 5 200 062 |
| templates | base | 109 | 4 200 000 | 600 000 | 5 100 000 |
| templates | opt | 105 | 4 200 000 | 0 | 4 200 000 |
| tree | base | 100 | 2 172 852 | 8 277 846 | 18 128 592 |
| tree | opt | **61** | 2 172 852 | 0 | 3 745 746 |
| selfhost-small | base | 62 | 569 418 | 5 294 320 | 7 939 640 |
| selfhost-small | opt | **49** | 569 418 | 1 781 705 | 3 140 155 |
| selfhost-large | base | 749 | 5 290 607 | 54 561 332 | 83 114 527 |
| selfhost-large | opt | **603** | 5 290 607 | 17 290 458 | 30 511 031 |

## Reading the table

- **Time** drops from about 5 percent (`templates`, dominated by allocating the temporary strings) to 40 percent (`tree`, `mapfilter`, `arraylist`).
- **Retains** drop by 60 to 100 percent, **releases** by 40 to 80 percent.
- **Allocations and peak memory are identical by construction.** RcOpt moves and removes counting; it does not change what is allocated or when objects die.
- The native self-hosted backend gets 20 percent faster on its large input (749 to 603 ms) even though it now also runs the RcOpt analysis on its input. That analysis costs about 120 ms of the 603; compiling with `--no-rc-opt` is 25 percent cheaper in the backend for a program that then runs slower. A cheaper analysis is a candidate for later work.
- What remains counted is mostly *owned results* (`ArrayList.get`, `HashMap.get`, iterator `next` return +1 and the caller releases), boxing, and stores into collections. They would need borrowed return values and owned parameters for virtual slots, which are next steps.

## Where the counting came from

Found with the counters and by reading the generated C:

1. every read of a reference field was copied into a retained temporary;
2. traversal variables (`p = p.next`) retained the next node and released the old one on every iteration;
3. constructor arguments such as `Tree(make(d - 1), make(d - 1))` created owned temporaries that the constructor retained and the caller released again;
4. `return x as T` of an owned temporary (every generic getter) retained and released;
5. releases of `NULL` were calls into the runtime;
6. string temporaries of templates: one retain and release per piece, unchanged by D1.

The rules that remove them are in [Reference counting and RcOpt](/internals/refcounting).

## Reproducing

```sh
cd compiler && ./gradlew installDist && cd ..
bash compiler/bench/run.sh --reps 7           # about 8 minutes; --only tree for one benchmark
cat compiler/build/bench/results.md
./minic build prog.kt -o prog --stats         # counters for any program; --no-rc-opt for the baseline
```

Timing noise on a shared 4-core machine is about 5 percent; the counters are exact.

## Memory checker and annotations

None of these numbers needed `@Owned` annotations, `free()` calls or Strict mode. The conventions RcOpt infers (stored constructor parameters are owned, everything else borrowed, field reads are borrows while nothing overwrites a heap reference) are exactly what `@Owned` and `@Borrowed` state in Strict code, so the inference rules may become the default for unannotated parameters there.
