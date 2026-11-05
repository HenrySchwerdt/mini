# Mini and Kotlin/JVM

Mini accepts Kotlin source. Its own parser, type checker and lowering are the default frontend, checked against Kotlin K2 as an oracle. The JVM compiler driver can also select K2 with `--frontend=k2`. What differs is everything after the frontend: there is no JVM, no JDK, no garbage collector, and the standard library is Kotlin source compiled together with your program.

## What works

All of these compile to native code and are covered by programs under `compiler/testdata/` whose expected output comes from real Kotlin on the JVM. The [language tour](/tour/) has a runnable page for most of them.

| Area | Supported |
|---|---|
| Functions | top-level, local, recursion, default and named arguments, `vararg` and spread, extension functions and properties, `inline` (including non-local `return` and `reified` type parameters), `lateinit`, `lazy`, `tailrec` (compiled to a loop), `operator`/`infix` functions |
| Types | `Int`, `Long`, `Boolean`, `Double`, `Char`, `String`, `Unit`, `Any`, nullable types, arrays (`Array<T>`, `IntArray`, `LongArray`, `DoubleArray`, `BooleanArray`, `CharArray`) |
| Classes | primary and secondary constructors, `init`, properties with accessors, `open`/`abstract`/`override`, interfaces with default methods, `object`, `companion object`, nested, inner, local and anonymous classes |
| Special classes | `data class` (`toString`/`equals`/`hashCode`/`copy`/`componentN`), `enum class` (constructors, entries with bodies, interfaces, `entries`, `values()`, `valueOf()`), `sealed` hierarchies with exhaustive `when`, `fun interface` with SAM conversion |
| Generics | generic classes, interfaces and functions, bounds such as `T : Comparable<T>`, variance annotations |
| Lambdas | closures, captured `var`s, function types (also with receiver), function references (bound, unbound, constructor, local), property references (`::prop`, `obj::prop`, `Class::prop` as `KProperty0`/`KProperty1` with `get`, `set`, `name`) |
| Control flow | `if`/`when` as expressions, `for` over ranges, arrays and iterables, `while`, labeled `break`/`continue`, string templates |
| Null safety | `?.`, `?:`, `!!`, smart casts, `is`/`as`/`as?` |
| Exceptions | `throw`, `try`/`catch`/`finally` (also as an expression), the standard exception classes, user subclasses; runtime failures (`/ by zero`, `!!` on null, bad casts, index out of bounds) are exceptions |
| Standard library | `String`, `StringBuilder`, `List`/`MutableList`/`Map`/`Set` with `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`, `map`/`filter`/`fold`/`sortedBy`/`joinToString` and friends, `Pair`/`Triple`, ranges, `Result`/`runCatching`, `buildString`. See [Standard library](/stdlib) |
| Reflection | only `Foo::class`/`x::class` with `simpleName` and `qualifiedName` |

## Differences you will notice

