---
layout: home
title: Mini
hero:
  name: Mini
  text: Kotlin, compiled to native code
  tagline: Write ordinary Kotlin. Get a native binary through C and clang. Opt into manual memory management that the compiler can check.
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: Memory guide
      link: /memory/
    - theme: alt
      text: GitHub
      link: https://github.com/HenrySchwerdt/mini
features:
  - title: Kotlin, 1:1
    details: Same grammar, same semantics, same standard library source. Classes, data/sealed/enum/object, generics, lambdas, inline/reified, exceptions, extensions and collections all compile natively. Every Mini program is valid Kotlin.
    link: /tour/
    linkText: Language tour
  - title: Three memory tiers
    details: Managed (reference counting, the default), Arena/Heap/Pool allocators chosen with context parameters, and Strict mode where every allocation needs a proven owner. You pick the tier per file.
    link: /memory/
    linkText: Memory guide
  - title: Leaks are compile errors
    details: In Strict mode the checker finds leaks, double frees, use-after-free, use-after-move and arena escapes before the program runs, with rustc-style diagnostics and stable error codes.
    link: /reference/diagnostics/
    linkText: Diagnostics reference
---

## Leaks are compile errors

Switch a file to Strict mode and the compiler tracks every allocation. This program forgets to release `buf` on the early-return path:

<<< @/samples/memory/leak.kt{kotlin}

```text
$ ./minic check website/samples/memory/leak.kt
error[M0102]: allocation from `Heap` is never released
  --> website/samples/memory/leak.kt:13:15   allocated here; path: early return
   13 |     val buf = Buffer(n)
      |               ^
  help: call `buf.free()` on every path (or `buf.use { ... }` for AutoCloseable), or move it to an owner
```

The fix is to release on that path too ([the fixed program](https://github.com/HenrySchwerdt/mini/blob/main/website/samples/memory/leak_fixed.kt) is checked in CI like every sample on this site). The same analysis finds [double frees](/reference/diagnostics/M0103), [use after free](/reference/diagnostics/M0101), [use after move](/reference/diagnostics/M0104) and [arena escapes](/reference/diagnostics/M0105).

## Plain Kotlin, native binary

<<< @/samples/guide/hello.kt{kotlin}

```sh
./minic build hello.kt -o hello && ./hello
```

Mini translates Kotlin through C17 and links a small C runtime. There is no JVM at run time and no garbage collector. See [how it works](/internals/).

::: tip Status
Mini is a working research compiler. Linux and macOS are supported; Windows, coroutines, C interop and an LLVM backend are [planned](/roadmap). Strict mode is checked today, but native code generation for programs that use allocator types is still in progress. See [Roadmap and status](/roadmap) for the honest list.
:::
