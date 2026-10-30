---
title: "26. Capstone: a reliable import report"
notebook: true
learnNotebook: true
aside: false
---

# 26. Capstone: a reliable import report

Build one program that combines the course instead of solving another isolated syntax puzzle. The task-report project accepted or dropped a record. This version preserves structured errors, detects duplicate IDs and separates validation from presentation.

Work through the stages before opening the complete implementation. Every runnable cell is a full program, so it can be copied to a local file without depending on hidden notebook state.

## Define the requirements before the types

Input lines have the form `id|title|minutes`. The ID and minutes are positive integers; the title is nonblank; fields are trimmed. An ID may appear at most once among accepted records. A rejected record does not reserve its ID. Blank lines are invalid records rather than silently ignored.

The output lists accepted tasks in ID order, followed by total minutes and rejected-line diagnostics in input order. Line numbers begin at one. Keep the original line number even after earlier records have been rejected.

These choices are deliberate. A different import format could stop at the first failure or reject the whole batch. Here we report all rejected lines while keeping valid data visible, with no external side effects.

## Stage 1: sketch the data model

You need a successful record, a problem with source context and a result that distinguishes the two. Keep errors separate from display strings that combine a line number with the message.

```kotlin
// An independent sketch of the boundary, before implementing the parser.
data class ImportTask(val id: Int, val title: String, val minutes: Int)
data class ImportProblem(val line: Int, val message: String)
sealed interface RowResult
data class GoodRow(val task: ImportTask) : RowResult
data class BadRow(val problem: ImportProblem) : RowResult

fun main() {
    val result: RowResult = BadRow(ImportProblem(2, "minutes must be positive"))
    val label = when (result) {
        is GoodRow -> "task ${result.task.id}"
        is BadRow -> "line ${result.problem.line}: ${result.problem.message}"
    }
    println(label)
}
```
```output
line 2: minutes must be positive
```

A successful record no longer needs nullable ID, title or minutes fields. It is created after validation. The problem retains a location so callers can show the user where to look.

For a production API you might give problems typed reasons or stable codes. The educational version uses messages to keep the complete program readable, while still distinguishing success from failure structurally.

## Stage 2: plan validation order

Write the guards before the implementation:

1. Split the line and require exactly three fields.
2. Parse the ID and require it to be positive.
3. Trim the title and require it to be nonblank.
4. Parse minutes and require them to be positive.
5. Return a valid record.

Duplicate detection belongs to the batch stage because a single-line parser does not know about earlier accepted records. This separation lets you test parsing independently and makes the batch's state explicit.

When a line has several faults, this parser reports the first guard that fails. That is part of the contract. To report every issue in one line you would need a different result shape and validation strategy.

## Stage 3: implement, aggregate and present

Read the functions in order, but keep each responsibility separate: `parseRow` validates one record, `importRows` applies batch rules, and `formatImport` produces deterministic text.

