---
title: "14. The @ operator"
notebook: true
learnNotebook: true
aside: false
---

# 14. The `@` operator

Mini adds exactly one operator to Kotlin: a binary `@`, in the spirit of Python's `@` ([PEP 465](https://peps.python.org/pep-0465/)). It is the matrix-multiplication operator. Nothing in the standard library defines it; you give your own types an `operator fun matmul`.

::: warning Not Kotlin
Programs that use `@` are not valid Kotlin and the Kotlin compiler cannot read them. Everything else in Mini is.
:::


**By the end:** define the operation behind `@`, predict its precedence and distinguish a binary operator from an annotation or a label. This is a Mini syntax extension, so it is the point where these examples leave standard Kotlin syntax.

## Define it, use it

`a @ b` means `a.matmul(b)`. The function must be an `operator fun matmul(other: X): R`, as a member or an extension, with exactly one parameter.

<<< @/samples/guide/matmul.kt{kotlin}

Press **Run**. A 2x2 matrix times a matrix, a matrix times a vector and a dot product, all with the same operator, chosen by the type of the right operand (`matmul` can be overloaded). The last lines show `@=`:

- `a @= b` calls `a.matmulAssign(b)` if there is one, otherwise it means `a = a @ b` for a `var`, the same rule as `*=` and `timesAssign`.
- `@` has the precedence of `*`, `/` and `%` and is left-associative: `a + b @ c` is `a + (b @ c)` and `a @ b @ c` is `(a @ b) @ c`.

## A small experiment

Powers of the Fibonacci matrix give Fibonacci numbers. Change `n` and watch the output:

```kotlin
class M(val a: Long, val b: Long, val c: Long, val d: Long) {
    operator fun matmul(o: M): M = M(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d)
}

fun fib(n: Int): Long {
    var result = M(1, 0, 0, 1)      // the identity
    var base = M(1, 1, 1, 0)
    var k = n
    while (k > 0) {
        if (k % 2 == 1) result = result @ base
        base = base @ base
        k /= 2
    }
    return result.b
}

println((1..10).map { fib(it) })
println(fib(80))
```
```output
[1, 1, 2, 3, 5, 8, 13, 21, 34, 55]
23416728348467685
```

## Where `@` is not an operator

`@` is the binary operator only after a complete operand and with whitespace on both sides. `@Name` with no space after it is always an annotation (`@Ann`, `@get:Ann`, `@file:Ann`), and labels (`return@label`, `this@Outer`, `break@loop`) keep their Kotlin meaning. A type without an `operator fun matmul` gives the error `no operator matmul for T`, and the call is an ordinary call that works in every memory mode and on every backend.

The reference section is [Mini and Kotlin/JVM: Matrix operator](/guide/mini-vs-kotlin#matrix-operator).

## Checkpoint

How is `a + b @ c` grouped? Does using `@` change who owns the result? Is `a @b` another spelling of matrix multiplication?

::: details Reasoning
It groups as `a + (b @ c)`. Ownership follows the same rules as an ordinary `matmul` call. Whitespace on both sides distinguishes the binary operator; `a @b` is not a product.
:::

## Where to go next

Continue with [From notebook to native project](./workflow) to put the pieces into a source tree with repeatable checks. Further references:

- [Install Mini](/install) and build a project with the [`mini` tool](/reference/mini).
- The [memory guide](/memory/) and the [diagnostics reference](/reference/diagnostics/) for everything about Strict mode.
- [How it works](/internals/) if you want to know how the compiler does it.
- The [playground](/playground) and the [notebook](/notebook) for your own experiments.
