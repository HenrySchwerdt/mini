---
title: "24. Test behavior and debug systematically"
notebook: true
learnNotebook: true
aside: false
---

# 24. Test behavior and debug systematically

Running an example once shows what happened for one input. Learning to program also means knowing how to challenge a result, reduce a failure and decide whether a change fixed the original problem.

We will test a small range calculation, make boundary cases visible and use an implementation-independent property to catch mistakes.

## Begin with a contract

Suppose `totalMinutes` sums nonnegative durations, ignoring negative entries. Empty input returns zero. The result is a `Long` so a collection of valid `Int` durations can have a wider total.

```kotlin
fun totalMinutes(values: List<Int>): Long {
    var total = 0L
    for (value in values) if (value >= 0) total += value.toLong()
    return total
}

fun main() {
    check(totalMinutes(emptyList()) == 0L)
    check(totalMinutes(listOf(5)) == 5L)
    check(totalMinutes(listOf(5, -2, 7)) == 12L)
    check(totalMinutes(listOf(Int.MAX_VALUE, Int.MAX_VALUE)) == 4294967294L)
    println("4 checks passed")
}
```
```output
4 checks passed
```

Each case challenges a different part of the contract: empty input, one value, filtering and accumulation width. Ten variants of the same ordinary positive list would add less confidence than these different cases.

`check` throws if an expectation is false. That is enough to demonstrate testing inside the notebook. A native project can put these expectations in discoverable test functions and use `mini test`, as shown in the workflow chapter.

## Boundaries distinguish similar implementations

Consider selecting values up to a budget. The difference between `<` and `<=` is invisible unless an input reaches the boundary.

```kotlin
fun fitsBudget(minutes: Int, budget: Int): Boolean = minutes <= budget

fun main() {
    val durations = listOf(9, 10, 11)
    println(durations.map { fitsBudget(it, 10) })
    check(fitsBudget(10, 10))
    check(!fitsBudget(11, 10))
}
```
```output
[true, true, false]
```

For a lower bound, test below, at and above it. For a collection, test empty, singleton and multiple values. For a parser, include missing separators, extra fields, invalid numbers and overflow. These are not arbitrary edge cases; they distinguish interpretations of the contract.

Do not let the implementation choose the expected result for you. If the test calculates its expected answer using the same expression as the function, both can repeat the same mistake.

## Compare properties, not just printed snapshots

A property describes a relationship that should hold for a family of inputs. For a sum of independently accepted values, splitting a list and adding the two totals should equal the total of the combined list.

```kotlin
fun acceptedTotal(values: List<Int>): Long =
    values.fold(0L) { sum, value -> if (value >= 0) sum + value.toLong() else sum }

fun main() {
    val left = listOf(3, -1, 8)
    val right = listOf(0, 4)
    check(acceptedTotal(left + right) == acceptedTotal(left) + acceptedTotal(right))
    check(acceptedTotal(left.reversed()) == acceptedTotal(left))
    println("split and order properties passed")
}
```
```output
split and order properties passed
```

The first check tests composition; the second tests independence from order. These are selected examples of properties, not an exhaustive mathematical proof. A larger test suite can generate many small inputs while still keeping fixed regression cases for failures already discovered.

Properties must match the domain. A formatting function preserving arrival order should not satisfy order independence. A state machine might deliberately reject a reordered sequence of operations.

## Debug a failure in a repeatable order

When an output surprises you, record the input and the expected result first. Then inspect the smallest step that can explain the difference.

1. Confirm you are executing the cell or binary you changed.
2. Read the first relevant diagnostic rather than fixing the last symptom first.
3. Reduce the input until the failure still occurs with fewer moving parts.
4. Inspect intermediate values at the boundary where they become wrong.
5. Change one suspected cause, rerun the original case and retain a regression check.

In a shared notebook fragment, also inspect declarations in earlier cells. Editing a function above changes the assembled program even when that earlier cell's statements are not executed. Runtime state does not persist between runs, so a previous successful mutation is not an explanation for the next run's state.

## Know which tool answers which question

| Tool | Answers | Does not establish |
|---|---|---|
| Compiler/type checker | Is the source accepted and type-consistent? | Correct business behavior for every input |
| Mini memory checker | Are its tracked ownership rules satisfied? | Every foreign API annotation is truthful |
| Unit or notebook check | Does a behavior hold for selected cases? | All possible cases or native performance |
| Browser run | What does this WebAssembly program do here? | Host-library integration |
| Native integration test | Do components work together on this target? | Behavior on every platform |
| Measurement | What cost occurs for this workload? | Why it occurs without further investigation |

These tools complement each other. Passing the checker cannot replace a parser test. A parser test cannot prove a borrowed pointer remains valid across a foreign call.

## Exercise: catch the inclusive-boundary bug

The contract says that values equal to the limit count. Add checks that fail for the starter, then fix it. Its printed result should become `2` and `0`.

```kotlin exercise
fun countWithin(values: List<Int>, limit: Int): Int {
    var count = 0
    for (value in values) if (value < limit) count++
    return count
}

fun main() {
    println(countWithin(listOf(9, 10, 11), 10))
    println(countWithin(emptyList(), 10))
}
```
```output
2
0
```

::: details Show solution
```kotlin
fun countWithin(values: List<Int>, limit: Int): Int {
    var count = 0
    for (value in values) if (value <= limit) count++
    return count
}

fun main() {
    check(countWithin(listOf(10), 10) == 1)
    check(countWithin(listOf(11), 10) == 0)
    check(countWithin(emptyList(), 10) == 0)
    println(countWithin(listOf(9, 10, 11), 10))
    println(countWithin(emptyList(), 10))
}
```
```output
2
0
```

The singleton boundary case makes the cause obvious. Keep it even if you later replace the loop with `count { ... }`.
:::

## Practice before the final project

Write down three cases for the task parser that are not in its example input. Predict whether each is accepted and which error it should produce. Then run them. Any disagreement is useful: it reveals either an implementation defect or a missing requirement.

For native projects, see the [project-tool testing reference](/reference/mini). For lifetime failures, keep the diagnostic code and the smallest failing ownership path alongside your reproduction.
