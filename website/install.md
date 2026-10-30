---
title: Install Mini
description: Install mini and minic together, understand which command to use, and run your first native Kotlin project or file.
---

# Install Mini

One installation gives you both **`mini`**, the project tool, and **`minic`**, the compiler driver. You do not choose between two downloads. Both use Mini's native compiler and standard library.

::: tip Try it first
You do not need to install anything to try Mini: the [tutorial](/learn/) and the [playground](/playground) run the real compiler in your browser.
:::

## One command

```sh
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh
```

The script installs the latest release into `~/.mini` and tells you to put its `bin` directory on your `PATH`:

```sh
export PATH="$HOME/.mini/bin:$PATH"      # add this line to ~/.bashrc, ~/.zshrc or your shell profile
```

Open a new terminal after adding that line to your shell profile, or run it in the current terminal. Confirm that both commands are available:

```sh
mini --version
minic --version
mini doctor
```

`mini doctor` checks the toolchain and explains missing requirements. You need a C compiler to build or run native programs; checking source alone does not need one. No JDK or Gradle is needed for either normal command.

## `mini` or `minic`?

| Command | Use it when | Example |
|---|---|---|
| **`mini`** | You are working on a project with a `mini.toml`, sources, tests and dependencies. It discovers the project's files and runs the compiler for you. | `mini new hello`, then `mini run` |
| **`minic`** | You want to compile, run or check one or more `.kt` files directly, without creating a project. You name the input files yourself. | `minic run hello.kt` |
| `minic-native` | You are working on compiler internals and need low-level C, MIR or LLVM output. It is the compiler engine used by the other tools. | Normally no direct invocation is needed. |

**Start with `mini` for an application.** Use `minic` for a single-file exercise, a small reproduction or an explicit list of source files. Neither command starts the JVM. `mini` is itself a native Kotlin program; `minic` is a small shell driver that invokes `minic-native` and your C compiler.

### Your first project with `mini`

From a directory where you keep projects:

```sh
mini new hello
cd hello
mini run
mini check
mini test
```

`mini new` creates `mini.toml`, `src/`, `tests/`, a README and a git repository. The generated application prints `Hello, world!`; its sample tests check the greeting function. Run the remaining commands from **inside `hello`** so the project tool can find `mini.toml`.

- Edit the Kotlin files in `src/`; `mini run` builds and executes the project.
- Use `mini build` when you want to build without executing it, or `mini build --release` for an optimized project build.
- Use `mini check` for type and memory checks without generating an executable.
- Add `@Test` functions in `tests/` and run `mini test`.
- Use `mini help` or `mini help run` for command help. Dependencies and workspaces are covered in the [`mini` reference](/reference/mini).

You do not need to run `minic` separately after `mini build` or `mini run`: the project tool already invokes the compiler engine.

### Your first file with `minic`

For a standalone example, save the following as `hello.kt` in a directory of your choice. No `mini.toml` is needed. You can run this code in the browser first:

```kotlin
fun main() {
    println("Hello, Mini!")
}
```
```output
Hello, Mini!
```

```sh
minic run hello.kt
```

This compiles into a temporary directory, runs the program, then removes the temporary build. To keep a native executable instead:

```sh
minic build hello.kt -o hello
./hello
```

The output is `Hello, Mini!` in both cases. Without `-o`, `minic build hello.kt` uses `hello` as the output name. The compiler bundles the reachable standard-library code and links Mini's runtime; you do not run the resulting executable through `mini`, `minic` or Java.

```sh
minic check hello.kt                  # type and memory checks, no executable
minic build hello.kt -o hello -O2     # choose a C optimization level
minic emit-c hello.kt -o hello.c      # inspect generated C instead of linking
minic help build                     # options for this command
```

For several source files, list them explicitly, for example `minic build main.kt helpers.kt -o app`. Exactly one supplies the application's `main`. Move to a `mini` project when you want automatic source discovery, tests or dependencies. See the [`minic` reference](/reference/minic).

