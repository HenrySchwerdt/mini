---
title: "6. Memory: managed by default"
notebook: true
---

# 6. Memory: managed by default

From here on Mini differs from Kotlin. Kotlin on the JVM has a garbage collector. Mini has none: it has a **memory model with three tiers** and you choose the tier per file.

| Tier | Who frees memory | What the compiler checks |
|---|---|---|
| **Managed** (default) | reference counting, automatic | nothing, it is plain Kotlin |
| **Arena / Heap / Pool** | an allocator you pass in as a *context parameter* | lifetimes of arena objects |
| **Strict** | you, with `free()` and `use {}` | every allocation has exactly one owner and is released exactly once |

This chapter is the first tier, the one you have been using since chapter 1.

## Reference counting

Every object has a counter in its header. Storing a reference increments it, dropping one decrements it, and at zero the object releases what it holds and returns its memory. No pauses, no tracing, and memory is returned the moment the last reference goes.

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

The program is correct and prints what you expect; it just never returns those two objects. There is no cycle collector yet. The usual fixes are a non-owning back reference (`@Borrowed`) or putting the objects in an arena, which frees all of them at once. Both are in the next chapters.

## Choosing a tier per file

A file opts into a tier with an annotation on its first line:

```kotlin norun
@file:MemoryMode(Mode.Strict)
```

Without it the file is Managed. Tiers mix: a Strict file can call Managed code and the other way around, with rules at the boundary. You can also opt a single class back out of Strict:

<<< @/samples/memory/mixed_modes.kt{kotlin}

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
