# Command line

`minic` is the compiler driver. A release (`install.sh`, `mini-<version>-<target>.tar.gz`) ships the self-hosted compiler and a `bin/minic` driver; a source checkout has the `./minic` wrapper, which builds the JVM bridge with Gradle on first use. The two have the same core commands (`build`, `check`) and diagnostics; the release driver is described first. It is a bare compiler; a project tool, `mini`, is [planned](/roadmap#planned).

## The released `minic`

```text
minic run   <file.kt>... [-- <program args>]        build into a temporary directory, run, clean up
minic build <file.kt>... [-o <out>]                 compile to an executable (default: ./<file name without .kt>)
minic check <file.kt>...                            parse, type-check, memory-check; no C compiler needed
minic emit-c <file.kt>... [-o <out.c>]              write the generated C file
minic --version | --help | <command> --help
```

| Option | Meaning |
|---|---|
| `-O0` `-O1` `-O2` `-O3` | C optimization level. Default `-O2` for `build`, `-O1` for `run` (shorter build for a throw-away binary) |
| `-g`, `--debug` | `-O0` with debug information; the generated C carries `#line` directives, so gdb/lldb, sanitizers and crash reports show `.kt` files and lines |
| `--cc <compiler>` | C compiler; default `$CC`, then `cc`, `clang`, `gcc` |
| `--emit-c <file>` | keep the generated C |
| `-Werror` | memory-checker warnings fail the build |
| `--no-rc-opt` | skip the reference-count optimization |
| `-v`, `--time` | print the commands that run / how long the compiler, the C compiler and the linker took |
| `--color=auto\|always\|never` | colored diagnostics; `auto` means "stderr is a terminal and `NO_COLOR` is not set" |
| `--error-format=human\|json` | `json` prints one JSON object per diagnostic on stderr: `severity`, `code`, `message`, `file`, `line`, `col`, `endCol`, `label`, `secondary`, `help`, `docs` |
| `--error-limit=<n>` | stop printing after n diagnostics (default 20, `0` = all) |

Unknown commands and options get a "did you mean" suggestion. Diagnostics have the form

```text
app.kt:3:13: error: unresolved reference 'y'
   3 |     println(y)
     |             ^

error: could not compile app.kt (1 error, 0 warnings)
```

and the memory-checker ones (`error[M0102]: ...`) end with a note naming each code once with a link to its [page](/reference/diagnostics/).

**Exit status:** 0 success, 1 compile error (or an unusable environment such as a missing C compiler), 2 usage error, 3 internal compiler error. `minic run` ends with the exit status of the program. If the C compiler rejects the C that `minic` generated, that is reported as an internal compiler error (exit 3) with a request to report it, and the C file is kept as `minic-ice-<name>.c`; it is never shown as a raw C error. A missing C compiler is explained with how to install one and how to select one (`CC`, `--cc`).

## The checkout `minic` (JVM bridge)

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
