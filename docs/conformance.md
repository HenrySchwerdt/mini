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

## Fourth round (branch claude/conformance-4)

| | after round 3 | round 4 |
|---|---:|---:|
| **pass** | **5182 (86.79%)** | **5243 (87.81%)** |
| compile-error | 417 | 402 |
| ice | 125 | 112 |
| runtime-fail | 188 | 173 |
| crash | 57 | 39 |
| timeout | 2 | 2 |

Wrong-code and crash fixes (programs that compiled but crashed or ran wrongly):

* an anonymous object created through its type before its body was converted (`object : A { ... }.f()` in a top-level property getter, a
  suspend lambda in an interface default method) never got its fake overrides: the vtable / itable slots of the overridden members were
  `abstract` (`AbstractMethodError`);
* `list.map(::member)` written in a class body (a reference to the member of the implicit `this`) was not bound: the closure had no receiver
  (signal 11). It binds `this`, an outer `this` or the object now;
* `provideDelegate` was never called; it is resolved and evaluated at the initialisation of the delegated property (members, top-level,
  local, extension receivers);
* `a == b` where both operands are smart cast to `Double` / `Float` (`a != null && b != null && a == b`, `x is Double && y is Double`) uses
  IEEE comparison (`-0.0 == 0.0`, `NaN != NaN`) instead of `equals`;
* `var i: Int? = 5; i++` / `i += 1` and the use of `i` after it: the variable is smart cast to the initializer / assigned type (ICE
  "inc/dec is not resolved", "arithmetic on Any").

Other fixes: `suspend val` getters are suspend functions and `kotlin.coroutines.coroutineContext` exists (the stdlib declares it through
`currentContinuationIntrinsic()`, which the compiler replaces by the continuation of the enclosing suspend function, so that the release
stage0 still builds the sources); `val x by map` / `var x by mutableMap` (`Map.getValue`, `MutableMap.setValue`); `Enum.name` / `ordinal` through
`T : Enum<T>` receivers; SAM constructors through typealiases of (non-generic) fun interfaces; lazy `CharSequence.withIndex()`.

kotlin.text for `CharSequence` receivers: `stdlib/kotlin/text/CharSequencesMore.kt` has the CharSequence overloads of the rest of kotlin.text
(`isEmpty`, `isBlank`, `trim*`, `startsWith` / `endsWith` / `contains` / `indexOf` / `lastIndexOf` with `ignoreCase`, `lines`, `split`,
`take` / `drop*`, `padStart`, `removePrefix`, `filter`, `map`, `fold`, `associate*`, `windowed`, `chunked`, `zip`, `first` / `last` / `single`,
`regionMatches`, `contentEquals`, `commonPrefixWith`, ...; the String overloads stay the more specific, direct paths; the others go through
`length` / `get` or `toString()`). Arrays got `contentEquals`, `contentHashCode`, `contentDeepToString` / `Equals` / `HashCode`.

