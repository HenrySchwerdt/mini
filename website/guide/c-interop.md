# C interop

Mini calls C directly: no wrapper layer, no generated glue, no runtime shim. The API is the one of Kotlin/Native's `kotlinx.cinterop`, so code written for it mostly works unchanged. The sample below is compiled and run by the documentation checks.

<<< @/samples/guide/cinterop.kt{kotlin}

```text
<<< @/samples/guide/cinterop.out
```

## Calling C functions

`external fun` with `@CName("symbol")` is a direct call of the C function. Without `@CName` in your own files the Kotlin name is the symbol.

| Kotlin | C |
|---|---|
| `Byte`, `Short`, `Int`, `Long` | `int8_t`, `int16_t`, `int32_t`, `int64_t` (`size_t` and `ssize_t` are `Long`) |
| `Float`, `Double` | `float`, `double` |
| `Boolean` | `bool` |
| `Char` | `uint16_t` |
| `Unit` | `void` |
| `CPointer<T>`, `CPointer<T>?`, `COpaquePointer` | `T *`, `void *` (null is `NULL`) |
| `CPointer<CFunction<(Int) -> Int>>` | `int (*)(int)` |

Unsigned types do not exist yet: use the signed type of the same width. `String`, classes and structs by value have no C representation and are rejected with a clear message. Structs are passed by pointer only.

C functions with `...` are declared once per call shape with `@CVariadic(fixed)`, the number of leading fixed parameters:

```kotlin
@CVariadic(3) @CName("snprintf")
external fun snprintfInt(buf: CPointer<ByteVar>, n: Long, fmt: CPointer<ByteVar>, v: Int): Int
```

`@file:CInclude("header.h")` is a real `#include` in the generated C file (headers next to your sources are found), `@file:CLink("m")` adds `-lm` to the link line, and `minic build ... --c-src helper.c` compiles and links your own C file. The LLVM backend takes the declarations from the Kotlin signatures and needs no headers. Only exported symbols can be called, not macros or `static inline` functions.

## Memory and pointers

Pointers, `IntVar` and the other variables, and structs are plain machine addresses: a `CPointer` is one `void *`, never an object, never reference counted. They cannot be stored in `Any`, `List` or other generic code (a clear error tells you).

* variables: `ByteVar ShortVar IntVar LongVar FloatVar DoubleVar BooleanVar CPointerVar<T>`, read and written with `.value`; `x.ptr` is the address, `p.pointed` the variable at an address, `p.reinterpret<T>()` a cast
* arrays and arithmetic: `allocArray<IntVar>(n)`, `p[i]`, `p[i] = v`, `p + n`, `p - n`, `p - q`
* `sizeOf<T>()` and `alignOf<T>()`
* `memScoped { ... }` frees everything allocated in it when it ends, `nativeHeap.alloc<T>()` / `nativeHeap.free(x)` is malloc and free; both zero the memory
* `"text".cstr.ptr` (inside `memScoped`) and `p.toKString()` convert UTF-8 C strings

## Structs

```kotlin
@file:Suppress("WRONG_MODIFIER_TARGET")

@CStruct("timespec")
external class Timespec : CStructVar {
    var tv_sec: Long
    var tv_nsec: Long
}
```

The properties are the fields in declaration order; the compiler lays them out with the C rules (natural alignment, padding) and `sizeOf<Timespec>()` agrees with the C compiler. A `val` property of another struct type is embedded in place, `@CArray(16) val name: CPointer<ByteVar>` is an array in place, and `@CStruct("name", size = 64, align = 8)` overrides the computed size or alignment. Arrays of structs: `recs[i].field` reads the i-th element in place.

The K2 frontend does not accept `external class`; the `@file:Suppress("WRONG_MODIFIER_TARGET")` line is what lets it, and the Mini frontend ignores it.

## Callbacks

`staticCFunction { a: Int, b: Int -> a + b }` is a C function pointer. The lambda cannot capture anything (a C pointer has no place for state): that is a compile error. Pass state through a `void *` argument the C library hands back. A reference to a top-level function works too: `staticCFunction(::compare)`. An exception that escapes a callback ends the program, because it cannot travel through C frames.

A function pointer is called like a function: `val f: CPointer<CFunction<(Int, Int) -> Int>> = ...; f(1, 2)`.

## Ownership

Pointers are checked like other owned values in `Strict` files ([memory model](/memory/)):

* `memScoped` is a `Scoped` allocator like `Arena`. A pointer into it that leaves the block (return, a field or global, an escaping lambda) is **M0105**.

<<< @/samples/guide/cinterop_escape.kt{kotlin}

* `nativeHeap` is a `Deallocating` allocator: a missing `free` is **M0102**, a second one **M0103**, a use afterwards **M0101**.
* A pointer returned by a C function is *externally managed*: no obligation, nobody may free it. `@Owned(free = "fn")` on the `external fun` says the caller owns the result and must release it by calling the C function `fn`, with the same M0102/M0103/M0101 checks:

```kotlin
@Owned(free = "free") @CName("malloc")
external fun cMalloc(n: Long): CPointer<ByteVar>?
@CName("free")
external fun cFree(p: CPointer<ByteVar>?)
```

## Limits

* 64-bit targets only (pointers are 8 bytes); the 32-bit LLVM prototype has no interop.
* No unsigned integer types, no structs or unions by value, no bit fields, no C varargs through Kotlin `vararg`.
* `@CName` on a non-external function (exporting Kotlin code to C) and a binding generator from headers are planned, not done.
