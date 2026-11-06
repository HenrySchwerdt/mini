# Conformance: the Kotlin "box" tests

`tools/conformance` measures how much real Kotlin Mini compiles and runs correctly. It runs the official
**codegen box tests** of JetBrains/kotlin (`compiler/testData/codegen/box` and `boxInline`, about 10 000 files) through `minic`.
Each test defines `fun box(): String`; the harness compiles the test together with a generated `main` that prints `box()`,
runs the binary and expects `OK`.

```sh
./mini --version                     # build the toolchain (build/native)
tools/conformance/run.sh             # fetch the tests (once), run all of them, write tools/conformance/results/
tools/conformance/run.sh --filter box/ranges --filter box/enum      # only paths that contain one of the substrings
tools/conformance/run.sh --retest tools/conformance/results/results.json   # re-run what did not pass (the rest is kept)
MINIC=/path/to/other/bin/minic tools/conformance/run.sh             # another toolchain
```

* The tests are fetched with a shallow, sparse clone into `~/.cache/kotlin-box` (outside the repository), pinned to the tag
  `v2.0.21` (commit `284e9b4041bd815b5b1b489070bbb1f1db6de35c`, see `run.sh`). Only the two test directories are checked out.
* Tests run in parallel (`--jobs`, default: number of CPUs). A binary gets `--timeout` seconds (10), a compilation 90.
* Output: `tools/conformance/results/results.json` (every test with status, first diagnostic and the failure buckets) and
  `summary.md` (counts, skip reasons, the largest failure buckets).
* Statuses: `pass`, `compile-error` (minic exit 1), `ice` (minic crashed or the C compiler rejected the generated code),
  `runtime-fail` (wrong result or non-zero exit), `crash` (the program died from a signal), `timeout`, `skipped`.
* Failure buckets group the failures by status and the first diagnostic with quoted names, numbers and paths removed.

## Which tests count

A test is **not applicable** (skipped, left out of the pass rate) when it tests the JVM or another platform rather than the
language. The rules are in `run.py` (`skip_reason`); every skipped test carries one reason in the report:

| reason | rule |
|---|---|
| `target-backend` | `// TARGET_BACKEND:` without `NATIVE` (JVM, JS, WASM only), or `// TARGET_PLATFORM: JVM` |
| `ignored-on-native` | `// IGNORE_BACKEND(_K2): NATIVE` or `ANY`, `// DONT_TARGET_EXACT_BACKEND: NATIVE` |
| `jvm-dir` | below a directory that is about the JVM: `reflection`, `javaInterop`, `fullJdk`, `invokedynamic`, `compileKotlinAgainstKotlin`, `multiplatform`, `synthetic`, `jvm*`, `java*`, `js*`, `wasm*`, ... |
| `jvm-directive` | `WITH_REFLECT`, `FULL_JDK`, `WITH_JDK`, `JVM_TARGET`, `JVM_DEFAULT_MODE`, `CHECK_BYTECODE_*`, `ASSERTIONS_MODE`, `SAM_CONVERSIONS`, `LAMBDAS`, ... |
| `jvm-api` | the source uses `java.*` / `javax.*`, `::class.java`, `.javaClass`, `kotlin.reflect`, `kotlin.jvm.*`, `Thread`, `System.getProperty`, ... |
| `multi-module` | `// MODULE:` (several compilation units with dependencies) |
| `compiler-config` | directives that change the compiler configuration (`API_VERSION`, `DIAGNOSTICS`, ...) |
| `foreign-source` | `// FILE:` of a Java, C or other non-Kotlin file |

Everything else is run, including the tests that use `@JvmStatic` / `@JvmField` / `@JvmName` (Mini accepts them as no-ops).

What the harness adds around a test:

