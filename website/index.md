---
layout: home
title: Mini
titleTemplate: Kotlin, compiled to native code
hero:
  name: Mini
  text: Kotlin, compiled to native code
  tagline: Write ordinary Kotlin and get a small, fast native binary. When you need control, opt into manual memory management that the compiler checks for you.
  actions:
    - theme: brand
      text: Learn Mini
      link: /learn/
    - theme: alt
      text: Install
      link: /install
    - theme: alt
      text: Playground
      link: /playground
features:
  - icon: { src: /icons/kotlin.svg }
    title: Kotlin syntax, 1:1
    details: Same grammar, same semantics, same standard library source. Classes, data, sealed and enum types, generics, lambdas, inline and reified, exceptions, extensions and collections all compile natively. Every Mini program is valid Kotlin (bar one operator).
    link: /learn/basics
    linkText: Start the tutorial
  - icon: { src: /icons/speed.svg }
    title: Native speed
    details: Kotlin goes through C17 or LLVM to clang, with no JVM and no garbage collector. About 1.0 to 1.3 times the time of C on five of eight benchmarks, 1 ms start-up, 80 to 210 KiB binaries.
    link: /benchmarks
    linkText: Benchmarks
  - icon: { src: /icons/shield.svg }
    title: A memory model the compiler checks
    details: Reference counting by default, allocators as context parameters, and Strict mode where leaks, double frees and use after free are compile errors with stable codes.
    link: /learn/memory-strict
    linkText: Try the checker
  - icon: { src: /icons/link.svg }
    title: C interop
    details: Call C directly with the API of Kotlin/Native's cinterop. Structs, callbacks, variadics, and pointers that the memory checker tracks like any other owned value.
    link: /learn/c-interop
    linkText: C interop
  - icon: { src: /icons/browser.svg }
    title: Runs in your browser
    details: The whole compiler is built for WebAssembly. Every example in these docs is editable and runnable, with the output right below it. Nothing to install to learn Mini.
    link: /playground
    linkText: Open the playground
  - icon: { src: /icons/cycle.svg }
    title: Self-hosted
    details: The compiler is written in Kotlin and compiles itself, byte for byte (stage 2 equals stage 3). The release needs no JDK, and a C compiler alone can bootstrap it from a generated seed.
    link: /install#build-from-source
    linkText: Build from source
---

<div class="home-body">

## Train it. Draw it. Try it.

Teach a model to recognize handwritten digits, then test it with your own mouse or touch input. The [**digit lab**](/libraries/digits) downloads real data over HTTPS, compares raw matrix multiplication with **Linear**, and shows three **mini.plot** APIs — all in an editable Mini notebook in your browser.

[Open the digit lab →](/libraries/digits) · [Explore the libraries](/libraries/)

## Leaks are compile errors

Switch a file to Strict mode and the compiler tracks every allocation. This program forgets to release `buf` on the early-return path. Press **Check**: the checker runs in your browser.

<<< @/samples/memory/leak.kt{kotlin}

The fix is to release on that path too ([the fixed program](/learn/memory-strict#a-leak) is checked in CI like every example on this site). The same analysis finds [double frees](/reference/diagnostics/M0103), [use after free](/reference/diagnostics/M0101), [use after move](/reference/diagnostics/M0104) and [arena escapes](/reference/diagnostics/M0105).

## Plain Kotlin, native binary

<<< @/samples/guide/hello.kt{kotlin}

```sh
minic build hello.kt -o hello && ./hello
```

Mini translates Kotlin through C17 (or LLVM IR) and links a small C runtime. There is no JVM at run time and no garbage collector. See [how it works](/internals/).

## Where next

- **New to Mini?** [Learn Mini](/learn/) is a step-by-step tutorial with live cells and exercises.
- **Want it on your machine?** [Install](/install), then the [`minic`](/reference/minic) and [`mini`](/reference/mini) references.
- **Memory model?** The [memory guide](/memory/) and the [diagnostics reference](/reference/diagnostics/).
- **Under the hood?** [How it works](/internals/), the [benchmarks](/benchmarks) and the [roadmap](/roadmap).

::: tip Status
Mini is a working research compiler. Linux and macOS are supported; Windows, coroutines and a native `mini` tool are [planned](/roadmap). Strict mode is checked today, but native code generation for programs that use allocator types is still in progress. See [Roadmap and status](/roadmap) for the honest list.
:::

</div>
