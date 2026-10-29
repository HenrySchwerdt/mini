# Memory guide

Kotlin on the JVM has a garbage collector, so you never think about who owns an object. Mini has none. Instead it gives you three tiers, and you choose per file (or per class) how much control you want and how much the compiler checks for you.

| Tier | You write | The compiler does |
|---|---|---|
| **Managed** (default) | ordinary Kotlin | reference counting; removes most of the counting work (RcOpt) |
| **Allocators** | `context(arena) { ... }`, `Arena`, `Heap`, `Pool` | places each allocation in the allocator in scope |
| **Strict** | `@file:MemoryMode(Mode.Strict)`, `@Owned`, `x.free()` | proves that every allocation has an owner and is released exactly once, or refuses to compile |

This guide goes from the first to the last. Each page builds on the previous one.

1. [Managed by default](./managed): what you get for free, and when you want more.
2. [Allocators and context parameters](./allocators): choosing where objects live.
3. [Arenas and Scoped allocators](./arenas): everything dies together.
4. [Strict mode](./strict): turning the checker on.
5. [Ownership](./ownership): owned and borrowed, `free()`, `use {}`, moves.
6. [Fields and containers](./fields): owned values inside objects.
7. [Lambdas and captures](./lambdas)
8. [Threads and ThreadSafe](./threads)
9. [`@NoAlloc`](./noalloc)

The reference is the [memory model specification](https://github.com/HenrySchwerdt/mini/blob/main/docs/memory-model.md); every diagnostic has a page in the [diagnostics reference](/reference/diagnostics/).

## Every Mini program is valid Kotlin

The memory features are not new syntax. Allocators are classes in the package `mini.memory`, ownership is expressed with annotations (`@Owned`, `@Borrowed`), `free()` and `move()` are ordinary extension functions, and allocator selection uses Kotlin's context parameters (`context(a: Arena)`, Kotlin 2.2+). The whole test corpus is also compiled with the real `kotlinc`, so IDEs, formatters and highlighting work unchanged, and on the JVM the allocators are naive stand-ins.

## How the samples are checked

Every listing in this guide is a file under `website/samples/memory/`, and CI runs the compiler on each of them. Memory samples are verified with `./minic check`: either the checker accepts the program, or it rejects it with exactly the error code stated on the page.

::: warning Current status of Strict mode
The **checker** implements the rules of the specification (a few are deliberately approximate, see the limits on each page) and is tested against a corpus of 56 programs with expected diagnostics and 33 accepted programs. **Native code generation** for programs that use `Heap`, `Arena` or `Pool` is not finished: `minic build` currently stops while compiling `mini.memory` (`unsupported construct: value class 'NativePtr'`). Programs that are Managed, or Strict but without allocator types, build and run natively; the samples that do so have an output shown on the page. The rest are shown with the checker's verdict only. This is tracked as milestone D2 on the [roadmap](/roadmap).
:::
