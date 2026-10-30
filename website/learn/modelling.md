---
title: "6. Model states and protect invariants"
notebook: true
learnNotebook: true
aside: false
---

# 6. Model states and protect invariants

A type is more than a bag of properties. It can express which states are meaningful and which operations preserve them. We will model a small booking and examine the places where a convenient representation allows contradictory data.

## Start with the question your data must answer

Imagine a booking with a `paid` flag, a `cancelled` flag and a nullable receipt. Can a cancelled booking be paid? Must a paid booking have a receipt? If the model accepts every combination, every caller has to rediscover those rules.

An enum is useful when the alternatives carry no different data. A sealed hierarchy is useful when each alternative has its own payload.

```kotlin
sealed interface BookingState
data class AwaitingPayment(val cents: Int) : BookingState
data class Paid(val receipt: String) : BookingState
data class Cancelled(val reason: String) : BookingState

fun bookingLabel(state: BookingState): String = when (state) {
    is AwaitingPayment -> "pay ${state.cents} cents"
    is Paid -> "receipt ${state.receipt}"
    is Cancelled -> "cancelled: ${state.reason}"
}

fun main() {
    println(bookingLabel(AwaitingPayment(500)))
    println(bookingLabel(Paid("R-7")))
    println(bookingLabel(Cancelled("sold out")))
}
```
```output
pay 500 cents
receipt R-7
cancelled: sold out
```

A `Paid` value always has a receipt, and an `AwaitingPayment` value has a price instead. The model removes some impossible combinations rather than asking every caller to avoid them manually.

The `when` has no `else`. Adding another permitted subtype makes missing cases visible to the compiler. An `else` that always prints `unknown` would suppress that reminder. Use it only when a generic fallback is really the desired behavior.

## Construction is a boundary

A type can reject invalid inputs in its initializer. The constructor parameters are available to `init`, and properties can keep mutation private.

```kotlin
class SeatCounter(capacity: Int) {
    val capacity = capacity
    var reserved = 0
        private set

    init {
        require(capacity >= 0) { "capacity must not be negative" }
    }

    val remaining: Int get() = capacity - reserved

    fun reserve(): Boolean {
        if (remaining == 0) return false
        reserved++
        return true
    }
}

fun main() {
    val seats = SeatCounter(2)
    println(seats.reserve())
    println(seats.reserve())
    println(seats.reserve())
    println("${seats.reserved}/${seats.capacity}, remaining=${seats.remaining}")
}
```
```output
true
true
false
2/2, remaining=0
```

The invariant is `0 <= reserved <= capacity`. Construction establishes it, and `reserve` preserves it. An outside caller cannot assign `reserved = 100` because the setter is private. `remaining` is derived instead of stored separately, so it cannot become inconsistent with the other properties.

A constructor parameter without `val` or `var` is not automatically a public property. In this example we explicitly initialize a property from it. A property getter can compute a result; reading a property does not universally imply a stored field or a trivial operation.

## Equality answers a domain question

For a data class, constructor properties participate in generated value equality. An ordinary class uses its default identity-based equality unless you implement a different contract.

```kotlin
data class Coordinate(val x: Int, val y: Int)
class Marker(val label: String)

fun main() {
    val a = Coordinate(1, 2)
    val b = Coordinate(1, 2)
    val marker = Marker("home")
    println(a == b)
    println(a === b)
    println(marker === marker)
    println(Marker("home") == Marker("home"))
}
```
```output
true
false
true
false
```

`==` asks for equality according to the type's contract. `===` asks whether two references identify the same object. Avoid using identity to compare boxed numbers: their representation is not the meaning of the numeric value.

When a type becomes a map key, equality and hashing must agree. Mutating properties used by a key's hash after inserting it can make lookups fail. Prefer stable key data, such as an immutable ID or a data class of read-only scalar properties.

## A data-class copy is shallow

```kotlin
data class ReadingPlan(val title: String, val pages: MutableList<Int>)

fun main() {
    val first = ReadingPlan("Kotlin", mutableListOf(1, 2))
    val second = first.copy(title = "Mini")
    second.pages.add(3)
    println(first)
    println(second)
    val separate = first.copy(pages = first.pages.toMutableList())
    separate.pages.add(4)
    println(first.pages)
    println(separate.pages)
}
```
```output
ReadingPlan(title=Kotlin, pages=[1, 2, 3])
ReadingPlan(title=Mini, pages=[1, 2, 3])
[1, 2, 3]
[1, 2, 3, 4]
```

`copy` constructs another outer object using the same property values unless you supply replacements. A reference to a list is one of those values. Copying the list structure explicitly separates additions and removals, but still does not clone mutable elements inside it.

Choose sharing deliberately. Immutable data can be safe to share and cheaper than cloning. Mutable state needs a clear owner or a clear protocol. Mini's memory modes manage allocation lifetime; they do not automatically make your domain model immutable.

## Prefer composition when behavior can be supplied

```kotlin
interface PriceRule {
    fun apply(cents: Int): Int
}

class HalfPrice : PriceRule {
    override fun apply(cents: Int): Int = cents / 2
}

class Checkout(private val rule: PriceRule) {
    fun total(cents: Int): Int = rule.apply(cents)
}

fun main() {
    println(Checkout(HalfPrice()).total(900))
}
```
```output
450
```

`Checkout` delegates the pricing decision to a collaborator instead of inheriting from a growing tree of checkout subclasses. Interfaces describe a behavior contract; they do not by themselves enforce every business rule. Here we still need to decide whether a negative input is acceptable.

Use inheritance when a subtype really satisfies the parent's contract. Kotlin makes classes final by default, so inheritance is a decision you mark with `open`, rather than an accidental extension point.

## Exercise: make missing states visible

Add `Unavailable(reason: String)` to the sealed result below and handle it explicitly in `describe`. Then print `unavailable: maintenance` after the existing line. Do not add an `else` branch.

```kotlin exercise
sealed interface Availability
data class Available(val seats: Int) : Availability

fun describe(value: Availability): String = when (value) {
    is Available -> "available: ${value.seats}"
}

fun main() {
    println(describe(Available(3)))
}
```
```output
available: 3
unavailable: maintenance
```

::: details Show solution
```kotlin
sealed interface Availability
data class Available(val seats: Int) : Availability
data class Unavailable(val reason: String) : Availability

fun describe(value: Availability): String = when (value) {
    is Available -> "available: ${value.seats}"
    is Unavailable -> "unavailable: ${value.reason}"
}

fun main() {
    println(describe(Available(3)))
    println(describe(Unavailable("maintenance")))
}
```
```output
available: 3
unavailable: maintenance
```

After adding the subtype, check the program before changing `describe`. The incomplete `when` is the compiler pointing to a decision you still need to make.
:::

## Design checkpoint

What should happen if `SeatCounter` is given a negative capacity? Which property prevents outsiders from breaking the reservation count? Which objects are shared after `ReadingPlan.copy`? State the answers before deciding that a type is “immutable” or “safe.”

Language references: Kotlin [data classes](https://kotlinlang.org/docs/data-classes.html), [classes](https://kotlinlang.org/docs/classes.html) and [properties](https://kotlinlang.org/docs/properties.html).
