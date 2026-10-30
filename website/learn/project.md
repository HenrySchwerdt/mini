---
title: "13. Build a task report"
notebook: true
learnNotebook: true
aside: false
---

# 13. Build a task report

Now combine the language features into a small program: read task descriptions, reject invalid records and summarize the unfinished work. The input stays in memory so the complete example runs in the browser. Later, a native application could obtain the same records from a file or another boundary.

**What you will practice:** data classes, nullable results, validation, collection pipelines, stable output and checks for edge cases. Keep this program in Managed mode. We will study explicit ownership separately rather than adding it before we need it.

## First define the data contract

A record has the form `title|minutes|done`. For this exercise the separator is literal, titles cannot contain `|`, minutes must be positive and the final field must be `yes` or `no`. This is a small text format, not a general CSV parser.

```kotlin
// These declarations are shared with the fragment cells below.
data class Task(val title: String, val minutes: Int, val done: Boolean)

fun parseTask(line: String): Task? {
    val parts = line.split("|")
    if (parts.size != 3) return null
    val title = parts[0].trim()
    val minutes = parts[1].trim().toIntOrNull() ?: return null
    val status = parts[2].trim()
    if (title.isEmpty() || minutes <= 0) return null
    if (status != "yes" && status != "no") return null
    return Task(title, minutes, status == "yes")
}

println(parseTask("Read Kotlin|20|no"))
println(parseTask("broken|many|no"))
```
```output
Task(title=Read Kotlin, minutes=20, done=false)
null
```

The parser turns an untrusted string into either a valid `Task` or absence. It never substitutes `0` for a malformed duration, because that would silently turn invalid input into apparently valid data.

Try blank titles, extra separators and an unknown status. `toIntOrNull` also rejects numbers outside the range of an `Int`. If a real UI needed to tell users *why* a record failed, replace the nullable result with a sealed result containing an error message and source line.

## Validate before calculating

We keep the count of rejected records. Silently dropping them would make a plausible report whose totals are wrong.

```kotlin
val taskLines = listOf(
    "Read Kotlin|20|no",
    "Write parser|35|yes",
    "Try Strict|15|no",
    "broken|many|no",
    "Write tests|10|no"
)
val tasks = taskLines.mapNotNull { parseTask(it) }
println("accepted = ${tasks.size}")
println("rejected = ${taskLines.size - tasks.size}")
```
```output
accepted = 4
rejected = 1
```

This is a fragment, so the parser above is available even if you did not run that earlier cell. Its declarations are compiled into the program; its `println` statements are not replayed. Each execution recreates `taskLines` and `tasks` from their initializers.

## Separate formatting from the data

A function returning a string is easier to inspect and test than a function that prints from several branches. The caller decides where to display it.

```kotlin
fun taskReport(items: List<Task>): String {
    val pending = items.filter { !it.done }.sortedBy { it.minutes }
    val total = pending.fold(0L) { sum, task -> sum + task.minutes.toLong() }
    return buildString {
        append("Pending: ").append(pending.size).append('\n')
        for (task in pending) {
            append(task.title).append(": ").append(task.minutes).append(" min\n")
        }
        append("Total: ").append(total).append(" min")
    }
}

println(taskReport(tasks))
```
```output
Pending: 3
Write tests: 10 min
Try Strict: 15 min
Read Kotlin: 20 min
Total: 45 min
```

The accumulator is a `Long` from the start; converting a wrapped `Int` sum afterward would be too late. We sort explicitly so presentation does not depend on a hash table's iteration order.

**Experiment:** mark `Read Kotlin` as done in the input cell, then run this cell. The report changes because the declaration initializer is compiled again. Simply running the input cell and mutating a local in its `main` would not preserve that mutation for this one.

## Check properties, not just one happy path

`check` throws when an expectation fails. These checks run as an ordinary Mini program and verify behavior that could easily regress during a refactor.

```kotlin
check(parseTask(" |3|no") == null)
check(parseTask("task|0|no") == null)
check(parseTask("task|3|maybe") == null)
check(parseTask("task|3|yes") == Task("task", 3, true))
check(taskReport(emptyList()) == "Pending: 0\nTotal: 0 min")
check(taskReport(listOf(Task("done", 5, true))) == "Pending: 0\nTotal: 0 min")
println("All report checks passed")
```
```output
All report checks passed
```

Do not remove a failed check to make a refactor look successful. Read which contract changed. In a native project these cases belong in test functions; [the native workflow chapter](./workflow) shows the project tool.

## Exercise: filter by a time budget

Write `withinBudget` so that it returns unfinished tasks taking at most the given number of minutes, in ascending duration order. The complete exercise is independent of the shared cells above.

```kotlin exercise
data class Task(val title: String, val minutes: Int, val done: Boolean)

fun withinBudget(items: List<Task>, budget: Int): List<Task> = items

fun main() {
    val items = listOf(Task("Read", 20, false), Task("Test", 10, false), Task("Done", 5, true))
    println(withinBudget(items, 15).map { it.title })
}
```
```output
[Test]
```

::: details Show solution
```kotlin
data class Task(val title: String, val minutes: Int, val done: Boolean)

fun withinBudget(items: List<Task>, budget: Int): List<Task> =
    items.filter { !it.done && it.minutes <= budget }.sortedBy { it.minutes }

fun main() {
    val items = listOf(Task("Read", 20, false), Task("Test", 10, false), Task("Done", 5, true))
    println(withinBudget(items, 15).map { it.title })
}
```
```output
[Test]
```

Also test a task exactly equal to the budget, an empty input, a negative budget and several tasks with the same duration. The boundary case distinguishes `<` from `<=`.
:::

## What Mini changes here

All data and functions above are ordinary Kotlin. Under Mini, their reachable objects are released by reference counting rather than a JVM garbage collector. Collection transformations can allocate intermediate lists. That is a cost to understand, not a reason to replace readable code without measuring it.

This graph has tasks contained in lists and no back references, so it does not form a reference cycle. Adding a callback stored on a task that captures the owning report could change that. Next we will learn how to reason about the object graph and when a different allocator becomes useful.
