# Getting started

Mini is a Kotlin compiler. You write `.kt` files, `minic` turns them into C17 and `clang` links the result with a small C runtime into a native executable.

## Requirements

| Needed | Why |
|---|---|
| clang (or gcc) | compiles the generated C |
| A release (`install.sh`) or a checkout of the repository | the compiler finds `runtime/` and `stdlib/` next to itself |
| JDK 21 and network access to Maven Central | only for the JVM `./minic` wrapper of a checkout (K2 frontend, Gradle); the native `mini` and `minic` of a release need neither |
| libuv (optional) | only needed to run the runtime test suite, not to compile programs |

Supported platforms are Linux and macOS. Windows is [planned](/roadmap).

## Install the project tool and compiler

```sh
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
export PATH="$HOME/.mini/bin:$PATH"
mini new hello && cd hello && mini run
```

`mini` is the native project tool (no JVM, no Gradle), `minic` the compiler it calls; the [command line reference](/reference/cli#the-mini-project-tool) lists its commands. In a checkout, `./mini new hello` builds the tool and the native compiler on first use without Gradle.

## Build the JVM compiler from a checkout

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
- [Language tour](/tour/): runnable programs for the Kotlin features Mini supports.
- [Memory guide](/memory/): the part that makes Mini different.
- [How it works](/internals/): the compiler pipeline and the runtime.
