# Differential fuzzing

`tools/fuzz/` generates random Kotlin programs, runs them with mini and with kotlinc 2.1.20 on the JVM, and compares the output. The goal is
memory safety and conformance: every difference, crash, sanitizer report, leak and compiler crash is a bug in mini (or in the generator).

## The generator

`tools/fuzz/gen.py SEED` prints one program. It is deterministic in the seed, well typed by construction (an expression is built for a wanted
type from the variables in scope), terminates (loops have constant bounds, recursion has a bounded argument) and prints values and
checksums only. Everything the program does is observable through its output; every exception is caught in `main`, so the exit status is 0.

Covered: classes, abstract and open classes with `super` calls, interfaces with default methods and delegation (`by`), generics (classes,
functions, bounds), lambdas and closures over mutable variables, collections (lists, maps, sets, arrays, sequences, `groupBy`, `fold`,
`zip`, `windowed`...), strings and `StringBuilder`, nullables (`?.`, `?:`, `let`, smart casts), `when` (subjects, ranges, sealed classes,
enums), exceptions (custom hierarchies, `finally`, `check`/`require`), data classes (`copy`, destructuring, `hashCode`), objects and
companions, extension functions, operator overloading, inner classes, `lazy`, vararg and named arguments, `tailrec`, `inline`, labeled
`break`/`continue`, reference-cyclic object graphs (the cycle collector), `sequence { yield }`, `generateSequence`, and suspend functions
driven by a small `startCoroutine` scheduler.

Left out on purpose, because the output would differ for reasons that are not bugs: hash order (`HashMap`, `HashSet`), floating point
formatting of arbitrary values (only exactly representable values are printed), identity hash codes, stack traces and `Thread`.

## Running

```
python3 tools/fuzz/fuzz.py run --seeds 1000:2000 --variants c,llvm,c-asan,llvm-asan --jobs 4 --out /tmp/fuzz
python3 tools/fuzz/fuzz.py reduce --seed 1234 --variant c-asan --out /tmp/fuzz
python3 tools/fuzz/fuzz.py check --expect tools/fuzz/smoke --variants c      # no JVM, the stored expectations
python3 tools/fuzz/fuzz.py save --seeds 0:50 --dest tools/fuzz/smoke        # (re)write the stored programs with kotlinc's output
```

The root of the checkout is the default (`build/native/bin/minic-native`, `build/native/lib/libminirt.a`, `stdlib.mlib` when there is one);
`--kotlinc` (or `KOTLINC`) names kotlinc 2.1.20. The JVM side compiles 40 programs per kotlinc run, one package each, and caches the expected
output by program text in `OUT/expected`.

Variants: `c`, `llvm` (textual IR through clang), `c-asan`, `llvm-asan` (AddressSanitizer + UBSan + LeakSanitizer on the runtime and the
program, the same flags as the `leak` suites of `scripts/test-e2e.sh`).

Failures are classified and saved under `OUT/fail/SEED.VARIANT.KIND.{kt,expected,txt}`:

| kind | meaning |
|---|---|
| `mismatch` | the output differs from the JVM |
| `compile-error` | mini rejects a program kotlinc accepts (a missing feature or a frontend bug) |
| `ice` | the compiler crashed or exited with an internal error |
| `cc-error` | the generated C or IR does not compile |
| `crash`, `timeout` | the program died from a signal, exited non-zero or did not finish |
| `sanitizer`, `leak` | an ASan/UBSan report, a LeakSanitizer report |
| `gen-invalid` | kotlinc rejects the generated program (a generator bug, listed at the end of a run) |

## Sanitizers

The fixed smoke programs (`tools/fuzz/smoke`) are part of the sanitizer suites `fuzz-leak` and `fuzz-llvm-leak` (ASan + LSan + UBSan, see
"Sanitizer runs" in `docs/memory-model.md`); a finding in a generated program is a bug in the runtime or the code generator and gets a reduced
program in the smoke directory.

## Reducing

`reduce` minimises a failing program by delta debugging on balanced line spans: it tries to delete every statement or block (largest
first; 40 candidates are checked per kotlinc run and in parallel on the mini side) and keeps a deletion when kotlinc still accepts the
program and mini still fails in the same way (same kind, and for compiler, sanitizer and C compiler failures the same first message line with
the numbers masked). The prelude, `run()` and `main` stay. The result is written to `OUT/reduced.SEED.VARIANT.kt`.

## Smoke test in CI

`tools/fuzz/smoke/` holds 50 fixed programs with the JVM output stored. `scripts/test-e2e.sh --suite fuzz` (part of the default suites)
builds and runs them through the C backend; `fuzz-llvm` does the same through LLVM, `fuzz-leak` under the sanitizers. No JVM is needed.

## Found so far

See the list below; each fixed bug has a regression test in `compiler/testdata`.

About 1,500 distinct programs (sizes 14 and 24) were compared with kotlinc 2.1.20, in about 2,300 runs over the variants (C, LLVM, C with
sanitizers). Two compiler bugs came out, both fixed with a regression test; the rest were differences that are not bugs.

### Fixed

* **An operand evaluated twice** (`compiler/testdata/operand_evaluated_once.kt`). The lowering of `Int.compareTo`, `Long.compareTo`,
  `Char.compareTo` and `Long.hashCode` (`Ir2MirCalls.kt`) wrote the operands twice into the expression `(a > b) - (a < b)`, so an operand
  with a side effect ran twice (`7.compareTo(next())` printed what `next()` prints twice). Minimal program:

  ```kotlin
  fun f(): Int { println("in f"); return 3 }
  fun main() { println(7.compareTo(f())) }   // JVM: "in f", 1; mini printed "in f" twice
  ```

  Operands with effects are now bound to a temporary first (in order).
* **A leak per skipped iteration** (`compiler/testdata/loop_continue_releases.kt`, found by the `c-asan` variant). RcOpt releases a local right
  after its last use. When a `continue` before that statement jumps to the end of the loop body, the release there was already dropped
  because the early release was assumed to happen on every path: the object leaked once per skipped iteration, for every loop kind except
  `do while`. It was in every release up to 0.10.0. Minimal program (LeakSanitizer: three leaked objects):

  ```kotlin
  var n = 0
  fun cond(): Boolean { n++; return n < 100 }
  fun main() {
      for (i in 0 until 3) {
          val s = StringBuilder().append(i)
          if (cond()) continue
          println(s)
      }
  }
  ```

  RcOpt now does not release early when an earlier statement of the loop body contains a `continue` of that loop.

### Not bugs (the generator avoids them)

* `Throwable.initCause` and other JVM-only members do not exist in mini (nor in Kotlin/Native); the generator builds exceptions with the
  `cause` constructor argument.
* A selector with side effects in `sortedBy { }` is called a different number of times: the JVM compares through `compareBy`, which calls the
  selector for every comparison, mini once per element. Both are allowed (the output order of the side effects is not specified).
* `HashMap` iteration order is not generated (the JVM order is the hash order).

### Open

None known. The reducer needs a kotlinc run per round (about 20 seconds idle, more on a busy machine); a failing program of 200 lines takes
about 10 rounds. `run` and `reduce` need kotlinc; only `check` (the stored smoke programs) works without a JVM.
