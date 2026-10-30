---
title: Learn Mini
notebook: true
---

# Learn Mini

A hands-on tutorial. Every code block on these pages is a live cell: **edit it, press Run, and the compiler that runs in your browser builds and runs it**. No install needed. If you would rather work locally, [install Mini](/install) and paste the code into a `.kt` file.

```kotlin
fun main() {
    println("Hello from your browser!")
}
```
```output
Hello from your browser!
```

The first Run downloads the compiler (about 100 MB, cached by your browser afterwards; it starts in the background as soon as a page opens, see the badge in the corner). The cells compile real Mini with the real compiler: what you see is what `minic` prints on your machine, apart from timing.

## The chapters

Mini is Kotlin, so the first part is a quick Kotlin tour. The second part is what Kotlin does not give you.

| # | Chapter | You learn |
|---|---|---|
| 1 | [Hello, Mini](./hello) | `main`, `println`, string templates, building a native binary |
| 2 | [Values, functions and control flow](./basics) | `val`/`var`, functions, `if`, `when`, loops, ranges |
| 3 | [Classes, data and sealed types](./classes) | classes, interfaces, `data`, `enum`, `sealed`, `object` |
| 4 | [Lambdas, collections and strings](./collections) | higher-order functions, `List`/`Map`/`Set`, `StringBuilder`, generics |
| 5 | [Null safety and exceptions](./errors) | `?.`, `?:`, smart casts, `try`/`catch`, extension functions |
| 6 | [Memory: managed by default](./memory-managed) | reference counting, what it costs, `@file:MemoryMode` |
| 7 | [Allocators and arenas](./memory-allocators) | `Heap`, `Arena`, allocators as context parameters |
| 8 | [Strict mode](./memory-strict) | leaks, double free, use after free as compile errors, with live Check cells |
| 9 | [C interop](./c-interop) | calling C, and what the browser cannot run |
| 10 | [Performance](./performance) | native code, RC elision, `-O`, the LLVM backends, numbers |
| 11 | [The `@` operator](./matrix) | Mini's one syntax extension |

Chapters 1 to 5 can be skipped if you know Kotlin. Chapters 6 to 8 are the heart of Mini.

## How the cells work

- **Run** compiles the cell and runs it in a fresh WebAssembly instance. Output appears below the cell, with the exit code and time. **Stop** ends a running program. Programs are killed after 10 seconds or 4 MiB of output.
- **Check** runs only the type checker and the memory checker. It is quick (a few MB of the compiler) and prints diagnostics like `minic check` does, with the error code linked to its [reference page](/reference/diagnostics/).
- **Edit** turns the code into an editor (syntax highlighting, squiggles for diagnostics, Ctrl+Enter to run). **Reset** returns to the original code.
- **Playground** opens the cell in the full [playground](/playground).
- A green check mark below the output says that it matches the output printed in this tutorial. In an exercise it means you reached the goal.

### Cells share declarations

Tutorial pages are *notebooks*. A cell that is not a complete program (no `fun main`) sees the **declarations** of the cells above it (functions, classes, `val`s at the top level, imports) and its own statements run as the body of `main`. The statements of earlier cells are not run again. Try it:

```kotlin
val greeting = "Hello"
fun shout(s: String) = s.uppercase() + "!"
println(shout(greeting))
```
```output
HELLO!
```

```kotlin
// `greeting` and `shout` come from the cell above
println(shout("$greeting, notebook"))
```
```output
HELLO, NOTEBOOK!
```

Every Run is a separate program, so values do not survive from one Run to the next, and a top-level `var` starts from its initial value every time. A cell with its own `fun main` stands alone. **Run all** at the top of a chapter runs every cell from top to bottom.

::: tip Not in the browser
Some things need a real machine: calling C libraries, files and the network, anything that runs longer than a few seconds. Those blocks are plain highlighted code and say so. [Install Mini](/install) to try them.
:::

## Exercises

Each chapter ends with exercises. The starter code compiles and runs; change it until the output matches the goal. *Show solution* reveals one possible answer, which is a live cell too.

Ready? Start with [Hello, Mini](./hello).
