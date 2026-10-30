---
title: "4. Functions, scope and decomposition"
notebook: true
learnNotebook: true
aside: false
---

# 4. Functions, scope and decomposition

You have used functions for arithmetic and branching. Now use them to organize a program. A good function exposes what it needs, gives its result a useful meaning and leaves its caller able to reason about what happened.

We will grow a small delivery-price calculation, distinguish returned values from side effects, and make hidden dependencies visible.

## Follow one call by hand

```kotlin
fun deliveryPrice(distance: Int, rate: Int): Int {
    val base = 200
    val variable = distance * rate
    return base + variable
}

fun main() {
    val first = deliveryPrice(4, 30)
    val second = deliveryPrice(2, 50)
    println(first)
    println(second)
}
```
```output
320
300
```

For the first call, `distance` receives `4` and `rate` receives `30`. The function creates its local values, computes `320` and returns it. Its locals are not names available to `main`. The second call has its own parameter values and locals.

A **parameter** is the name in the declaration; an **argument** is the expression supplied by a caller. Arguments are evaluated to values before the call. Returning transfers control and a result back to the caller; it does not print anything automatically.

## Return a value or perform an action

```kotlin
fun priceLabel(cents: Int): String = "$cents cents"

fun announce(cents: Int): Unit {
    println(priceLabel(cents))
}

fun main() {
    val label = priceLabel(320)
    println(label.uppercase())
    announce(300)
}
```
```output
320 CENTS
300 cents
```

`priceLabel` computes a string. `announce` performs output and returns `Unit`, the type used when there is no meaningful result. You can usually omit `: Unit`.

Returning a string gives the caller choices: display it, compare it in a test, put it in a larger report or ignore it. Printing inside every helper makes those choices harder. Keep calculation and formatting separate from input/output when the separation helps reuse and testing.

A single-expression function uses `=`. A block body uses braces and an explicit `return` when returning a value. Do not accidentally replace an expression body with a block and forget the return type or the `return` statement.

## Name the choices a caller makes

```kotlin
fun quote(distance: Int, rate: Int = 30, base: Int = 200): Int =
    base + distance * rate

fun main() {
    println(quote(distance = 4))
    println(quote(distance = 4, base = 100))
    println(quote(distance = 4, rate = 50, base = 100))
}
```
```output
320
220
300
```

Defaults describe a common choice while keeping other choices available. Named arguments help distinguish several numbers with the same type. A caller reading `quote(4, 30, 200)` has to remember the order; named arguments make the roles explicit.

Avoid adding a Boolean for every possible behavior. If a function needs many flags that interact, separate operations or introduce a configuration type whose fields have clear meanings. A shorter call is not automatically easier to understand.

## Scope: which declaration does a name refer to?

A local name is visible inside its enclosing block and nested blocks, after its declaration. A nested declaration can hide an outer name, which is called shadowing.

```kotlin
val defaultRate = 30

fun scopedQuote(distance: Int): Int {
    val rate = defaultRate
    fun variablePart(): Int = distance * rate
    return 200 + variablePart()
}

fun main() {
    println(scopedQuote(4))
    val defaultRate = 99
    println(defaultRate)
    println(scopedQuote(4))
}
```
```output
320
99
320
```

The local `defaultRate` in `main` does not rewrite the top-level declaration used by `scopedQuote`. Name resolution follows where code is declared, not which caller happens to execute it.

The local function `variablePart` uses `distance` and `rate` from its enclosing function. That is useful when a helper belongs to one operation. If several operations need it, move it to an appropriate shared scope and pass its dependencies explicitly.

Prefer `private` for implementation helpers that other files should not call. `internal` exposes declarations within a module; public declarations form an API that callers may rely on. Keep changes to implementation details from becoming changes to everyone else's contracts.

## Early return makes a guard visible

```kotlin
fun discount(total: Int, percent: Int): Int {
    if (total <= 0) return 0
    if (percent <= 0) return total
    val boundedPercent = if (percent > 100) 100 else percent
    return total - total * boundedPercent / 100
}

fun main() {
    println(discount(1000, 20))
    println(discount(1000, 150))
    println(discount(0, 20))
}
```
```output
800
0
0
```

The policy here clamps a percentage above 100. Another API might reject it. The important point is to choose the policy rather than letting arithmetic accidentally define one.

This example uses small cent amounts. For large totals, widen before multiplying `total * boundedPercent`; widening after multiplication cannot repair an overflow.

A guard handles a special case before the main calculation. Nested `if` blocks are appropriate when the nesting expresses the problem; a series of guards is useful when each one eliminates an invalid or trivial case.

There is an ownership consequence in Mini: an early return after acquiring an owned resource needs to discharge that ownership. An early return before acquisition has no such obligation. We will follow these paths explicitly in the ownership chapters.

## Repeated work: loop or recursion?

```kotlin
fun factorial(n: Int): Long {
    if (n <= 1) return 1L
    return n.toLong() * factorial(n - 1)
}

fun main() {
    println(factorial(5))
    println(factorial(0))
}
```
```output
120
1
```

Trace `factorial(3)` as `3 * factorial(2)`, then `3 * 2 * factorial(1)`, then return through the calls. The base case stops the recursion. Without progress toward a base case the program keeps calling itself until it fails.

This example deliberately handles only small nonnegative inputs. For larger inputs the result overflows a `Long`; deep recursion also consumes stack space. A loop is often a better expression of a simple counter, while recursion naturally fits a tree. Do not assume an arbitrary recursive function receives tail-call optimization.

## Exercise: make the dependencies explicit

Write a `deliveryTotal` function taking a list of distances and a rate, returning the sum of `distance * rate`. Empty input should return zero. The starter returns the count of deliveries instead of their price.

```kotlin exercise
fun deliveryTotal(distances: List<Int>, rate: Int): Int = distances.size

fun main() {
    println(deliveryTotal(listOf(2, 4, 1), 30))
    println(deliveryTotal(emptyList(), 30))
}
```
```output
210
0
```

::: details Hint
Start with a local sum of zero, loop over distances and return the sum after the loop. The rate belongs in a parameter rather than a mutable global.
:::

::: details Show solution
```kotlin
fun deliveryTotal(distances: List<Int>, rate: Int): Int {
    var total = 0
    for (distance in distances) total += distance * rate
    return total
}

fun main() {
    println(deliveryTotal(listOf(2, 4, 1), 30))
    println(deliveryTotal(emptyList(), 30))
}
```
```output
210
0
```

Now choose a policy for negative distances, test it and state it in the contract. Avoid introducing a hidden fallback that callers cannot distinguish from valid zero-cost work.
:::

Language reference: Kotlin [functions](https://kotlinlang.org/docs/functions.html). Mini's [ownership guide](/memory/ownership) later explains how a function signature also communicates borrowing and transfer.
