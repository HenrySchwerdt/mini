---
title: "13. Performance"
notebook: true
learnNotebook: true
aside: false
---

# 13. Performance

Mini compiles Kotlin to C17 (or LLVM IR) and lets clang optimize it. There is no virtual machine, no garbage collector and no warm-up. A hello-world starts in about a millisecond and the binary is 80 to 210 KiB.


**By the end:** separate compile time from runtime, compare equivalent algorithms and use native counters to investigate allocation costs. A browser timing is a useful experiment in that browser, not a native benchmark.

## Measure something

This cell counts the primes below one million with a sieve. The panel under the cell shows how long the run took:

```kotlin
fun countPrimes(limit: Int): Int {
    val composite = BooleanArray(limit + 1)
    var count = 0
    for (i in 2..limit) {
        if (!composite[i]) {
            count++
            var j = i.toLong() * i
            while (j <= limit) {
                composite[j.toInt()] = true
                j += i
            }
        }
    }
    return count
}

println(countPrimes(1_000_000))
```
```output
78498
```

In the browser this runs as WebAssembly. Compiled natively with `minic build -O2`, the same program is typically several times faster, because the browser's WebAssembly engine is not clang and because the checks the sandbox needs are not free. Edit the limit and see how the time scales.

## What the compiler gives you

- **`-O0`, `-O1` (default), `-O2`, `-O3`, `-Os`.** The optimization level of the C compiler.
- **RcOpt, reference-count elision.** The pass that removes most retain and release calls from Managed code. On the project's micro benchmarks the time of `arraylist`, `mapfilter` and `tree` drops by 35 to 40 percent, and `tree` goes from 8.3 million retains to none. `--no-rc-opt` turns it off for comparison.
- **`--stats`.** Builds the program so that it prints allocation and reference-count counters to stderr at exit: allocations, frees, live objects at exit and the peak, and retain/release calls split into non-atomic, atomic and no-ops. Use it to find where counting remains.
- **`--emit-c file.c`.** Keeps the generated C. It is readable; the [How it works](/internals/) pages walk through it.
- **Backends.** The default backend writes C. `--backend=llvm` writes textual LLVM IR, and a build of the compiler with the LLVM C API bound in has `--backend=llvm-api`, which emits object files directly and can **run a program in memory with a JIT** (`--run`). See the [command line reference](/reference/minic).
- **Allocators.** Arenas allocate by bumping a pointer and free everything at once, with no per-object counting. For a workload with many small objects of one lifetime (a request, a compiler phase, a frame) that is the biggest single lever. [Chapter 10](./memory-allocators) shows the syntax.

## Numbers

The project measures eight programs against C, Rust, Go, Kotlin/JVM and Kotlin/Native on a 4-core VM (best of 5, results checked). After the allocator and the inlined array accessors, Mini in Managed mode takes this long relative to C at `-O2` (1.00 is as fast as C):

| program | Mini relative to C | program | Mini relative to C |
|---|---:|---|---:|
| mandelbrot | 1.01 | binarytrees | 1.28 |
| nbody | 1.04 | spectralnorm | 1.25 |
| fannkuch | 1.26 | strbuild | 1.40 |
| json | 1.67 | wordcount | 6.5 |

Mini is on par with C where the program is numeric and within a factor of about 1.3 on five of the eight. It is clearly slower on `wordcount` (a generic `HashMap` with boxed counts) and `json`. The [benchmarks page](/benchmarks) has the full tables with all languages, the memory use, the profiles of where the time goes and the commands to reproduce them. Numbers on a shared VM are good to about 5 percent.

## Checkpoint

Does a faster browser Run prove a faster native binary? What should you check before replacing a pipeline with a loop?

::: details Reasoning
No. Targets, compilation, sandbox overhead and workloads differ. First verify identical results and representative input, then compare native runs under the same build settings. Inspect allocation and reference-count counters if those are your suspected cost.
:::

## Exercise

::: tip Exercise: make it faster
The starter counts primes by trial division and takes noticeably longer than the sieve. Replace `isPrime` by a faster approach: only test divisors up to the square root. The output must stay `9592`.
:::

```kotlin exercise
fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    for (d in 2 until n) {
        if (n % d == 0) return false
    }
    return true
}

fun main() {
    var count = 0
    for (n in 1..100_000) if (isPrime(n)) count++
    println(count)
}
```
```output
9592
```

::: details Show solution
```kotlin
fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    var d = 2
    while (d.toLong() * d <= n) {
        if (n % d == 0) return false
        d++
    }
    return true
}

fun main() {
    var count = 0
    for (n in 1..100_000) if (isPrime(n)) count++
    println(count)
}
```
```output
9592
```
:::

Next: [The `@` operator](./matrix).
