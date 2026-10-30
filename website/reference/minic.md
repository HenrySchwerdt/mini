---
title: "minic: the compiler"
---

# `minic`: the compiler

Use `mini` for a project and `minic` for explicit source files. A release installs both, together with the compiler engine. The same native driver is also used in a source checkout:

| Program | You have it when | What it is |
|---|---|---|
| [`minic`](#minic-the-driver-of-a-release) | you [installed a release](/install) | a small shell driver: `minic run foo.kt`, `minic build foo.kt`, `minic check foo.kt` |
| [`minic-native`](#minic-native-the-compiler) | the same install (`bin/minic-native`) | the self-hosted compiler: Kotlin to C, the memory checker, MIR and LLVM IR output |
| [`./minic` of a checkout](#minic-in-a-source-checkout) | you cloned the repository | a wrapper that builds the native toolchain if needed, then runs the same driver as a release |

Everything below is what the code implements today.

## `minic`, the driver of a release

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

## `minic-native`, the compiler

`bin/minic-native` is the compiler proper. It reads the program files, then `--stdlib` followed by the standard library files, in the order given: files before `--stdlib` are the program (the first one names the module), files after it are the standard library. `--user` switches back to program files. Pure Kotlin, compiled by itself.

```text
minic-native [-o <out.c>] [--emit-mir <out.mir>] [--emit-ll <out.ll>] [--target=wasm32] [--emit-c <out.c>]
             [--no-rc-opt] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
minic-native --check [--brief] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
minic-native --backend=llvm-api -o <out.o> [--exe <prog> [--link-arg <arg>]...] [--cc <compiler>] [--opt=O0..O3]
             [--no-rc-opt] <user.kt>... --stdlib <stdlib.kt>...
minic-native --backend=llvm-api --run [--time] [--opt=O0..O3] <user.kt>... --stdlib <stdlib.kt>... [-- <program args>]
```

| Option | Meaning |
|---|---|
| `-o <file>` | write C (or, with `--backend=llvm-api`, the object file) there |
| `--emit-c <file>` | write the C file there |
| `--emit-mir <file>` | write the MIR, the compiler's intermediate representation, as text ([MIR](/internals/mir)) |
| `--emit-ll <file>` | write textual LLVM IR; with `--target=wasm32` for `wasm32-wasip1` |
| `--target=wasm32` | the LLVM IR is for WebAssembly instead of the host (this is how the [in-browser compiler](/playground) is built) |
| `--no-rc-opt` | skip the reference-count elision pass (RcOpt); the baseline for comparisons |
| `-Werror` | memory-checker warnings fail the compilation |
| `--check` | only parse, type-check and run the memory checker, see below |
| `--brief` | with `--check`: print only `line:col: CODE` lines |
| `--backend=c\|llvm\|llvm-api` | the code generator, see below. Default `c` |
| `--opt=O0..O3` | optimization level inside the LLVM API build (default `O1`) |
| `--exe <prog>` | with `llvm-api`: also link an executable with the system C compiler |
| `--link-arg <arg>` | with `--exe`: an extra linker argument (repeatable) |
| `--cc <compiler>` | with `--exe`: the C compiler used to link (default `cc`) |
| `--run` | with `llvm-api`: compile in memory with a JIT and run `main` in this process. Arguments after `--` go to the program |
| `--time` | with `--run`: print timings |

The input must have a `fun main` unless you only ask for `--check`. At least one output (`-o`, `--emit-*`, `--exe`, `--run` or `--check`) is required.

### Checking only

```sh
minic-native --check hello.kt --stdlib $(find ~/.mini/stdlib -name '*.kt' | sort)
```

`--check` runs the frontend and the Strict-mode ownership checker and generates no code. Diagnostics are printed in the rustc-like format described in the [diagnostics reference](/reference/diagnostics/). Exit status 1 on an error, 0 otherwise (warnings do not fail unless `-Werror`). All files are parsed and type-checked; invalid Kotlin is rejected in any memory mode. Strict files additionally carry tracked ownership obligations. This is the same checker that runs in the browser for the **Check** buttons on this site.

### Backends

| `--backend=` | Output | Notes |
|---|---|---|
| `c` (default) | a C17 file, compiled by your C compiler | what releases use |
| `llvm` | textual LLVM IR (`--emit-ll`) | for `clang`; this is the path of the WebAssembly build |
| `llvm-api` | an object file, an executable, or an in-memory JIT run | needs the **build of the compiler with the LLVM C API bound in** (`compiler/llvm-c`, LLVM 18). The release archives are built without it, and asking for it there is an error: `--backend=llvm-api needs the build of minic-native with the LLVM C API` |

`--run` and `--exe` are only valid with `--backend=llvm-api`.

### MIR and the rest of the pipeline

`--emit-mir` writes what the C and LLVM backends consume. [How it works](/internals/) follows a program through the stages.

## `./minic` in a source checkout

In a clone of the repository, `./minic` first ensures that the native toolchain in `build/native` exists, then runs `build/native/bin/minic`. It shares the release driver's `build`, `run`, `check` and `emit-c` commands. No JDK or Gradle is needed.

```sh
./mini doctor
./minic run examples/hello.kt
./minic check examples/memory_leak.kt
```

The first invocation builds the tools using an available native compiler or a downloaded release. Later invocations rebuild when toolchain sources change. Use `./minic` to select this checkout explicitly; `minic` without the prefix selects the first installation on `PATH`.

For compiler internals, call `build/native/bin/minic-native` directly with the options [above](#minic-native-the-compiler). Advanced engine options are not automatically accepted by the `minic` shell driver; inspect the help for the executable you are actually invoking.

### Optional K2 oracle

The JVM reference compiler is a separate developer tool under `tools/k2-oracle`. Only this optional comparison tool needs JDK 21 and Gradle:

```sh
cd tools/k2-oracle
./gradlew :jvm-bridge:installDist
./jvm-bridge/build/install/minic/bin/minic --help
```

Its legacy CLI includes experiments and options that are not exposed by the native driver, such as selecting K2, `--stats` and `mem-report`. It is not what `./minic` at the repository root runs. See the [K2 oracle README](https://github.com/HenrySchwerdt/mini/blob/main/tools/k2-oracle/README.md) for the comparison workflow.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | compile error: a Kotlin error, an `unsupported construct` or a memory-checker error; also an unusable environment such as a missing C compiler |
| 2 | usage error |
| 3 | internal compiler error, including C that the C compiler rejects (reported with a link to file an issue) |

## The standard library

The Mini stdlib (`stdlib/`, next to `runtime/`) is compiled from source together with your program; only what is reachable ends up in the C file. See [Standard library](/stdlib).

See also: [`mini`](./mini), the project tool, and [Install](/install).
