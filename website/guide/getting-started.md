# Getting started

Mini is a Kotlin compiler. You write `.kt` files, `minic` turns them into C17 and `clang` links the result with a small C runtime into a native executable.

::: tip Just want to use Mini?
[Install a prebuilt release](/install) with one command (no JDK needed), or try the compiler in your browser in the [tutorial](/learn/). This page is the workflow in a **source checkout** of the repository, which is what you use to work on the compiler.
:::

## Requirements

| Needed | Why |
|---|---|
| JDK 21 | `minic` runs the Kotlin K2 frontend on the JVM (at compile time only; the output does not need a JVM) |
| clang (or gcc, selected with `--cc`) | compiles the generated C |
| A checkout of the repository | the compiler finds `runtime/` and `stdlib/` next to itself |
| Network access to Maven Central on the first run | Gradle downloads the compiler dependencies |
| libuv (optional) | only needed to run the runtime test suite, not to compile programs |

Supported platforms are Linux (x64, arm64) and macOS (Apple silicon). Windows is [planned](/roadmap).

## Get and build the compiler

```sh
git clone https://github.com/HenrySchwerdt/mini
cd mini
./minic build examples/hello.kt -o build/hello
```

The `./minic` script builds the compiler with Gradle on first use (and again whenever compiler sources are newer than the installed binary), then runs it. You can also build it explicitly:

```sh
cd compiler && ./gradlew installDist
```

## Hello, world

<<< @/samples/guide/hello.kt{kotlin}

```sh
./minic build examples/hello.kt -o build/hello
./build/hello
```

Output, produced by building and running exactly this file in CI:

<<< @/samples/guide/hello.out{text}

`minic build` takes one or more `.kt` files (exactly one has `fun main()`), the output path with `-o`, and a few options. See the [command line reference](/reference/cli).

## `./minic check`: only the memory checker

```sh
./minic check examples/memory_leak.kt
```

`check` runs the front end and the Strict-mode ownership checker and stops there: no C is generated and nothing is linked. It prints diagnostics on stderr and exits with status 1 if there is an error. `--brief` prints only `line:col: CODE` lines on stdout. `minic build` runs the same checker before code generation, so an error stops the build too.

A file is only checked if it opts in with `@file:MemoryMode(Mode.Strict)`; ordinary files have nothing to report. The [memory guide](/memory/) explains what is checked.

## Where to go next

- [Mini and Kotlin/JVM](./mini-vs-kotlin): what works, what does not, known gaps.
- [Learn Mini](/learn/): the interactive tutorial, every example runs in your browser.
- [Language tour](/tour/): the listings for the Kotlin features Mini supports.
- [Memory guide](/memory/): the part that makes Mini different.
- [How it works](/internals/): the compiler pipeline and the runtime.