* every `// FILE: x.kt` part is a separate source file; text before the first `// FILE:` is `test.kt`;
* the generated `main` calls `box()` (or `pkg.box()` when the test declares a package);
* diagnostic markup of the frontend tests (`<!NAME!>...<!>`) is removed, `OPTIONAL_JVM_INLINE_ANNOTATION` becomes `@JvmInline`;
* tests that use `kotlin.test` get the subset that `mini test` ships (`compiler/mini-tool/src/TestCommand.kt`), tests that use
  `helpers.*` or `WITH_COROUTINES` get `tools/conformance/helpers.kt` (a port of the suite's coroutine helpers).

## Second round (branch claude/conformance-2)

| | after round 1 | round 2 |
|---|---:|---:|
| **pass** | **4837 (81.01%)** | **5058 (84.71%)** |
| compile-error | 640 | 460 |
| ice | 203 | 133 |
| runtime-fail | 227 | 245 |
| crash | 61 | 71 |
| timeout | 3 | 4 |

(Some tests moved from compile-error to a later failure stage: they now reach run time, where the next problem shows.)
Fixed in round 2:

* suspension points inside lambdas of `inline` functions: `SuspendInline.kt` expands such calls on the IR (deep copy of the inline
  function body, lambda bodies substituted for their invocations, `return` through a `do { } while (false)` block) before the state
  machine is built; also `suspend inline` functions, suspend lambdas for inline parameters, anonymous functions, non-local returns from
  inline lambdas inside suspend functions, `suspend { }`;
* adapted callable references (defaults, vararg, coercion to Unit), references bound to objects / companions, `E::valueOf`;
* Unit-typed parameters (overrides of generic functions with `T = Unit`), Nothing-typed variables, `var` of a class type captured by a
  lambda (owned temporary in a heap cell), `init` lambdas in classes with only secondary constructors;
* default arguments declared on interface functions, fun interfaces that inherit their method, `values()` in enum companions,
  `array.indices` / `array.iterator()` as values, `assert`, `AssertionError(Any?)`, `Char.toByte()`;
* the harness helper `StateMachineChecker.check` now matches the original (an extra `proceed()` and the early-finish check).

Still open (largest): suspend conversion of callable references, `coroutineContext`, enums as `Comparable` at run time (`sorted()` on
enum entries fails: the Enum builtin has no itable entry), `CharSequence` as a real interface, generic value classes, builder inference,
non-local `break`/`continue`, `field` inside lambdas of accessors, `finally` that throws after `return`.

## Third round (branch claude/conformance-3)

| | after round 2 | round 3 |
|---|---:|---:|
| **pass** | **5058 (84.71%)** | **5182 (86.79%)** |
| compile-error | 460 | 417 |
| ice | 133 | 125 |
| runtime-fail | 245 | 188 |
| crash | 71 | 57 |
| timeout | 4 | 2 |

Wrong-code bugs fixed (programs that compiled and ran, but produced wrong results):

* `finally` after `return` / `break` / `continue`, the identity shortcut of `equals`, `try` of type Unit / String, `null!!` typed as Nothing;
* named arguments are evaluated in source order;
* a class that inherits an interface default through a superclass and a more specific interface override now gets the override
  (`Ast2IrFake.kt`: a class-owned fake override whose declarations are all overridden by another candidate is dropped; interface fakes stay in
  `overridden` because they carry the itable links);
* `super.p` / `super.p = v` called the overriding accessor (reading the derived class's field, `null` in initialisers); the accessor call now
  carries the super qualifier;
* data class `equals` compares `Double` / `Float` properties with total order (`NaN == NaN`, `0.0 != -0.0`).

Other fixes: enum `Comparable` at run time (`sorted()`, `maxOf`, `<`; checked), suspend conversion of callable references, non-local
`break` / `continue` from lambdas of inline functions (the IR loop of the enclosing function is the target, the inline expansion in Ir2Mir
resolves it), `field` inside a lambda in an accessor, `KCallable.name` of callable references (`::f.name`, a constant; a bound receiver is still evaluated).

`CharSequence` is a real interface now: a `CharSequence` value is an `Any` reference (a `String`, or an object of a class that implements the
interface: `StringBuilder`, user classes). `length` / `get` / `subSequence` go through `mk_cs_length` / `mk_cs_get` / `mk_cs_sub_sequence`
(runtime, `v1l.c`), which take the String fast path (`mk_string_char_at`, `mk_string_substring`: both string representations) and otherwise
call the `CharSequence` itable (slots 0, 1, 2); `is CharSequence` is `mk_is_char_sequence`; a statically known `String` keeps the direct
paths. `StringBuilder` gets the supertype in `Sema.ensureSupertypes` (the standard library sources are built by the release stage0, which does not
know the rule, so they only say `override`); `Devirt` treats the `CharSequence` itable as reachable from the runtime like `Comparable`'s.
`CharSequence.iterator()`, `withIndex()`, `indices`, `lastIndex`, `forEach`, `forEachIndexed` are in `stdlib/kotlin/text/CharSequences.kt`
(`isEmpty`, `isBlank` and the other `String` helpers are still declared on `String` only).

Still open (largest): `coroutineContext` (a `suspend` property: the getter is not treated as suspend), generic value classes, Float/Double
range operators, builder inference, the `unresolved call` long tail (166), crashes (57), C compiler rejects (40), properIeee754
comparisons through smart casts, more `CharSequence` extension functions (`CharSequence` receivers for the rest of kotlin.text).

## Results

Toolchain of origin/main (821ef51) against the final state of this branch, both with the same harness:

| | origin/main (821ef51) | this branch |
|---|---:|---:|
| test files | 10070 | 10070 |
| not applicable (skipped) | 4099 | 4099 |
| applicable | 5971 | 5971 |
| **pass** | **4008 (67.12%)** | **4837 (81.01%)** |
| compile-error | 1403 | 640 |
| ice (compiler crash, C compiler rejects the code) | 238 | 203 |
| runtime-fail | 210 | 227 |
| crash (signal) | 110 | 61 |
| timeout | 2 | 3 |

The remaining failures are listed with counts in `tools/conformance/results/summary.md` (`summary-baseline.md` is the baseline); the largest groups by cause (a failure can match one group only approximately, the groups overlap a little):

| count (matching failures) | cause |
|---:|---|
| 56 | suspension points inside lambdas of `inline` functions (`list.forEach { delay(1) }`, `run { yield() }`): the lambda is expanded by Ir2Mir after the state machine is built; needs an IR-level inliner before `SuspendLowering` |
| 50 | adapted callable references: defaults, `vararg`, coercion to Unit, suspend conversion (`::foo` where `foo` has more parameters than the expected function type), `::foo.name` |
| 243 | `unresolved call`: a long tail (about 100 different names), e.g. `foo(vararg x: Unit, y: Any)`, typealias SAM constructors, companion accessors of several companions, `coroutineContext` (suspend properties) |
| 60 | the C compiler rejects the generated code: Unit / Nothing in unusual positions (`Array(42, return "OK")`, `x + break`), generic Unit casts |
| 61 | crashes (signal 11 / 6): `tailrec suspend`, reified `T::class` in `when`, bridges of generic properties, `AbstractMethodError` in a few delegation / diamond cases |
| 12 | `CharSequence` is erased to `String` (docs/stdlib.md): `for (c in cs: CharSequence)`, `CharSequence.withIndex()`, `indices` |
| 41 | array members (`indices`, `arrayInstanceOf`, `copyOf` ...) in positions the lowering has no special case for |
| 18 | builder inference (`buildList { }`, `sequence { }` without explicit type arguments) |
| 87 | `box/inlineClasses` in total: a user value class named like a stdlib type (`UInt`), generic boxing in lambdas and callable references (spread over the other causes) |

