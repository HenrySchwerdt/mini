---
title: "15. Draw reference graphs and lifetimes"
notebook: true
learnNotebook: true
aside: false
---

# 15. Draw reference graphs and lifetimes

A memory diagram answers a different question from a variable list. A variable is a name in your program; an object is an allocation; a reference connects a name or another object to that allocation. Draw those separately before deciding what should be released.

We will follow sharing, copies, captures and cycles. These examples use Managed mode, so you can focus on the graph before introducing explicit ownership.

## Two names, one object

```kotlin
class Reading(val value: Int)

fun main() {
    val first = Reading(7)
    val alias = first
    val equalLooking = Reading(7)
    println(first === alias)
    println(first === equalLooking)
    println("${alias.value}, ${equalLooking.value}")
}
```
```output
true
false
7, 7
```

Draw `first` and `alias` as two arrows to the same box. Draw `equalLooking` as an arrow to a second box. Matching field values do not merge allocations.

A function parameter often adds a temporary way to access an existing object rather than creating a new object. Returning a reference is also different from cloning the referenced object. Ask what the function actually constructs, not how many names appear in its source.

In Managed mode the runtime releases an object once no counted references keep it alive. The compiler can remove unnecessary counting operations, so a diagram of every conceptual reference is not an exact list of generated retain calls. It is still a useful model of who can reach an object.

## Containers keep elements alive

```kotlin
class Sample(val name: String)

fun main() {
    val sample = Sample("temperature")
    val queue = mutableListOf(sample)
    val archive = mutableMapOf("latest" to sample)
    queue.clear()
    println(queue.size)
    println(archive["latest"]?.name)
    archive.clear()
    println(sample.name)
}
```
```output
0
temperature
temperature
```

Initially the local, list and map can all reach the same sample. Clearing the list removes only its edges. Clearing the map removes another path. The local still refers to the sample, which explains the final access.

Do not infer release from a container becoming empty, or from one local leaving a scope. There may be other references. Conversely, a cache that grows forever may keep perfectly reference-counted objects alive forever: the issue is a long-lived root, not a failed decrement.

A leak investigation therefore begins with “what still reaches this object?” A count of allocations alone does not answer that question.

## Closures retain what they need

```kotlin
class CounterState(var value: Int)

fun makeIncrementer(): () -> Int {
    val state = CounterState(0)
    return {
        state.value++
        state.value
    }
}

fun main() {
    val increment = makeIncrementer()
    println(increment())
    println(increment())
}
```
```output
1
2
```

The local name `state` belongs to a function call that has returned. The object remains reachable because the returned closure captures it. That is required for the program to work; it is not itself a leak.

Now imagine storing that closure in a field of `state`. The state would keep the closure alive, and the closure would keep the state alive. This is a cycle. A callback can create an owning edge even if the code never spells a `parent` or `next` field.

Capture only the data a callback needs when possible. If a callback needs the integer value at creation time, copy that value out before constructing it. If it needs live access to an object, define who owns the callback and when it is removed.

## A cycle has no zero-count starting point

```kotlin
class Link(val label: String) {
    var next: Link? = null
}

fun main() {
    val a = Link("a")
    val b = Link("b")
    a.next = b
    b.next = a
    println(a.next?.label)
    println(b.next?.label)
    b.next = null
    a.next = null
    println("links disconnected")
}
```
```output
b
a
links disconnected
```

Before the last two assignments, draw `a -> b -> a`. If the outside names disappear while both edges remain, the objects keep each other's counts above zero. Mini currently has no cycle collector.

This example disconnects the links while the objects are still accessible. In a real application you need a lifecycle operation that always does so, including failure paths. Relying on someone remembering to clear a link eventually is a fragile design.

Other options are to represent relationships by IDs, give a group of objects one region lifetime, or use a carefully designed non-owning link. None of those choices follows automatically from changing `val` to `var` or adding a data-class copy.

## Scope lifetime and resource lifetime differ

An object can remain reachable after its file handle, socket or arena has been closed. A reference to a wrapper does not reopen the resource. Likewise, automatic memory release is not a reliable way to schedule domain actions such as committing a transaction.

Use explicit resource protocols and structured cleanup. In Mini's Strict model, ownership annotations and scoped allocators provide additional checking, but a Managed wrapper around a foreign resource still needs a clear API.

Separate these questions:

1. Can any code still reach the wrapper object?
2. Is the underlying resource still valid?
3. Who has the responsibility to close it?
4. Is a borrowed view permitted to outlive that responsibility?

Confusing these lifetimes produces bugs even in languages with garbage collection.

## Exercise: capture a snapshot

The returned function should report the value at creation time, even if the source changes later. The starter captures the mutable object instead of a snapshot.

```kotlin exercise
class Level(var value: Int)

fun snapshot(level: Level): () -> Int = { level.value }

fun main() {
    val source = Level(3)
    val read = snapshot(source)
    source.value = 9
    println(read())
    println(source.value)
}
```
```output
3
9
```

::: details Hint
Read the integer before returning the lambda. Capturing a primitive snapshot and capturing a mutable object are different contracts.
:::

::: details Show solution
```kotlin
class Level(var value: Int)

fun snapshot(level: Level): () -> Int {
    val saved = level.value
    return { saved }
}

fun main() {
    val source = Level(3)
    val read = snapshot(source)
    source.value = 9
    println(read())
    println(source.value)
}
```
```output
3
9
```
:::

## Checkpoint: draw before choosing an allocator

Draw a report owning tasks, a task pointing back to the report, and a callback stored on the task that captures the report. Which edges keep a cycle alive? Would copying only the task list structure remove those edges? Which relationship could instead be an ID or a short-lived borrow?

The answer depends on which access the application actually needs. The useful outcome is an explicit graph and lifecycle, not a particular annotation.

Mini references: [Managed memory](/memory/managed), [closures](/internals/closures), [reference counting](/internals/refcounting) and [borrowed fields](/memory/fields#back-references).
