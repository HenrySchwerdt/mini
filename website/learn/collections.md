---
title: "4. Lambdas, collections and strings"
notebook: true
---

# 4. Lambdas, collections and strings

## Lambdas

A lambda is a function value: `{ x: Int -> x + 3 }`. When it has one parameter you can call it `it`. Functions can take functions, and a lambda can capture and change variables of the code around it.

```kotlin
fun makeCounter(): () -> Int {
    var n = 0
    return { n += 1; n }
}

val c1 = makeCounter()
val c2 = makeCounter()
println("${c1()} ${c1()} ${c1()} ${c2()}")
```
```output
1 2 3 1
```

`n` is captured by the lambda and lives as long as the lambda does. Under the hood it is a shared, reference-counted cell; the [closures chapter of How it works](/internals/closures) shows the generated C.

`inline` functions are expanded at the call site, so their lambdas cost nothing and may `return` from the enclosing function:

```kotlin
inline fun twice(action: () -> Unit) {
    action()
    action()
}

fun firstBig(xs: List<Int>): String {
    xs.forEach { if (it > 10) return "big: $it" }
    return "none"
}

var log = ""
twice { log += "x" }
println(log)
println(firstBig(listOf(1, 20, 30)))
println(firstBig(listOf(1, 2)))
```
```output
xx
big: 20
none
```

## Collections

`List`, `MutableList`, `Set`, `Map` and `HashMap` come from Mini's standard library, which is Kotlin source compiled together with your program. Only the part you use ends up in the binary.

```kotlin
val xs = listOf(5, 3, 8, 1)
println(xs.map { it * 2 })
println(xs.filter { it > 2 }.sorted())
println(xs.fold(0) { acc, x -> acc + x })
println(xs.zip(listOf("a", "b", "c")))
```
```output
[10, 6, 16, 2]
[3, 5, 8]
17
[(5, a), (3, b), (8, c)]
```

```kotlin
data class Person(val name: String, val age: Int)

val people = listOf(Person("Ann", 31), Person("Bo", 25), Person("Cy", 40))
println(people.sortedBy { it.age }.map { it.name })
println(people.maxByOrNull { it.age })
println(people.sumOf { it.age })
```
```output
[Bo, Ann, Cy]
Person(name=Cy, age=40)
96
```

Maps count things well:

```kotlin
val counts = HashMap<String, Int>()
for (w in "a b a c b a".split(" ")) counts[w] = (counts[w] ?: 0) + 1
println(counts)
println(counts["a"])
```
```output
{a=3, b=2, c=1}
3
```

The iteration order of `HashMap` and `HashSet` is the one of `java.util.HashMap`, so output matches what Kotlin on the JVM prints.

## Strings

```kotlin
println("Hello, Mini".lowercase().replace("mini", "world").padEnd(15, '.'))
println("a,b,c".split(",").joinToString("|"))
println(buildString {
    for (i in 1..3) append(i).append(';')
})
```
```output
hello, world...
a|b|c
1;2;3;
```

## Generics

Generic classes and functions work as in Kotlin, including bounds. `reified` (with `inline`) keeps the type at run time.

```kotlin
class Box<T>(var value: T) {
    fun <R> map(f: (T) -> R): Box<R> = Box(f(value))
    override fun toString(): String = "Box($value)"
}

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T {
    var best = a
    if (b > best) best = b
    if (c > best) best = c
    return best
}

println(Box(20).map { it + 1 })
println(Box(20).map { "n=$it" })
println(maxOf3(3, 9, 4))
println(maxOf3("pear", "apple", "zebra"))
```
```output
Box(21)
Box(n=20)
9
zebra
```

## Exercise

::: tip Exercise: word lengths
Print the words of the sentence sorted by length, longest first, one per line as `word: length`. Ties keep their original order. The starter prints them unsorted.
:::

```kotlin exercise
fun main() {
    val words = "the quick brown fox jumps over the lazy dog".split(" ")
    for (w in words) println("$w: ${w.length}")
}
```
```output
quick: 5
brown: 5
jumps: 5
over: 4
lazy: 4
the: 3
fox: 3
the: 3
dog: 3
```

::: details Show solution
```kotlin
fun main() {
    val words = "the quick brown fox jumps over the lazy dog".split(" ")
    for (w in words.sortedByDescending { it.length }) println("$w: ${w.length}")
}
```
```output
quick: 5
brown: 5
jumps: 5
over: 4
lazy: 4
the: 3
fox: 3
the: 3
dog: 3
```
:::

Next: [Null safety and exceptions](./errors).
