---
title: "9. C interop"
notebook: true
---

# 9. C interop

Mini calls C directly: no wrapper layer, no generated glue. The API is the one of Kotlin/Native's `kotlinx.cinterop`, so code written for it mostly works unchanged.

::: warning Not in the browser
The in-browser compiler produces WebAssembly with no C libraries and no system calls beyond standard output, so a program that calls into C cannot run here. The listings in this chapter are plain code. To run them, [install Mini](/install). The **Check** cell at the end works in the browser, because the memory checker needs no C at all.
:::

## Declaring and calling C functions

An `external fun` with `@CName("symbol")` is a direct call of the C function:

```kotlin norun
@file:CInclude("time.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("strlen")
external fun strlen(s: CPointer<ByteVar>): Long

fun main() {
    memScoped {
        println(strlen("héllo".cstr.ptr))     // 6: the length of the UTF-8 bytes
    }
}
```

`@file:CInclude("header.h")` becomes a real `#include` in the generated C, `@file:CLink("m")` adds `-lm` to the link line and `--c-src helper.c` compiles your own C file into the program. Only exported symbols can be called, not macros or `static inline` functions.

Types map one to one:

| Kotlin | C |
|---|---|
| `Byte`, `Short`, `Int`, `Long` | `int8_t`, `int16_t`, `int32_t`, `int64_t` |
| `Float`, `Double` | `float`, `double` |
| `Boolean`, `Unit` | `bool`, `void` |
| `CPointer<T>`, `COpaquePointer` | `T *`, `void *` |
| `CPointer<CFunction<(Int) -> Int>>` | `int (*)(int)` |

Strings, classes and structs by value have no C representation. Unsigned types do not exist yet.

## Memory and callbacks

Pointers are plain machine addresses, never reference counted. `memScoped { ... }` frees everything allocated inside when it ends, and `staticCFunction { ... }` turns a non-capturing lambda into a C function pointer. This is the complete sample the documentation CI builds and runs natively: it calls `strlen`, sorts an array with `qsort` and a Kotlin comparator, and fills a `struct tm` with `gmtime_r`:

<<< @/samples/guide/cinterop.kt{kotlin}

Its output:

<<< @/samples/guide/cinterop.out{text}

Structs are declared as `external class` with `@CStruct("tm")` and laid out with the C rules. The [C interop guide](/guide/c-interop) has the full list.

## The memory checker knows about C memory

In a Strict file the pointer rules are the same as for every other owned value. `memScoped` is a scoped allocator like `Arena`: a pointer into it must not leave the block. This one you can check right here:

<<< @/samples/guide/cinterop_escape.kt{kotlin}

[M0105](/reference/diagnostics/M0105). And a pointer returned by C is *externally managed* unless you say otherwise: `@Owned(free = "free")` on the `external fun` makes the caller the owner, with the same leak, double free and use-after-free checks as for `Heap`.

## Limits

64-bit targets only. No unsigned types, no structs by value, no bit fields. Exporting Kotlin functions to C and a binding generator from headers are planned. The [roadmap](/roadmap) is the honest list.

Next: [Performance](./performance).
