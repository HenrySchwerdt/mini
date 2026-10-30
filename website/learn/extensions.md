---
title: "12. Extensions, receivers and scope"
notebook: true
learnNotebook: true
aside: false
---

# 12. Extensions, receivers and scope

Kotlin lets you make an API read naturally without changing the class it works on. Extensions, receiver lambdas and scope functions are three pieces of the same idea: choosing which object is convenient to work with inside a block.

**By the end:** distinguish an extension from a virtual member, read `T.() -> Unit`, and choose a scope function by what the expression returns.

## Extensions do not modify a class

`fun String.label()` adds a callable spelling for a regular function. Inside it, `this` is the receiver. The function cannot reach private members of `String` or add storage to it.

```kotlin
fun String.label(): String = "[${trim().uppercase()}]"
val String.labelWidth: Int get() = label().length

fun main() {
    println(" mini ".label())
    println("mini".labelWidth)
}
```
```output
[MINI]
6
```

An extension property needs a getter because it has no backing field. A member of the same name takes precedence over an extension.

**Static resolution matters.** The compiler selects an extension from the declared type of the receiver, not its runtime class:

```kotlin
open class Animal
class Cat : Animal()
fun Animal.sound(): String = "animal"
fun Cat.sound(): String = "cat"

fun main() {
    val pet: Animal = Cat()
    println(pet.sound())
    println(Cat().sound())
}
```
```output
animal
cat
```

Use an `open` member and `override` when you want dynamic dispatch. An extension is useful for adapting an API at the call site; it is not an alternative inheritance mechanism.

## A lambda can have a receiver

Compare `(StringBuilder) -> Unit`, which supplies an explicit argument, with `StringBuilder.() -> Unit`, which supplies `this`. The latter lets a builder block call `append` directly.

```kotlin
fun message(block: StringBuilder.() -> Unit): String {
    val builder = StringBuilder()
    builder.block()
    return builder.toString()
}

fun main() {
    println(message {
        append("Mini")
        append(": ")
        append(6 * 7)
    })
}
```
```output
Mini: 42
```

`builder.block()` invokes the supplied function with `builder` as its receiver. The block does not create a subclass or a new language. Kotlin's `buildString` is the ready-made version of this pattern.

In Mini, receiver syntax and allocator context are different concepts. `apply` makes `this` convenient; `context(arena)` selects an allocator context. A scope function by itself never opts an object into arena ownership.

## Pick scope functions by their result

| Function | Object inside the block | Result of the whole expression | Useful question |
|---|---|---|---|
| `let` | `it` or a named argument | Last expression | What value should I derive? |
| `run` | `this` | Last expression | What should this object's computation produce? |
| `with(obj)` | `this` | Last expression | Which object supplies this block's operations? |
| `apply` | `this` | Original object | How should I configure this object? |
| `also` | `it` or a named argument | Original object | What extra action should I perform? |

```kotlin
class Settings {
    var name = "untitled"
    var limit = 0
}

fun main() {
    val settings = Settings().apply {
        name = "report"
        limit = 3
    }
    val summary = settings.let { "${it.name}: ${it.limit}" }
    println(summary)
    println(settings.run { limit * 2 })
}
```
```output
report: 3
6
```

Avoid nesting several implicit receivers: a named local and a direct call are often easier to read. Scope functions do not improve an API just by shortening it.

## Null handling with `let`

`?.let` runs the block only when the receiver is non-null. The Elvis expression can supply the missing case. Prefer a simple `if` when you need several branches or complicated early returns.

```kotlin
fun greeting(name: String?): String =
    name?.let { "Hello, ${it.uppercase()}" } ?: "Hello, visitor"

fun main() {
    println(greeting("Ada"))
    println(greeting(null))
}
```
```output
Hello, ADA
Hello, visitor
```

The `it` here is non-null; there is no need to add `!!`. If your block itself returns null, the Elvis fallback also runs. That is different from “the input was missing,” so choose the expression carefully.

## Checkpoint

What does `Settings().apply { limit = 3 }` return? Would replacing `apply` with `run` preserve that type? Why does `pet.sound()` print `animal` even though the object is a `Cat`?

::: details Reasoning
`apply` returns the original `Settings`. `run` returns the last expression of its block; an assignment has `Unit` as its result. The extension call is resolved from the declared `Animal` type. A virtual member would instead dispatch to an override on `Cat`.
:::

## Exercise: format through a receiver

Use `buildString` to create a report with one `name: score` line per entry. Keep separators between entries rather than at the end. The starter only prints the number of entries.

```kotlin exercise
fun main() {
    val scores = listOf("Ada" to 8, "Grace" to 10)
    println(scores.size)
}
```
```output
Ada: 8
Grace: 10
```

::: details Show solution
```kotlin
fun main() {
    val scores = listOf("Ada" to 8, "Grace" to 10)
    println(buildString {
        for (i in scores.indices) {
            if (i > 0) append('\n')
            val (name, score) = scores[i]
            append(name).append(": ").append(score)
        }
    })
}
```
```output
Ada: 8
Grace: 10
```
:::

Further reading: Kotlin's [extensions](https://kotlinlang.org/docs/extensions.html), [scope functions](https://kotlinlang.org/docs/scope-functions.html) and [function literals with receiver](https://kotlinlang.org/docs/lambdas.html#function-literals-with-receiver).