- **No JVM and no JDK classes.** `java.*` does not exist. The standard library is a Mini port of Kotlin's, plus `mini.*` packages (`mini.memory`, `mini.io`).
- **Matrix operator `@`.** Mini adds one operator Kotlin does not have, see [below](#matrix-operator). Programs that use it are not valid Kotlin and K2 cannot read them.
- **Memory.** Objects are reference counted by default. Reference cycles leak because there is no cycle collector yet. Strict mode and allocators are Mini additions; see the [memory guide](/memory/).
- **Uncaught exceptions.** An exception that escapes `main` prints `Exception in thread "main" <toString>` on stderr and exits with status 1. There are no stack traces.
- **Initialization order.** Top-level properties are initialized eagerly, in textual order, before `main`, as on the JVM.
- **Unicode.** Strings are UTF-16 like Kotlin, but character classification and case conversion only cover ASCII and Latin-1.
- **Single-threaded stdlib.** `lazy` is not synchronized. The only threading API is `mini.memory.spawn`, see [Threads](/memory/threads).
- **Hash iteration order** of `HashMap`/`HashSet` replicates `java.util.HashMap`, so programs print the same output as on the JVM.

## Matrix operator `@` {#matrix-operator}

Mini extends Kotlin's operator conventions with a binary `@` in the spirit of Python's `@` ([PEP 465](https://peps.python.org/pep-0465/)). Nothing in the standard library defines it; you give your own types a `matmul` operator:

- `a @ b` is `a.matmul(b)`. The function must be declared `operator fun matmul(other: X): R`, as a member or as an extension, and takes exactly one parameter.
- `a @= b` calls `a.matmulAssign(b)` (it must return `Unit`) if there is one, otherwise it means `a = a @ b` for a `var`, the same rules as `*=` and `timesAssign`. On a `var` for which both functions apply the assignment is ambiguous and rejected, as in Kotlin.
- `@` has the precedence of `*`, `/` and `%` and is left-associative: `a + b @ c` is `a + (b @ c)` and `a @ b @ c` is `(a @ b) @ c`.
- A type without an `operator fun matmul` gives the error `no operator matmul for T`.
- The call is an ordinary call: it works in every memory mode (in Strict mode the owned result of `a @ b` is tracked like the result of any other call) and on every backend.

<<< @/samples/guide/matmul.kt{kotlin}

Output:

<<< @/samples/guide/matmul.out{text}

**How `@` is told apart from annotations and labels.** `@` is the binary operator only after a complete operand and with whitespace on both sides (`a @ b`; `a @= b` for the assignment). `@Name` with no whitespace after the `@` is always an annotation (`@Ann expr`, `@get:Ann`, `@file:Ann`), and `name@` / `return@label` / `this@Outer` / `break@loop` keep their Kotlin meaning. `a @b` is never a product (it is a syntax error).

## Language coverage

Declarations and references are covered as follows (programs in `compiler/testdata/`: `enum_bodies`, `objects_inner`, `typealiases`, `annotations_args`, `callable_refs`, `tailrec_misc`, `labels_overloads`).

- **Enums**: constructors with properties, entries with bodies overriding abstract or open members, interfaces, `entries`, `values()`, `valueOf()`, `ordinal`, `name`, `compareTo`, exhaustive `when`.
- **Objects and classes**: `object`, object expressions (several supertypes, captured locals), companion objects that implement interfaces and can be used as values, `inner` classes with `this@Outer`, classes nested in local classes.
- **Type aliases**: plain, generic and function-type aliases, aliases of aliases, and constructors, companions and objects through an alias are resolved like the type they stand for.
- **Annotations**: annotation classes with parameters (defaults, enum, class and array arguments), use-site targets and repeated annotations are kept on the declarations in the IR (`IrAnnotation`) and generate no code; `@Suppress`, `@JvmName`, `@Deprecated`, `@Throws` and friends are accepted.
- **References**: `::function`, `obj::method`, `Class::method`, `::Constructor`, `::topLevelProperty` and `obj::prop` (`KProperty0`, `KMutableProperty0` with `get`/`set`), `Class::prop` (`KProperty1`, usable as a function value such as `list.map(Foo::name)`), extension properties.
- **Calls**: default and named arguments (also inherited by overrides), `vararg` with spread, `operator` `get`/`set` with several indices, `invoke`, `contains`, `rangeTo`, `rangeUntil`, `componentN`, destructuring in `for` and lambdas, labels on lambdas, loops and `return@label`, `tailrec`, `Unit?` and `Nothing`.
- **Overloads** that differ only in the result type of a lambda (`sumOf { ... }` for `Int`, `Long`, `Double`) are chosen by the type of the lambda body.

## Known gaps

These are the documented limits (`compiler/README.md`, `docs/stdlib.md`) plus what the samples on this site ran into. Using one gives a compile error such as `unsupported construct: ...`, never wrong code.

- [Coroutines](/guide/coroutines): `suspend` functions, `sequence { }` and `mini.coroutines` work, with limits: no suspension points inside lambdas of `inline` functions, no builder inference (`sequence<Int> { }`), no `select`/`actor`/`StateFlow`, and the event loop and thread pools are native only (the browser has `sequence { }`, not `runBlocking`).
- Numeric types: `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Char` and the unsigned types `UByte`, `UShort`, `UInt`, `ULong` (literals `1u`, `1uL`, arrays `UIntArray`, ranges) follow Kotlin, including boxing in `Any`, nullable and generic positions. `Float` is a real 32-bit type. `value class` (also `@JvmInline`) over a primitive is unboxed; over a reference (String, objects, generics) it is an ordinary class with value `equals`/`hashCode`/`toString`. A value class with a secondary constructor is boxed too. `Number` members (`toInt()`, ...) work on boxed numbers. Not available: `UIntArray`/`ULongArray` vararg interop with C, `Iterable<UInt>.sum()`, unsigned `MIN_VALUE`/`MAX_VALUE` as compile-time `const`.
- Local `object` declarations are rejected as in Kotlin; `typealias` is only allowed at the top level.
- A lambda parameter of an `inline` function may only be invoked or passed on to another inline function; use `noinline` to store it.
- No reflection beyond `KClass` names. `Regex` and `"..".format(..)` exist (own engine, java.util.Formatter subset); the static `String.format(..)` form does not (see `docs/stdlib.md`, section 10).
- Native [C interop](/guide/c-interop) supports `external`/`@CName`, pointers, callbacks and scoped C memory. It cannot run against host libraries in the browser. Networking and async IO are still [planned](/roadmap).
- The generated C uses GNU statement expressions, so it needs clang or gcc (not MSVC).
- Reference cycles leak under reference counting, for example a closure stored in a field of the object it captures.

::: tip Strict mode and native code
Programs in Strict mode are checked and build natively, including programs that use the allocator types (`Heap`, `Arena`, `Pool`) and `context(...) { }`. Only `spawn` (threads) is not available yet.
:::
