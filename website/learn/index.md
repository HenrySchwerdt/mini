---
title: Learn Mini
notebook: true
learnNotebook: true
aside: false
---

# Kotlin, with a native way of thinking

Learn the language, build something useful, then understand what Mini does with its memory. This is a guided notebook: explanations stay in place, while every runnable code cell is yours to change.

Mini compiles Kotlin to native programs. You learn Kotlin's types, functions, objects and collections first, then Mini's reference counting, allocators, ownership checker and native boundary. Mini also adds a matrix operator, `@`; that chapter calls out the syntax extension explicitly.

**No installation needed.** Read a cell, predict its result, then press **Run**. Click directly into the code to experiment; **Ctrl/Cmd+Enter** runs the focused cell. **Reset** restores the example. Nothing you edit changes the published lesson.

```kotlin
fun main() {
    val language = "Kotlin"
    val destination = "native code"
    println("$language → $destination, with Mini")
}
```
```output
Kotlin → native code, with Mini
```

## A course, not a list of language features

This course has **26 chapters**, arranged so that a concept first appears in a small example, then in an API or project, and finally in a lifetime or testing decision. Read in order if you are new to programming. Each chapter gives you code to inspect, a prediction to make and an experiment or exercise to complete.

Use the chapter list on the left to jump directly to a topic. On a phone, open **Menu**. The links at the bottom continue to the next lesson. Keep the code editable while treating explanations as your reference: you cannot accidentally delete a lesson or change its structure.

| Part | Chapters | Skills to practice |
|---|---|---|
| Getting started | [1. Hello](./hello), [2. Values and types](./values), [3. Branching and loops](./basics), [4. Functions and scope](./functions) | Trace execution, choose types, understand references and write small operations with explicit inputs and results. |
| Model data and behavior | [5. Classes](./classes), [6. States and invariants](./modelling) | Define constructors, protect valid state, distinguish equality from identity and choose sealed alternatives. |
| Work with data | [7. Lambdas and collections](./collections), [8. Collection design](./collection-design), [9. Null safety](./errors), [10. Failure and parsing](./error-design) | Transform data without silently losing information; make ordering, missing values and invalid input explicit. |
| Build reusable programs | [11. Generics](./generics), [12. Extensions and receivers](./extensions), [13. Task-report project](./project) | Read type contracts, build small APIs and connect parsing, transformation and formatting. |
| Understand Mini memory | [14. Managed memory](./memory-managed), [15. Reference graphs](./memory-graphs), [16. Allocators](./memory-allocators), [17. Strict mode](./memory-strict) | Draw an object graph, recognize cycles, explain region lifetimes and read ownership diagnostics. |
| Design Mini lifetimes | [18. Ownership APIs](./ownership), [19. Resources](./resources), [20. C interop](./c-interop), [21. Performance](./performance), [22. Memory boundaries](./memory-design) | Follow transfer and cleanup across calls, failures, fields, callbacks and native boundaries; understand current checker limits. |
| Practice and build | [23. Matrix operator](./matrix), [24. Tests and debugging](./testing), [25. Native workflow](./workflow), [26. Import-report capstone](./capstone) | Verify behavior, reduce failures and build a larger program with structured errors, deterministic reports and explicit lifetime decisions. |

### Choose a starting point

**New to programming:** begin with Hello and spend time predicting each result. Do not skip the small exercises in favor of the longest example. A useful session is one or two chapters, followed by writing a similar example without looking.

**Know another language:** work through values, modelling, collection design and error design before assuming familiar syntax has familiar behavior. Pay particular attention to read-only references, shallow copies, nullable lookup results and extension dispatch.

**Already know Kotlin:** start with Managed memory and reference graphs, then continue through the lifetime chapters. Return to the capstone to practice Mini's project workflow and its current boundaries around collections and allocators.

The estimated chapter times include hands-on work, not just reading. Plan several sessions: about **15–20 hours** for a first careful pass, longer if programming itself is new. Mastery comes from changing and explaining examples, not reaching the last page quickly.

### A practice routine that reveals gaps

For each chapter, do four things:

1. Predict a result before Run or Check.
2. Change one input or operation and predict the change.
3. Complete the exercise before opening the solution.
4. Explain the rule in your own words and test one boundary the lesson did not show.

Hints and solutions are separate so you can ask for a nudge before seeing the entire answer. A solution is one implementation; its contract and the reasons it works matter more than copying its spelling.

## Read → predict → run → explain

Before running this cell, decide whether the second line prints `1` or `2`:

```kotlin
fun main() {
    val numbers = mutableListOf(1)
    val sameNumbers = numbers
    sameNumbers.add(2)
    println(numbers)
    println(numbers.size)
}
```
```output
[1, 2]
2
```

`val` fixes a reference; it does not freeze the object. Both names refer to the same list. This distinction will return in data classes, collections and memory ownership. After running, replace `sameNumbers = numbers` with `sameNumbers = numbers.toMutableList()` and explain the new output.

**Exercises are experiments, not exams.** Their starter code runs but deliberately misses the requested output. The goal appears below the cell. Try a small edit, run again and only then reveal a solution. You can use **Playground** for a separate experiment or the [free-form notebook](/notebook) to write your own explanations and add cells.

## How this notebook executes code

A Run compiles a program and executes it in a fresh WebAssembly instance. The first run loads the browser toolchain; its status appears in the corner. Later runs reuse the downloaded compiler. If the build you are reading has no toolchain, the examples and documented results remain readable and you can [run them locally](/install).

A **complete program**, with its own `fun main`, runs independently. A **fragment** sees declarations from the earlier fragment cells on that page. Imports, functions, classes and top-level properties accumulate; earlier statements do not execute. Chapters start a fresh declaration context.

```kotlin
val greeting = "Hello"
fun welcome(name: String) = "$greeting, $name!"
println(welcome("Mini"))
```
```output
Hello, Mini!
```

```kotlin
// This fragment uses declarations from the fragment above.
println(welcome("notebook"))
```
```output
Hello, notebook!
```

Each run initializes top-level properties again. There is no persistent Python-style kernel, and running a later cell does not preserve the effects of an earlier run. Edit an existing declaration rather than defining it twice. A `@file:` annotation in a shared fragment affects the assembled program, so Strict examples use independent complete programs.

**Run all** runs the chapter's examples in order, skips exercise starters and stops at an unexpected result. **Check** runs the type and memory checker without executing the program. Some ownership examples intentionally fail: their expected diagnostic, such as `M0102`, is part of the lesson. Native-only listings are labelled and have no Run button.

## Where the explanations come from

The sequence takes inspiration from the official [Kotlin tour](https://kotlinlang.org/docs/kotlin-tour-welcome.html): small executable examples, one concept at a time, followed by practice. The explanations and exercises here are written for Mini. Links in the chapters point to Kotlin's documentation for the language rules and to this repository's guides for Mini's behavior.

Kotlin documentation covers more platforms and libraries than Mini implements. A Kotlin/JVM example using `java.*`, or a library expecting JVM bytecode, cannot simply be linked into a Mini binary. Use the [compatibility guide](/guide/mini-vs-kotlin), the [standard library reference](/stdlib) and the [roadmap](/roadmap) to check a feature's status.

Start with [Hello, Mini](./hello).
