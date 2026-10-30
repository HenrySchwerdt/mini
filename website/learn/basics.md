---
title: "2. Values, functions and control flow"
notebook: true
---

# 2. Values, functions and control flow

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

Next: [Classes, data and sealed types](./classes). The [language tour](/tour/basics) has the full listing for this chapter.
