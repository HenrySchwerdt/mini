# Standard library from source (v1-S)

Status: milestone v1-S. Related: `docs/plan-kotlin.md` (sections 6 and 10b), `compiler/README.md`,
code in `compiler/jvm-bridge` (`FrontendDriver.kt`, `Stdlib.kt`, `Reachability.kt`, `Ir2MirIntrinsics.kt`),
sources in `stdlib/`, tests in `stdlib-test/` and `compiler/jvm-bridge/src/test/kotlin/StdlibJvmTest.kt`.

## 1. Decision

`minic build a.kt [b.kt ...]` sends the user files **together with all sources from `stdlib/`**
through the K2 frontend run. There is **no** JVM kotlin-stdlib JAR and **no JDK** involved. As a result every
function of the standard library (including `inline` functions like `map`/`filter`) has an IR body that `Ir2Mir` can translate to
MIR. Before translation, `Reachability` cuts off everything that is not reachable from the user code,
so the generated C stays small (`println("x")` → 66 lines of C, a `StringBuilder` program → ~220 lines).

Configuration (`FrontendDriver.runFrontend`):

| Setting | Value | Reason |
|---|---|---|
| Compiler | `K2JVMCompiler` as before, plugin `IrGenerationExtension` after fir2ir | IR after fir2ir, before the JVM lowerings |
| `-no-stdlib -no-reflect` | on | no kotlin-stdlib JAR; `kotlin.*` comes from our sources |
| `-no-jdk` (`args.noJdk`) | on | no `java.lang.*`; `kotlin.collections.List` stays an ordinary Kotlin interface (no JVM mapping to `java.util.List`) |
| `-Xallow-kotlin-package` | on | sources may declare `package kotlin...` |
| `-Xstdlib-compilation` | on | as when building the real stdlib; not strictly required for the frontend and fir2ir (an attempt without the flag: same result, only the JVM lowerings crash, which we skip anyway), kept as a safeguard |
| Input | user files + `stdlib/**/*.kt` | `--stdlib <dir>` overrides, `--no-stdlib` switches back to the old behavior (JVM JAR) |
| after the consumer | `module.files.clear()` | otherwise the JVM backend phase would try to "lower" our sources and crash (e.g. `kotlin/arrayOfNulls`) |

Per build this costs one K2 run over about 5700 lines of stdlib source (about 3 s on top of startup).
A cache stage (FIR/klib of the stdlib) is planned as an optimization but is not part of v1-S.

## 2. Spike: what worked, what did not

All attempts with kotlin-compiler-embeddable 2.4.21, tool: `K2JVMCompiler` on the command line, scripts in the scratch directory.

* **`-no-stdlib` alone:** `Unresolved reference 'Boolean'/'Int'/'String'`, `missing stdlib class`. On the JVM the builtins come
  from `.kotlin_builtins` files in the stdlib JAR; without the JAR they do not exist.
* **Own `package kotlin` without `-Xallow-kotlin-package`:** error. With the flag and an own `open class Any` in the source,
  K2 resolves `kotlin.Any` from the source. **Builtins from source work.**
* **Bodiless declarations** (`fun plus(other: Int): Int` in `class Int`): only work with `@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY",
  "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "NON_MEMBER_FUNCTION_NO_BODY")`; K2 honors error suppression in source files. For that there must be
  `kotlin.Suppress` with `@Target(.. FILE, EXPRESSION ..)` and `@Retention(SOURCE)`, so also `kotlin.annotation.Target/Retention/AnnotationTarget`.
* **Original sources of stdlib 2.4.21** (`kotlin-stdlib-2.4.21-sources.jar`, `commonMain`/`jvmMain`): contain the builtins as `expect`/`actual`
  with annotations (`@JvmBuiltin`, `@SuppressBytecodeGeneration`); compiled as single files they depend on `-Xmulti-platform`.
  We wrote **our own, non-expect builtins** instead (derived from `jvmMain/*.kt`, primitives via a generator).
* **`expect`/`actual` + `-Xcommon-sources`:** without `-Xmulti-platform` the error is "can be used only in multiplatform projects"; with the flag
  "'expect' and corresponding 'actual' are declared in the same module". It only works with real fragments
  (`-Xfragments=common,jvm -Xfragment-sources=… -Xfragment-refines=jvm:common`), and then **every** source file must be
  assigned to a fragment. Possible, but without benefit: we have exactly one platform. Not chosen.
* **Metadata/common pipeline** (`K2MetadataCompiler`): not pursued further because it offers no fir2ir/IR run (and therefore no `IrGenerationExtension`);
  the JVM pipeline without a JDK delivers the same IR with the API we already use.
