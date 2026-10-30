---
title: "6. Generics and type contracts"
notebook: true
learnNotebook: true
aside: false
---

# 6. Generics and type contracts

You can already write `List<Int>` and pass a lambda to `map`. Now we will build that kind of API ourselves. The aim is to preserve information about values instead of falling back to `Any` and casting it back later.

**By the end:** write a generic container, constrain an operation to the types it can handle, and explain why a read-only collection is easier to share than a mutable one.

## A type parameter is a promise

`T` stands for one type chosen at the call site. It is not “turn off checking.” In `Parcel<String>`, both `value` and the return value of `unwrap` are known to be `String`.

```kotlin
class Parcel<T>(val value: T) {
    fun unwrap(): T = value
    fun <R> map(transform: (T) -> R): Parcel<R> = Parcel(transform(value))
}

fun main() {
    val name = Parcel("mini")
    val length: Parcel<Int> = name.map { it.length }
    println(name.unwrap().uppercase())
    println(length.unwrap() + 10)
}
```
```output
MINI
14
```

The class owns `T`; `map` introduces a different parameter `R`. The function `(T) -> R` describes the bridge between them. No caller needs to cast the result. Change the transform to `{ it + "!" }` and also change the declared result type: the compiler should reject the old `Parcel<Int>` annotation.

In Mini, an `Int` in a generic container may need a boxed representation. Type safety does not imply the representation is identical to a primitive local variable. We will measure allocation costs in [Performance](./performance).

## Bounds describe an operation you need

An unconstrained `T` is not necessarily comparable. An upper bound such as `T : Comparable<T>` says which behavior the function requires.

```kotlin
fun <T : Comparable<T>> larger(a: T, b: T): T = if (a >= b) a else b

fun main() {
    println(larger(12, 7))
    println(larger("pear", "apple"))
}
```
```output
12
pear
```

Both arguments must fit the same contract. Try `larger(12, "seven")`; the operation has no sensible shared ordering for those arguments. A bound documents that requirement before the program runs.

An unconstrained type parameter can also represent a nullable type. Use `T : Any` when an API must exclude null. This is a type-level choice; adding `!!` inside the function merely postpones a failure until runtime.

## Why `out` means “produce”

Suppose an API only produces values. A source of strings can be used by someone who only needs objects, because every string is an object. Kotlin lets you write this relationship with `out`.

```kotlin
interface Source<out T> {
    fun get(): T
}

class FixedSource<T>(private val item: T) : Source<T> {
    override fun get(): T = item
}

fun main() {
    val words: Source<String> = FixedSource("hello")
    val objects: Source<Any> = words
    println(objects.get())
}
```
```output
hello
```

If `Source` also accepted arbitrary `T` values, that assignment would be unsafe: someone could push an integer into the string source through the `Source<Any>` reference. The compiler therefore restricts how an `out T` can be used.

The reverse relationship is `in T`: a consumer of any object can serve a caller who only sends strings. A type that both consumes and produces `T`, such as a mutable box, is generally invariant. Do not memorize arrows first; ask which operations a caller can perform.

This is also why `List<String>` can be read as `List<Any>`, while `MutableList<String>` cannot be treated as `MutableList<Any>`. Read-only is an API restriction, not proof that nobody holds a mutable alias.

## Reified type checks

Ordinary generic code cannot just test `value is T`. An `inline` function with `reified T` makes that type available at its call site:

```kotlin
inline fun <reified T> matches(value: Any): Boolean = value is T

fun main() {
    println(matches<String>("Mini"))
    println(matches<Int>("Mini"))
    println(matches<Int>(42))
}
```
```output
true
false
true
```

This is useful for small type-directed helpers. It does not turn Mini into a full reflection platform, nor does a check of `List<*>` prove the type of every element. For supported reflection, consult the [compatibility guide](/guide/mini-vs-kotlin).

## Checkpoint

Before continuing, explain these three choices in your own words:

- Why does `Parcel<T>.map` need its own result type `R`?
- Why can a producer promise `out T`, but a mutable box usually cannot?
- Why is a compile-time bound preferable to casting an `Any` at every call?

::: details Reasoning
`map` can change the element type, so fixing its result to `T` would reject useful transformations such as a string to its length. A producer only returns values, so treating its result as a broader type is safe. A mutable box also accepts values, which would let a broader alias store the wrong kind. A bound exposes required behavior to every caller and lets the compiler reject invalid calls.
:::

## Exercise: preserve the result type

Write a generic `transformAll` that takes a `List<T>` and `(T) -> R`, returning a `List<R>`. The starter deliberately ignores the transform. Make it print the lengths, then add a second call that returns uppercase words.

```kotlin exercise
fun <T, R> transformAll(items: List<T>, transform: (T) -> R): List<R> {
    return emptyList()
}

fun main() {
    println(transformAll(listOf("mini", "notebook")) { it.length })
}
```
```output
[4, 8]
```

::: details Show solution
```kotlin
fun <T, R> transformAll(items: List<T>, transform: (T) -> R): List<R> {
    val result = mutableListOf<R>()
    for (item in items) result.add(transform(item))
    return result
}

fun main() {
    println(transformAll(listOf("mini", "notebook")) { it.length })
}
```
```output
[4, 8]
```

The standard library's `map` already expresses this operation. Implementing it once helps you read its signature; in application code, use the existing function.
:::

Further reading: Kotlin's [generics and variance](https://kotlinlang.org/docs/generics.html) and [inline functions](https://kotlinlang.org/docs/inline-functions.html), plus Mini's [generics implementation](/internals/generics).
