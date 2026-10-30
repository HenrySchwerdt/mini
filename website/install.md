---
title: Install Mini
description: Install the prebuilt Mini compiler with one command, verify it, and build from source if you need to.
---

# Install Mini

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

Then check it and build a program. Save this as `hello.kt` (you can run it right here first):

```kotlin
fun main() {
    println("Hello, Mini!")
}
```
```output
Hello, Mini!
```

```sh
minic version
minic build hello.kt -o hello
./hello
```

```text
Hello, Mini!
```

That is the whole setup: no JDK, no Gradle, no network access after the download.

## Requirements

| You need | Why |
|---|---|
| A **C compiler** (`clang` or `gcc`, found as `cc`) | `minic` translates Kotlin to C and the C compiler turns it into a native executable. Install `clang` with `sudo apt install clang`, `sudo dnf install clang` or `xcode-select --install`. Use another compiler with `--cc` or the `CC` variable. |
| `curl` or `wget`, `tar` | to run the installer |
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

It writes nothing outside `$MINI_HOME` and does not edit your shell profile.

### Options

These environment variables are read by `install.sh`:

| Variable | Meaning |
|---|---|
| `MINI_VERSION` | the version to install, `0.4.0` or `v0.4.0` (default: the latest release) |
| `MINI_HOME` | the install directory (default: `~/.mini`) |
| `MINI_TARBALL` | install from a local `mini-<version>-<target>.tar.gz` instead of downloading. It is checked against a `SHA256SUMS` next to it if there is one. For testing and offline machines. |
| `TMPDIR` | where the temporary download directory goes |

```sh
# a specific version in a custom directory
curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | MINI_VERSION=0.4.0 MINI_HOME=/opt/mini sh
```

Prefer to read the script before running it? It is [`install.sh`](https://github.com/HenrySchwerdt/mini/blob/main/install.sh) in the repository root, about 100 lines of POSIX `sh`.

## What gets installed

```text
~/.mini/
  bin/minic            the driver you run: minic build foo.kt -o foo (a shell script)
  bin/minic-native     the compiler itself: Kotlin to C, self-hosted, no JDK
  bin/mini             the project tool: mini new / build / run / test (native)
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
version=0.4.0; target=linux-x64
base=https://github.com/HenrySchwerdt/mini/releases/download/v$version
curl -fsSLO $base/mini-$version-$target.tar.gz
curl -fsSLO $base/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS         # macOS: shasum -a 256 -c SHA256SUMS
```

```text
mini-0.4.0-linux-x64.tar.gz: OK
```

Then install the checked archive with `MINI_TARBALL=./mini-0.4.0-linux-x64.tar.gz sh install.sh`.

## Update

Run the installer again. It replaces `bin`, `lib`, `include` and `stdlib` and leaves nothing else behind.

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
tar -xzf mini-0.4.0-bootstrap-c.tar.gz && cd mini-0.4.0-bootstrap-c
./build.sh                       # CC=clang OPT=-O2 ./build.sh to choose the compiler and level
# -> ./minic-native, the stage 0 compiler
```

With a checkout of the same version you can then rebuild the compiler with itself and verify the fixpoint (stage 2 and stage 3 are byte-identical):

```sh
git clone https://github.com/HenrySchwerdt/mini && cd mini
compiler/bootstrap.sh --stage0 /path/to/minic-native
```

### From a checkout

This is the development setup. It needs a C compiler (no JDK, no Gradle) and builds the compiler natively on first use, starting from the latest release:

```sh
git clone https://github.com/HenrySchwerdt/mini && cd mini
./minic build examples/hello.kt -o build/hello && ./build/hello
```

The `./minic` and `./mini` scripts in the repository build the native toolchain into `build/native` on first use (a minute or two) and rebuild it when its sources change. The optional K2 cross-checks against the official Kotlin compiler (JDK 21) live in `tools/k2-oracle`.

## If something goes wrong

| Message | Fix |
|---|---|
| `no prebuilt Mini for ...` | your platform has no release archive, build from source |
| `no release found at ...` | there is no release yet, or you are offline. Set `MINI_VERSION` or `MINI_TARBALL`, or build from source. |
| `SHA256 mismatch` | the download is damaged or was tampered with. Run the installer again; if it repeats, open an issue. |
| `minic: command not found` | `~/.mini/bin` is not on your `PATH` (see above) |
| a C compiler error right after `minic build` | no usable C compiler: install `clang` or `gcc`, or pass `--cc` |

Next: the [tutorial](/learn/), or [Getting started](/guide/getting-started) for the source-checkout workflow.
