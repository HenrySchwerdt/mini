# 1. Managed by default

A file without `@file:MemoryMode` is **Managed**: plain Kotlin with no obligations.

<<< @/samples/memory/managed.kt{kotlin}

This program is built and run natively in CI:

<<< @/samples/memory/managed.out{text}

You never free anything. Each object has a reference count in its header; storing a reference retains it, dropping one releases it, and when the count reaches zero the object releases its own fields and its memory is returned. The `Account` objects above survive `accounts.clear()` because the map still holds them.

## What the compiler does for you

Naive reference counting is slow because it touches the count constantly. The `RcOpt` pass removes most of that work without changing what your program does: reads of fields and traversal variables become borrows, the last use of a local moves its reference instead of retaining a second one, and constructor parameters that are stored are passed owned. On the project's benchmarks this removes 60 to 100 percent of the retains. See [Reference counting and RcOpt](/internals/refcounting) and [Benchmarks](/benchmarks).

The counting is non-atomic while an object stays on one thread and becomes atomic when it is shared (the `MK_FLAG_SHARED` flag), so single-threaded code does not pay for atomics.

## Why and when to manage memory yourself

Managed mode is the right default. Move to the next tiers when:

- **You have cycles.** There is no cycle collector. A parent pointer, a doubly linked list or a closure stored in the object it captures leaks. An arena or `@Borrowed` back references fix this.
- **Memory has a clear lifetime.** A compiler phase, an HTTP request or a game frame allocates thousands of small objects that all die together. An [arena](./arenas) allocates by bumping a pointer and frees everything in one step, with no per-object counting.
- **You need predictable behavior.** `@NoAlloc` functions and manual `free()` give you control over when memory is released ([Strict mode](./strict)).
- **You want a proof, not a hope.** In Strict mode a missing release is a compile error instead of a leak found in production.

The tiers mix. A Strict file can call Managed code and the other way around, with rules at the boundary described in [Strict mode](./strict#strict-and-managed-code).

## Rules of Managed mode, in short

- Every reference-typed local owns one reference and releases it at every exit of its scope.
- Parameters are borrowed: the caller keeps the object alive for the call.
- String literals and `object` singletons are immortal; retaining them does nothing.
- **Reference cycles leak.** This is a known limitation of the current runtime.

Next: [Allocators and context parameters](./allocators).
