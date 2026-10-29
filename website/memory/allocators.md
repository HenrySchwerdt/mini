# 2. Allocators and context parameters

Every constructor call `Foo(...)` is an **allocation site**. In Managed code the allocator is always `Rc` (reference counting). To choose another one, you put an allocator in scope.

## Allocators are ordinary classes

They live in `mini.memory` (Kotlin source in `stdlib/mini/memory/`). What the compiler checks depends on which **traits** (plain Kotlin interfaces) an allocator implements:

| Trait | Meaning | Compiler checks |
|---|---|---|
| `Deallocating` | objects are released one by one | every allocation is freed exactly once on every path, or moved on |
| `Scoped` | everything dies when the allocator closes | no object leaves the scope (see [Arenas](./arenas)) |
| `Managed` | reference counting | nothing, optimization only |
| `ThreadSafe` | marker: may be used by several threads | objects of other allocators must not cross to another thread ([Threads](./threads)) |

| Allocator | Traits | Notes |
|---|---|---|
| `Heap` | `Deallocating`, `ThreadSafe` | malloc/free. Stateless: `Heap()` is free and carries no obligation |
| `Arena` | `Scoped` (so `AutoCloseable`) | bump allocator; must be closed; not thread-safe |
| `Pool<T>(capacity)` | `Deallocating`, `AutoCloseable` | one block size, free list; must be closed; not thread-safe |
| `Rc` | `Managed`, `ThreadSafe` | the default of Managed code |
| `DebugAllocator(inner)` | `Deallocating`, `ThreadSafe` | wraps another allocator and reports leaks and double frees with the allocation site at run time |

## Context parameters

An allocator is put in scope with a Kotlin context parameter. Either on a function:

```kotlin
context(h: Heap)
fun load(n: Int): Buffer = Buffer(n)       // allocated from `h`
```

or with a block:

```kotlin
context(Heap()) {                           // everything allocated in here uses this Heap
    val b = Buffer(8)
    b.free()
}
```

The next sample shows both. `range` allocates from whatever arena its caller provides; the caller decides where the nodes live:

<<< @/samples/memory/context_fn.kt{kotlin}

The checker accepts this program (verified in CI with `./minic check`). It prints `10` on the JVM.

## How an allocation site picks its allocator

The first rule that applies wins:

1. **A context allocator.** The innermost visible `context(...)` of an allocator type is used. If two allocator parameters are visible at the same level (`context(a: Arena, h: Heap)`), the choice is ambiguous: [M0114](/reference/diagnostics/M0114). Nest blocks instead.
2. **`@AllocatedBy(X::class)` on the class.** This restricts rule 1 to context allocators of type `X`. Without a matching context, only `Heap` and `Rc` have a global default instance; `Arena` and `Pool` always need a context.
3. **The module default.** In a Managed file that is `Rc`. In a Strict file there is none.
4. **Nothing found.** If the value does not escape, the specification says it lives on the stack with no obligations (stack placement itself is not implemented yet, so today it is simply not an error). If it escapes, the compiler reports [M0204](/reference/diagnostics/M0204).

"Escapes" means the value flows into a return, an `@Owned` parameter, a field, a global, an escaping lambda or another thread. Passing to a borrowed parameter and calling methods do not make a value escape.

The allocator is decided by the file or class where the **class is declared**, not by the caller: a Strict class constructed from Managed code still follows rules 1 and 2, and otherwise gets `Rc`.

## `@AllocatedBy`

<<< @/samples/memory/pool.kt{kotlin}

`Item` can only be allocated from a `Pool` in scope. Pool objects are freed one by one (`a.free()`), and the pool itself is a resource that `use {}` closes. The checker accepts the program.

## What actually happens at run time

Heap objects are one block with a hidden prefix that remembers the allocator they came from, so `x.free()` does not need to be told. `Arena` is a bump allocator (chunks double up to 4 MiB), `Pool` a fixed-size free list. The details are in [The runtime](/internals/runtime).

Next: [Arenas and Scoped allocators](./arenas).
