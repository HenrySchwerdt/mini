---
title: Command line
---

# Command line

One [installation](/install) provides both commands. Use `mini` to work on a project, or `minic` to name source files directly. They share the native compiler engine, `minic-native`; you normally do not invoke that engine yourself.

| | `mini` | `minic` |
|---|---|---|
| **Role** | project tool | compiler driver |
| **Input** | a `mini.toml` project, with sources, tests and dependencies | one or more `.kt` files you list explicitly |
| **Run** | `mini run` from the project | `minic run hello.kt` |
| **Check** | `mini check` | `minic check hello.kt` |
| **Help** | `mini help`, `mini help run` | `minic --help`, `minic help run` |
| **Reference** | [`mini`](./mini) | [`minic`](./minic) |

Neither needs a JDK or Gradle. A C compiler is needed when producing a native executable; checking alone does not invoke one.

## The `mini` project tool

```sh
mini new hello
cd hello
mini run
mini check
mini test
```

`mini` discovers source files and dependencies from the project. You do not need a separate `minic build` step. Use `mini build` to build without running, `mini build --release` for an optimized build and `mini doctor` to inspect your toolchain.

## The `minic` compiler driver

Save a Kotlin program with `fun main` in `hello.kt`, then choose whether to run it temporarily or keep an executable:

```sh
minic run hello.kt
minic build hello.kt -o hello
./hello
minic check hello.kt
```

`run` builds in a temporary directory and cleans it afterward. `build` writes an executable; its default output name is the first file's name without `.kt`. With multiple inputs, exactly one provides `main`.

## Quick reference

| I want to | Command |
|---|---|
| start a project | `mini new name` |
| build and run a project | `mini run` |
| check a project | `mini check` |
| run project tests | `mini test` |
| inspect installation requirements | `mini doctor` |
| build and run one file | `minic run prog.kt` |
| compile a program | `minic build prog.kt -o prog` |
| choose an optimization level | `minic build prog.kt -o prog -O2` |
| see the generated C | `minic emit-c prog.kt -o prog.c` |
| check explicit source files | `minic check prog.kt` |
| understand an ownership diagnostic | `mini explain M0102`, or the [diagnostics reference](/reference/diagnostics/) |

## In a source checkout

`./mini` and `./minic` select the repository's wrappers and build the native toolchain when necessary. Commands without `./` select the first tool on your `PATH`, which may be an installed release. Both normal workflows are native. The optional JVM/K2 comparison tool lives separately under `tools/k2-oracle`.

Compiler-engine flags for MIR or LLVM output belong to `minic-native`; they are not automatically forwarded by the `minic` driver. Check the [`minic` reference](./minic) before using an advanced flag from an older example.

## Exit codes

Both commands use `0` for success, `1` for ordinary compile/build/test failures and `2` for a command-line mistake. `minic` uses `3` for an internal compiler error. A successful build-and-run returns the executed program's exit status.
