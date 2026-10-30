---
title: "15. From notebook to native project"
notebook: true
learnNotebook: true
aside: false
---

# 15. From notebook to native project

The notebook has made the language visible. A project gives the same program a source tree, repeatable checks and tests. This chapter is a bridge to your own work, with a checklist of decisions you can now explain.

## Create a project

[Install Mini](/install), then run these commands in a terminal. Shell commands are instructions for your computer, not cells executed by the browser.

```sh
mini new task-report
cd task-report
mini run
mini check
mini test
```

`mini` is the project tool. It reads `mini.toml`, builds sources under `src/` and runs tests under `tests/`. `minic` is the lower-level compiler for individual programs. These names are easy to confuse: reach for `mini` when working on a project, and `minic` when you want to inspect one file or compiler output.

Move the `Task`, `parseTask` and `taskReport` declarations from [the project chapter](./project) into the generated source tree, keeping exactly one `main`. A notebook fragment may contain top-level print statements because the notebook generates `main` for you; a normal `.kt` application needs those statements inside a function.

Keep input acquisition separate from `parseTask`. That lets you test the same parser with in-memory strings and later obtain input from a native API without changing its contract.

## Make checks repeatable

The project tool discovers `@Test` functions in `tests/`. For example, after moving the parser into the project, create a test file:

```kotlin norun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Test
fun parsesTask() {
    assertEquals(Task("Read", 20, false), parseTask("Read|20|no"))
}

@Test
fun rejectsInvalidDuration() {
    assertNull(parseTask("Read|many|no"))
    assertNull(parseTask("Read|0|no"))
}
```

This listing needs your project declarations and the test runner; it is not a standalone browser program. Run it with `mini test`. If you add a package to the sources, import it in the test file too. The [project tool reference](/reference/mini) describes source discovery and test commands.

Check the public behavior: malformed input, boundary values and an empty report. Avoid making tests depend on the exact number of temporary lists used internally. You should be able to replace a pipeline with a loop while keeping the same tests.

## Choose a memory policy deliberately

Start in Managed mode. If profiling shows allocation or reference-counting overhead in a phase with one clear lifetime, investigate an arena. If you need explicit ownership that the compiler checks, isolate that responsibility in a Strict file and state its allocator contract.

| Question | Starting point |
|---|---|
| Do I just need a correct small application? | Ordinary Kotlin in Managed mode. |
| Can objects point back to their owner? | Draw the reference graph and check for cycles. |
| Do many objects live and die together? | Study an arena and make the region boundary explicit. |
| Does a function transfer responsibility for release? | State that in the ownership contract and check every exit path. |
| Does a C function return a pointer? | Read its release function and lifetime contract before wrapping it. |
| Is something slow? | Measure a native release build and inspect counters before choosing an allocator. |

The current limitations in the [memory guide](/memory/) and [roadmap](/roadmap) matter. A checker accepting a program does not establish that every native backend can generate it. Likewise, a passing browser example does not exercise a host C library or prove native performance.

## Read diagnostics as a path through your code

An ownership error points to an allocation, an invalid use or an exit path. First identify the object and the responsibility it carries. Then ask what happens on every return, branch and exception path. Adding `free()` until the message disappears is not a reliable way to decide who owns an object.

```sh
mini check
mini explain M0102
```

The [diagnostic catalog](/reference/diagnostics/) is also available in the browser. Keep a small failing case when investigating a confusing message; it is easier to reason about two branches than an entire application.

## Dependencies and the native boundary

Mini's standard library is compiled Kotlin source. Dependencies are Mini libraries obtained through the project tool, with paths or pinned git revisions. A Maven coordinate for a JVM library does not make its JAR usable by Mini.

```sh
cd ..
mini new report-core --lib
cd task-report
mini add report-core --path ../report-core
mini tree
```

Starting inside `task-report`, these commands create a sibling library and add it to the application, as described in [the project tool reference](/reference/mini). Keep reusable parsing and formatting in the library and let an application own input and output. If an API relies on `java.*`, reflection or coroutines, check Mini's implementation status before designing around it.

## Final experiment: keep a contract while changing an implementation

Both functions below calculate the sum of squares of even numbers. One makes the stages explicit with collections; the other expresses a loop. Predict the output, then change the inputs to include zero and negative values.

```kotlin
fun pipeline(values: List<Int>): Int =
    values.filter { it % 2 == 0 }.map { it * it }.fold(0) { sum, x -> sum + x }

fun loop(values: List<Int>): Int {
    var total = 0
    for (value in values) if (value % 2 == 0) total += value * value
    return total
}

fun main() {
    val values = listOf(1, 2, 3, 4, 5, 6)
    check(pipeline(values) == loop(values))
    println(loop(values))
}
```
```output
56
```

Equal results are necessary before comparing speed. This one sample is not a proof for every possible input; add representative cases and decide how overflow should be handled. Use native measurements for native performance, and keep the readable implementation unless evidence justifies changing it.

## Where to go next

You can now explain Kotlin's types and null handling, read lambdas and receiver APIs, assemble a small program and describe Mini's main differences. The next step is to build something small enough that you can still draw its object graph.

- Extend the report with structured parse errors and test their messages.
- Work through [ownership](/memory/ownership), [fields and containers](/memory/fields) and [lambda captures](/memory/lambdas) before designing an explicit-memory API.
- Read [How it works](/internals/) to connect Kotlin source to MIR, reference-count optimization and emitted code.
- Use the [playground](/playground) for a focused reproduction or the [notebook](/notebook) for your own mixed notes and experiments.

Kotlin's [tour](https://kotlinlang.org/docs/kotlin-tour-welcome.html) remains a useful language companion. Mini's own reference is the authority for its allocators, backend support and native tools.
