---
title: "minic: the compiler"
---

# `minic`: the compiler

There are three programs with similar names. Which one you have depends on how you got Mini:

| Program | You have it when | What it is |
|---|---|---|
| [`minic`](#minic-the-driver-of-a-release) | you [installed a release](/install) | a small shell driver: `minic build foo.kt -o foo` |
| [`minic-native`](#minic-native-the-compiler) | the same install (`bin/minic-native`) | the self-hosted compiler: Kotlin to C, the memory checker, MIR and LLVM IR output |
| [`./minic` of a checkout](#minic-in-a-source-checkout) | you cloned the repository | the JVM build of the same compiler, with a few more commands and options |

Everything below is what the code implements today.

## `minic`, the driver of a release

```text
minic build <file.kt>... -o <out> [-O0|-O1|-O2|-O3|-Os] [--emit-c <out.c>] [--cc <compiler>]
minic emit-c <file.kt>... -o <out.c>
minic version
```

`minic build` compiles one or more `.kt` files (exactly one with `fun main()`) to a native executable. It runs `minic-native` to translate Kotlin and the standard library (`stdlib/` of the installation) to one C file, then your C compiler with the bundled `lib/libminirt.a` and `include/`:

```text
cc -std=c17 <opt> -w -I include out.c lib/libminirt.a -lm -o out
```

| Option | Meaning |
|---|---|
| `-o <out>` | the output: the executable for `build`, the C file for `emit-c`. Required |
| `-O0`, `-O1`, `-O2`, `-O3`, `-Os` | optimization level for the C compiler. Default `-O1` |
| `--emit-c <out.c>` | keep the generated C file there (otherwise it is temporary and deleted) |
| `--cc <compiler>` | the C compiler. Default: the `CC` environment variable, then `cc` |

`minic emit-c` stops after the translation. `minic version` prints the version of the installation.

```sh
minic build hello.kt -o hello
minic build app.kt util.kt -o app -O2
minic build hello.kt -o hello --emit-c hello.c --cc clang
CC=gcc minic build hello.kt -o hello
```

Environment: `CC` selects the C compiler. The driver finds `bin/minic-native`, `lib/`, `include/` and `stdlib/` relative to itself, also through symlinks.

This driver does not run the memory checker as a separate command; for that, use [`minic-native --check`](#checking-only) below. (The Strict-mode checker runs inside `minic-native` whenever it translates a program, so errors stop `minic build` too.)

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

`--check` runs the frontend and the Strict-mode ownership checker and generates no code. Diagnostics are printed in the rustc-like format described in the [diagnostics reference](/reference/diagnostics/). Exit status 1 on an error, 0 otherwise (warnings do not fail unless `-Werror`). Only files with `@file:MemoryMode(Mode.Strict)` can have findings; ordinary files check clean. This is the same checker that runs in the browser for the **Check** buttons on this site.

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

In a clone of the repository, `./minic` builds the JVM compiler with Gradle on first use and runs it. It needs JDK 21 and has these commands:

```text
minic build <file.kt>... -o <out> [--emit-c <path>] [--cc clang]
            [--runtime-src <dir>] [--runtime-include <dir>] [--cflag <flag>]...
            [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]... [-Werror]
            [--stats] [--no-rc-opt] [--no-strict] [--no-conv] [-Wrc] [--frontend=mini|k2]
            [--backend=c|llvm] [--emit-ll <path>] [--link-cc <compiler>] [--c-src <file.c>]...
minic check <file.kt>... [--extra-src <file.kt>]... [--brief] [-Werror] [--frontend=k2|mini]
minic emit-mir <file.kt>... -o <out.mir> [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]...
minic mem-report <file.kt>... [--top <n>] [--user-only] [--no-rc-opt] [--no-strict] [--no-conv]
```

It finds `runtime/` and `stdlib/` by walking up from the current directory.

### `minic build`

| Option | Meaning |
|---|---|
| `-o <out>` | output executable |
| `--emit-c <path>` | keep the generated C file there (otherwise a temporary file) |
| `--cc <compiler>` | C compiler to use, default `clang`. The default flags are `-std=c17 -O1 -Wall` |
| `--cflag <flag>` | extra C compiler flag (repeatable), for example `--cflag -O2` |
| `--runtime-src <dir>`, `--runtime-include <dir>` | runtime sources and header; the default is `runtime/` of the repository |
| `--stdlib <dir>` / `--no-stdlib` | use another stdlib source directory, or the JVM kotlin-stdlib JAR (a legacy mode) |
| `--extra-src <file.kt>` | extra Kotlin sources, compiled only as declarations |
| `-Werror` | memory-checker warnings fail the build |
| `--stats` | build with `-DMK_STATS`: the program prints allocation and reference-count counters to stderr at exit |
| `--no-rc-opt` | skip the RcOpt pass (baseline reference counting) |
| `--no-strict`, `--no-conv` | no Strict code generation; no borrowed-result conventions for the stdlib hot paths (experiments) |
| `-Wrc` | M0207 warnings: reference counting remains in a function of your files |
| `--frontend=mini\|k2` | the own frontend (default) or the Kotlin K2 frontend (the reference implementation) |
| `--backend=c\|llvm` | C (default) or textual LLVM IR compiled by clang; `--emit-ll <path>` keeps the IR and `--link-cc` selects the compiler that links |
| `--c-src <file.c>` | a C file compiled and linked into the program ([C interop](/guide/c-interop)); repeatable |

`minic build` runs the Strict-mode checker after the frontend and before code generation: errors stop the build, warnings are printed.

### `minic check`

Runs the frontend and the Strict-mode ownership checker only: no C is generated and nothing is linked. Diagnostics go to stderr. With `--brief`, only `line:col: CODE` lines are printed on stdout. Exit status 1 on an error, 0 otherwise.

### `minic emit-mir`

Writes the [MIR](/internals/mir) as text.

### `minic mem-report`

Shows where reference counting remains in your program, function by function (`--top <n>` lists that many functions in detail, `--user-only` hides the standard library).

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | compile error: a Kotlin error, an `unsupported construct`, a memory-checker error, or a C compiler failure |
| 2 | usage error |
| 3 | internal emitter error (checkout `minic`) |

## The standard library

The Mini stdlib (`stdlib/`, next to `runtime/`) is compiled from source together with your program; only what is reachable ends up in the C file. See [Standard library](/stdlib).

See also: [`mini`](./mini), the project tool, and [Install](/install).