```kotlin
// This complete program runs independently of the sketch above.
data class Task(val id: Int, val title: String, val minutes: Int)
data class Problem(val line: Int, val message: String)
sealed interface ParsedRow
data class Accepted(val task: Task) : ParsedRow
data class Rejected(val problem: Problem) : ParsedRow
data class ImportResult(val tasks: List<Task>, val problems: List<Problem>)

fun parseRow(text: String, line: Int): ParsedRow {
    val parts = text.split("|")
    if (parts.size != 3) return Rejected(Problem(line, "expected 3 fields"))
    val id = parts[0].trim().toIntOrNull()
        ?: return Rejected(Problem(line, "invalid id"))
    if (id <= 0) return Rejected(Problem(line, "id must be positive"))
    val title = parts[1].trim()
    if (title.isEmpty()) return Rejected(Problem(line, "empty title"))
    val minutes = parts[2].trim().toIntOrNull()
        ?: return Rejected(Problem(line, "invalid minutes"))
    if (minutes <= 0) return Rejected(Problem(line, "minutes must be positive"))
    return Accepted(Task(id, title, minutes))
}

fun importRows(lines: List<String>): ImportResult {
    val tasks = mutableListOf<Task>()
    val problems = mutableListOf<Problem>()
    val seen = mutableSetOf<Int>()
    for (index in lines.indices) {
        when (val parsed = parseRow(lines[index], index + 1)) {
            is Accepted -> {
                if (parsed.task.id in seen) {
                    problems.add(Problem(index + 1, "duplicate id ${parsed.task.id}"))
                } else {
                    seen.add(parsed.task.id)
                    tasks.add(parsed.task)
                }
            }
            is Rejected -> problems.add(parsed.problem)
        }
    }
    return ImportResult(tasks.toList(), problems.toList())
}

fun formatImport(result: ImportResult): String = buildString {
    for (task in result.tasks.sortedBy { it.id }) {
        append(task.id).append(": ").append(task.title)
            .append(" (").append(task.minutes).append(" min)\n")
    }
    val total = result.tasks.fold(0L) { sum, task -> sum + task.minutes.toLong() }
    append("Total: ").append(total).append(" min\n")
    append("Rejected: ").append(result.problems.size)
    for (problem in result.problems) {
        append('\n').append("line ").append(problem.line)
            .append(": ").append(problem.message)
    }
}

fun main() {
    val result = importRows(listOf(
        "2|Read ownership|25",
        "1|Write tests|15",
        "2|Duplicate|5",
        "3| |10",
        "4|Measure|many"
    ))
    check(result.tasks.size == 2)
    check(result.problems.map { it.line } == listOf(3, 4, 5))
    check(importRows(emptyList()).tasks.isEmpty())
    check(importRows(listOf("1|bad|0", "1|valid|5")).tasks.size == 1)
    println(formatImport(result))
}
```
```output
1: Write tests (15 min)
2: Read ownership (25 min)
Total: 40 min
Rejected: 3
line 3: duplicate id 2
line 4: empty title
line 5: invalid minutes
```

## Understand the decisions in the implementation

`seen` is updated only after accepting a valid unique record. Otherwise a bad line could block a later valid one with the same ID. The source line number comes from the input index, rather than the current number of accepted records.

The result copies the mutable list structures before returning them. The record types contain read-only scalar/string properties, so callers do not receive the builder lists. If records later acquire mutable child objects, reconsider whether those objects are intentionally shared.

Sorting belongs in presentation because validation does not depend on display order. Problems remain in input order so users can work through their source document. The total starts as a `Long`, preserving the wider accumulation type from the first addition.

The program performs no file writes during validation. If a future version saves records to an external system, decide whether it requires all rows to be valid before applying effects. A successful parser does not make partial writes transactional.

## Stage 4: challenge the behavior

Before looking at a solution, extend `main` with checks for:

- A blank line and a line with too many fields.
- IDs zero, negative and outside `Int` range.
- A title with surrounding spaces.
- Two valid records sharing an ID.
- An invalid record followed by a valid record with the same ID.
- Two durations whose total exceeds `Int.MAX_VALUE`.
- An input with only rejected lines.

For each test, state the expected accepted records and problem line numbers. Checking only the number of problems can miss reporting the wrong source line.

## Stage 5: an extension to implement yourself

Add a budget parameter to the report. Print `Within budget: yes` or `Within budget: no` after the total. The comparison should include equality: a total equal to the budget is within budget. Keep parsing and duplicate detection unchanged.

::: details Design hint
Pass the budget to the formatting or summary operation, not to `parseRow`. Validate the budget separately if negative budgets are forbidden. Reuse a single total rather than calculating it in different integer types in different places.
:::

::: details Further extensions
Introduce typed error reasons; support escaped separators with a real parser; add a filter over accepted tasks; extract parsing into a library; or turn all contract checks into native test functions. Add one capability at a time and keep the original regression cases.
:::

## Explain Mini's behavior for this program

The implementation intentionally remains Managed. Its lists and records have ordinary reference-counted lifetimes and no back references forming cycles. Intermediate collections and result copies may allocate, but that is a measurement question before it is a reason to change the memory model.

Pasting a Strict annotation above this code is not a complete migration: tracked owners cannot currently be stored in ordinary collections, and API boundaries would need explicit contracts. If the importer grows a large temporary graph, draw that region separately and return an independent report rather than leaking graph references through callbacks.

A successful capstone means you can explain the types, failure policy, tests and lifetime boundary, not just reproduce the displayed output. Move it into a native project when you can change one requirement and predict which functions and tests should change with it.
