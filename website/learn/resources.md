---
title: "19. Resources and error paths"
notebook: true
learnNotebook: true
aside: false
---

# 19. Resources and error paths

Memory is one resource among many. Files, foreign buffers and transactions can need explicit cleanup even when the wrapper object is still reachable. This chapter separates acquiring a resource, using it, releasing it and deciding how to report failure.

## Observe structured cleanup

We can demonstrate the control-flow pattern with an ordinary Managed object implementing `AutoCloseable`. Its `close` method prints a message so the order is visible.

```kotlin
class TraceResource(val name: String) : AutoCloseable {
    override fun close() {
        println("close $name")
    }
}

fun workWithResource(fail: Boolean): String {
    TraceResource("input").use { resource ->
        println("use ${resource.name}")
        if (fail) throw IllegalStateException("bad input")
        return "done"
    }
}

fun main() {
    println(workWithResource(false))
    try {
        workWithResource(true)
    } catch (error: IllegalStateException) {
        println("caught ${error.message}")
    }
}
```
```output
use input
close input
done
use input
close input
caught bad input
```

On success, cleanup happens before the returned string is printed. On failure, cleanup happens before the caller catches the exception. The `use` block ties acquisition to a lexical region instead of relying on one manually placed cleanup call after the work.

This Managed example demonstrates resource protocol behavior. It does not prove anything about the exact instant an ordinary object's memory is reclaimed. Calling `close` and releasing an allocation are separate concepts unless the memory model gives them a specific relationship.

## Return before acquiring when possible

Validation that does not need a resource can run before acquiring it. This reduces both work and the number of paths that must clean up.

```kotlin
class TracedSession : AutoCloseable {
    init { println("open") }
    override fun close() { println("close") }
}

fun processCount(count: Int): Int {
    if (count < 0) return 0
    return TracedSession().use { count * 2 }
}

fun main() {
    println(processCount(-1))
    println(processCount(3))
}
```
```output
0
open
close
6
```

The first call does not open anything. The second call creates a resource, computes a result and closes it. This is an intentional policy for an educational example; a production API might report negative input instead of returning zero.

When reviewing a function, mark the point at which each resource is acquired. Only paths after that point carry its cleanup obligation. Doing this on paper is often enough to reveal an early return that skips release.

## Strict ownership gives cleanup another role

In Mini's Strict model, `free` on an `AutoCloseable` owner calls `close` and then releases owned fields and the allocation. `use` ends the ownership at the end of the block; inside the block the value is borrowed.

<<< @/samples/memory/use_closeable.kt{kotlin}

Press Check, then follow the two resources separately. The first is bound to `use`; the second is explicitly freed. Both paths discharge responsibility once. Do not add another `free` after `use` in an attempt to be extra careful: that would try to end an ownership that has already ended.

A resource's `close` body should release the foreign handle or perform the protocol-specific cleanup it owns. It should not manually free tracked fields that Mini's owning-field cleanup already releases.

## Multiple resources close in reverse nesting order

```kotlin
class Trace(val name: String) : AutoCloseable {
    init { println("open $name") }
    override fun close() { println("close $name") }
}

fun main() {
    Trace("outer").use {
        Trace("inner").use {
            println("work")
        }
    }
}
```
```output
open outer
open inner
work
close inner
close outer
```

The inner block ends first. This order is useful when the inner resource depends on the outer one. If opening the inner resource fails, the outer `use` still has a cleanup path.

Ownership does not automatically define what to do if cleanup itself throws. Consult the relevant resource API and avoid hiding an original failure with an unrelated cleanup error. The educational `Trace` objects here deliberately have non-throwing `close` methods.

## Foreign memory needs a matching release contract

A pointer returned by C may be borrowed, caller-owned, static or valid only until the next API call. Its type as an address does not reveal all of that. Read the API documentation before choosing a wrapper.

Pair the correct allocation and release functions. A pointer allocated by one library might need that library's release function, not an arbitrary `free`. Mini can track an annotated owned foreign result, but an incorrect foreign annotation gives it an incorrect premise.

Copying a pointer does not copy the pointed-to bytes. Copying bytes out into an owned result can be a useful boundary, especially when a scoped buffer is about to close. Keeping an address from `memScoped` after the scope ends is the same kind of region escape as keeping an arena object.

## Exercise: keep cleanup on early return

The function should return `empty` for zero and `value=N` otherwise. Both paths must print `close resource` before the caller prints the result. The starter returns early before reaching its manual cleanup call.

```kotlin exercise
class ExerciseResource : AutoCloseable {
    override fun close() { println("close resource") }
}

fun describeCount(count: Int): String {
    val resource = ExerciseResource()
    if (count == 0) return "empty"
    resource.close()
    return "value=$count"
}

fun main() {
    println(describeCount(0))
    println(describeCount(2))
}
```
```output
close resource
empty
close resource
value=2
```

::: details Hint
Move the work into `ExerciseResource().use { ... }` so both returns leave the same structured cleanup region.
:::

::: details Show solution
```kotlin
class ExerciseResource : AutoCloseable {
    override fun close() { println("close resource") }
}

fun describeCount(count: Int): String = ExerciseResource().use {
    if (count == 0) "empty" else "value=$count"
}

fun main() {
    println(describeCount(0))
    println(describeCount(2))
}
```
```output
close resource
empty
close resource
value=2
```
:::

## Review checklist

For each resource, identify its acquisition point, the code allowed to use it, its release operation and every exit from that region. Then ask whether a returned value or callback contains a reference into the resource. A function that closes its resource but returns an invalid view has still broken its contract.

Continue to the [native C boundary](./c-interop) for representation and pointer examples. Mini's [ownership reference](/memory/ownership) defines the additional meaning of `use`, `close` and `free` for tracked owners.
