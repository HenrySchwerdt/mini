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

## A path through the notebook

Use the chapters on the left to jump anywhere. On a phone, open **Menu** to find them. Each chapter has a previous/next link at the bottom, examples, a short reasoning checkpoint and an exercise with a solution you can reveal.

| Part | Chapters | What you will be able to do |
|---|---|---|
| Kotlin foundations | [1. Hello](./hello) · [2. Values and control flow](./basics) · [3. Types and classes](./classes) · [4. Collections](./collections) · [5. Null safety](./errors) | Read and write small Kotlin programs; model data and handle missing or invalid input. |
| Thinking in Kotlin | [6. Generics](./generics) · [7. Extensions and scope](./extensions) · [8. A task report](./project) | Express reusable contracts and combine the pieces in one working program. |
| What makes Mini different | [9. Managed memory](./memory-managed) · [10. Allocators](./memory-allocators) · [11. Strict](./memory-strict) · [12. C](./c-interop) · [13. Performance](./performance) · [14. Matrix operator](./matrix) | Explain who releases memory, read ownership diagnostics, and distinguish browser experiments from native execution. |
| Keep building | [15. A native project](./workflow) | Turn the notebook's program into a project you can build, check and test locally. |

Allow several sessions: roughly five to six hours including experiments. If you already know Kotlin, start at [Managed memory](./memory-managed), then return to the project as a practical checkpoint. If programming is new to you, take the first five chapters in order and change one thing at a time.

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
