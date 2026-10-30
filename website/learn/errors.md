---
title: "5. Null safety and exceptions"
notebook: true
learnNotebook: true
aside: false
---

# 5. Null safety and exceptions


**By the end:** represent absence with a nullable type, narrow a type with a check, and decide between a missing result and an exception. A failed conversion of user input is usually a case to handle, not a reason to add `!!`.

## Null safety

A type like `String` can never be `null`. `String?` can, and the compiler makes you deal with it.

```kotlin
class User(val name: String, val email: String?)

val alice = User("alice", "alice@example.com")
val bob = User("bob", null)

println(alice.email?.length)      // safe call: null if the receiver is null
println(bob.email?.length)
println(bob.email ?: "no email")  // elvis: a default for null
```
```output
17
null
no email
```

After a check, the compiler knows more (a *smart cast*):

```kotlin
fun describe(x: Any?): String = when (x) {
    null -> "null"
    is Int -> "int ${x + 1}"
    is String -> "string of length ${x.length}"
    else -> "something else"
}

for (v in listOf(null, 41, "kotlin", 1.5)) println(describe(v))
```
```output
null
int 42
string of length 6
something else
```

## Extension functions

Add functions to types you do not own. They are resolved statically. The extension syntax adds no dynamic dispatch; the function body can still allocate or perform expensive work.

```kotlin
fun Int.isEven(): Boolean = this % 2 == 0
fun String.shout(): String = uppercase() + "!"
val Int.squared: Int get() = this * this

println(4.isEven())
println("hey".shout())
println(7.squared)
```
```output
true
HEY!
49
```

## Exceptions

`throw`, `try`, `catch` and `finally` work as in Kotlin. `try` is an expression. Runtime failures (division by zero, `!!` on `null`, a failed `check`) are ordinary exceptions you can catch.

```kotlin
class AppException(val code: Int, message: String) : Exception(message)

fun risky(n: Int): Int {
    if (n < 0) throw IllegalArgumentException("negative: $n")
    if (n == 0) throw AppException(404, "zero")
    return 100 / n
}

fun attempt(n: Int): String {
    return try {
        "ok ${risky(n)}"
    } catch (e: IllegalArgumentException) {
        "bad argument (${e.message})"
    } catch (e: AppException) {
        "app error ${e.code}: ${e.message}"
    } finally {
        println("finally for $n")
    }
}

println(attempt(4))
println(attempt(-1))
println(attempt(0))
```
```output
finally for 4
ok 25
finally for -1
bad argument (negative: -1)
finally for 0
app error 404: zero
```

An exception that nobody catches ends the program with status 1 and a message on stderr:

```kotlin exit=1
fun main() {
    println("before")
    throw IllegalStateException("boom")
}
```

Press Run: the output panel shows `before`, the message on stderr and `exit code 1`. There are no stack traces yet. Mini implements exceptions with `setjmp`/`longjmp` in the generated C; [How it works](/internals/exceptions) shows how.

## Checkpoint

When does `?.` return null? Is `?:` a fallback for every exception? Does `!!` prove a value is present?

::: details Reasoning
A safe call skips the call and returns null for a null receiver. Elvis supplies an alternative for a null result; it does not catch exceptions. `!!` asserts non-null at runtime and can throw, so it is not a substitute for validating input.
:::

## Exercise

::: tip Exercise: safe parsing
`toIntOrNull()` returns `null` when the text is not a number. Make the program print the sum of the numbers in the list and skip the entries that are not numbers. It should print `sum = 12`. The starter adds the length of each entry instead of its value.
:::

```kotlin exercise
fun main() {
    val inputs = listOf("5", "x", "7")
    var sum = 0
    for (s in inputs) {
        sum += s.length
    }
    println("sum = $sum")
}
```
```output
sum = 12
```

::: details Show solution
```kotlin
fun main() {
    val inputs = listOf("5", "x", "7")
    var sum = 0
    for (s in inputs) {
        sum += s.toIntOrNull() ?: 0
    }
    println("sum = $sum")
}
```
```output
sum = 12
```
:::

Next: [Generics and type contracts](./generics). After extensions and the project, continue with [Managed memory](./memory-managed). More listings: [exceptions](/tour/exceptions) and [extensions and null safety](/tour/extensions).

Language reference: [Kotlin null safety](https://kotlinlang.org/docs/null-safety.html). Mini-specific behavior is documented in the [compatibility guide](/guide/mini-vs-kotlin).
