---
title: "1. Hello, Mini"
notebook: true
---

# 1. Hello, Mini

A Mini program is a Kotlin program: a file with a `main` function.

```kotlin
fun main() {
    println("Hello, Mini!")
}
```
```output
Hello, Mini!
```

Press **Run**. The code is compiled to WebAssembly by Mini's own compiler (the same one you [install](/install) on your machine, built for the browser) and run on the spot.

## Change it

Press **Edit** and change the text. Compile errors show up with a position and a message instead of output. Try breaking it on purpose: delete a closing quote and press **Check**.

## String templates

`$name` and `${expression}` put values into strings:

```kotlin
val name = "Mini"
val year = 2026
println("Hello, $name!")
println("In $year, 6 * 7 = ${6 * 7}")
```
```output
Hello, Mini!
In 2026, 6 * 7 = 42
```

This cell has no `fun main`. On a tutorial page the statements of a cell become the body of a generated `main`, and `val name` and `val year` are top-level declarations that later cells can use:

```kotlin
println("Goodbye, $name (${year + 1} is next year)")
```
```output
Goodbye, Mini (2027 is next year)
```

## From source to a native binary

On your machine the same program is a build away from a native executable:

```sh
minic build hello.kt -o hello
./hello
```

`minic` translates Kotlin to C17, and your C compiler links the result with a small runtime. There is no JVM and no garbage collector at run time. The [installation page](/install) has the details, and [How it works](/internals/) explains the pipeline.

## Exercise

::: tip Exercise: greet everyone
Change `main` so that it prints one greeting per name in the list, in the form `Hello, Ada!`.
:::

```kotlin exercise
fun main() {
    val names = listOf("Ada", "Grace", "Linus")
    println("Hello, ${names[0]}!")
}
```
```output
Hello, Ada!
Hello, Grace!
Hello, Linus!
```

::: details Show solution
```kotlin
fun main() {
    val names = listOf("Ada", "Grace", "Linus")
    for (name in names) {
        println("Hello, $name!")
    }
}
```
```output
Hello, Ada!
Hello, Grace!
Hello, Linus!
```
:::

Next: [Values, functions and control flow](./basics).
