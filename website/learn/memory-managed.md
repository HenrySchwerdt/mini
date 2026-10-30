---
title: "9. Memory: managed by default"
notebook: true
learnNotebook: true
aside: false
---

# 9. Memory: managed by default

From here on Mini differs from Kotlin. Kotlin on the JVM has a garbage collector. Mini has none: it has a **memory model with three tiers** and you choose the tier per file.

| Tier | Who frees memory | What the compiler checks |
|---|---|---|
| **Managed** (default) | reference counting, automatic | nothing, it is plain Kotlin |
| **Arena / Heap / Pool** | an allocator you pass in as a *context parameter* | lifetimes of arena objects |
| **Strict** | you, with `free()` and `use {}` | every allocation has exactly one owner and is released exactly once |

This chapter is the first tier, the one you have been using since chapter 1.


**By the end:** draw a small reference graph, explain why sharing is different from copying, and recognize a cycle that ordinary reference counting cannot release. You do not need to introduce explicit allocators to write useful Mini programs.

## Reference counting

Every object has a counter in its header. Storing a reference increments it, dropping one decrements it, and at zero the object releases what it holds and returns its memory. There is no tracing collection cycle. Releasing a large object graph still takes time, so automatic reference counting is not a promise of pause-free execution.

This program has two references to every `Account`. Clearing the list does not free them, because the map still holds them:

<<< @/samples/memory/managed.kt{kotlin}

Press **Run**: the output matches what the project's CI prints after building and running the same file natively. The cell is a complete program (it has its own `main`), so it does not share declarations with the cells around it.

References behave as in Kotlin: two names, one object.

```kotlin
class Box(var value: Int)

val a = Box(1)
val b = a            // a second reference, not a copy
b.value = 42
println(a.value)
```
```output
42
```

## What you do not have to think about

Naive reference counting would update counters all the time. Mini's `RcOpt` pass removes most of that: reads of fields become borrows, the last use of a local moves the reference instead of retaining it again, and a loop variable that walks a list does not retain each node. On the project's benchmarks 60 to 100 percent of the retains disappear. You can see the counters yourself on your machine with `minic build --stats` (see [Performance](./performance)).

## The one thing to know: cycles

A reference counter cannot see a cycle. If two objects point at each other, neither count reaches zero:

```kotlin
class Node(val name: String) {
    var other: Node? = null
}

val x = Node("x")
val y = Node("y")
x.other = y
y.other = x          // x -> y -> x: both objects leak when main ends
println("${x.other?.name} ${y.other?.name}")
```
```output
y x
```

The visible output is correct, but the cycle is a memory leak. There is no cycle collector yet. Redesign the graph to avoid a strong cycle, break a link when its logical lifetime ends, or investigate an arena for objects with one shared lifetime. The explicit ownership model also has borrowed references; they need a lifetime contract rather than an annotation pasted onto an arbitrary Managed graph. Both are in the next chapters.

## Choosing a tier per file

A file opts into a tier with an annotation on its first line:

```kotlin norun
@file:MemoryMode(Mode.Strict)
```

Without it the file is Managed. Tiers mix: a Strict file can call Managed code and the other way around, with rules at the boundary. You can also opt a single class back out of Strict:

<<< @/samples/memory/mixed_modes.kt{kotlin}

## Checkpoint

Why can clearing a list leave its elements alive? Can a two-object cycle remain allocated after both local names disappear? Does Managed mode mean your program cannot leak?

::: details Reasoning
Other references, such as a map, may keep the elements alive. A cycle keeps its own counts above zero even after outside references vanish. Managed code handles ordinary release automatically, but reference cycles and unintentionally retained objects can still leak.
:::

## Exercise

::: tip Exercise: shared state
In the starter `b` is a second reference to the same counter, so both lines print `6`. Change `main` so that `b` is its own `Counter` and the program prints `1` and `5`.
:::

```kotlin exercise
class Counter(var n: Int)

fun main() {
    val a = Counter(0)
    val b = a
    a.n += 1
    b.n += 5
    println(a.n)
    println(b.n)
}
```
```output
1
5
```

::: details Show solution
```kotlin
class Counter(var n: Int)

fun main() {
    val a = Counter(0)
    val b = Counter(a.n)     // a new object instead of a second reference
    a.n += 1
    b.n += 5
    println(a.n)
    println(b.n)
}
```
```output
1
5
```
:::

Next: [Allocators and arenas](./memory-allocators). The reference version of this chapter is [Managed by default](/memory/managed).