* **Fir2Ir requires a fixed set of classes:** after the FIR run, fir2ir aborts with `IllegalStateException: Class kotlin/X is not found` until *all*
  classes exist that `IrBuiltInsOverSymbolFinder`/`Fir2IrBuiltinSymbolsContainer` load via `StandardClassIds`. The set we have to
  provide: `Any, Nothing, Unit, Boolean, Char, Byte/Short/Int/Long/Float/Double, Number, String, CharSequence, Comparable, Enum, Array,
  {Boolean,Byte,Char,Double,Float,Int,Long,Short}Array, {…}Iterator (kotlin.collections), Iterable, Collection, List, Set, Map, Map.Entry,
  Iterator, Throwable, Annotation, DeprecationLevel, KCallable/KClass/KFunction/KProperty*` (see `stdlib/kotlin/*.kt`).
* **`enum class`** against an `Enum(name: String, ordinal: Int)`: K2 reports `no value passed for parameter 'name'`. Solution: `protected constructor()`
  (just one declaration; `name`/`ordinal` are special cases for Ir2Mir anyway).
* **JVM mapping (`List` ↔ `java.util.List`, `StringBuilder` = typealias to `java.lang.StringBuilder`):** arises solely from the JDK
  on the classpath. With `-no-jdk` and our own sources it does not exist; our own `kotlin.collections.List`, `kotlin.collections.ArrayList`,
  `kotlin.text.StringBuilder` are ordinary classes. The small consequence: `java.lang.Thread` (used unqualified by `mini.memory.Spawn`)
  does not exist and is replaced by a placeholder `stdlib/java/lang/Thread.kt`.
* **Default resolution in JVM tests** (see 7): types like `List` from the default imports win over star imports; only explicit
  single imports win. Affects only the JVM test, not the compiler.

Reason for the decision in one sentence: **own sources, JVM pipeline without a JDK and without the stdlib JAR, one plugin, no additional compiler hack** —
the smallest change to the existing setup, and `-no-jdk` prevents exactly the mappings that would get in our way.

## 3. Layout of `stdlib/`

```
kotlin/                 Builtins (marked Builtin) and core: Any, Int, String, Enum, Array ..., Throwable/Exceptions,
                        Preconditions (require/check/error/TODO), Standard (let/run/apply/also/with/takeIf/repeat),
                        Tuples (Pair/Triple/to), Lazy, Comparator, Annotations, Platform (AutoCloseable/use)
kotlin/collections/     Collections.kt (interfaces, from the stdlib), AbstractCollections, ArrayList, HashMap/LinkedHashMap,
                        HashSet/LinkedHashSet, Iterables (map/filter/...), Builders (listOf/mapOf/..., Map functions)
kotlin/text/            Strings, StringBuilder, Char, StringNumberConversions
kotlin/ranges, math, comparisons, io, jvm, reflect, annotation, internal
mini/internal/          CSymbol.kt (annotation), Intrinsics.kt (the entire native interface)
mini/memory/            from the memory track (unchanged)
java/lang/Thread.kt     placeholder for mini.memory.Spawn
```

### Builtins

Files with `@file:kotlin.internal.Builtin` contain the declarations the compiler itself knows (`kotlin.Int`, `String`, `Array`, ranges ...).
They have no bodies. `minic` analyzes them along with everything else but **never converts them to C**: `Reachability.prune` removes these files before `Ir2Mir`;
calls like `Int.plus`, `String.length`, `println` are still recognized by `Ir2Mir` by name (as before with the JAR stdlib).
All other sources are ordinary Kotlin code and are translated as far as they are reachable.

`kotlin.io.println/print` are `external` without `@CSymbol`; `Ir2Mir` knows them as special cases (`mk_println_*`).

### Reachability (`Reachability.kt`)

* Roots: **all** declarations of the user files (not just `main`, so that error messages for unreachable, unsupported user code
  stay the same).
* Followed: calls, constructor calls, object/enum references, type operators, callable references and every type that appears in declarations and
  expressions. A reached class keeps all constructors, initializers, properties and all *dispatch-capable* functions
  (open, `override`, abstract, interface members). **Final, non-overriding methods are only kept if they are called.**
* Top-level functions are kept individually.
* Calls to members of builtin types (e.g. `Iterator.hasNext` in `for` loops) do not count.
* The function is purely bridge-side (works on Kotlin IR). A variant at MIR level would be possible but would already have paid for
  converting all stdlib functions.

## 4. Native primitives ("intrinsics"): convention

