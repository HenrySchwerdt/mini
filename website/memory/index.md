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

Every listing in this guide is a file under `website/samples/memory/`, and CI runs the compiler on each of them. Accepted samples are built, run and compared with their expected output; rejected samples are verified with `./minic check` against exactly the error code stated on the page.

::: tip Current status of Strict mode
The **checker** implements the rules of the specification (a few are deliberately approximate, see the limits on each page) and is tested against a corpus of 56 programs with expected diagnostics and 33 accepted programs. Programs that use `Heap`, `Arena` and `Pool` build and run natively (C and LLVM backends), so every accepted sample has a Run cell. Only the samples that use `spawn` stay check-only, because threads are not available yet.
:::
