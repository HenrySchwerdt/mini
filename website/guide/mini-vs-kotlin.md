# Mini and Kotlin/JVM

Mini accepts Kotlin source and uses the real Kotlin K2 frontend (pinned to Kotlin 2.4.21) to read it, so the grammar, name resolution and type checking are Kotlin's. What differs is everything after the frontend: there is no JVM, no JDK, no garbage collector, and the standard library is Kotlin source compiled together with your program.

## What works

All of these compile to native code and are covered by programs under `compiler/testdata/` whose expected output comes from real Kotlin on the JVM. The [language tour](/tour/) has a runnable page for most of them.

| Area | Supported |
|---|---|
| Functions | top-level, local, recursion, default and named arguments, `vararg` and spread, extension functions and properties, `inline` (including non-local `return` and `reified` type parameters), `lateinit`, `lazy` |
| Types | `Int`, `Long`, `Boolean`, `Double`, `Char`, `String`, `Unit`, `Any`, nullable types, arrays (`Array<T>`, `IntArray`, `LongArray`, `DoubleArray`, `BooleanArray`, `CharArray`) |
| Classes | primary and secondary constructors, `init`, properties with accessors, `open`/`abstract`/`override`, interfaces with default methods, `object`, `companion object`, nested, inner, local and anonymous classes |
| Special classes | `data class` (`toString`/`equals`/`hashCode`/`copy`/`componentN`), `enum class` (members, `values()`, `valueOf()`), `sealed` hierarchies with exhaustive `when`, `fun interface` with SAM conversion |
| Generics | generic classes, interfaces and functions, bounds such as `T : Comparable<T>`, variance annotations |
| Lambdas | closures, captured `var`s, function types (also with receiver), function references (bound, unbound, constructor, local) |
| Control flow | `if`/`when` as expressions, `for` over ranges, arrays and iterables, `while`, labeled `break`/`continue`, string templates |
| Null safety | `?.`, `?:`, `!!`, smart casts, `is`/`as`/`as?` |
| Exceptions | `throw`, `try`/`catch`/`finally` (also as an expression), the standard exception classes, user subclasses; runtime failures (`/ by zero`, `!!` on null, bad casts, index out of bounds) are exceptions |
| Standard library | `String`, `StringBuilder`, `List`/`MutableList`/`Map`/`Set` with `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`, `map`/`filter`/`fold`/`sortedBy`/`joinToString` and friends, `Pair`/`Triple`, ranges, `Result`/`runCatching`, `buildString`. See [Standard library](/stdlib) |
| Reflection | only `Foo::class`/`x::class` with `simpleName` and `qualifiedName` |

## Differences you will notice

- **No JVM and no JDK classes.** `java.*` does not exist. The standard library is a Mini port of Kotlin's, plus `mini.*` packages (`mini.memory`, `mini.io`).
- **Memory.** Objects are reference counted by default. Reference cycles leak because there is no cycle collector yet. Strict mode and allocators are Mini additions; see the [memory guide](/memory/).
- **Uncaught exceptions.** An exception that escapes `main` prints `Exception in thread "main" <toString>` on stderr and exits with status 1. There are no stack traces.
- **Initialization order.** Top-level properties are initialized eagerly, in textual order, before `main`, as on the JVM.
- **Unicode.** Strings are UTF-16 like Kotlin, but character classification and case conversion only cover ASCII and Latin-1.
- **Single-threaded stdlib.** `lazy` is not synchronized. The only threading API is `mini.memory.spawn`, see [Threads](/memory/threads).
- **Hash iteration order** of `HashMap`/`HashSet` replicates `java.util.HashMap`, so programs print the same output as on the JVM.

## Known gaps

These are the documented limits (`compiler/README.md`, `docs/stdlib.md`) plus what the samples on this site ran into. Using one gives a compile error such as `unsupported construct: ...`, never wrong code.

- No coroutines or `suspend` functions (planned, on top of libuv).
- No property references (`::prop`), except `::lateinitProp.isInitialized`.
- No `Byte`, `Short` or `Float` in the backend, no `value class`, no unsigned types.
- Annotations with arguments are not supported by the backend (the `mini.memory` annotations are handled by the checker).
- Not supported: enum entries with bodies, local `object` declarations, nested classes inside local classes, `Unit?`; type aliases are ignored (no code is generated for them).
- A lambda parameter of an `inline` function may only be invoked or passed on to another inline function; use `noinline` to store it.
- `entries` of an enum class did not resolve in the sample on the [types page](/tour/types), so it uses `values()` instead.
- No regular expressions, no `String.format`, no reflection beyond `KClass` names.
- No C interop (`external`/`@CName`), no networking or async IO yet. These are [planned](/roadmap).
- The generated C uses GNU statement expressions, so it needs clang or gcc (not MSVC).
- Reference cycles leak under reference counting, for example a closure stored in a field of the object it captures.

::: warning Strict mode and native code
Programs in Strict mode are checked, but programs that use the allocator types (`Heap`, `Arena`, `Pool`) do not yet build natively: `minic build` stops with `unsupported construct: value class 'NativePtr'` while compiling `mini.memory`. Strict files that never touch an allocator (such as the [`@NoAlloc` sample](/memory/noalloc)) do build. Details in the [roadmap](/roadmap).
:::
