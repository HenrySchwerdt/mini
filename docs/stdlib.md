# Standard library from source (v1-S)

Status: milestone v1-S. Related: `docs/plan-kotlin.md` (sections 6 and 10b), `compiler/README.md`,
code in `tools/k2-oracle/jvm-bridge` (`FrontendDriver.kt`, `Stdlib.kt`, `Reachability.kt`, `Ir2MirIntrinsics.kt`),
sources in `stdlib/`, tests in `stdlib-test/` and `tools/k2-oracle/jvm-bridge/src/test/kotlin/StdlibJvmTest.kt`.

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
* Character classification and upper/lower case only for ASCII and Latin-1. `Regex` and `format` exist since the parity pass (section 10).
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
* `kotlin`: unsigned types `UByte`/`UShort`/`UInt`/`ULong` as value classes (`Unsigned.kt`, generated pattern: arithmetic wraps, `/`, `%`, comparison, `toString` are unsigned; `UIntArray` & co., `UIntRange`, `toUInt()`...), `ByteArray`/`ShortArray`, `String.encodeToByteArray()`/`ByteArray.decodeToString()` (UTF-8), `Char(code)`, `Int/Long.countOneBits()/rotateLeft()`, `Result`/`runCatching` (an ordinary class, not a value class), `Double.isNaN/isInfinite/isFinite`, `Double/Float.MIN_VALUE/MAX_VALUE`,
  `KClass.simpleName/qualifiedName` (`KClassImpl`, created by the backend for `Foo::class` / `x::class` from the type info name).

## Coroutines, sequences and threads

`kotlin.coroutines`, `kotlin.sequences`, `java.lang.Thread` / `kotlin.concurrent.thread`, `mini.concurrent` (`Lock`, `Condition`,
atomics, clocks) and `mini.coroutines` (jobs, `launch`/`async`, dispatchers, `Mutex`, `Channel`, `Flow`) are part of the standard
library; the native part is `mini.internal.Sys` over `runtime/src/sys.c`. Details: `docs/coroutines.md`.

## 10. Parity with the Kotlin common stdlib (the "stdlib-parity" pass)

The native tests of this pass live in `stdlib-test/native` (a Mini package: `cd stdlib-test/native && ../../mini test`) and in the
`compiler/testdata/stdlib_*` programs of `scripts/test-e2e.sh`. Expected values follow the behavior of kotlin-stdlib on the JVM
(reasoned from the stdlib sources; this pass had no JVM oracle). `tools/gen-stdlib-arrays.py` generates
`stdlib/kotlin/collections/PrimitiveArrayFunctions.kt`.

Coverage of everyday API, per package. "yes" = all common functions, "most" = the everyday ones, gaps are listed.

