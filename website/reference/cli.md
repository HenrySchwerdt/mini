# Command line

`minic` is the compiler driver. From a source checkout use the `./minic` wrapper, which builds the compiler with Gradle on first use. It is a bare compiler. For projects there is the project tool `mini`, described [below](#the-mini-project-tool); it is a native binary and needs no JDK and no Gradle.

```text
minic build <file.kt>... -o <out> [--emit-c <path>] [--cc clang]
            [--runtime-src <dir>] [--runtime-include <dir>] [--cflag <flag>]...
            [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]... [-Werror]
            [--stats] [--no-rc-opt]
minic check <file.kt> [--extra-src <file.kt>]... [--brief] [-Werror]
minic emit-mir <file.kt>... -o <out.mir> [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]...
```

## `minic build`

Compiles one or more `.kt` files (exactly one has `fun main()`) to a native executable.

| Option | Meaning |
|---|---|
| `-o <out>` | output executable |
| `--emit-c <path>` | keep the generated C file there (otherwise a temporary file) |
| `--cc <compiler>` | C compiler to use, default `clang` |
| `--cflag <flag>` | extra C compiler flag (repeatable). The default flags are `-std=c17 -O1 -Wall` |
| `--runtime-src <dir>`, `--runtime-include <dir>` | runtime sources and header; the default is `runtime/` in the repository root, found by walking up from the current directory |
| `--stdlib <dir>` / `--no-stdlib` | use another stdlib source directory, or the JVM kotlin-stdlib JAR (then stdlib functions have no bodies; a legacy mode) |
| `--extra-src <file.kt>` | extra Kotlin sources, compiled only as declarations (not translated to C) |
| `-Werror` | memory-checker warnings fail the build |
| `--stats` | build with `-DMK_STATS`: the program prints allocation and reference-count counters to stderr at exit |
| `--no-rc-opt` | skip the RcOpt pass (baseline reference counting) |

`minic build` runs the Strict-mode checker after the frontend and before code generation: errors stop the build, warnings are printed.

## `minic check`

Runs the frontend and the Strict-mode ownership checker only. Diagnostics go to stderr in the rustc-like format. With `--brief`, only `line:col: CODE` lines are printed on stdout. Exit status 1 on an error, 0 otherwise (warnings do not fail unless `-Werror`).

## `minic emit-mir`

Runs the frontend and writes [MIR](/internals/mir) as text. The self-hosted native backend `minic-backend` consumes this format.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | compile error: a Kotlin error, an `unsupported construct`, a memory-checker error, or a C compiler failure |
| 2 | usage error |
| 3 | internal emitter error |

## The standard library

The Mini stdlib (`stdlib/`, found next to `runtime/`) is compiled from source together with your program; only what is reachable ends up in the C file. See [Standard library](/stdlib).

## The `mini` project tool

`mini` is to Mini what `cargo` is to Rust (and `minic` is `rustc`). It is Kotlin compiled by Mini itself, a native binary of about 3 MB with no JVM, no Gradle and no other runtime; it calls the native compiler `minic-native` and your C compiler.

```sh
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
export PATH="$HOME/.mini/bin:$PATH"

mini new hello && cd hello
mini run                 # build and run
mini test                # run the @Test functions in tests/
mini check               # type-check and run the memory checker, no binary
mini build --release     # optimized build in build/release/
mini explain M0102       # explain a diagnostic
mini doctor              # check the toolchain and suggest fixes
```

A project is a directory with a `mini.toml` manifest and sources in `src/` (`main.kt` for an app, `lib.kt` for a library); dependencies are paths or git repositories, pinned in `mini.lock`. In a source checkout, `./mini` builds the tool on first use and needs no Gradle. The full reference is [docs/mini-tool.md](https://github.com/HenrySchwerdt/mini/blob/main/docs/mini-tool.md).