Once installed, local programs without uncached git dependencies can build offline. The initial installation, release updates and downloading new dependencies need network access.

## Requirements

| You need | Why |
|---|---|
| A **C compiler** (`clang` or `gcc`, found as `cc`) | `minic` translates Kotlin to C and the C compiler turns it into a native executable. Install `clang` with `sudo apt install clang`, `sudo dnf install clang` or `xcode-select --install`. Use another compiler with `--cc` or the `CC` variable. |
| `curl`, `tar` | for the copy-and-paste installer command; the installer can use `wget` with an explicitly pinned `MINI_VERSION` |
| `git` | for project repository creation and git dependencies; `mini new hello --no-git` skips repository creation |
| `sha256sum` or `shasum` | the installer verifies the download |

No JDK is needed: the compiler is a native program that compiles itself.

## Supported platforms

| Platform | Release archive |
|---|---|
| Linux x86-64 | `mini-<version>-linux-x64.tar.gz` |
| Linux arm64 | `mini-<version>-linux-arm64.tar.gz` |
| macOS on Apple silicon | `mini-<version>-macos-arm64.tar.gz` |

**Windows is not supported yet** (the generated C uses GNU extensions that MSVC does not have). Use WSL, which gives you the Linux build. Intel Macs have no prebuilt archive; [build from source](#build-from-source).

## What the installer does

1. Detects your platform from `uname` (`Linux/x86_64`, `Linux/aarch64`, `Darwin/arm64`). Anything else stops with the list above.
2. Finds the latest release by following the redirect of `https://github.com/HenrySchwerdt/mini/releases/latest`, which needs no API call and has no rate limit. `MINI_VERSION` pins a version.
3. Downloads `mini-<version>-<target>.tar.gz` and `SHA256SUMS` from the release and **verifies the checksum**. A mismatch aborts the installation.
4. Unpacks the archive into `$MINI_HOME` (default `~/.mini`), replacing the previous `bin`, `lib`, `include` and `stdlib`.
5. Prints the `PATH` line if `bin` is not on your `PATH` yet, and reminds you that a C compiler is needed.

The installed toolchain lives under `$MINI_HOME`; downloads are unpacked in a temporary directory and cleaned up afterward. The script does not edit your shell profile.

### Options

These environment variables are read by `install.sh`:

| Variable | Meaning |
|---|---|
| `MINI_VERSION` | the version to install, `0.5.0` or `v0.5.0` (default: the latest release) |
| `MINI_HOME` | the install directory (default: `~/.mini`) |
| `MINI_TARBALL` | install from a local `mini-<version>-<target>.tar.gz` instead of downloading. It is checked against a `SHA256SUMS` next to it if there is one. For testing and offline machines. |
| `TMPDIR` | where the temporary download directory goes |

```sh
# a specific version in a custom directory
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | MINI_VERSION=0.5.0 MINI_HOME=/opt/mini sh
```

Prefer to read the script before running it? It is [`install.sh`](https://github.com/HenrySchwerdt/mini/blob/main/install.sh) in the repository root, about 100 lines of POSIX `sh`.

## What gets installed

```text
~/.mini/
  bin/minic            the compiler driver: run / build / check / emit-c (a shell script)
  bin/minic-native     the compiler itself: Kotlin to C, self-hosted, no JDK
  bin/mini             the project tool: new / build / run / check / test / dependencies (native)
  lib/libminirt.a      the runtime (reference counting, allocators, strings, exceptions), prebuilt
  include/             minirt.h and the other runtime headers
  stdlib/              the standard library as Kotlin source, compiled together with your program
  VERSION  LICENSE  NOTICE  README.md
```

`minic` finds its files relative to its own location, so you can move the directory or symlink `bin/minic` into a directory that is already on your `PATH`. The [`minic` reference](/reference/minic) lists every command and option.

The project tool [`mini`](/reference/mini) (`mini new`, `mini run`, `mini test`, `mini.toml`, dependencies) is a native binary in `bin/mini`, next to the compiler. It needs no JDK either.

## Verify a download by hand

Every release has a `SHA256SUMS` file next to the archives. The installer checks it for you. To do it yourself:

```sh
version=0.5.0; target=linux-x64
base=https://github.com/HenrySchwerdt/mini/releases/download/v$version
curl -fsSLO $base/mini-$version-$target.tar.gz
curl -fsSLO $base/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS         # macOS: shasum -a 256 -c SHA256SUMS
```

```text
mini-0.5.0-linux-x64.tar.gz: OK
```

Then install the checked archive with `MINI_TARBALL=./mini-0.5.0-linux-x64.tar.gz sh install.sh`.

## Update

Run the installer again to update `mini`, `minic`, the compiler engine, runtime and standard library together. It replaces the toolchain files inside the installation directory. Your projects live in their own directories and do not need to be recreated. Re-run `mini --version`, `minic --version` and `mini doctor` afterward.

## Uninstall

Everything lives in one directory:

```sh
rm -rf ~/.mini            # or $MINI_HOME
```

Then remove the `PATH` line from your shell profile.

## Build from source

You build from source to work on the compiler, to use a platform without a release archive, or because you want to check what you run. There are two routes.

### From the C bootstrap seed (a C compiler is enough)

Every release publishes `mini-<version>-bootstrap-c.tar.gz`: the C that the compiler generates for its own sources, plus the runtime sources. A C compiler builds a working compiler from it, with no JDK and no prebuilt binary:

```sh
tar -xzf mini-0.5.0-bootstrap-c.tar.gz && cd mini-0.5.0-bootstrap-c
./build.sh                       # CC=clang OPT=-O2 ./build.sh to choose the compiler and level
# -> ./minic-native, the stage 0 compiler
```

The seed produces the compiler engine, not a full `mini` project-tool installation. With a checkout of the same version you can then rebuild the compiler with itself and verify the fixpoint (stage 2 and stage 3 are byte-identical):

```sh
git clone --branch v0.5.0 https://github.com/HenrySchwerdt/mini && cd mini
compiler/bootstrap.sh --stage0 /path/to/minic-native
```

### From a checkout

This is the development setup. It needs a C compiler (no JDK, no Gradle) and builds the compiler natively on first use, starting from the latest release:

```sh
git clone https://github.com/HenrySchwerdt/mini && cd mini
./mini doctor
./minic run examples/hello.kt
```

The `./minic` and `./mini` scripts in the repository build the native toolchain into `build/native` on first use (a minute or two) and rebuild it when its sources change. The `./` prefix selects the scripts from this checkout; `mini` or `minic` without it may select an installed release on your `PATH`. The optional K2 cross-checks against the official Kotlin compiler (JDK 21 and Gradle) live separately in `tools/k2-oracle`; they are not part of a normal installation or native build.

## If something goes wrong

| Message | Fix |
|---|---|
| `no prebuilt Mini for ...` | your platform has no release archive, build from source |
| `no release found at ...` | there is no release yet, or you are offline. Set `MINI_VERSION` or `MINI_TARBALL`, or build from source. |
| `SHA256 mismatch` | the download is damaged or was tampered with. Run the installer again; if it repeats, open an issue. |
| `mini: command not found` or `minic: command not found` | add the installation's `bin` directory to `PATH`, then open a new terminal or run the export line above |
| could not find `mini.toml` | use `cd` to enter your project, or use `minic run file.kt` for a standalone file |
| the wrong version runs | inspect `command -v mini` and `command -v minic`; a checkout or older installation may occur earlier on `PATH` |
| no C compiler found | install `clang` or `gcc`, run `mini doctor`, or select a compiler with `CC` (`minic` also has `--cc`) |
| generated C is rejected (internal compiler error) | keep the reported `minic-ice-*.c` file and report the failure with your source and toolchain version |

Next: the [tutorial](/learn/), or [Getting started](/guide/getting-started) for the source-checkout workflow.