| Package | State | In Mini | Missing or different |
|---|---|---|---|
| `kotlin` | most | `Pair/Triple` (+`toList`), `lazy`, `Result/runCatching`, `check/require/error/TODO/requireNotNull/checkNotNull`, `let/run/apply/also/with/takeIf/repeat`, `Double/Float.toBits/toRawBits`, `Int/Long.mod/floorDiv/countOneBits/countLeadingZeroBits/countTrailingZeroBits/takeHighestOneBit/rotateLeft`, `Char(code)`, `AutoCloseable.use` | `Double.fromBits/Float.fromBits` (`mk_sys_bits_double/float`), `LazyThreadSafetyMode` (every mode is the single-threaded lazy), `lazy(mode)`, `lazyOf`, `Any?.hashCode()`, unsigned bit operations (`countOneBits/countLeadingZeroBits/countTrailingZeroBits/takeHighestOneBit/takeLowestOneBit/rotateLeft/rotateRight`), `Byte/Short.and/or/xor/inv`, `Throwable.stackTraceToString/printStackTrace/addSuppressed/suppressedExceptions` (no stack frames: the exception and its causes), `ConcurrentModificationException`, `kotlin.system` (`measureTimeMillis`, `measureNanoTime`, `exitProcess`), `String.format(...)` static form (extensions of `String.Companion` are called through the companion: the lowering represents the companion objects of the primitive types and `String` like `Unit`) | `StackOverflowError` / `OutOfMemoryError` and the other JVM `Error`s, `Throwable.stackTrace`, `Char.category` and the Unicode `Char` classification beyond Latin-1, `java.util.concurrent.atomic.*` (use `mini.concurrent`) |
| `kotlin.collections` | most | `List/Set/Map` + mutable, `ArrayList`, `ArrayDeque`, `HashMap/HashSet` (JVM iteration order), `LinkedHashMap/Set`, builders (`listOf`, `mutableMapOf`, `buildList/buildMap/buildSet`, `List(n){}`), ~all `Iterable/Collection/List/Map` operators incl. `groupingBy().eachCount/fold/reduce/aggregate`, scans (`scan/runningReduce/runningFoldIndexed`), `*To` variants, `maxWith/minWith/maxOf/minOf(+OrNull)`, `firstNotNullOf`, `onEach(Indexed)`, `windowed/chunked/zipWithNext`, `MutableMap.computeIfAbsent/compute/merge/replace`, `removeIf`, `shuffle/shuffled/random`, `sortedMapOf/sortedSetOf/toSortedMap/toSortedSet` (`java.util.TreeMap/TreeSet`, sorted arrays), arrays: all primitive arrays `Byte/Short/Int/Long/Double/Float/Char/Boolean`, the unsigned arrays (`UByteArray` .. `ULongArray`: `forEach/map/filter/reversed/sorted/sum/max/min/copyOf/joinToString/zip/...`) and `Array<T>`: `copyOf/copyOfRange/copyInto/fill/sort*/sorted*/reverse*(also with ranges)/shuffle/random/asSequence/runningFold/scan/reduceIndexed/foldRight/componentN/mapTo/filterTo/associateTo/groupingBy/toSortedSet/contentEquals/contentHashCode/plus/sum/average/map/filter/fold/...` (generated by `tools/gen-stdlib-arrays.py`), `Map.withDefault` (also as a `val x by map.withDefault { .. }` delegate), `windowed/chunked` with `transform`, `zipWithNext { }`, `reduceOrNull`, `single(predicate)`, `binarySearch(..)`, `List.component1..5`, `MutableList.fill/sortDescending/sortByDescending/addFirst/addLast`, `addAll/removeAll/retainAll(Array/Sequence)`, `buildList(capacity)`, `setOfNotNull`, `Grouping.eachCountTo/foldTo/reduceTo/aggregateTo`, `AbstractIterator`, `for (x in iterator)`, `MutableList.iterator()` is a `MutableIterator`, `LinkedHashMap(capacity, loadFactor)` | `Iterable.sumOf(UInt)` for non-array receivers, `onEach` on `MutableList` returns `List`; `TreeMap.headMap/tailMap/subMap` return copies, not views; `Map.forEach { k, v -> }` (JVM `BiConsumer`), `MutableMap.replaceAll/remove(k, v)` (JVM) |
| `kotlin.sequences` | most | `Sequence`, `sequenceOf`, `emptySequence`, `asSequence`, `generateSequence` (3 forms), lazy `map/filter/take/drop/takeWhile/dropWhile/flatMap/flatten/distinct/zip/withIndex/onEach/plus/minus` and also lazy `windowed/chunked/zipWithNext/runningFold/runningReduce/scan(+Indexed)` (they work on infinite sequences), `sorted*` (consume the source when iterated, like kotlin-stdlib), all terminal operators, `filterIsInstance`, `firstNotNullOf`, `groupingBy`, `String.asSequence/lineSequence/splitToSequence`, `Map.asSequence` | `flatMap` with an `Iterable`-returning lambda (only the `Sequence` form: Mini has no overload resolution by lambda return type) |
| `kotlin.text` | most | `String` functions on `String` receivers (`split` with limit/ignoreCase/vararg/Regex, `windowed`, `zip`, `replace*`, `trimMargin/trimIndent/prependIndent`, `padStart/padEnd`, `substring(range)`, `ignoreCase` overloads, `toIntOrNull` & co, `commonPrefix/SuffixWith`, ...), `Char` functions (ASCII + Latin-1), full `StringBuilder` API, `buildString`, `String.format` / `"%d %s %.2f".format(..)` (java.util.Formatter subset: `%d %s %S %b %B %h %H %c %C %f %e %E %g %G %x %X %o %n %%`, flags `- 0 + space , ( #`, width, precision, `%1$s`, `%<s`, `Byte/Short/Int/Long/Float/Double` arguments; floating point from the shortest decimal digits, HALF_UP like Java; bad specifiers throw the `java.util.*FormatException` classes of the JVM (`UnknownFormatConversionException`, `MissingFormatArgumentException`, `IllegalFormatConversionException`, `FormatFlagsConversionMismatchException`, `IllegalFormatPrecisionException`, `MissingFormatWidthException`, `IllegalFormatFlagsException`, ...)), `Regex` (own backtracking engine, see below), `String(CharArray)` | full Unicode in `Char.isLetter/uppercase` (Latin-1 only), `String.format(Locale, ..)`, `%a` / `%t` conversions, `BigInteger` / `BigDecimal` / `Formattable` arguments, `Regex` on code points (`.` and negated classes match one UTF-16 unit, `\\x{1F600}` beyond the BMP), `String.codePointAt/intern` (JVM), `Regex` features: Unicode classes beyond ASCII/Latin-1, `\X`, possessive/atomic only through `(?>..)`/`++`, `(?<name>)` lookbehind with variable length is supported by brute force, regex recursion depth is proportional to the iterations of a group loop, `toByteArray/encodeToByteArray` |
| `kotlin.text.Regex` | most | `Regex(pattern[, options])`, `RegexOption` (`IGNORE_CASE, MULTILINE, DOT_MATCHES_ALL, COMMENTS, LITERAL, UNIX_LINES`), `matches/matchEntire/containsMatchIn/find/findAll/matchAt`, `replace` (string with `$1`/`${name}`/`\` and lambda), `replaceFirst`, `split(limit)`, `MatchResult` (`value/range/groups/groupValues/destructured/next`), named groups, `Regex.escape` (`\\Q..\\E` like `Pattern.quote`) `/fromLiteral/escapeReplacement`, `\\R`, `\\Q..\\E` with a quantifier on the last character, `String.toRegex/matches/contains/replace/split(Regex)`; syntax: literals, escapes, classes (nested, ranges, `&&`, `\d\w\s\p{..}`), `. ^ $ \A \z \Z \b \B`, groups, `(?:)`, `(?<n>)`, atomic groups, alternation, greedy/lazy/possessive quantifiers, backreferences, lookahead/lookbehind, inline flags | cyclic node graphs of loops are reclaimed only by exit (reference counting leaks cycles, see risk 4) |
| `kotlin.ranges` | most | `IntRange/LongRange/CharRange`, progressions, `step/downTo/until/reversed`, `coerceIn/AtLeast/AtMost` (also with ranges), `Float/Double` ranges (`ClosedFloatingPointRange` with `lessThanOrEquals`, `0.0..1.0`, `1f..2f`, `..<`), ranges of any `Comparable` (`"a".."c"`), `coerceIn/AtLeast/AtMost` for every numeric type, unsigned types, `Comparable<T>` and with `ClosedRange` / `ClosedFloatingPointRange` (same messages as kotlin-stdlib), `random/randomOrNull` of `Int/Long/Char/UInt/ULongRange` | mixed `Int`/floating point `contains` (deprecated in Kotlin as well) |
| `kotlin.comparisons` | yes | `compareBy`, `compareByDescending`, `thenBy`, `thenByDescending`, `then`, `naturalOrder`, `reverseOrder`, `compareValues`, `compareValuesBy`, `nullsFirst/nullsLast`, `compareBy(comparator, selector)`, `String.CASE_INSENSITIVE_ORDER`, `maxOf/minOf` (2, 3 and vararg forms for every primitive type, `Comparable`, with a `Comparator`; `Double`/`Float` follow `kotlin.math.max/min`: `NaN` wins, `-0.0 < 0.0`) | `thenComparing` (JVM) |
| `kotlin.math` | most | `PI/E`, `abs/min/max/sign`, `sqrt/cbrt/pow/exp/ln/log*/trig/hyperbolic`, `floor/ceil/round/truncate`, `roundToInt/roundToLong` (ties towards +infinity like the JVM), `hypot`, `IEEErem/nextUp/nextDown/withSign/ulp`, the `Float` overloads of all functions, `Float.mod`, `absoluteValue` | `Long.absoluteValue.pow` |
| `kotlin.random` | yes | `Random(seed)` / `Random.Default` with the same XorWow generator and the same algorithms as kotlin-stdlib (`nextInt/Long/Double/Float/Boolean/bits`, bounded forms), `shuffle/shuffled/random/randomOrNull`, `nextBytes`, `nextInt(IntRange)`, `nextLong(LongRange)`, `nextUInt/nextULong` (also bounded and with ranges) | `Random.asJavaRandom` |
| `kotlin.time` | most | `Duration` (a class holding nanoseconds, range about +-292 years, then `INFINITE`), `Int/Long/Double.seconds/minutes/...` (`import kotlin.time.Duration.Companion.seconds` works), arithmetic, comparison, `inWhole*`, `toDouble/toLong/toInt(unit)`, `toComponents` (5, 4, 3 and 2 values), `toIsoString`, `Iterable<Duration>.sum()`, `toString` exactly like kotlin-stdlib, `toString(unit, decimals)`, `DurationUnit`, `TimeSource.Monotonic`, `TimeMark`, `measureTime`, `measureTimedValue` | `Duration.parse`, `Instant/Clock`, `TimeSource.markNow().elapsedNow()` on custom sources |
| `kotlin.io` | most | `println/print` (all types), `readln`, `readLine`, `readlnOrNull` (UTF-8 lines, `\r\n` stripped), `use {}` on every `Closeable` / `AutoCloseable` (an exception of `close()` after a failing block becomes suppressed, like the JVM), `File.readText/writeText/appendText/readBytes/writeBytes/appendBytes/readLines/forEachLine/useLines/forEachBlock/bufferedReader/reader/writer/bufferedWriter/printWriter/inputStream/outputStream/copyTo/copyRecursively/deleteRecursively/walk*` (byte exact, with charsets UTF-8, ISO-8859-1, US-ASCII, UTF-16/BE/LE), `Reader/InputStream/Writer` extensions, `String.reader/byteInputStream`, `kotlin.io.path`, `java.io` (`File`, `FileFilter`/`FilenameFilter`, `FileReader/Writer`, `FileInput/OutputStream`, `Buffered*`, `InputStreamReader/OutputStreamWriter` with charsets, `PrintWriter/PrintStream`, `ByteArray*Stream`, `String*`), `java.nio.file.Path/Files`, `System.in/out/err`, `mini.io` file functions | `Scanner`, `RandomAccessFile`, `File` permissions and `setLastModified` (accepted, no effect), symbolic links, file locks, a working directory (relative paths stay relative), `readText` of stdin as a `File` |
| `kotlin.test` (`mini test`) | most | `assertEquals` (with `absoluteTolerance`), `assertNotEquals`, `assertSame/NotSame`, `assertTrue/False` (also with lambda), `assertNull/NotNull` (also with block), `assertFails`, `assertFailsWith<T>`, `assertContentEquals` (iterables and arrays), `assertIs/IsNot`, `assertContains`, `expect`, `fail` | `assertEquals` for `UInt`, `@BeforeTest/@AfterTest` |

Compiler changes that came with the pass (small, covered by tests): a candidate whose extension receiver class cannot match the call no longer
hides the expected argument type of the other overloads (`Array<T>.sortWith` next to `MutableList<T>.sortWith`); members of an `object`
imported by name (`import kotlin.time.Duration.Companion.seconds`) get the object as receiver.

Stage0 note: the stdlib is compiled by the previous release (`build/stage0`) when the toolchain bootstraps, so it must only use constructs
that release compiles. Two source rules follow: write `Comparator<T> { .. }` with the type argument when passing a SAM-constructor call to an
overloaded function, and do not declare extensions on `X.Companion` types.

Size: `lib/stdlib.mlib` grew from 1.47 MB to about 3 MB (the primitive-array functions are the largest part: 6 array types x ~110 functions).
The conformance round 5b (docs/conformance.md) added the long tail: the mlib is about 8.6 MB now (the primitive arrays are 8 types x ~290 functions
and the unsigned arrays 4 x ~125, all generated), a hello-world compile costs about 0.3 s more user time than before.

Two rules for library code that came out of round 5b: an `inline` function of the library must not contain an object expression (`object : Grouping<..>`:
the expansion in a program from the precompiled library fails with "construction of '<anonymous>'"; use a private class), and two overloads whose
erased parameters are equal (`StringBuilder(String)` / `StringBuilder(CharSequence)`) clash as C symbols: write the second one as a factory function.
The test programs of the stdlib (`compiler/testdata/stdlib_*`) run against `lib/stdlib.mlib`, run them with `scripts/test-e2e.sh --suite c stdlib_`.

## 11. API coverage against the Kotlin stdlib

`tools/stdlib-coverage/coverage.py` lists the public declarations of the Kotlin 2.1 common stdlib (commonMain, generated, the builtins
and `print`/`readln` of jvmMain from `kotlin-stdlib-sources.jar`, unpacked by `tools/stdlib-coverage/fetch.sh`) that `stdlib/` lacks, keyed by
package, receiver or class, name and arity. `--list <package>` prints the missing ones, `--top N` ranks them by how often the name occurs
in the conformance tests. The scan is lexical: deprecated-hidden and `@SinceKotlin` newer than 2.1 declarations are skipped, a member
may live in a supertype, and an extension counts as present when mini declares it for a supertype of the receiver or as a member.

Coverage per package (current state):

| package | reference | present | missing | coverage |
|---|---:|---:|---:|---:|
| `kotlin.collections` | 2940 | 2939 | 1 | 100.0% |
| `kotlin` | 732 | 728 | 4 | 99.5% |
| `kotlin.text` | 587 | 587 | 0 | 100.0% |
| `kotlin.ranges` | 208 | 208 | 0 | 100.0% |
| `kotlin.sequences` | 186 | 186 | 0 | 100.0% |
| `kotlin.time` | 138 | 138 | 0 | 100.0% |
| `kotlin.concurrent.atomics` | 91 | 91 | 0 | 100.0% |
| `kotlin.math` | 53 | 53 | 0 | 100.0% |
| `kotlin.reflect` | 50 | 50 | 0 | 100.0% |
| `kotlin.random` | 41 | 41 | 0 | 100.0% |
| `kotlin.coroutines` | 40 | 40 | 0 | 100.0% |
| `kotlin.comparisons` | 33 | 33 | 0 | 100.0% |
| `kotlin.uuid` | 25 | 25 | 0 | 100.0% |
| `kotlin.annotation` | 24 | 24 | 0 | 100.0% |
| `kotlin.contracts` | 19 | 19 | 0 | 100.0% |
| `kotlin.io.encoding` | 17 | 17 | 0 | 100.0% |
| `kotlin.jvm` | 16 | 16 | 0 | 100.0% |
| `kotlin.experimental` | 12 | 12 | 0 | 100.0% |
| `kotlin.properties` | 12 | 12 | 0 | 100.0% |
| `kotlin.io` | 6 | 6 | 0 | 100.0% |
| `kotlin.coroutines.cancellation` | 5 | 5 | 0 | 100.0% |
| `kotlin.coroutines.intrinsics` | 3 | 3 | 0 | 100.0% |
| `kotlin.enums` | 2 | 2 | 0 | 100.0% |
| `kotlin.concurrent` | 1 | 1 | 0 | 100.0% |
| **total** | **5241** | **5236** | **5** | **99.9%** |

Before this pass: **total**  **5241**  **4433**  **808**  **84.6%** (total row: reference / present / missing / coverage). Added in the first pass: Base64,
HexFormat and the hex conversions, Uuid, `kotlin.concurrent.atomics`, Appendable, Typography, TimeMark / ComparableTimeMark /
ValueTimeMark / TestTimeSource / measureTime on time sources, `kotlin.experimental` Byte/Short bit operations, Byte/Short floorDiv/mod/rotate,
`toString(radix)` of the remaining integer types. The second pass (90.0% to 99.9%) added:

- the long tail of the array families (`Array`, the eight primitive arrays, the four unsigned arrays), ported from the generated sources of
  kotlin-stdlib: `maxOfWith` / `minOfWith` (and `OrNull`), `associateByTo`, `groupByTo`, `flatMapIndexedTo`, `reduceRightIndexed*`,
  `runningReduceIndexed`, `union` / `intersect` / `subtract`, the unsigned `scan` / `runningFold` / `foldRight` / `sumOf` / `minOf*` and so on,
  and the same for `Sequence`, `CharSequence`, `Iterable`, `List` and `Map` (`stdlib/kotlin/collections/*Tail.kt`, `*Generated.kt`);
- `typeOf<T>()` and the KType family (`KType`, `KTypeProjection`, `KVariance`, `KTypeParameter`, `KClassifier`) and `KClass.isInstance` /
  `cast` / `safeCast`, see "Reflection" below; `KProperty2.get`, `KMutableProperty2.set`, `KMutableProperty1.setValue`;
- `DeepRecursiveFunction`, `KotlinVersion`, `kotlin.time.Clock` / `Instant` (ported, the wall clock is `sysWallMillis`), `Duration.toIsoString`,
  the public `ObservableProperty`, `AbstractCoroutineContextKey` and the polymorphic-key helpers, `CancellationException`, `enumEntries` in
  `kotlin.enums`, `MatchNamedGroupCollection`, `UIntRange` / `ULongRange` (now in `kotlin.ranges`, with `endExclusive`), `endExclusive` of
  `IntRange` / `LongRange` / `CharRange`, `toMap` of arrays and sequences of pairs, `Random.nextUBytes`, the deprecated `Char.toByte()` family
  and the remaining annotation classes (`JvmRecord`, `Volatile`, `Metadata`, ...).

The scan is lexical (see above), so a few members of builtin classes stay listed as missing (`Boolean.Companion`, `Enum.Companion`,
`Enum(name, ordinal)`, `Number()`, the `Comparator` typealias): they have no use in a native program. The behaviour of the ported functions is
checked by the conformance-style programs `compiler/testdata/stdlib_*_tail.kt`, `stdlib_typeof.kt`, `stdlib_instant.kt`,
`stdlib_deep_recursive.kt`, `stdlib_coroutine_keys.kt` and `stdlib_enum_entries.kt`, whose `.out` files were produced by running the same
sources on the JVM with kotlinc 2.1.20.

The I/O, `String.format` and `Regex` surface is checked the same way by `compiler/testdata/stdlib3_*.kt` (`.out` from kotlinc 2.1.20 on the JVM, a
`.in` file is the stdin of the program): `stdlib3_format_full` (every conversion, flag, width/precision and the exception classes of bad
specifiers), `stdlib3_format_grid` (45 doubles x 25 specifiers, integers, bytes, shorts, strings), `stdlib3_regex_full` / `stdlib3_regex_more`
(named groups, lookahead/lookbehind, `replace` with a lambda, `split` with a limit, `findAll`, options, syntax), `stdlib3_io_full`,
`stdlib3_io_binary` (byte-exact files, charsets, `kotlin.io.path`), `stdlib3_file_io`, `stdlib3_stdin` and `stdlib3_stdin_lines`.
Files are byte exact since stdlib-3: the runtime has `mk_b1_read_bytes`, `mk_b1_write_bytes` (create/truncate or append) and `mk_b1_file_stat`
(size, modification time); `FileWriter` / `FileOutputStream` append what they are given on `flush()` / `close()`.

### Reflection

Mini has no reflection, but `typeOf<T>()` is supported for static types: the compiler (`Ir2MirReflect.kt`) lowers the call to a `KTypeImpl`
(`stdlib/kotlin/reflect/KTypes.kt`) built from the text of the type argument, in the format of `KType.toString()` of kotlin-stdlib
(`kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.Int>?>`, `kotlin.Comparable<in kotlin.Int>`, `Foo<*>`). The type
parameters of the enclosing inline functions are substituted (`inline fun <reified T> f() = typeOf<List<T>>()`); a type parameter that cannot be
substituted becomes a `KTypeParameter` (name only, upper bound `Any?`, invariant, not reified). `classifier` gives the `KClass` (equal to
`Foo::class`), `arguments` the projections, `isMarkedNullable` the nullability; two KTypes are equal when their text is equal. Not available:
`createType`, `supertypes`, `withNullability`, `javaType`, members of classes. `KClass.isInstance` asks the runtime (`mk_type_is_named`)
whether the dynamic type or one of its supertypes has the class's name.

