---
title: "3. Classes, data and sealed types"
notebook: true
learnNotebook: true
aside: false
---

# 3. Classes, data and sealed types


**By the end:** put data and behavior together, choose between a plain class and a data class, and model a closed set of alternatives with a sealed hierarchy. Pay attention to the difference between a new object and another reference to an existing object.

## Classes

The primary constructor lists the properties. Methods are `fun`s inside the class, and `override` is required where it applies.

```kotlin
class Counter(val name: String) {
    var count = 0
        private set

    fun inc(): Counter {
        count++
        return this
    }

    override fun toString() = "$name=$count"
}

val c = Counter("clicks")
c.inc().inc().inc()
println(c)
```
```output
clicks=3
```

## Interfaces and inheritance

Interfaces can have default methods. Classes are `final` unless they are `open`.

```kotlin
interface Shape {
    val name: String
    fun area(): Double
    fun describe(): String = "$name with area ${area()}"
}

class Rect(val w: Double, val h: Double) : Shape {
    override val name = "rect"
    override fun area() = w * h
}

class Circle(val r: Double) : Shape {
    override val name = "circle"
    override fun area() = 3.0 * r * r      // pi is close enough to 3 today
}

val shapes: List<Shape> = listOf(Rect(2.0, 3.5), Circle(2.0))
for (s in shapes) println(s.describe())
```
```output
rect with area 7.0
circle with area 12.0
```

## Data classes

`data class` generates `equals`, `hashCode`, `toString`, `copy` and destructuring.

```kotlin
data class Point(val x: Int, val y: Int)

val p = Point(1, 2)
val (x, y) = p
println("$p x=$x y=$y")
println(p == Point(1, 2))
println(p.copy(y = 5))
```
```output
Point(x=1, y=2) x=1 y=2
true
Point(x=1, y=5)
```

## Enums

```kotlin
enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, 1), EAST(1, 0), SOUTH(0, -1), WEST(-1, 0);

    fun turnRight(): Direction = values()[(ordinal + 1) % values().size]
}

var d = Direction.NORTH
repeat(5) { d = d.turnRight() }
println("$d ${d.dx},${d.dy}")
```
```output
EAST 1,0
```

## Sealed types and exhaustive `when`

A `sealed` hierarchy lists every subclass in one place, so a `when` over it needs no `else`, and adding a case later makes every incomplete `when` a compile error.

```kotlin
sealed class Expr
class Num(val value: Int) : Expr()
class Add(val left: Expr, val right: Expr) : Expr()
class Mul(val left: Expr, val right: Expr) : Expr()
class Neg(val inner: Expr) : Expr()

fun eval(e: Expr): Int = when (e) {
    is Num -> e.value
    is Add -> eval(e.left) + eval(e.right)
    is Mul -> eval(e.left) * eval(e.right)
    is Neg -> -eval(e.inner)
}

println(eval(Add(Num(2), Mul(Num(3), Neg(Num(4))))))
```
```output
-10
```

## `object`

`object` declares a singleton, and `companion object` holds what would be static elsewhere.

```kotlin
object Registry {
    private var next = 100
    fun nextId(): Int = next++
}

println(Registry.nextId())
println(Registry.nextId())
```
```output
100
101
```

## Checkpoint

When should you choose a data class? Does `copy` duplicate objects held in its properties? What changes when you add a new sealed subtype?

::: details Reasoning
Use a data class for a value whose equality follows its constructor properties. `copy` is shallow, so referenced mutable objects remain shared. A new subtype makes an exhaustive `when` incomplete until you handle the case.
:::

## Exercise

::: tip Exercise: a new shape
Add a `Square(val side: Double)` class implementing `Shape` and make the program print `square with area 9.0`.
:::

```kotlin exercise
interface Shape {
    val name: String
    fun area(): Double
    fun describe(): String = "$name with area ${area()}"
}

class Rect(val w: Double, val h: Double) : Shape {
    override val name = "rect"
    override fun area() = w * h
}

fun main() {
    println(Rect(3.0, 3.0).describe())
}
```
```output
square with area 9.0
```

::: details Show solution
```kotlin
interface Shape {
    val name: String
    fun area(): Double
    fun describe(): String = "$name with area ${area()}"
}

class Square(val side: Double) : Shape {
    override val name = "square"
    override fun area() = side * side
}

fun main() {
    println(Square(3.0).describe())
}
```
```output
square with area 9.0
```
:::

Next: [Lambdas, collections and strings](./collections). More listings: [classes](/tour/classes) and [data, enum and sealed](/tour/types) in the tour.

Language reference: [Kotlin classes](https://kotlinlang.org/docs/classes.html). Mini-specific behavior is documented in the [compatibility guide](/guide/mini-vs-kotlin).
