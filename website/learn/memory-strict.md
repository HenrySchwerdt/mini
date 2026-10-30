---
title: "17. Strict mode and ownership"
notebook: true
learnNotebook: true
aside: false
---

# 17. Strict mode and ownership

Managed code releases ordinary acyclic objects automatically; cycles can still leak. Strict code gives you full control and takes the safety net away, and in exchange **the compiler proves you did it right**. A file opts in with its first line:

```kotlin norun
@file:MemoryMode(Mode.Strict)

import mini.memory.*
```

In Strict mode every allocation has exactly one **owner**, and the owner must release it exactly once on every path, or hand it on. If the compiler cannot prove that, the program does not compile. Everything below is a live **Check** cell: edit the code and press Check to watch the checker answer. Diagnostics carry a stable code such as `M0102`; click it for the [reference page](/reference/diagnostics/).

**By the end:** follow an owned allocation through every exit path, distinguish borrowing from moving, and repair a leak without creating a double free. These are checker experiments: an expected diagnostic is a result to understand, not an instruction to execute unsafe code.

## A leak

This function forgets to release `buf` on the early-return path:

<<< @/samples/memory/leak.kt{kotlin}

Press **Check**. The error names the allocation (`allocated here`), the path that leaks (`early return`) and a suggested fix. The checker follows control flow: the path where `fill(buf)` is false returns `null` while `buf` is still owned. The fix releases on that path too. Edit the cell above and add `buf.free()` before `return null` to see the error go away, or check the fixed program:

<<< @/samples/memory/leak_fixed.kt{kotlin}

On the other path `return buf` **moves** the ownership to the caller (the return type is `@Owned`), and the caller frees it.

## Use after free

<<< @/samples/memory/use_after_free.kt{kotlin}

[M0101](/reference/diagnostics/M0101). Once `b.free()` has run, `b` is dead. The error is at the use, not at the free.

## Double free

```kotlin check=M0103
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    context(Heap()) {
        val b = Box(1)
        b.free()
        b.free()               // freed twice
    }
}
```

[M0103](/reference/diagnostics/M0103). Without the checker this is the classic memory corruption bug. Here it is one line of red text.

## Moving ownership

A plain assignment only **borrows**: `val alias = a` leaves `a` the owner. To hand the obligation to someone else, move it explicitly. After a move the old name is dead:

<<< @/samples/memory/use_after_move.kt{kotlin}

[M0104](/reference/diagnostics/M0104). The accepted version does not touch `a` after the move:

<<< @/samples/memory/move.kt{kotlin}

## Borrowing and owning parameters

Parameters are **borrowed** by default: the callee may read the object but not free it. A parameter marked `@Owned` takes over the obligation:

<<< @/samples/memory/borrowed_param.kt{kotlin}

Freeing a borrowed parameter is an error, [M0107](/reference/diagnostics/M0107):

<<< @/samples/memory/free_borrowed.kt{kotlin}

## Resources: `use {}`

`AutoCloseable` objects can be released with `use { }`, which runs `close()` on every path out of the block, including `return` and exceptions:

<<< @/samples/memory/use_closeable.kt{kotlin}

## No allocation at all

`@NoAlloc` promises that a function does not allocate. An object that does not escape lives on the stack and is allowed; one that escapes would be a real allocation and is an error. This one is a complete program, so it **runs** too:

<<< @/samples/memory/noalloc.kt{kotlin}

Press **Run** and it prints `5`. This variant escapes the object and is rejected:

<<< @/samples/memory/noalloc_error.kt{kotlin}

## What the checker does not do

It follows references to instances of classes declared in a Strict file (and the `Arena` and `Pool` resources). `Int`, `String` and objects of Managed classes carry no obligations. Escape analysis for stack placement (M0201, M0203) is specified but not implemented. The [Strict mode reference](/memory/strict) and the [diagnostics reference](/reference/diagnostics/) list every rule and code, and [the checker internals](/internals/checker) explain how it works.

## Checkpoint

If one branch returns early, where must an allocation be released? Is `val alias = owner` an ownership transfer? Can you free a borrowed parameter?

::: details Reasoning
Every exit path must discharge or transfer the ownership obligation. An alias only borrows; `move()` explicitly transfers ownership. Borrowed parameters do not grant permission to free the object.
:::

## Exercise

::: tip Exercise: fix the leak
The checker reports [M0102](/reference/diagnostics/M0102) because one branch forgets to free. Fix it so the checker accepts the program. Hint: both branches have to end the ownership of `b`.
:::

```kotlin exercise check=M0102
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    context(Heap()) {
        val b = Box(5)
        if (b.v > 3) {
            println("big")
            b.free()
        } else {
            println("small")
        }
    }
}
```

::: details Show solution
```kotlin check
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    context(Heap()) {
        val b = Box(5)
        if (b.v > 3) {
            println("big")
        } else {
            println("small")
        }
        b.free()               // one release after the branches covers both paths
    }
}
```
:::
