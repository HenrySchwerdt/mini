---
title: "3. Branching, functions and loops"
notebook: true
learnNotebook: true
aside: false
---

# 3. Branching, functions and loops

**By the end:** choose between a read-only binding and a mutable variable, write a function with a clear result type, use branching as an expression and recognize the boundaries of a range. The exercises reuse these ideas instead of introducing new syntax at the last moment.

## `val` and `var`

`val` is a read-only name, `var` can be reassigned. Types are inferred; you can write them with `:`.

```kotlin
val language = "Mini"
var chaptersLeft: Int = 10
chaptersLeft -= 1
println("$language: $chaptersLeft chapters left")
```
```output
Mini: 9 chapters left
```

`Int` and `Long` have Kotlin semantics. Arithmetic wraps on overflow, and division by zero throws:

```kotlin
val big = Int.MAX_VALUE
println(big + 1)
println(7 / 2)
println(7 % 3)
println(7.0 / 2)
println(big.toLong() + 1)
```
```output
-2147483648
3
1
3.5
2147483648
```

## Functions

Functions have typed parameters, an optional default value and a return type. A single expression can follow `=`.

```kotlin
fun square(n: Int): Int = n * n

fun greet(name: String, greeting: String = "Hello"): String = "$greeting, $name!"

println(square(7))
println(greet("Ada"))
println(greet("Ada", greeting = "Hi"))
```
```output
49
Hello, Ada!
Hi, Ada!
```

The functions `square` and `greet` are declarations, so the cells below can call them.

## `if` and `when`

`if` is an expression. `when` is `switch` done right: it takes conditions, values, ranges and types, and it is also an expression.

```kotlin
fun classify(n: Int): String = when {
    n < 0 -> "negative"
    n == 0 -> "zero"
    n % 2 == 0 -> "even"
    else -> "odd"
}

for (n in listOf(-5, 0, 4, 7)) {
    println("$n is ${classify(n)}")
}
println(if (square(3) > 8) "big" else "small")
```
```output
-5 is negative
0 is zero
4 is even
7 is odd
big
```

## Trace a branch before writing a loop

An `if` chooses one branch; it does not run both and keep the better result. As an expression it supplies a value, so each path must produce a value compatible with the result's type.

```kotlin
fun main() {
    val temperature = 18
    val instruction = if (temperature < 16) {
        "heat"
    } else {
        "leave as is"
    }
    println(instruction)
}
```
```output
leave as is
```

The braces form blocks. Their last expressions supply the result. Writing `println("heat")` as that last expression would return `Unit` from that branch rather than the string `"heat"`. Computing a message and printing it afterward keeps those responsibilities clear.

For `when`, conditions are considered in order and only the first matching branch runs. In FizzBuzz, checking divisibility by three before the combined three-and-five case would classify fifteen too early. A condition can be true without ever being reached.

## A loop repeats a state transition

Start with a small loop whose changing state you can write down. On each iteration, record the current item, the old total and the new total.

```kotlin
fun main() {
    var total = 0
    for (amount in listOf(2, 4, 3)) {
        total += amount
        println("added $amount, total=$total")
    }
    println("final=$total")
}
```
```output
added 2, total=2
added 4, total=6
added 3, total=9
final=9
```

The loop variable receives a new element each time; `total` retains the running state within this one execution. If the list is empty, the body runs zero times and the total stays at its initializer. That is why zero is the right starting value for a sum.

A `while` checks a Boolean before each iteration. Its body needs to move the state toward making that condition false. If it never does, the loop may never stop. The browser limits long-running examples, but a timeout is not a correction to the algorithm.

## Indices and boundaries

```kotlin
fun main() {
    val names = listOf("Ada", "Bo", "Cy")
    for (index in names.indices) println("$index: ${names[index]}")
    println((0 until names.size).joinToString(","))
    println((0..names.size).joinToString(","))
}
```
```output
0: Ada
1: Bo
2: Cy
0,1,2
0,1,2,3
```

The last range contains an index that cannot be used to access this list. An inclusive endpoint and a count are different quantities. Prefer looping over elements when you do not need an index, and use `indices` when you do.

`break` ends the current loop. `continue` skips the remainder of the current iteration and starts the next one. Neither returns from the enclosing function. With nested loops, an unlabelled `break` only ends the inner loop; labels make a different target explicit.

### Practice without a solution first

Print the running product of `2`, `3` and `4`. Decide the initial accumulator before coding: zero is correct for a sum but would make every product zero. Then repeat the exercise with an empty list and explain the result your initializer gives you.

## Loops and ranges

`for` goes over anything iterable. Ranges (`..`, `until`, `downTo`, `step`) over `Int`, `Long` and `Char` compile to plain C loops.

```kotlin
var sum = 0
for (i in 1..10 step 3) sum += i      // 1 + 4 + 7 + 10
println("sum = $sum")

for (i in 3 downTo 1) print("$i ")
println("liftoff")

var n = 27
var steps = 0
while (n != 1) {
    n = if (n % 2 == 0) n / 2 else 3 * n + 1
    steps++
}
println("Collatz(27) takes $steps steps")
```
```output
sum = 22
3 2 1 liftoff
Collatz(27) takes 111 steps
```

Labels let `break` and `continue` leave an outer loop:

```kotlin
outer@ for (i in 1..3) {
    for (j in 1..3) {
        if (i * j == 4) break@outer
        print("$i*$j; ")
    }
}
println()
```
```output
1*1; 1*2; 1*3; 2*1; 
```

## Checkpoint

What does `7 / 2` return? How is `0 until size` different from `0..size`? Why should a function accept a parameter rather than read a mutable top-level variable?

::: details Reasoning
Integer division returns `3`. `until` excludes the end, so `0 until size` covers valid indices; `0..size` includes one index too many. Parameters make a function's dependencies explicit and make different inputs easier to test.
:::

## Exercise

::: tip Exercise: FizzBuzz
Print the numbers 1 to 15, but `Fizz` for multiples of 3, `Buzz` for multiples of 5 and `FizzBuzz` for both. One per line. The starter prints only the numbers.
:::

```kotlin exercise
fun main() {
    for (i in 1..15) {
        println(i)
    }
}
```
```output
1
2
Fizz
4
Buzz
Fizz
7
8
Fizz
Buzz
11
Fizz
13
14
FizzBuzz
```

::: details Show solution
```kotlin
fun main() {
    for (i in 1..15) {
        println(
            when {
                i % 15 == 0 -> "FizzBuzz"
                i % 3 == 0 -> "Fizz"
                i % 5 == 0 -> "Buzz"
                else -> i.toString()
            }
        )
    }
}
```
```output
1
2
Fizz
4
Buzz
Fizz
7
8
Fizz
Buzz
11
Fizz
13
14
FizzBuzz
```
:::

Language reference: [Kotlin basic syntax](https://kotlinlang.org/docs/basic-syntax.html). Mini-specific behavior is documented in the [compatibility guide](/guide/mini-vs-kotlin).