Everything native goes through **`mini.internal.CSymbol("mk_name")`** (`stdlib/mini/internal/CSymbol.kt`):

```kotlin
@CSymbol("mk_string_substring")
public external fun stringSubstring(s: String, start: Int, end: Int): String
```

Rules (apply to top-level functions, member functions, property getters):

1. The call becomes an **ordinary C call** `mk_name(receiver?, arg1, ...)` (`MirExpr.RuntimeCall`, see `Ir2MirIntrinsics.kt`;
   hook in `Ir2MirCalls.kt: call()`).
2. Type mapping: `Int/Long/Boolean/Double/Char` → `MkInt/MkLong/MkBoolean/MkDouble/MkChar`; `String` → `const MkString *`; every other
   reference type (including `Any`) → `MkObject *`. Nullable references are the same pointers (`NULL`).
3. **Arguments are borrowed**; a **reference result is owned (+1)** and must be returned retained (`mk_ref_array_get` retains).
4. Result type `Nothing`: the C function is `_Noreturn`; the call is emitted as a statement.
5. The declaration is `external` (no body) or lives in a builtin file. Top-level `external`/`@CSymbol` functions are removed from the files by
   `Reachability`; the call is recognized by the callee's symbol.
6. Every symbol is listed in `runtime/include/minirt.h` in the block `/* ---- v1-S ---- */` (implementation `runtime/src/v1s.c`,
   test `runtime/tests/test_v1s.c`). `StdlibJvmTest.intrinsicsAreInSync` checks that every `@CSymbol` is in the header and has the same
   signature in the JVM implementation (`stdlib-test/jvm/mini/internal/Intrinsics.kt`).

The entire native surface of v1-S (except builtin members of `String` and the console):

| Group | Functions (Kotlin → C) |
|---|---|
| String | `String.get` → `mk_string_char_at`, `String.compareTo` → `mk_string_compare`, `String.length` → `mk_string_length`, `stringSubstring`, `stringParseDouble`, `stringIsDouble` |
| StringBuilder | `sbNew`, `sbAppendString`, `sbAppendChar`, `sbLength`, `sbCharAt`, `sbSetCharAt`, `sbSetLength`, `sbToString` (the handle is an `Any`) |
| Reference arrays | `refArrayNew`, `refArrayGet`, `refArraySet`, `refArrayLength` (storage of `ArrayList`, `HashMap`) |
| Misc | `typeName` (`MkTypeInfo.name`), `identityHashCode` |
| Errors | `throwIllegalArgument`, `throwIllegalState`, `throwIndexOutOfBounds`, `throwNoSuchElement`, `throwNumberFormat`, `throwUnsupportedOperation`, `throwNotImplemented` |

Design decision: strings, `StringBuilder`, `ArrayList` etc. need **no** compiler knowledge about arrays or generics:
handles are opaque `Any` objects of the runtime.

### Errors (`throw`): the only seam

The backend did not know exceptions yet. **Every** `throw` site of the stdlib is therefore written as a call to `mini.internal.throwXxx(message)`.
Natively, `mk_throw_*` aborts with `fatal: <Kind>: <Message>` on stderr (like `mk_panic`); in the JVM test the functions throw real exceptions.
As soon as the backend supports `throw`/`try`, *only the body* in `mini/internal/Intrinsics.kt` becomes `throw IllegalArgumentException(message)` etc.
The exception classes themselves (`kotlin/Throwable.kt`) already exist. Note: `Throwable.toString()` returns `kotlin.IllegalStateException: msg`
(JVM: `java.lang.IllegalStateException: msg`).

## 5. Scope and state of the native translation

Column "native": a test under `compiler/testdata/stdlib_*.kt` builds and runs with output identical to the JVM.

