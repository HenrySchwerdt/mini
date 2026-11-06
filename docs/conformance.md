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

