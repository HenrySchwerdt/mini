---
title: "2. Values, types and expressions"
notebook: true
learnNotebook: true
aside: false
---

# 2. Values, types and expressions

A program stores information and performs operations on it. Before learning more syntax, make those two ideas concrete. A **value** is a piece of information, such as the number `12`, the text `"Mini"`, or the Boolean `true`. A **type** describes which values and operations fit together. An **expression** computes a value.

You will learn to read a declaration, choose numeric types deliberately, recognize shared objects and predict an expression without running it first.

## Read a declaration from left to right

```kotlin
fun main() {
    val notebooks: Int = 3
    val minutesPerNotebook = 20
    val total = notebooks * minutesPerNotebook
    println(total)
}
```
```output
60
```

`val` introduces a read-only binding. `notebooks` is the name. `: Int` declares its type. The expression after `=` supplies the initial value. The compiler infers the type of `minutesPerNotebook` and `total` from their initializers, so you do not have to repeat `Int` everywhere.

Type inference does not make a variable dynamically typed. Once `minutesPerNotebook` is an `Int`, assigning a string to it is an error. Add types where they explain a public contract; omit obvious local types when the initializer is clearer.

An identifier is a name you choose, not text printed automatically. `println(notebooks)` reads the variable; `println("notebooks")` prints the literal word. Try both before continuing.

## Assignment is different from equality

`=` stores a value. `==` compares values and returns a Boolean. `var` permits later assignment. A later assignment has to preserve the variable's type.

```kotlin
fun main() {
    var remaining = 3
    remaining = remaining - 1
    println(remaining)
    println(remaining == 2)
    remaining -= 1
    println(remaining)
}
```
```output
2
true
1
```

On the assignment line the right-hand side is evaluated first, using the old value. The result then becomes the new value. `remaining -= 1` is a compound assignment. It does not mean “subtract one from every past value of this variable.”

Prefer `val` until you need reassignment. In a loop, a running total is often a sensible `var`. A fixed configuration value usually is not. A short lifetime and a clear purpose matter more than eliminating every mutable local.

## Numbers: decide what an operation means

`Int` is the usual whole-number type; `Long` has a wider whole-number range. `Double` represents floating-point numbers. An `L` suffix selects a `Long`, and a decimal point normally selects a `Double`.

```kotlin
fun main() {
    val items = 7
    val boxes = 2
    println(items / boxes)
    println(items % boxes)
    println(items.toDouble() / boxes)
    println(1_000_000L * 3_000L)
}
```
```output
3
1
3.5
3000000000
```

Whole-number division discards the fractional part. Remainder `%` answers a different question: what remains after division? It is useful for even/odd tests and wrapping an index.

Conversion order matters. `(items / boxes).toDouble()` computes integer division first and then converts `3` to `3.0`. `items.toDouble() / boxes` converts before division and gives `3.5`. Changing the storage type of a result cannot undo information already lost in an intermediate expression.

The same rule applies to overflow. `Int.MAX_VALUE.toLong() + 1` widens before adding; `(Int.MAX_VALUE + 1).toLong()` widens an already wrapped result. Pick a wide enough accumulator at the beginning of a calculation.

Floating-point numbers approximate many decimal fractions. Do not use a `Double` as an exact representation of a money amount. For a small money example, store integer cents and define rounding explicitly. Mini's C interop also has narrow numeric scalars; they are a later boundary topic, not the default types to use in these exercises.

## Booleans express decisions

Comparisons such as `<`, `>=`, `==` and `!=` produce Booleans. Combine conditions with `&&` (and), `||` (or) and `!` (not).

```kotlin
fun main() {
    val seats = 0
    val waiting = 4
    val canDivide = seats > 0 && waiting / seats < 3
    println(canDivide)
    println(seats > 0 || waiting > 0)
    println(!(waiting == 0))
}
```
```output
false
true
true
```

`&&` and `||` short-circuit: they evaluate the right side only when needed. Here `waiting / seats` is never evaluated because `seats > 0` is false. Reversing the operands would attempt division by zero. A guard needs to come before the operation it protects.

Parentheses make intent visible. They are especially helpful when a condition mixes arithmetic and several Boolean operations. Prefer a named Boolean such as `hasCapacity` to repeating a complicated expression in three places.

## Strings and characters are different

A `String` is text in double quotes. A `Char` is one UTF-16 code unit in single quotes. String templates insert values without manual concatenation.

```kotlin
fun main() {
    val initial: Char = 'M'
    val name = "Mini"
    println("$initial starts $name")
    println("$name has ${name.length} code units")
    println("first=${name[0]}, last=${name[name.length - 1]}")
    println("one\ntwo")
}
```
```output
M starts Mini
Mini has 4 code units
first=M, last=i
one
two
```

Indices begin at zero. A string of length four has indices zero through three. Accessing index four is outside the string. Do not assume every visible character has length one: supplementary Unicode characters need two UTF-16 code units. Mini's case conversion and character classification also have documented limits; the compatibility guide is more precise than a visual guess from the text.

`\n` represents a newline inside a quoted string. `${...}` accepts an expression, while `$name` is convenient for a simple name. A string is not mutable: operations such as `uppercase` return a result rather than changing the original text.

## A read-only name does not freeze an object

```kotlin
fun main() {
    val original = mutableListOf("read")
    val alias = original
    alias.add("practice")
    val snapshot = original.toList()
    original.add("review")
    println(original)
    println(alias)
    println(snapshot)
}
```
```output
[read, practice, review]
[read, practice, review]
[read, practice]
```

`original` and `alias` refer to the same mutable list. `toList()` produces a separate list of the current elements, so later structural changes do not appear in `snapshot`. This does not deeply clone element objects. If the elements were mutable tasks, their internal state could still be shared.

This distinction is essential for Mini: a reference is an edge to an object, not a second allocation. We will return to it when we draw memory graphs.

## Exercise: calculate before formatting

A group buys three tickets costing 125 cents each and pays 500 cents. Calculate the total and change using integers, then print the result. The starter accidentally computes only one ticket's price.

```kotlin exercise
fun main() {
    val count = 3
    val priceCents = 125
    val paidCents = 500
    val total = priceCents
    println("total=$total")
    println("change=${paidCents - total}")
}
```
```output
total=375
change=125
```

::: details Hint
Identify which expression should depend on both the number of tickets and the price of one ticket. Printing is the final step; first make the calculation correct.
:::

::: details Show solution
```kotlin
fun main() {
    val count = 3
    val priceCents = 125
    val paidCents = 500
    val total = count * priceCents
    println("total=$total")
    println("change=${paidCents - total}")
}
```
```output
total=375
change=125
```

Now try zero tickets and one ticket. What policy should apply if the payment is too small? That requires a decision, which is the subject of the next chapter.
:::

## Explain it back

Without running anything, explain why `5 / 2` and `5.toDouble() / 2` differ, why two `val` names can observe the same changing list, and why a guard placed after a division cannot protect it. If an answer feels uncertain, change just that part of a cell and compare your prediction with the result.

Language references: Kotlin [numbers](https://kotlinlang.org/docs/numbers.html) and [basic syntax](https://kotlinlang.org/docs/basic-syntax.html). For Mini's numeric and Unicode limits, see [Mini and Kotlin/JVM](/guide/mini-vs-kotlin).