| Area | Source | native today | Obstacle |
|---|---|---|---|
| `String`: substring/take/drop/startsWith/endsWith/indexOf/lastIndexOf/contains/trim*/padStart/padEnd/repeat/reversed/lowercase/uppercase/replace/substringBefore/After(Last)/removePrefix/Suffix/isBlank/isEmpty/first/last/compareTo | `kotlin/text/Strings.kt` | **yes** (`stdlib_strings`) | |
| `String.split/lines/toList` | Strings.kt | no | need `ArrayList<String>` (generics) |
| `String.count/forEach/filter/any/all/trim{}` | Strings.kt | no | lambdas |
| `StringBuilder` (append/insert/delete/reverse/setLength/get/set/substring/appendLine/clear/…) | `text/StringBuilder.kt` | **yes** (`stdlib_stringbuilder`) | |
| `buildString { }` | StringBuilder.kt | no | lambdas |
| `toInt/toIntOrNull/toLong/toLongOrNull/toDouble/toDoubleOrNull/toBoolean`, `Int/Long.toString(radix)` | `text/StringNumberConversions.kt` | **yes** (`stdlib_numbers`, `stdlib_panic`) | |
| `Char.isDigit/isLetter/isWhitespace/isUpperCase/uppercaseChar/digitToInt/…` | `text/Char.kt` | **yes** | ASCII/Latin-1 only |
| `maxOf/minOf` (Int/Long/Double/Char), `coerceIn/AtLeast/AtMost`, `abs` | `comparisons`, `ranges/Coerce.kt`, `math/Math.kt` | **yes** | |
| `Pair/Triple/to`, `Comparator`, `compareBy`, `lazy`, `let/run/also/apply/with/takeIf/repeat`, `require/check/requireNotNull/error/TODO` | `kotlin/*.kt` | no | generics, lambdas |
| `Iterable/Iterator/Collection/List/MutableList/Set/MutableSet/Map/MutableMap` (+ `Abstract*`) | `kotlin/collections/*` | no | generics (interfaces/classes) |
| `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`, `LinkedHashSet` | `ArrayList.kt`, `HashMap.kt`, `HashSet.kt` | no | generics |
| `listOf/mutableListOf/arrayListOf/emptyList/setOf/mapOf/hashMapOf/...`, `buildList`, `List(n){}` | `Builders.kt` | no | generics, `vararg`, lambdas |
| `map/filter/forEach/joinToString/associate*/groupBy/first/last/any/all/count/sum/sumOf/min/max*/sortedBy/sortedWith/sorted/reversed/take/drop/zip/fold/reduce/distinct/partition/flatMap/…` | `Iterables.kt` | no | generics, lambdas, extension functions on interfaces |

Everything marked "no" in the table is written as Kotlin, goes through K2 and is **checked on the JVM against the tests** (see 7).
Once generics/lambdas/exceptions exist in the backend, ideally nothing is left to port here.

### Known deviations from Kotlin

* `StringBuilder` is not (yet) a `CharSequence`/`Appendable`: `CharSequence` is a builtin interface and not a translatable type for the backend.
  Accordingly `isEmpty/isNotEmpty/isBlank/isNotBlank/indices/lastIndex` are declared on `String` instead of `CharSequence`.
* Character classification and upper/lower case only for ASCII and Latin-1. No regex (`split(Regex)`, `replace(Regex)`), no `format`.
* `HashMap`/`HashSet` replicate the iteration order of `java.util.HashMap` (table size, spreading, resize, `mapCapacity` like the JVM stdlib),
  so that programs produce the same output; no tree bins.
* Read-only lists/sets/maps are the mutable implementations behind the interface (as on Kotlin/Native); `emptyList()` is a singleton.
* `Double.toString`, `Float`: float operations are missing in the backend; the sources avoid `Float` where possible (only `mapCapacity` uses `Double`).
* Everything is single-threaded (`lazy` is `UnsafeLazy`), `java.lang.Thread` is a placeholder that aborts with an error when called.
* `Throwable` has no stack trace API.
* `vararg` functions (`listOf(a, b)`) use `Array<T>`; a native `Array` does not exist yet (backend task, see risks).

## 6. Notes on extending the backend

* Extension functions: `Ir2Mir` used to reject them; v1-S removed the check for `ExtensionReceiver` (two lines in `Ir2MirClasses.kt`/`Ir2MirExpressions.kt`),
  the rest works unchanged (the receiver is simply the first parameter).
* Overloads that differ only by type arguments (`Iterable<Int>.sum()` / `Iterable<Long>.sum()`, `sumOf` with a lambda return type)
  carry `@JvmName`. The backend's name mangling has to take that into account (e.g. via `@JvmName` or by including type arguments in the name),
  otherwise the C names collide.
* `String` members with `@CSymbol` are intercepted by the hook in `call()` before the old special cases (`<get-length>`, `hashCode` ...) apply.

## 7. Tests

* `StdlibJvmTest.stdlibGivesIrForEverything`: all stdlib sources go through K2 with the compiler configuration from section 1; every function
  (not abstract, not `external`, not builtin) has an IR body.
