---
title: "7. Allocators and arenas"
notebook: true
---

# 7. Allocators and arenas

In Managed code every `Foo(...)` allocates with reference counting. To decide *where* objects come from, you put an **allocator** in scope and the compiler uses it for every allocation site inside.

::: info These cells are Check cells
Programs that use allocator objects are checked by the compiler today, and native code generation for them is still in progress (see the [roadmap](/roadmap)). So the cells in this chapter and the next have a **Check** button instead of **Run**: it runs the same memory checker as `minic check`, in your browser.
:::

## Allocators are ordinary classes

They live in `mini.memory`:

| Allocator | Frees | Notes |
|---|---|---|
| `Heap` | object by object, with `free()` | malloc and free |
| `Arena` | everything at once, when it is closed | bump allocator, the cheapest |
| `Pool<T>(capacity)` | object by object, `T` blocks only | fixed-size free list |
| `Rc` | automatically | the Managed default |

## Allocators are context parameters

An allocator is not a hidden global: it is a **context parameter**. A function declares which allocator it allocates from, and the caller decides.

<<< @/samples/memory/context_fn.kt{kotlin}

`range` is declared `context(a: Arena)`. It builds a linked list in whichever arena its caller provides. `main` creates the arena, `use {}` closes it at the end of the block, and `context(arena) { ... }` puts it in scope. Press **Check**: the checker accepts the program (it prints `10` when run on the JVM).

A `context(...) { }` block can also name an allocator directly, which is how a `Heap` is used:

<<< @/samples/memory/heap_free.kt{kotlin}

`Box(41)` inside the block allocates from the `Heap`, so `b` owns it, and `b.free()` ends the ownership. Delete the `b.free()` line, press **Edit** and then **Check** to see what happens. That is the subject of the next chapter.

## Arenas

An `Arena` allocates by bumping a pointer and frees **everything** when it closes. There is nothing to forget and nothing to double free. In return there is a lifetime rule: an object allocated in an arena must not outlive it.

<<< @/samples/memory/arena_copy_out.kt{kotlin}

The `Int` leaves the arena block, the `Point`s do not. The checker accepts that. This is the program that breaks the rule:

<<< @/samples/memory/arena_escape.kt{kotlin}

[M0105](/reference/diagnostics/M0105): the `Box` lives in `arena`, which is gone when `build` returns. Without the checker this would be a dangling pointer. Allowed: passing arena objects inward, copying out values that need no allocation, and a `context(a: Arena)` function returning arena objects to its caller (the region is then the caller's).

## Pools and `@AllocatedBy`

A `Pool` hands out blocks of one class from a free list. `@AllocatedBy(Pool::class)` says that a class can only be allocated from one:

<<< @/samples/memory/pool.kt{kotlin}

## A warning, not an error

Allocating in a loop inside an arena is legal, but nothing is reclaimed before the arena closes. The checker tells you with the warning [M0202](/reference/diagnostics/M0202):

<<< @/samples/memory/arena_loop.kt{kotlin}

Warnings do not fail a build unless you pass `-Werror`.

## Exercise

::: tip Exercise: keep the arena object inside
This function returns an object that lives in an arena that is closed by then, error [M0105](/reference/diagnostics/M0105). Change it so the checker accepts it: return the plain `Int` instead of the `Box`.
:::

```kotlin exercise check=M0105
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun build(): Box {
    Arena().use { arena ->
        context(arena) {
            return Box(7)
        }
    }
}

fun main() {
    println(build().v)
}
```

::: details Show solution
```kotlin check
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun build(): Int {
    Arena().use { arena ->
        context(arena) {
            return Box(7).v      // an Int may leave the arena, the Box may not
        }
    }
}

fun main() {
    println(build())
}
```
:::

Next: [Strict mode](./memory-strict). The reference chapters are [Allocators and context](/memory/allocators) and [Arenas and Scoped](/memory/arenas).