Still open (largest): `tailrec suspend` (9 tests: the self call is a state machine call, the recursion overflows the stack), enum entry
bodies that refer to their own entry in an initializer (the entries are created inside the holder constructor), the C compiler rejects
(`Array(42, return "OK")`, `x + break`: Unit / Nothing as values; 35 of the remaining 40), generic value classes (11 of the 15 "arithmetic on
Any"), generic typealiases of fun interfaces, `::member` inside a lambda with a receiver, builder inference, `unchecked cast` checks of generic
arrays, Float/Double range operators on `Comparable` ranges, `Double.fromBits`, `Map.withDefault`, unsigned array `forEach` / `reversed`.

## Fifth round (b) (branch claude/conformance-5b): the standard-library long tail

| | after round 4 | round 5b |
|---|---:|---:|
| **pass** | **5243 (87.81%)** | **5306 (88.86%)** |
| compile-error | 402 | 348 |
| ice | 112 | 102 |
| runtime-fail | 173 | 174 |
| crash | 39 | 39 |
| timeout | 2 | 2 |

The failing tests were re-run (`run.sh --retest`); a sample of 824 previously passing tests across all directories was re-run on the final
toolchain: 824 / 824 pass, no regression.

APIs added (stdlib/, each family with a test in compiler/testdata):

* numbers: `Double.fromBits` / `Float.fromBits` (runtime bit-cast helpers `mk_sys_bits_double` / `mk_sys_bits_float`), `Float.mod`, Byte / Short
  bit operations, unsigned bit operations, `Char.toFloat`, `Char` companion constants, `ulp` / `nextUp` / `nextDown` / `nextTowards` / `withSign`,
  Float `kotlin.math` overloads;
* ranges: `ClosedFloatingPointRange`, `OpenEndRange`, `rangeTo` / `rangeUntil` on Float / Double / `Comparable`, `coerce*` for all numeric types
  and ranges, mixed unsigned `contains`, `random` / `randomOrNull`; the last element of a progression follows Kotlin's `differenceModulo`
  (overflow cases);
* comparisons: `compareValuesBy`, `compareBy(comparator, selector)`, `nullsFirst` / `nullsLast`, `thenBy`, `minOf` / `maxOf` overloads;
* random and time: `nextBytes`, `nextInt(IntRange)`, `nextLong(LongRange)`, `nextUInt` / `nextULong`; `Duration.toComponents`, `toIsoString`,
  `parse*`; `measureTimeMillis` / `measureNanoTime`, `exitProcess`, `lazy(mode)`, `lazyOf`, `printStackTrace`, `stackTraceToString`,
  `addSuppressed`, `ConcurrentModificationException`;
* arrays: the long tail of primitive arrays (generated, `tools/gen-stdlib-arrays.py`, now with Byte and Short), unsigned arrays (generated,
  `unsigned` mode) and `Array<T>`;
* collections: `Map.withDefault`, `Grouping` operations (`eachCountTo`, `foldTo`, `aggregateTo`, ...), `groupByTo` / `associateByTo` with two
  selectors, `windowed` / `chunked` / `zipWithNext` with transforms (Kotlin's argument checks and messages), `binarySearch`, `component1..5`,
  `fill`, `sortDescending`, `addAll` / `removeAll` / `retainAll` for arrays and sequences, `buildList` / `buildMap` with capacity, `Map<out K, V>`
  `get` / `containsKey` / `remove`, `AbstractIterator`, load-factor constructors;
* sequences: windowed / chunked / zipWithNext / runningFold / runningReduce / scan are lazy now, plus `filterIsInstance`, `foldIndexed`,
  `reduceIndexed`, `minWith`, `flatMapIndexed`, `toCollection`, `unzip`, `SequenceGrouping`, `minus` overloads;
* text: `Charset` / `Charsets` (UTF-8, UTF-16, ISO-8859-1, US-ASCII), `toByteArray(charset)`, `String(bytes, charset)`, `chunkedSequence`,
  `windowedSequence`, `trim*(vararg chars)`, `toByte` / `toShort` / `toLong(radix)` / `toUInt` ... / `toBooleanStrict`, `CASE_INSENSITIVE_ORDER`,
  `String.format`, many `CharSequence` operations; Unicode for the Basic Multilingual Plane: `isLetter`, `isDigit`, `isUpperCase`, `isWhitespace`,
  `uppercaseChar`, `lowercaseChar`, `titlecase`, `digitToInt`, `CharCategory`, full upper case mapping (`ss` for the German sharp s), final sigma,
  `Char.equals(ignoreCase)` (`stdlib/kotlin/text/UnicodeTables.kt` is generated by `tools/gen-unicode-tables.py`; ASCII keeps a fast path that
  never touches the tables).

Small compiler fixes that the library needed: a call on a companion with an extension receiver (`Double.fromBits(x)`, `String.CASE_INSENSITIVE_ORDER`),
primitive companions (`Int.Companion`, ...) as values, `CharSequence.subSequence` on strings, a lambda argument for an overloaded `vararg` of
lambdas (`compareBy { }`), SAM lambdas are checked against the SAM arity, projected SAM types (`sortedWith { a, b -> }`).

Library rules learned: the stdlib is also built by the release stage0 (0.9.0): no top-level `ByteArray` properties, no `Outer.Inner()` calls; generic
generated inline functions are type-checked by every compile (about 1.3 ms each), so the generated array families cost compile time: lib/stdlib.mlib grew
from 5.7 to 8.9 MB and `--check` of a hello world from 0.5 to 1.0 s (the unsigned array family alone is about 0.28 s). Nothing that reaches user programs
pulls code in: unreachable library code is still not emitted (the `multifile` test checks that `uppercase()` does not bring `ArrayList` along, which is why
the full upper case table is a plain array scan, not a `HashMap`). A lazy check of inline bodies in stubs would remove the cost; it is left for a
frontend change.

Still missing: Unicode outside the Basic Multilingual Plane (`Char` is 16 bit; code points above U+FFFF are not classified), `Regex` features
beyond the basics, `compareValuesBy` with vararg selectors returning different types (compiler: the lambdas are analysed before the type argument is
known), Byte literal inference in generics, `box/arrays/primitiveArrays.kt` (the C compiler times out on one very large generated function).

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