* `StdlibJvmTest.stdlibUnitTestsOnJvm`: the stdlib sources (without builtins, `package kotlin.x` → `mkt.x`, `mini.internal` replaced by
  `stdlib-test/jvm/mini/internal/Intrinsics.kt`, all classes explicitly imported) are compiled for the JVM and run with the
  unit tests from `stdlib-test/tests/` (about 40 tests: strings, StringBuilder, lists, sets, maps, HashMap order,
  sorting, aggregates, scope functions, preconditions). `UsesMiniStdlib` makes sure that *our* classes are really what is tested.
* `StdlibJvmTest.testsAgreeWithRealStdlib`: the same tests run against the real kotlin-stdlib; the expected values are real Kotlin behavior.
* `StdlibJvmTest.intrinsicsAreInSync`: `@CSymbol` ↔ `minirt.h` ↔ JVM implementation.
* `EndToEndTest` with `compiler/testdata/stdlib_{strings,stringbuilder,numbers,panic}.kt` (`.out` comes from the real JVM).
* `runtime/tests/test_v1s.c` (ASan/UBSan) for the C functions.

## 8. Risks and open points

1. **Generics/lambdas in the backend** are a prerequisite for collections and `map`/`filter`. The sources are written for it, but untested natively;
   backend gaps only become visible there (name mangling for overloads, `Array<T>` for `vararg`, `Map.Entry` as an interface inside an interface,
   `object` expressions in `HashMap` (anonymous iterators), `private class` in files).
2. **Build time**: every `minic build` analyzes the entire stdlib (about 3 s). EndToEndTest has grown to ~110 s as a result. Countermeasure: precompile the stdlib IR/FIR
   once, or load subsets on import.
3. **Static reachability** is conservative (classes keep all dispatch-capable methods): a reached `ArrayList` pulls in e.g. `AbstractMutableList` with
   `subList`, `equals`, `hashCode`. A finer analysis needs type-flow information (RTA).
4. **Memory model**: reference cycles (e.g. `LinkedHashMap` nodes `before`/`after`) leak under RC. `HashNode.before/after` form cycles, the backend
   needs weak references or a cycle collector before `LinkedHashMap` is leak-free.
5. **Interface types from builtin files** (`CharSequence`, `Comparable`, `Iterator` ...) are unknown to `Ir2Mir`; as long as `CharSequence` is a builtin, there is
   no equal treatment of String/StringBuilder.
6. **Unicode**: ASCII/Latin-1 only; behavior for other characters deviates from the JVM.
7. **`Throwable.toString`/`typeName`**: the package name is `kotlin.…` instead of `java.lang.…`.
8. **License**: derived files carry the Apache-2.0 header and `stdlib/NOTICE`.

## 9. v1-A additions (the backend sources compile natively)

Ported from the Kotlin 2.4.21 stdlib sources (Apache-2.0 headers kept) or reimplemented, each covered by `stdlib-test/tests/V1aTests.kt`
(runs on the JVM against our sources and against the real stdlib):

* `kotlin.ranges`: `IntRange/LongRange/CharRange`, `IntProgression/...`, their iterators, `until`, `downTo`, `step`, `reversed`, `ClosedRange` are now real
  classes (`Ranges.kt`, no longer a builtin file); ranges are `Iterable`, so `(0 until n).map { }`, `.joinToString`, `.reversed()` work.
  `IntIterator` & co. are real abstract classes. Stdlib code that *returns* a range uses the constructor (`IntRange(0, size - 1)`) so that the JVM test
  (which renames the package) sees its own type.
* `kotlin.collections`: `ArrayDeque`, `asReversed`, `indexOfLast`, `ifEmpty`, reified `filterIsInstance`, `Set.plus/minus(Iterable)`, `windowed`/`chunked`/`zipWithNext`/`unzip`,
  `groupBy(key, value)`, `runningFold`, `putIfAbsent`, more `Map` functions (`mapNotNull`, `flatMap`, `maxByOrNull`, `filterTo`, `minus(keys)`, ...), `String.iterator()`/`withIndex()`.
* `kotlin.text`: `Char` delimiters for `substringBefore/After(Last)`, `ifEmpty/ifBlank/orEmpty`, `map/mapIndexed/forEachIndexed/indexOfFirst/indexOfLast/takeWhile/...`, `chunked`, `removeSurrounding`, `toCharArray`.
* `kotlin.comparisons`: `compareBy(vararg selectors)`, `thenByDescending`, `thenComparator`, `then`.
* `kotlin`: `Result`/`runCatching` (an ordinary class, not a value class), `Double.isNaN/isInfinite/isFinite`, `Double/Float.MIN_VALUE/MAX_VALUE`,
  `KClass.simpleName/qualifiedName` (`KClassImpl`, created by the backend for `Foo::class` / `x::class` from the type info name).
