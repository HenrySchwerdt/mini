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
  `summary.md` (counts, the Native-parity number, skip reasons, the largest failure buckets).
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
| `non-default-flag` | `// FREE_COMPILER_ARGS: -Xbinary=genericSafeCasts=true` (round 6 b): an opt-in mode of Kotlin/Native, see the sixth round |

Everything else is run, including the tests that use `@JvmStatic` / `@JvmField` / `@JvmName` (Mini accepts them as no-ops).

What the harness adds around a test:

* every `// FILE: x.kt` part is a separate source file; text before the first `// FILE:` is `test.kt`;
* the generated `main` calls `box()` (or `pkg.box()` when the test declares a package);
* diagnostic markup of the frontend tests (`<!NAME!>...<!>`) is removed, `OPTIONAL_JVM_INLINE_ANNOTATION` becomes `@JvmInline`;
* tests that use `kotlin.test` get the subset that `mini test` ships (`compiler/mini-tool/src/TestCommand.kt`), tests that use
  `helpers.*` or `WITH_COROUTINES` get `tools/conformance/helpers.kt` (a port of the suite's coroutine helpers).

## Native parity (second report)

The pass rate above is over every test that is applicable to a native compiler of Kotlin. `run.py` also reports a second number,
**Native parity**: the same denominator without the applicable tests that JetBrains itself documents as failing or not run on
Kotlin/Native (2.0.21, K2). The main number keeps its definition; the parity report only subtracts (`summary.md`, `results.json`: `summary.native_parity`, and
`parity` on every test).

Which directives the v2.0.21 `box` / `boxInline` sources use (counted over the 10 070 files), and what each one does here:

| directive | files | treatment |
|---|---:|---|
| `// TARGET_BACKEND: JVM`, `JVM_IR`, `JS`, `JS_IR`, `WASM` (no `NATIVE`) | 3 200+ | skipped (`target-backend`), as before |
| `// TARGET_BACKEND: NATIVE` | 32 | run |
| `// IGNORE_BACKEND: NATIVE` (62), `IGNORE_BACKEND_K2: NATIVE` (5) and the lists that contain `NATIVE` or `ANY` | about 145 | skipped (`ignored-on-native`), as before |
| `// DONT_TARGET_EXACT_BACKEND: NATIVE` | 28 | skipped (`ignored-on-native`), as before |
| `// IGNORE_BACKEND_K1: ... NATIVE` | 18 | **run**: the K1 spelling only applies to the old frontend; Kotlin/Native 2.0.21 compiles with K2 |
| `// IGNORE_NATIVE_K1: ...` | 3 | run (K1 only) |
| `// IGNORE_NATIVE: cacheMode=...` / `optimizationMode=...` / `target=...` (also `DISABLE_NATIVE: ...`) | 8 | run (the condition names a Native cache or optimisation mode, a plain build is not affected); all but one are in directories that are skipped anyway |
| `// DISABLE_NATIVE` without a condition | 1 | parity-excluded (`native-ignored`; the file is also skipped by `ignored-on-native`) |
| `// NATIVE error: ...`, `// K2/NATIVE fail: ...` (a note of the test's author) | 12 | parity-excluded (`native-known-failure`; every one of them is also skipped by the main rules) |
| `// NATIVE_STANDALONE` | 2 | run |
| `// FREE_COMPILER_ARGS: -Xbinary=genericSafeCasts=true` | 19 | run: Kotlin/Native passes them *with* that flag, so they count for parity; Mini does not check casts to type parameters |

Because the main skip rules already contain every directive that marks a test as ignored on Native, the parity report excludes **0** further
applicable tests with the 2.0.21 sources: both numbers are equal today. The report exists so that the two can diverge when a test is
ignored on Native for a reason that Mini does share (for example after a bump of the pinned tag) and so that the document records why the
K1-only spellings are not excluded.

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

## Fifth round (a) (branch claude/conformance-5a)

| | after round 4 | round 5 (a) |
|---|---:|---:|
| **pass (all applicable tests)** | **5243 (87.81%)** | **5425 (90.86%)** |
| Native parity (see above) | 5243 (87.81%) | 5425 (90.86%) |
| compile-error | 402 | 381 |
| ice | 112 | 42 |
| runtime-fail | 173 | 93 |
| crash | 39 | 28 |
| timeout | 2 | 2 |

182 tests moved to `pass`, none of the earlier passes was lost (`results.json` diff). 8 of the 182 come from the harness fixes below, the rest
from the compiler and the standard library. Most ICEs became passes (73) or runtime failures; a few runtime failures and crashes that were
hidden behind ICEs are new entries of those buckets.

Harness fixes (they correct false failures, no skip rule changed): the generated `main` sorts first (`00_main.kt`) and calls
`kotlin.io.println`, so a test that declares its own `fun main(args: Array<String>)` or its own `println` is run through `box()` (before, the
program printed nothing: 8 tests). The Native-parity report is new (see above). The remaining `kt513`-like failures print to stdout from
inside `box()`; the harness still compares the whole output with `OK`.

Wrong-code and crash fixes (programs that compiled and crashed or ran wrongly):

* a non-reified type parameter of an `inline` function stays erased in the expansion (`x as T` is unchecked; before, the type argument was
  substituted and `foo<Int>()` cast a String to Int: `ClassCastException`, 8 `boxInline/dontReify` tests);
* `x is T?` / `is Int?` / `is Nothing` and reified `is T` with a nullable type argument: null is an instance (the check ignored the `?`;
  `Int?` crashed the C backend);
* `super@Outer.f()` and the unqualified `super` of an inner class always used the outer class' `this`;
* `var (a, b) = ...` captured and assigned by a lambda (the variables were not boxed: the assignment was lost); a captured `val x: T`
  that is assigned later (`runOnce { x = ... }`) and a captured `lateinit var` that was never assigned (no exception);
* `assertEquals(1234, someLong)`: an integer literal adapts to the type of the other arguments (T = Long); before the literal stayed an Int
  and the values compared unequal; `Base<Long>(1)` in a super constructor call (the instantiated type parameters were inferred again);
* `label@{ ... }` as a call argument, a lambda argument of an infix call and of a constructor (`Array(n) { return@Array x }`): the labels
  were unknown (`return@label` returned from the wrong function: `Unit` result, ICE);
* a private member of a superclass is not a member of the subclass (`object : X("inner") { fun f() = n }` read the superclass field instead
  of the enclosing instance' one); the arguments of a super constructor call do not see the members of the class being constructed
  (`object : A(b)` inside `A` read the half-built object's `b`);
* the setter of a fake override in `class B : C, A<Int>()` stored an unboxed Int into the `T` field of `A` (signal 11);
* named arguments of a super constructor call and of a callable reference with a bound receiver are evaluated in source order;
* `createCoroutine(...).resumeWithException(e)` on a coroutine that was never started ran the block (the exception was swallowed);
  `createCoroutine` coroutines can be resumed once (a second resume throws `IllegalStateException`, like `SafeContinuation`);
* `Iterator` of `CharSequence`: `nextChar` called `length` again (observable through a custom `CharSequence`);
* callable references: references to suspend functions where a suspend function type is expected (signal 11), a member reference in a lambda
  with a receiver (`::write` bound to the receiver), a callable reference or a safe `?.Inner()` construction converted to a fun interface
  (signal 11), suspend conversion of a function value (`runS(lambdaVal)`), equality of callable references (`::f == ::f`, bound receivers
  compared, `hashCode`; property references too): equal references share one closure class whose `equals` compares the captured values;
* interface diamonds: `D : B, C` where `C` overrides what `B` inherits from `A` picked the inherited implementation (functions and
  property accessors); private interface functions are called directly (ICE "interface method not found");
* `field++` / `++field` in an accessor and `(label@ x)++`; `-2147483648` as an Int; `operator fun A.not(): A`;
* the companion object `init` blocks run when the class is first instantiated (the companion was created lazily and never initialised);
* IEEE comparison of `Double` / `Double?` operands (`-0.0 == 0.0`, `NaN != NaN`, null only equals null) for statically known types;
  the generated `equals` of a data class keeps the total order;
* an inner class of an inner class reads a member of the outermost class in an initializer (the outer instances were bound one level deep
  in constructors); property references to inherited / imported members (`::v` of a protected property, `::a` of an imported object member).

Other fixes: a `Nothing` value (`break`, `continue`, `return`, `throw`) used as an operand or an argument (`fn(return x)`, `for (j in continue
downTo 1)`) no longer produces invalid C; `Unit` properties; `f() as Any` where `f` returns Unit; `x is Nothing`.

Still open (largest, from `tools/conformance/results/summary.md`): the generic safe casts of Kotlin/Native
(`-Xbinary=genericSafeCasts=true`, 19 tests: Mini does not check casts to type parameters, a check would cost a type test on every generic
result), default arguments of inner class constructors that refer to an outer `this` (`this@A` in `inner class C(val a: A = this@A)`, 9),
`set` with default or `vararg` parameters in indexed assignments / increments (6 ICEs), Float / Double range operators and `contains` on
comparable ranges (13 ICEs, standard library), `Unit` as an operand of `==` (5), primitive companion objects (`Int.Companion`, 8),
enum entry self references and `tailrec suspend` (5c), generic value classes (5c), the inline expansion of `try` / `finally` with `return`
in the finally block (`returnInFinally`, signal 11), a SAM conversion to a fun interface with a suspend method (signal 11), equality of
references with a coercion to Unit (3), arrays in the `toString` of data classes (2), the local classes that capture loop variables of an
enclosing local class (2), the C compiler rejects of `Unit` / `Nothing` values in a few more positions (8).

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

## Fifth round (c) (branch claude/conformance-5c)

Language-feature gaps (frontend, lowering, suspend lowering, value classes). Rounds 5a (crashes / harness reporting) and 5b (stdlib long tail)
ran in parallel; `origin/main` (with the object-layout work) was merged twice, so the numbers include whatever those branches landed.

| | after round 4 | round 5 (c) |
|---|---:|---:|
| **pass** | **5243 (87.81%)** | **5415 (90.69%)** |
| compile-error | 402 | 281 |
| ice | 112 | 103 |
| runtime-fail | 173 | 149 |
| crash | 39 | 21 |
| timeout | 2 | 2 |

The five planned features:

1. **`tailrec suspend fun`** (`SuspendTailrec.kt`, 9 of 9 tests): before `SuspendLowering` the body is rewritten into
   `while (true) { val p' = $p; body }` over mutable copies of the parameters; a tail call (`return f(..)`, the branches of a returned
   `if` / `when`, the last statement of a `Unit` function, parentheses) assigns the copies through temporaries (all new values are computed
   before any parameter changes, an extension receiver may change: `(this - 1) test x`) and continues the loop. Every iteration works on
   fresh `val`s, so lambdas created in the body keep the values of their iteration. A tail call that omits an argument (a default value)
   and calls that are not in tail position stay ordinary calls. The state machine of the function then contains no self call.
2. **Enums**: the entry is stored in the holder as soon as its construction starts (an `init` block or a property initializer of an entry
   body may read `B.value`, `Z.name`; this was a null read, signal 11); the companion object is initialized together with the entries
   (after them, and the first use of the companion creates the entries first, like the static initializer of the JVM); `valueOf` initializes
   the class even for an unknown name.
3. **Generic value classes**: the failures were not in the box / unbox code but in the type `T : Int`: a value of a type parameter with a
   primitive upper bound is now an `IMPLICIT_CAST` to the bound where it is used (`x + 1`, `array[x]`, `x.toLong()`), and operands of
   primitive members typed `Any?` are unboxed. Together with `A<Int>::member` (a callable reference to a member of a generic class with
   written type arguments was parsed as a call), `is C<*>` smart casts of a `C<Int>?` (the narrowed type is the original without null) and the
   expected type of an outer call reaching the lambda result (`useX(call { X(42) })`: the result variable of `call` has only the upper bound
   `X<Int?>`, which is the expectation of the last expression) the inline-class tests went from 840 to 858 of 897.
4. **Builder inference**: `buildList { add(1) }`, `buildMap`, `buildSet`, `buildString`, `sequence { yield(x) }`, `iterator { }` and nesting
   of them worked already; what failed was `build outer@ { ... this@outer ... }` (the label of a lambda argument was dropped, 5 tests).
   Left: real postponed type variables (`uncompilingFun = { handleInt(it) }` infers `T` from the use inside the lambda), builder lambdas in
   an elvis / `if` synthetic call (`pclaRootIs*`), builder inference with local classes (3 ICEs).
5. **`::member` inside lambdas with a receiver**: the reference binds the receiver of `with(a) { map(::foo) }` / `run` / `apply`
   (members and properties), extension functions and properties of implicit receivers (`::aExtension` inside `class A`), the innermost scope
   group wins (`::memberFunction` of an inner class); `Outer::Inner`, `outer::Inner`, `::Inner` (constructors of inner classes), references
   to local extension functions (`A::localX`, `a::localX`), to constructors of the array classes (`::IntArray`, `::Array`) and to the
   constructor of a fun interface (`::KSupplier`).

Also fixed on the way (each has a test in `compiler/testdata`):

* equality and hash codes of callable references (`::f == ::f`, `a::f == a::f`, `A::f != a::f`): references to the same function share a
  closure class that compares the captured receiver; property references compare name and accessors; adapted references (defaults, vararg,
  suspend conversion) share a class per target and shape. Not done: references coerced to Unit are not told apart (`coercionToUnit*`).
* the companion objects of superclasses are in scope (members and properties; 11 `multipleCompanionsWithAccessors` tests);
* inner classes: default arguments of constructors and members read the outer instances (`this@A`), `init` blocks of doubly nested inner
  classes read the outermost one, the innermost implicit receiver wins (`Father.InClass()` in `Child2`), `super<K>@A.foo()` picks the
  receiver of the labelled class, unqualified `super.f()` looks the member up in the superclass and the interfaces;
* an array passed by name to a vararg parameter (`foo(s = s)`), `_` in explicit type arguments (`foo<Int, _>(...)`), `foo() == Unit`
  (the call runs, the value is the Unit object), labels of lambda arguments of constructor calls (`Array<String>(1) { return@Array "x" }`)
  and infix calls (`x myMap { return@myMap }`), extension functions called on a class name (`A.foo()` with a companion receiver), the
  companion of a primitive class as a value, a `fun interface` whose abstract method is implemented by a sub-interface, `do while (c);`
  and labels in front of local declarations;
* a secondary constructor of an inner class that delegates to `this(...)` no longer stores the outer instance a second time (the outer
  object leaked; found by the leak suite on the new `inner_secondary_ctor` test).

172 more tests pass than at the start of the round, and no test that passed before fails now (full harness run, same harness, compared with
the committed `results.json` of round 4). The crash column also contains what the parallel rounds and the layout work fixed.

Validation of the final sources: `scripts/test-e2e.sh` (the default suites, `llvm`, `llvm-leak`), `compiler/bootstrap.sh`, the full
`mini test`, `scripts/strict-corpus.sh --run` and the bootstrap with the 0.9.0 release as stage0 (`scripts/ci-stage0.sh`) all pass. The full
harness run used the toolchain of the commit before the last small fix (the inner-class constructor leak, which the harness does not
see); the rest of the validation ran on the final commit.

Still open (largest): `unresolved call` (callee names are about 100 different ones, e.g. `foo(vararg x: Unit)` forms of `kt17691`, generic
fun interface behind a typealias, `Flow.map { null }`), function references coerced to Unit and `::foo.name`, classes that extend `Number`
(the `mk_checked_number_to_*` runtime does not call the user's `toInt`), integer-literal arithmetic with an expected `Long`
(`val a: Long = 2147483647 + 1`: K2 computes in Int and converts), `Unit` as a value in data classes / locals (C compiler rejects), real
builder inference, `Outer.Inner()` inside lambdas of inline functions that capture the outer instance, multiple-module and
`// FILE:` tests whose first `fun box()` is a member (`properRecapturingInClass`: the harness picks the wrong package, 6 tests).

## Fifth round combined (5a + 5b + 5c)

The three branches of round 5 merged: one full harness pass over the combined tree (`results.json`, `summary.md`).

| | after round 4 | round 5 (a + b + c) |
|---|---:|---:|
| **pass (all applicable tests)** | **5243 (87.81%)** | **5614 (94.02%)** |
| Native parity | 5243 (87.81%) | 5614 (94.02%) |
| compile-error | 402 | 226 |
| ice | 112 | 32 |
| runtime-fail | 173 | 84 |
| crash | 39 | 13 |
| timeout | 2 | 2 |

The per-round tables above were measured on the branches alone (5a: 5425, 5b: 5306 against a different base, 5c: see its section).
The combined merge also fixed a regression found by CI: a non-reified type argument that is a C pointer (`memScoped { f() }` returning
`CPointer<..>?`) is substituted in the inline expansion again, so the LLVM C API backend compiles itself.

## Sixth round (a) (branch claude/conformance-6a)

Targets: the `compile-error` and `ice` buckets of round 5 (226 + 32 tests), mostly frontend / sema fixes plus a few small lowering fixes. A parallel branch
(6b) works on runtime failures and crashes. `results.json` / `summary.md` come from a full harness run (5709 passed) over the tree one commit before the last one, followed by a `--retest` of everything that did not pass on the final tree (it also fixes the one regression the full run found, `fir/localInvokeExtension`: a parameter `encode: W.(T) -> Unit` must win over the member extension `encode` of the enclosing object) and a re-run of a sample of the passing tests in the touched areas (`box/fir`, `smartCasts`, `extensionFunctions`, `callableReference`, `closures`, `funInterface`, `sam`, `operatorConventions`, `typealias`, `inference`, `functions`, `objects`, `innerNested`, `delegation`, `when`, `casts`; 932 applicable tests: 869 pass, none of them regressed, 36 are among the fixed ones). The wall time in `summary.md` is that of the retest.

| | after round 5 | round 6 (a) |
|---|---:|---:|
| **pass (all applicable tests)** | **5614 (94.02%)** | **5711 (95.65%)** |
| compile-error | 226 | 130 |
| ice | 32 | 27 |
| runtime-fail | 84 | 86 |
| crash | 13 | 14 |
| timeout | 2 | 3 |

Fixes, grouped by cause (every group has a test in `compiler/testdata`, named `conformance6a_*`, plus the box tests named here):

1. **`invoke` on values the callee name denotes** (`conformance6a_invoke_objects`, `conformance6a_invoke_values`; about 30 tests). `A(42)` where `A` is an object, a class with a
   companion object, a nested class with a companion, or a typealias of an object (`invokeOnObject1`, `invokeOnClassObject1/2`, `invokeOnClassObjectOfNestedClass1/2`,
   `typeAliasObjectCallable`, `implicitInvokeInCompanionObjectWithFunctionalArgument`, and the six `selfReferenceToCompanionObject*` tests that called `Host.ok()` through the
   companion's `invoke`), `operator fun A.invoke()` extensions on objects, enum entries `A.ONE(42)` / imported `ONE(42)` (`invokeOnEnum1/2`, `invokeOnImportedEnum1/2`), extension
   properties of function type (`4.ext()`, `kt1892`; the extension property with the most specific receiver wins: `D.attr` over `C.attr`, `kt3772`), a member property of an object with an extension
   `invoke` (`kt24209`), member extension `invoke` on expressions (`A()()`, `extensionInvokeOnExpr`, `kt14201_2`) and `recv.(fn)(args)` (`1.(fun Int.() = this + 1)()`, `A.(foo)()`:
   `executionOrder`, `kt4228`, `functionExpression*`, `functionLiteralExpression`). The sema now falls back to "the callee is a value": `valueInvokeGroups` in `Calls.kt`; the lowering
   keeps the dispatch receiver of a member-extension `invoke` next to the function value (`Ast2IrCalls.invokeCall`).
2. **Member extensions applied to implicit receivers** (`kt3285`, `kt17725`, `kt52432`, `kt6382`, `traitWithPrivateExtension`, `innerNested/extensionFun`, `objects/privateExtensionFromInitializer_kt4543`): `fun NoiseMaker.playWith()` declared in a class and called as
   `playWith()` inside a lambda with receiver `NoiseMaker`, or with `this` smart cast to the declaring class: the dispatch receiver (a class or lambda receiver) and the extension receiver
   (any implicit receiver) are paired (`addReceiverGroups`).
3. **Default values and argument binding.** Constructor default values see the parameters before them (`class A(val i: Int, val j: Int = i)`, data classes, enum constructors, inner classes: `kt2270`,
   `defaults10`, `innerWithDefaultArgument`, `constructorWithDefaultParam`); `a[i] = v` / `a[i] += v` where `set` has a defaulted or `vararg` parameter before the value (`set` binds the value to its last
   parameter, `callOperator` passes the bound arguments: the six `set is not resolved` ICEs `incWithDefaults`, `kt16520`, `plusAssignWithDefaults`, `classVarargGetSet*`); a vararg given by name does
   not swallow the next positional argument (`mixedNamedPosition/varargs*`); a function that a superclass implements and an interface gives default values for (`class C : I, Impl()`: `funInTraitChain`,
   `implementedByFake*`, `defaultsFromFakeOverride`, `withDefaultParameters`).
4. **SAM conversion of function values and typealiases.** A value of function type is accepted where a fun interface is expected (`run1(f)`, also nullable: `isNull(nullableFun())`, `nullableSam`,
   `lambdaRuntimeConversion`, `mangledSamWrappers*`; the sema subtype rule in `TypeOps.sub`, the lowering wraps a nullable value so that `null` stays `null`); the SAM constructor of a generic fun interface
   behind a typealias (`StringConsumer { }`: `funInterfaceTypealias`); constructors behind generic aliases with explicit type arguments (`ST<String>(...)`: `genericTypeAliasConstructor2`), inner classes behind aliases
   (`outer.OI(...)`), star projections through an alias (`AddLastDesc<*>`: `objectLiteralConstructor`), a bare generic alias in `is` / `as` (`typeAliasAsBareType`).
5. **Types.** `kotlin.Comparator<in T>`, `kotlin.ConcurrentModificationException`, `another.Outer()`: a package with a one-segment name qualifies a type (`nonTrivialProjectionInSuperType`, `kt10143`);
   a generic class written without type arguments in `is` / `as` / `when (x) { is A -> }` takes them from the subject (`class B<out U> : C<Nothing, U>()` against `C<Int, String>`: `kt10934`,
   `inferredTypeParameters`); a function type with a receiver is interchangeable with the one that takes it as its first parameter (`test1(b)`; `kt13312` still fails in the lowering).
6. **Contracts.** `require(x is T)`, `check(c)`, `requireNotNull(x)`, `checkNotNull(x)` and the simple user contracts `contract { returns() implies p }` / `implies (p != null)` give smart casts after
   the call (`contracts/require`, `contractForCast`, `conformance6a_alias_contract`).
7. **Integer literals with an expected `Long`.** `val a: Long = 2147483647 + 1`, `-(1 shl 31)`, `const val h: Long = 60 * 60 * 1000`: a tree of unsuffixed literals is computed in Int and converted
   (`overflowLong`, `longOverflow`, `literalToLongConversion`; the sema types the tree `Long`, the lowering folds the constant, `evalConst` knows the bit operators).
8. **Nothing and Unit as values** (lowering): `"" + break`, `null?.toString()`, `null?.run { return }`, `p.toString()` with `p: Nothing`, `a == b` with Nothing operands, `x ?: 0` with `x: Nothing?`
   (`breakFromOuter`, `innerLoopWithStack`, `popSizes`, `tryFinally1`, `kt5493`, `kt47279`, `divisionByZero`, `eqeq`; `conformance6a_nothing`). A Unit temporary is evaluated for its effect (no local of type Unit).
9. **Enum classes with secondary constructors only** (`noPrimaryConstructor`; the call of `Enum()` is a no-op); nested and inner classes of superclasses are in scope (`instantiateInDerived*`);
   the harness picks the top-level `fun box()` over a member of that name (`properRecapturingInClass`, 6 tests: harness only).

Measurements: hello world is still 23552 bytes; `minic check examples/hello.kt` takes 0.39-0.43 s (0.35-0.43 s before the round, same machine, noisy: the stdlib is unchanged).

Validation of the final sources: `compiler/bootstrap.sh` (stage2 == stage3), `compiler/bootstrap.sh --backend=llvm-api` (stage2 == stage3) with `repl-session.sh` on its stage3, the full `mini test` (exit 0),
`scripts/test-e2e.sh` with the default suites plus `llvm` and `llvm-leak` (1162 passed, 0 failed, 1 skipped), `scripts/strict-corpus.sh --run`
(111 ok) and the bootstrap with the 0.9.0 release as stage0 (`scripts/ci-stage0.sh`) all pass.

Still open (largest first): classes that extend `Number` (about 10 tests: `numberToChar/*`, `equalityForBoxesOf...`, the `Number` runtime does not call the user's `toInt`), builder inference with
postponed type variables (`kt53478`, `kt63733`, `pclaRoot*`, `kt47744`, `specialCallsWithLambdas`), function types as supertypes and `kotlin.Function` (`suspendFunctionAsSupertype*`, `asFunKSmall`,
`implIsNotFunction`), suspend conversion of function values to suspend fun interfaces (`chainedFunSuspendConversion...`, `intersectionTypeTo...`), annotation instances (7 tests),
adapted callable references (`referenceToVarargWithDefaults`, `unitCoercionOverloads`, `conversionCombinations`, `::foo.name` of a value), `do { var y } while (y < 10)` (the condition sees the body's
variables: needs a lowering change), `super` inside the delegation expression of an object literal (`kt11634*`), imported member extensions of objects (`useImportedMember*`), `Unit` fields of data
classes (`unitComponent`), closures in local functions with default values (`kt47073_nested`, `nestedInnerClass`, `kt20651_inlineLambda`), unsigned `sumOf` / vararg conversions (`arrays`, `varargsOfUnsignedTypes`).

## Sixth round (b) (branch claude/conformance-6b): runtime-fail and crash

The round owns the two buckets that are wrong code: the program compiles and runs but gives the wrong result (runtime-fail, 84) or dies from a
signal (crash, 13). Round 6a (compile-error, ice; frontend and stdlib) ran in parallel. One full harness pass of the final tree
(`results.json`, `summary.md`; 0 tests that passed on `origin/main` fail now):

| | after round 5 | round 6 (b) |
|---|---:|---:|
| applicable | 5971 | 5953 |
| **pass** | **5614 (94.02%)** | **5653 (94.96%)** |
| compile-error | 226 | 219 |
| ice | 32 | 31 |
| runtime-fail | 84 | 41 |
| crash | 13 | 7 |
| timeout | 2 | 2 |

42 tests that did not pass before pass now (31 runtime-fail, 4 crash, 6 compile-error, 1 ice); 18 tests left the denominator (see the first item).

**Decision: `-Xbinary=genericSafeCasts=true` is not implemented.** 19 tests set it in `FREE_COMPILER_ARGS`. Kotlin/Native does not check
`x as T` of a type parameter by default (the unchecked cast is the documented behaviour; the flag is an opt-in mode), and a check at every
generic result would cost time and code size in ordinary programs for a mode the default build does not have. The harness now skips them with
the reason `non-default-flag` (`skip_reason` in `run.py`): 18 of the 19 are applicable otherwise and leave the denominator (13 of them failed,
5 passed because they do not depend on the check). Nothing in the compiler changed for them.

Fixed, by cause (each has a test in `compiler/testdata`, the `conf6b_*` programs):

* **Suspend references and conversions** (`conf6b_suspend_ref`, `conf6b_implicit_ref`, `conf6b_sam_suspend`, `conf6b_recv_invoke`,
  `conf6b_prop_recv_invoke`): `val f = ::suspendFun` (no expected type) is a value of the suspend function type, so calling it passes the
  continuation (`simpleSuspendCallableReference`, `longArgs`, `suspendInlineReference`; the sema type is the `FunType`, the lowering still
  reads the recorded `KFunctionN` type; not done when a type argument is still unresolved, `::yield` in a builder lambda); an adapted
  reference written in a class body or where an extension receiver is implicit binds that receiver (`bound/emptyLHS`); a plain function value
  converted to a `fun interface` with a `suspend` method is called without the continuation (`funInterfaceWithSuspendMethod`); `recv.f(a, b)`
  where `f` is a value of type `Recv.(A, B) -> R` typed the literal arguments one parameter off (`1.c(56L, 55)` boxed 55 as a Long:
  `suspendFunctionTypeCall/manyParameters*`, also without suspend), and `recv.p(x)` with a member property `p` of such a type took `recv` as the
  dispatch receiver of the getter (`featureIntersection/funInterface/kt49294`, a crash).
* **Evaluation order and side effects** (`conf6b_misc1`, `conf6b_misc2`, `conf6b_misc3`, `conf6b_misc4`): `getC().x` of a `const val` still
  evaluates `getC()`; the default values of an inline function are evaluated after the arguments (`baz(y = bar())`); a spread `vararg` argument
  with side effects takes part in the source-order spill of named arguments; `foo()::x` evaluates `foo()` once even for a `var` (the getter and
  the setter shared the expression); `++a[i]` of a user `get` reads the element again for its value like K2 (KT-42077; `index_incdec` expects
  `reads=4` now); a `for` over a `var` array evaluates the array once (KT-21354).
* **Equality and members**: `a == b` of two values whose static type is a type parameter (reified `T` substituted by `Double` in an inline
  expansion) uses `equals`, not IEEE 754 (`ieee754/inline`); a data class does not generate `toString` / `hashCode` / `equals` that a
  superclass declares `final`; an interface or class whose only supertype is a function type (`fun interface F : () -> Int`) extends `Any`
  again (its `toString` override was not in the vtable); `x is Unit` (was an internal error); a lambda in a `vararg` of a fun interface is
  converted to it (`funConversionInVararg`); `super<M>@N.y += 200` calls the accessors of `M`.
* **Callable references**: a reference coerced to `Unit` (`captureUnit(V::target)`) is an adapted reference of its own class, so it is not
  equal to the plain one (`equality/coercionToUnit*`); `::A.name` is `<init>`, `E.A.name` is a constant that does not initialise the enum
  (`intrinsicConst/*`); `$`name`` (a backtick identifier) in a string template, and a quoted `_` stays a name (`multiDecl/*/UnderscoreNames`).
* **Data classes**: array properties print their content in the generated `toString` (`dataClasses/toString/arrayParams`, `primitiveArrays`).
* **`infix`**: `a f b` only looks at `infix` functions first, so `5 rem 2` finds an infix extension `Int.rem` although the member `rem` exists
  (`operatorConventions/infixFunctionOverBuiltinMember`).
* **Harness**: `box_package` prefers an unindented `fun box` over a member `fun box()` of an earlier file (the 6 `properRecapturingInClass`
  tests picked the wrong package; all 12 tests of the directory pass now); the verdict of a test is the last line of its output (a
  test that prints on the way, `println(list)`, was a false failure).

Validation of the final sources: `compiler/bootstrap.sh` (stage2 == stage3), `--backend=llvm-api` (stage2 == stage3) with
`compiler/llvm-c/tests/repl-session.sh`, the full `mini test` (79 passed), `scripts/test-e2e.sh` (all default suites, 884 passed; `--suite llvm
--suite llvm-leak`, 442 passed; the leak suites are clean for the new programs), `scripts/strict-corpus.sh --run` and `scripts/ci-stage0.sh`
with the 0.9.0 release as stage0 (stage2 == stage3). The runtime was not touched, the generated code changes only on the paths above (one
retain / release pair for a `for` over a `var` array).

Still open in the two buckets (41 + 7):

* lazy initialisation of top-level properties per file (`initializers/object`, `files/globalNotInited*`, `topLevelInitializtion/failInInitializer*`:
  Kotlin/Native initialises a file on first access, Mini at start), identity of small boxed values (`static_smallIntIdentity`) and of folded
  constants (`stringTrim`), `fieldInitializerOptimization`;
* a `return` in a `finally` of an inline function that overrides a pending non-local return crashes (`returnInFinally`, the result of the
  abandoned return stays unset), reified `T::class` on a primitive in a generic `when` (`k42000_1`, the `if` result is typed `T` in the expansion
  but the branch is unboxed), inline classes in collections (`kt27705/6`, `inlineCollectionOf*`), local classes whose inner classes read the
  captured variables of the *outer local class* (`closureOfInnerLocalClass`, `ownClosureOfInnerLocalClass`: the inner class captures at its own
  creation, K2 reads the outer instance's copy);
* `as?` / `as` to `Unit` of a non-Unit (`asUnit`), `T & Any` casts (`castToDefinitelyNotNullType`), smart casts of a `when` subject after a
  negative branch (`ieee754/smartCast*`), equality of adapted references with defaults and of SAM wrappers of local functions
  (`coercionToUnitWithDefaults`, `funInterface/equality/*`), the special bridges of `Collection` / `Map` implementations over `null`
  (`specialBuiltins/*`, `bridges/special*`, `overrideAbstractSetMethod`: JVM semantics of the stub bridges), `substitutelambdaExtensionReceiverType`
  (builder inference), `releaseIntercepted`, `suspendFunctionIsAs`, `unitSafeCall`, `kt44429`, `kt7017`, the context receiver overloads.

## Sixth round combined (6a + 6b)

The two branches of round 6 merged (`claude/conformance-6b` contains origin/main with 6a): one full harness pass over the combined tree (`results.json`, `summary.md`). The 19 `genericSafeCasts` tests are skipped as `non-default-flag` (6b), so the denominator is 5953 (6a alone: 5971). Compared test by test, nothing that passed in 6a or 6b alone fails in the merge; the only three tests that passed in 6a alone and are not counted now (`basics/kt7972`, `basics/unchecked_cast11`, `bridges/test22`) are the `non-default-flag` skips.

| | 6a alone | 6b alone | 6a + 6b |
|---|---:|---:|---:|
| applicable | 5971 | 5953 | 5953 |
| **pass** | **5711 (95.65%)** | **5653 (94.96%)** | **5745 (96.51%)** |
| compile-error | 130 | 219 | 129 |
| ice | 27 | 31 | 26 |
| runtime-fail | 86 | 41 | 42 |
| crash | 14 | 7 | 8 |
| timeout | 3 | 2 | 3 |

Per category (first two path components; categories with at least one failure in any of the three runs; pass / applicable):

| category | 6a alone | 6b alone | 6a + 6b |
|---|---:|---:|---:|
| box/inlineClasses | 711 / 732 | 709 / 732 | 711 / 732 |
| box/callableReference | 185 / 202 | 187 / 202 | 187 / 202 |
| box/coroutines | 425 / 446 | 431 / 446 | 432 / 446 |
| box/casts | 42 / 52 | 42 / 51 | 42 / 51 |
| box/specialBuiltins | 11 / 20 | 11 / 20 | 11 / 20 |
| box/funInterface | 26 / 36 | 24 / 36 | 29 / 36 |
| box/inference | 39 / 46 | 37 / 46 | 39 / 46 |
| box/primitiveTypes | 84 / 91 | 84 / 91 | 84 / 91 |
| box/annotations | 3 / 9 | 3 / 9 | 3 / 9 |
| box/builderInference | 64 / 70 | 64 / 70 | 64 / 70 |
| box/involvesIrInterpreter | 26 / 33 | 28 / 33 | 28 / 33 |
| box/closures | 102 / 106 | 102 / 106 | 102 / 106 |
| box/controlStructures | 206 / 212 | 204 / 212 | 208 / 212 |
| box/localClasses | 36 / 40 | 36 / 40 | 36 / 40 |
| boxInline/callableReference | 44 / 48 | 43 / 48 | 44 / 48 |
| boxInline/innerClasses | 4 / 8 | 4 / 8 | 4 / 8 |
| box/basics | 26 / 39 | 25 / 28 | 25 / 28 |
| box/enum | 78 / 81 | 77 / 81 | 78 / 81 |
| box/extensionFunctions | 25 / 28 | 22 / 28 | 25 / 28 |
| box/fir | 9 / 12 | 9 / 12 | 9 / 12 |
| box/initializers | 17 / 21 | 18 / 21 | 18 / 21 |
| box/lower | 6 / 9 | 6 / 9 | 6 / 9 |
| box/objects | 105 / 108 | 98 / 108 | 105 / 108 |
| box/topLevelInitializtion | 0 / 3 | 0 / 3 | 0 / 3 |
| box/arrays | 90 / 92 | 89 / 92 | 90 / 92 |
| box/bridges | 84 / 91 | 83 / 85 | 83 / 85 |
| box/dataObjects | 1 / 3 | 1 / 3 | 1 / 3 |
| box/ieee754 | 35 / 38 | 36 / 38 | 36 / 38 |
| box/inline | 45 / 49 | 46 / 49 | 47 / 49 |
| box/ranges | 817 / 819 | 817 / 819 | 817 / 819 |
| box/sam | 6 / 8 | 6 / 8 | 6 / 8 |
| box/secondaryConstructors | 28 / 30 | 28 / 30 | 28 / 30 |
| box/strings | 29 / 31 | 29 / 31 | 29 / 31 |
| box/unsignedTypes | 38 / 40 | 38 / 40 | 38 / 40 |
| box/volatile | 0 / 2 | 0 / 2 | 0 / 2 |
| boxInline/annotations | 0 / 2 | 0 / 2 | 0 / 2 |
| boxInline/complex | 6 / 8 | 6 / 8 | 6 / 8 |
| boxInline/nonLocalReturns | 81 / 83 | 81 / 83 | 81 / 83 |
| boxInline/private | 10 / 12 | 10 / 12 | 10 / 12 |
| box/binaryOp | 19 / 20 | 17 / 20 | 19 / 20 |
| box/builtinStubMethods | 1 / 2 | 1 / 2 | 1 / 2 |
| box/collections | 4 / 5 | 4 / 5 | 4 / 5 |
| box/controlflow | 18 / 19 | 18 / 19 | 18 / 19 |
| box/dataClasses | 46 / 50 | 48 / 50 | 49 / 50 |
| box/defaultArguments | 75 / 77 | 67 / 77 | 76 / 77 |
| box/delegatedProperty | 46 / 48 | 47 / 48 | 47 / 48 |
| box/diagnostics | 50 / 51 | 41 / 51 | 50 / 51 |
| box/fieldRename | 1 / 2 | 1 / 2 | 1 / 2 |
| box/function | 35 / 36 | 32 / 36 | 35 / 36 |
| box/innerNested | 48 / 49 | 46 / 49 | 48 / 49 |
| box/nothingValue | 1 / 2 | 1 / 2 | 1 / 2 |
| box/objectIntrinsics | 0 / 1 | 0 / 1 | 0 / 1 |
| box/operatorConventions | 31 / 33 | 31 / 33 | 32 / 33 |
| box/package | 9 / 10 | 9 / 10 | 9 / 10 |
| box/properties | 86 / 88 | 86 / 88 | 87 / 88 |
| box/regressions | 62 / 63 | 59 / 63 | 62 / 63 |
| box/statics | 7 / 8 | 7 / 8 | 7 / 8 |
| box/stringTrim | 0 / 1 | 0 / 1 | 0 / 1 |
| box/super | 33 / 35 | 34 / 35 | 34 / 35 |
| box/suspendConversion | 2 / 3 | 2 / 3 | 2 / 3 |
| box/vararg | 15 / 17 | 15 / 17 | 16 / 17 |
| box/when | 91 / 92 | 90 / 92 | 91 / 92 |
| boxInline/anonymousObject | 93 / 94 | 91 / 94 | 93 / 94 |
| boxInline/defaultValues | 70 / 71 | 70 / 71 | 70 / 71 |
| boxInline/noInline | 6 / 7 | 6 / 7 | 6 / 7 |
| boxInline/reified | 23 / 24 | 23 / 24 | 23 / 24 |
| boxInline/suspend | 69 / 70 | 69 / 70 | 69 / 70 |
| box/argumentOrder | 15 / 16 | 16 / 16 | 16 / 16 |
| box/boxingOptimization | 39 / 39 | 38 / 39 | 39 / 39 |
| box/classes | 115 / 115 | 113 / 115 | 115 / 115 |
| box/constants | 12 / 12 | 11 / 12 | 12 / 12 |
| box/contracts | 16 / 16 | 14 / 16 | 16 / 16 |
| box/delegation | 12 / 12 | 11 / 12 | 12 / 12 |
| box/elvis | 9 / 9 | 8 / 9 | 9 / 9 |
| box/functions | 98 / 98 | 91 / 98 | 98 / 98 |
| box/increment | 25 / 26 | 24 / 26 | 26 / 26 |
| box/mixedNamedPosition | 4 / 4 | 2 / 4 | 4 / 4 |
| box/multiDecl | 62 / 64 | 64 / 64 | 64 / 64 |
| box/propertyCallableReference | 6 / 7 | 7 / 7 | 7 / 7 |
| box/smartCasts | 29 / 29 | 27 / 29 | 29 / 29 |
| box/traits | 33 / 33 | 32 / 33 | 33 / 33 |
| box/typealias | 21 / 21 | 16 / 21 | 21 / 21 |
| box/unaryOp | 4 / 4 | 3 / 4 | 4 / 4 |
| box/unit | 13 / 13 | 12 / 13 | 13 / 13 |
| boxInline/argumentOrder | 10 / 12 | 12 / 12 | 12 / 12 |

## Seventh round (b) (branch claude/conformance-7b): runtime-fail and crash

Starting point: main after 6a + 6b, 5745 / 5953 (runtime-fail 42, crash 8). Regression tests: `compiler/testdata/conf7b_*.kt`.

Fixed by cause:

* sema: a lambda whose result is `Nothing` does not fix the result type variable (`doCall({ return x }, { y })`, non-local `return` through `finally`).
* `Number` members on a primitive-substituted reified receiver box it first (`k42000_1`); casts `as` / `as?` `Unit`, `as (T & Any)`;
  a `when` subject smart-cast to `Float` / `Double` by a failed branch.
* special bridges (`contains`, `indexOf`, `remove`, `get` ...): an argument the override cannot take answers `false` / `-1` / `null`.
* inner classes of unboxed value classes keep the `$outer` field unboxed (`kt27705`, `kt27706`); inner classes of local classes read the captured variables of the outer instance.
* file-level initialisers: only the file of `main` runs before it; a function of a file whose initialisers run program code starts it.
  Field initializers that only store the default value are dropped.
* fake-override inline members with reified type parameters are expanded from the real function; the delegate of an object literal is checked in the scope around it;
  `@LowPriorityInOverloadResolution` candidates only win alone; a callable reference converted to a fun interface shares the equals class of the reference.
* a member `fun f(s: String)` next to an extension `fun String.f()` (same erased signature) get distinct symbols (`captureExtensionReceiver`, was a crash).
* harness: a test that declares `fun main()` as a helper is renamed so that it does not become the entry.

Result: before this round (6a+6b main) 5745, 7b alone 5778 (runtime-fail 42 -> 21, crash 8 -> 0); merged with 7a (main 5808): **5838 of 5953 (98.07%)** (compile-error 74, ice 15, runtime-fail 23, crash 1, timeout 2), compared test by test with main: no test that passed there fails.

Still open (runtime-fail): `failInInitializer1/2` (exception inside a lazily started file initialiser must become `ExceptionInInitializerError`), boxed identity of small ints / constant
strings (`static_smallIntIdentity`, `stringTrim`), equality of adapted references with defaults, `nothingValueException`, `inheritFromAbstractMutableListInt`,
suspend `releaseIntercepted` / `unitSafeCall` / `suspendFunctionIsAs`, context-receiver overloads, `functionsDifferInTypeParameterBounds2`, `ieee754` smart casts with numeric promotion.

## Seventh round (a) (branch claude/conformance-7a)

Targets: the `compile-error` / `ice` buckets left by round 6a (frontend and lowering fixes). `results.json` / `summary.md` come from a full harness run on the tree merged with origin/main (6a + 6b + stub cache), followed by a `--retest` of everything that did not pass, after fixing the two regressions the full run found (a backticked `` `_` `` destructuring entry is a variable, the plain `_` is not: `DestructEntry.quoted`). Compared test by test with the main results: no test that passed there fails now, 63 more pass.

| | after round 6 (a + b) | round 7 (a) |
|---|---:|---:|
| **pass (applicable 5953)** | **5745 (96.51%)** | **5808 (97.56%)** |
| compile-error | 129 | 74 |
| ice | 26 | 15 |
| runtime-fail | 42 | 44 |
| crash | 8 | 9 |
| timeout | 3 | 3 |

Fixes, grouped by cause (tests `compiler/testdata/conformance7a_*`):

1. **Classes extending `Number`**, **function types and `kotlin.Function` as supertypes** (also suspend; suspend conversion of function values to suspend fun interfaces), **annotation instances**, data objects, companion property lookup, enum entries implementing interface properties, inline members of inner classes (`$outer` chain), Unit and Nothing typed parameters / fields (`conformance7a_number`, `_function_types`, `_misc`).
2. **Scoping**: `do { var y } while (y < 10)` sees the body's variables, local-class contexts, `super` in the delegation of an object literal (`conformance7a_scoping`).
3. **Members an object inherits, imported by name** (`import Reg.fromInterface`, `import Reg.genericFromSuper`, extension properties and functions from a superclass or generic interface of the object): the object is the dispatch receiver and the type arguments of the supertype are substituted (`Sema.importedVia`, `conformance7a_imported_members`; `useImportedMember*`).
4. **Unsigned varargs**: `vararg us: UInt` is a `UIntArray` (a value class over `IntArray`), also with spread and the empty vararg; `UInt` / `Int` retyping in `convertTo` (`conformance7a_imported_members`).
5. **Defaults of `vararg` parameters** are arrays (`vararg arr: Int = intArrayOf(1, 2)`: `function/defaultsWithVarArg2`, `varargWithDefaultValue`, `varArgNoInline`), and ``val (`_`, c) = ...`` (`conformance7a_vararg_defaults`, `multiDecl/UnderscoreNames`).

Per category with a change (pass / applicable, 6a + 6b vs this round):

| category | 6a + 6b | 7a |
|---|---:|---:|
| box/annotations | 3 / 9 | 7 / 9 |
| box/builderInference | 64 / 70 | 65 / 70 |
| box/callableReference | 187 / 202 | 192 / 202 |
| box/casts | 42 / 51 | 43 / 51 |
| box/closures | 102 / 106 | 105 / 106 |
| box/controlStructures | 208 / 212 | 210 / 212 |
| box/controlflow | 18 / 19 | 19 / 19 |
| box/coroutines | 432 / 446 | 437 / 446 |
| box/dataClasses | 49 / 50 | 50 / 50 |
| box/dataObjects | 1 / 3 | 3 / 3 |
| box/defaultArguments | 76 / 77 | 77 / 77 |
| box/delegatedProperty | 47 / 48 | 48 / 48 |
| box/enum | 78 / 81 | 79 / 81 |
| box/fieldRename | 1 / 2 | 2 / 2 |
| box/funInterface | 29 / 36 | 30 / 36 |
| box/function | 35 / 36 | 36 / 36 |
| box/inlineClasses | 711 / 732 | 715 / 732 |
| box/innerNested | 48 / 49 | 49 / 49 |
| box/involvesIrInterpreter | 28 / 33 | 29 / 33 |
| box/localClasses | 36 / 40 | 37 / 40 |
| box/lower | 6 / 9 | 7 / 9 |
| box/objects | 105 / 108 | 108 / 108 |
| box/primitiveTypes | 84 / 91 | 91 / 91 |
| box/properties | 87 / 88 | 88 / 88 |
| box/specialBuiltins | 11 / 20 | 12 / 20 |
| box/statics | 7 / 8 | 8 / 8 |
| box/strings | 29 / 31 | 30 / 31 |
| box/super | 34 / 35 | 35 / 35 |
| box/suspendConversion | 2 / 3 | 3 / 3 |
| box/unsignedTypes | 38 / 40 | 39 / 40 |
| box/vararg | 16 / 17 | 17 / 17 |
| boxInline/annotations | 0 / 2 | 2 / 2 |
| boxInline/defaultValues | 70 / 71 | 71 / 71 |
| boxInline/innerClasses | 4 / 8 | 7 / 8 |

Remaining: builder inference (`pclaRoot*`, `kt53478`, `kt63733`, `kt51988`), adapted references with `vararg` and defaults, member references to companion members, the inline-class ICEs (`inlineClasses/*`), `Nothing`-typed parameters in bridges, `signedToUnsignedConversions` (`@ImplicitIntegerCoercion`), `objectIntrinsics/objects` (`Enum` as a value), the `MultiDollarInterpolation` lexer cases and a few linking failures.

## Eighth round (a) (branch claude/conformance-8a)

Starting point: 5838 / 5953 (74 compile-error, 15 ice). Result: 5852 / 5953 (98.30 %), 63 compile-error, 15 ice, no test that passed before fails now.
What remains is a long tail of single tests (about 90 different causes), see `summary.md`.

| fix | tests |
|---|---:|
| `a[i] = { ... }` / `a[i] = label@{ ... }`: the lambda is analysed against the value parameter of `operator set` (`resolveOperator` takes the postponed argument) | 1 |
| `kotlin.test`: `assertFailsWith(KClass, message, block)` (compares the class of the exception) | 2 |
| special bridges (`contains`, `indexOf`, `containsKey`, ...) of an override that takes `Nothing` answer "not found" instead of throwing a `ClassCastException` | 3 |
| a value of a class that is a function type (`object : () -> Unit`) where a fun interface is expected: SAM conversion (`TypeOps.sub`, `Ast2IrCalls`) | 1 |
| `x?.f { g(x) }`: the arguments of a safe call see the receiver as not null (`dotExpr`) | 3 (inline classes named `UInt`, builder inference) |
| constants of the enclosing object in annotation arguments of a function's parameters (`Declarations.checkFun` sets the function scope) | 1 |
| harness: `<!EVALUATED{IR}("...")!>` markup of the IR interpreter tests is removed like the diagnostic markup | 3 |

Regression test: `compiler/testdata/conformance8a_misc.kt`.

## Eighth round (b) (branch claude/conformance-8b)

Starting point: 5853 / 5953 (63 compile-error, 14 ice, 20 runtime-fail, 1 crash, 2 timeout). Result: 5868 / 5953 (98.57 %), 56 compile-error, 7 ice, 19 runtime-fail, 1 crash, 2 timeout (15 tests fixed), no test that passed before fails now.
Every remaining failure is a single test with its own cause (see `summary.md`).

| fix | tests |
|---|---:|
| `while (break)` / `do { } while (break)` and `throw ... as T`: an operand that never completes (a `Nothing` placeholder) is not a C value (`loopCondition`, `typeOperator`) | 2 |
| `Array(n, return x)`: an initializer of type `Nothing` ends the function before the array is made | 1 |
| `recv.p(args)` where `p` is a member property of function type with receiver (`with(h) { 5.foo() }`): `CallInfo.invokePropRef` names the implicit receiver `p` is read from | 1 |
| a Unit value in front of an argument that needs statements (`eq(Unit, f())`) no longer gets a (typeless) temporary (`Ir2Mir.operands`) | 1 |
| expected types of nested generic calls: `val l: List<Box<Any?>> = listOf(Box(0))`, `arrayListOf(Box(0))` (the expected type of the call bounds the type parameters of its result before the arguments are analysed, also for overloads that agree), `a[i] = Box(2)` (the value of an indexed assignment is analysed against the parameter of `set`) | 2 |
| `A::member` where `member` belongs to the companion object of `A` (members, extensions and extension properties; also `Companion::ext`): bound to the companion, after the members / extensions of `A` | 1 |
| two local classes (or enum entry bodies of two enums) with the same name in one function got the same C name; the second one now gets a counter | 1 |
| a star-imported object / companion `C` wins over a private class `C` of the file when `C` is used as an expression | 1 |
| `::Z` of an unboxed value class (there is no constructor function) | 1 |
| an extension property named like a member property is no conflicting declaration | 1 |
| `pkg.property`: a top-level property read through the package name | 1 |
| a lambda for a fun interface that is the lower bound of an `in`-projection (`Container<in Element>.add { }`) | 1 |
| lexer: `$"..."` (one-dollar prefix of the multi-dollar interpolation) | 1 |

Regression test: `compiler/testdata/conformance8b_misc.kt`.

What was looked at and left: `.name` of a callable reference held in a value (`val r = C::f; r.name`, 4 tests: function reference closures carry no name), overload resolution that depends on the result type of a lambda
(`transform { ... }` against a local variable named `transform`, 3 tests), `Unit` as a null in generic positions (`res!!` for `T = Unit`, `unchecked_cast10`), fun interfaces whose abstract method is `toString`,
context-receiver overloads (`contextAndNoContextOverloads`), boxed identity of small values and folded constants, reified type parameters captured by an object in an inline function (`kt7017`), `KotlinNothingValueException`,
lazily started file initialisers (`failInInitializer1/2`).

## Ninth round (a) (branch claude/conformance-9a)

Starting point: 5868 / 5953 (56 compile-error, 7 ice, 19 runtime-fail, 1 crash, 2 timeout). Result: 5898 / 5953 (99.08 %), 35 compile-error, 5 ice, 12 runtime-fail, 1 crash, 2 timeout (30 tests fixed), compared per test with the baseline: no test that passed before fails now.

| fix | tests |
|---|---:|
| a lambda is no argument for a rigid type parameter (`shapeOk`): a local `transform: (T) -> R` no longer takes `transform { ... }` away from the extension `transform` | 3 |
| MIR `Cleanup`: an alias chain of more than 50 copies (a constructor with 55+ arguments that all need statements) was resolved only in part, the declarations went and uses stayed | (kt51530) |
| `releaseInterceptedContinuation`: a state machine keeps the continuation the interceptor made of it and hands it back when it completes | 1 |
| the Unit object, not null, in `Unit?` / generic positions: `a?.f()` for a Unit `f` (also after a suspension), Unit-returning lambdas and suspend functions return the object, a generic call with `T = Unit` | 5 |
| `x is SuspendFunction0<*>`: function objects of suspend lambdas, references and classes with a suspend function supertype carry a marker interface | 1 |
| `.name` of a function reference held in a value (closure classes of references are named after the target; stdlib `functionObjectName`) | 3 |
| `with(a) { call(::ext) }`: an extension of an implicit receiver is a bound reference in the arity check; `Obj::Nested` is a constructor reference; `E::name` / `Enum<*>` | 3 |
| an inline function expanded inside its own lambda argument (`consumer { consumer(f()) }`) no longer finds the outer lambda for its parameter | 1 |
| `fun interface F { override fun toString(): String }` and `fun interface F { fun String.foo() }` | 2 |
| overloads `foo()` / `context(Scope) foo()` (signature key, tie-break by contexts in scope, implicit receiver as context argument) | 1 |
| overloads and members that differ in type parameter bounds; a member of two bounds takes the more specific result type | 3 |
| `break` / `continue` through an anonymous function passed to an inline function | 1 |
| declared lambda parameter types bound the type variables (`foldl(0) { e: Int, acc: Long -> }`) | 1 |
| a local `val s` over a parameter `s` of function type: `s()` calls the parameter | 1 |
| stdlib: `ArrayList` iterators throw `ConcurrentModificationException`, `AbstractMutableList.remove` goes through `iterator()`, `sumOf` for unsigned arrays | 3 |
| harness: `suspend fun main` and `::main` of a test are renamed, `EmptyContinuation()` has a default context (as upstream) | 1 |
| `KotlinNothingValueException` (runtime class); a generic call typed `Nothing` throws it | 0 (the test needs the inference of `Nothing`) |

Regression tests: `compiler/testdata/conformance9a_misc.kt`, `conformance9a_coroutines.kt`, `conformance9a_more.kt`.

What was looked at and left: `failInInitializer1/2` (needs `ExceptionInInitializerError` and a failure state for file facades: `ObjectGet` would have to count as throwing in both emitters and `mk_object_init` remember the failure), `kt7017` (reified `T` captured by an object in an inline function: local classes are lowered once, not per instantiation), `nothingValueException` (inference of `Nothing` from `in`/`out` projections), `override val cause` of a Throwable subclass, builder inference with postponed type variables, adapted references with `vararg` passed as an array, boxed identity of small values and strings.

## Tenth round (a) (branch claude/conformance-10a)

Starting point: 5898 / 5953 (35 compile-error, 5 ice, 12 runtime-fail, 1 crash, 2 timeout). Result: 5915 / 5953 (99.36 %) on the tree merged with main, compared per test with the baseline: no regression, 17 tests fixed (a further timeout, `coroutines/kt51530`, passes alone and timed out under machine load).

| fix | tests |
|---|---:|
| builder inference: an unconstrained type variable that is a type argument of a lambda's receiver / parameter type (`MutableList<E>.() -> Unit`, `Consumer<T>`) stays open while the body is checked (`Sema.openVars`); calls in the body add bounds to it (taken back after a failed or unchosen overload attempt, `Attempt.openDelta`), it is fixed with the call and the records made in the body are rewritten (`zonkRecords`); only inside another builder lambda for a call that is an argument | 7 |
| ExceptionInInitializerError / NoClassDefFoundError: `ObjectGet` counts as throwing when the initialiser may; `mk_object_init` wraps an Exception (an Error passes), remembers the failed slot (later access raises NoClassDefFoundError), the fast path is unchanged | 2 |
| joined function types (`if (c) { { 1 } } else { { 1f } }`), `Unit` is no bound for the last statement of a builder lambda | 1 |
| nested classifiers visible in the header of the primary constructor; enum entry arguments are no instance scope | 1 |
| `Enum` as a value (companion), adapted references that pass a `vararg` as an array, reference without expected type picks an overload, `Foo<String>::Inner` outer type arguments | 3 |
| a receiver of function type of an inline function is no inlinable lambda (`inline fun (() -> String).f()`), `invoke()` on an implicit function-typed receiver | 1 |
| virtual `Throwable.cause` (vtable slot 4) | 1 |
| `in T` against an `out` projection infers `Nothing` | 1 |

Regression tests: `compiler/testdata/conformance10a_builder.kt`, `conformance10a_init.kt`, `conformance10a_misc.kt` (`uninit_field_npe` now expects the ExceptionInInitializerError of a failing object initialiser).

Left: generic inner classes lose the outer type arguments (`E<IC>.Inner().foo()`, `innerGenericConstuctor`, `kt27706Generic`), `kt7017` (local classes are lowered once, not per inline expansion), a lambda with an inlined call in an enum entry argument crashes the compiler (`kt20651_inlineLambda`), `eqNullableToPrimitiveWithSideEffects`, boxed small-value identity, the remaining ICEs and one-off inference cases listed in `tools/conformance/results/summary.md`.

## Eleventh round (a) (branch claude/conformance-11a)

Starting point: 5915 / 5953 (25 compile-error, 2 ice, 8 runtime-fail, 1 crash, 2 timeout, plus the 10a left-over list). Result: 5933 / 5953 (99.66 %) on the tree merged with main, compared per test with the 10a results: no regression, 18 tests fixed (one of them, `coroutines/kt51530`, is the load-dependent timeout of the earlier run). 20 failures remain.

| fix | tests |
|---|---:|
| generic inner classes keep the type arguments of the outer class: `ClassType.outer` (set by `Foo<String>.Inner<B>` types and by `outer.Inner(...)` / `with(outer) { Inner() }` constructor calls, substituted and zonked with the type; `substOf` of such a type includes the outer parameters; an `Inner` without `outer` is substituted through its implicit outer `Outer<T>`) | 4 |
| `a?.x == f()` with a primitive right operand: the comparison happens inside the safe call, `f()` is not evaluated when `a` is null (K2) | 1 |
| an inline call (`run { }`) in an enum entry argument: `return` of the expanded body had no enclosing function | 1 |
| `val p get() = field` (no initialiser, no type): `field` is an ordinary name (K2), not the backing field | 1 |
| unqualified `super.size` of `class B : Collection<String>, A0`: the implementing supertype, not the abstract one | 1 |
| `==` of smart-cast numbers of different types with one floating (`x is Double? && y is Int?`): both are promoted to Double, null stays null | 1 |
| an inline member with a lambda parameter inherited through a fake override (protected inline member of a private base class) is expanded | 1 |
| `is` / `as` against `NativePtr` / `COpaquePointer` (C pointers are no objects) | 1 |
| SAM conversion of a function-typed value inside inference (`nullsFirst(f)` with `f: (C, C) -> Int`), `f::invoke` of a function-typed value | 2 |
| `String?::prop` keeps the nullable receiver (parser flag `CallableRefExpr.nullableRecv`, in the stub cache) | 1 |
| `::KSupplier` written twice (several files) is one closure class: equal references | 1 |
| `Map.containsValue(x: Any)` / `containsKey` extensions with the receiver's type arguments widened (stdlib) | 2 |

Regression tests: `compiler/testdata/conformance11a_inner.kt`, `conformance11a_misc.kt`.

Left: `kt7017` (local classes of an inline function are lowered once, not per expansion of a reified type argument; needs a deep copy of `IrClass`), `kt42042` (old-inference leniency for violated bounds), `kt51247` / `conversionCombinations` / `safeCallRangeTo` (context-receiver references, equality of adapted references, literal-typed `::byteRange`), `lateinitInlineClasses`, `inlineSuspendFinally`, `intersectionTypeToFunInterfaceConversion`, `anonymousObjectInExtension`, `enumEntriesNameClashes`, `nestedCallsWithGenericToInt`, `genericCallableReferenceWithReifiedTypeParam`, `objectToPrimitiveWithAssertion`, the atomics / worker / implicit-unsigned tests, boxed small-value identity.

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

