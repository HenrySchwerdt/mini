---
title: "10. Design failure and parse input"
notebook: true
learnNotebook: true
aside: false
---

# 10. Design failure and parse input

A program's failure behavior is part of its API. Returning null, returning structured information and throwing an exception communicate different contracts. Learn to choose among them instead of surrounding an entire program with a catch that prints `something failed`.

We will parse a duration entered by a user and gradually improve the information available to its caller.

## Absence is useful when the reason is unimportant

```kotlin
fun positiveMinutes(text: String): Int? {
    val value = text.trim().toIntOrNull() ?: return null
    return if (value > 0) value else null
}

fun main() {
    for (text in listOf(" 15 ", "zero", "0", "-3")) {
        println("$text -> ${positiveMinutes(text)}")
    }
}
```
```output
 15  -> 15
zero -> null
0 -> null
-3 -> null
```

The function promises either a positive integer or no result. The caller cannot distinguish a non-number from a negative number. That may be enough for a search predicate, but it is weak feedback for an input form.

Do not return zero as a failure sentinel here: zero could later become a valid value, and callers might accidentally add it to a total as if parsing had succeeded. A nullable type forces a decision at the call site.

## A sealed result preserves the reason

```kotlin
sealed interface DurationResult
data class ValidDuration(val minutes: Int) : DurationResult
data class InvalidDuration(val message: String) : DurationResult

fun readDuration(text: String): DurationResult {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return InvalidDuration("enter a duration")
    val number = cleaned.toIntOrNull() ?: return InvalidDuration("use a whole number")
    if (number <= 0) return InvalidDuration("duration must be positive")
    return ValidDuration(number)
}

fun durationMessage(result: DurationResult): String = when (result) {
    is ValidDuration -> "${result.minutes} min"
    is InvalidDuration -> result.message
}

fun main() {
    for (text in listOf("", "many", "0", "15")) {
        println(durationMessage(readDuration(text)))
    }
}
```
```output
enter a duration
use a whole number
duration must be positive
15 min
```

Each guard explains one condition. The successful result is constructed only after those conditions pass. Keeping formatting outside `readDuration` makes it possible to change how errors are displayed without changing parsing.

If callers need stable machine-readable reasons, use separate sealed error cases or an error enum instead of comparing English message strings. Messages are for people; codes or types are for branching.

## Recover at a boundary that can make a decision

```kotlin
fun divideBudget(total: Int, participants: Int): Int {
    require(participants > 0) { "participants must be positive" }
    return total / participants
}

fun main() {
    try {
        println(divideBudget(100, 0))
    } catch (error: IllegalArgumentException) {
        println("Cannot split: ${error.message}")
    }
    println("ready for another input")
}
```
```output
Cannot split: participants must be positive
ready for another input
```

The caller can ask for another input, so it has a recovery policy. A helper deep in the calculation should usually not swallow the exception and invent a success value.

`require` checks an argument or precondition. `check` checks an expected state. Use either only when a violated condition should raise an exception. Expected invalid user input is often more naturally represented as an explicit result, as in the parser above.

Catch the specific failure you can handle. A catch around an entire pipeline may hide a programming error in a later step. Logging an exception and continuing with partial state is not a complete recovery strategy unless the rest of the program is designed for that state.

## Cleanup is separate from recovery

```kotlin
fun attempt(divisor: Int): String {
    return try {
        "answer=${12 / divisor}"
    } catch (error: ArithmeticException) {
        "invalid divisor"
    } finally {
        println("finished attempt")
    }
}

fun main() {
    println(attempt(3))
    println(attempt(0))
}
```
```output
finished attempt
answer=4
finished attempt
invalid divisor
```

`finally` runs when the attempt leaves the block, whether it succeeds or fails. It does not turn failure into success. Avoid returning from `finally`: that can replace the original result or failure and make the path hard to understand.

For a resource with `AutoCloseable`, prefer the structured `use` pattern rather than manually duplicating cleanup in success and catch branches. Mini gives resource lifetime additional ownership meaning, covered later in [Resources and error paths](./resources).

## Batch processing needs an explicit policy

For several records, decide whether to stop at the first error, keep all valid records with an error count, or collect every error. Each policy is reasonable in a different application.

A compiler often wants to report several independent errors in one run. A financial import may require the entire batch to be valid before applying any effects. A learning report might retain valid lines while clearly reporting rejected lines. Do not silently drop invalid records simply because `mapNotNull` makes that concise.

A useful batch result can contain both `accepted` and `problems`. Each problem should identify its source line. Keep that result separate from side effects so validation can finish before anything irreversible happens.

## Exercise: distinguish missing and invalid input

`readCount` should return `missing` for blank text, `invalid` for a non-number or a negative number, and `count=N` for a nonnegative number. Zero is valid. The starter marks every nonblank value as valid without parsing it.

```kotlin exercise
fun readCount(text: String): String {
    if (text.isBlank()) return "missing"
    return "count=$text"
}

fun main() {
    for (text in listOf("", "x", "-1", "0", " 4 ")) println(readCount(text))
}
```
```output
missing
invalid
invalid
count=0
count=4
```

::: details Hint
Normalize once, parse with `toIntOrNull`, then check the numeric boundary. `?: return "invalid"` handles a failed conversion without `!!`.
:::

::: details Show solution
```kotlin
fun readCount(text: String): String {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return "missing"
    val count = cleaned.toIntOrNull() ?: return "invalid"
    if (count < 0) return "invalid"
    return "count=$count"
}

fun main() {
    for (text in listOf("", "x", "-1", "0", " 4 ")) println(readCount(text))
}
```
```output
missing
invalid
invalid
count=0
count=4
```

For a reusable API, replace these display strings with structured result types and format them at the edge. Try that as a second exercise.
:::

## Explain the contract

Why can the nullable parser not tell a caller what to fix? Where should an application decide whether to continue after a bad line? What could be lost by catching every exception and returning an empty list? Answer in terms of information available to the caller, not just number of lines of code.

Language references: Kotlin [exception handling](https://kotlinlang.org/docs/exceptions.html) and [null safety](https://kotlinlang.org/docs/null-safety.html).
