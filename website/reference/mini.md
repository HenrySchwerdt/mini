---
title: "mini: the project tool"
---

# `mini`: the project tool

`mini` is to Mini what `cargo` is to Rust: it creates projects, builds them, runs and tests them, and manages dependencies, driven by a `mini.toml` file. It invokes `minic-native` and the C compiler for compilation. You do not need to run `minic` separately.

::: warning Status
`mini` is a native program, compiled from Kotlin by Mini itself, and ships in the [release archive](/install) next to `minic`: no JDK and no Gradle. It drives the native compiler `minic-native` and the C compiler. In a source checkout, the `mini` script in the repository root builds it natively on first use. For a single file, [`minic`](./minic) is all you need.
:::

```sh
git clone https://github.com/HenrySchwerdt/mini && cd mini
export PATH="$PWD:$PATH"            # puts the `mini` script on the PATH
mini new hello && cd hello
mini run
```

## Commands

```text
mini [OPTIONS] <COMMAND>
```

| Command | What it does |
|---|---|
| `mini new <NAME>` | Create a project in a new directory: `mini.toml`, `src/`, `tests/`, `.gitignore`, a README, and a git repository |
| `mini init` | Turn the current directory into a project (never overwrites existing files) |
| `mini build` | Compile the project to native code |
| `mini run [-- ARGS...]` | Build and run the app; the exit code of the program is the exit code of `mini run` |
| `mini check` | Type-check and run the memory checker, without producing a binary (needs no C compiler) |
| `mini test [FILTER]` | Run the `@Test` functions of the project |
| `mini clean` | Remove the `build/` directory (the lock file and the git cache are kept) |
| `mini add [NAME]` | Add a dependency to `mini.toml` |
| `mini remove <NAME>` | Remove a dependency |
| `mini update [NAME...]` | Move the pins of git dependencies in `mini.lock` to the newest commits |
| `mini tree` | Print the dependency tree |
| `mini explain [CODE]` | Explain a diagnostic code such as `M0102`; `--list` lists all codes |
| `mini doctor` | Check the native toolchain (compiler engine, C compiler, git, optional libuv) and suggest fixes |
| `mini completions <SHELL>` | Print a completion script for `bash`, `zsh` or `fish` |
| `mini help [COMMAND]` | Show help for a command |

`mini help <command>` prints the options and examples of each command.

### Global options

| Option | Meaning |
|---|---|
| `-q`, `--quiet` | Print only errors |
| `-v`, `--verbose` | Also print the commands that are run |
| `--color <WHEN>` | `auto`, `always` or `never` |
| `-h`, `--help` | Print help |
| `-V`, `--version` | Print the version |

### `mini new` and `mini init`

| Option | Meaning |
|---|---|
| `--lib` | Create a library (a source package) instead of an app |
| `--strict` | Start in Strict memory mode (`memory = "strict"`) |
| `--no-git` | Do not run `git init` |
| `--name <NAME>` | (`init` only) the package name; default: the directory name |

```sh
mini new hello                 # an app
mini new geometry --lib        # a library
mini new payments --strict     # an app that starts in Strict mode
```

An app gets `src/main.kt`, `src/greeting.kt` and `tests/greeting_test.kt`; a library gets `src/lib.kt` and `tests/lib_test.kt`. Package names start with a lowercase letter and contain only `a-z`, `0-9`, `_` and `-`.

### `mini build`, `mini run` and `mini check`

`mini build` compiles `src/` of the selected packages together with their dependencies and the standard library. An app becomes `build/debug/<name>` (`-g`) or, with `--release`, `build/release/<name>` (`-O2`). A library is compiled once to verify it. Nothing is rebuilt when no source, manifest or dependency changed.

| Option | Meaning |
|---|---|
| `-p`, `--package <NAME>` | Select a workspace member (default: all, or the one in the current directory). Repeatable |
| `--release` | Optimised build (`build/release/`, `-O2`) (`build`, `run`, `test`) |
| `--strict` | Treat the project's own code as Strict memory mode, checker on |
| `-Werror` | Treat memory-checker warnings as errors |
| `--offline` | Never access the network; use only cached git repositories |
| `--locked` | Fail if `mini.lock` would have to change (for CI) |
| `--message-format <FMT>` | `human` or `json`: one JSON object per line, for editors (`build`, `run`, `check`) |
| `--manifest-path <PATH>` | The `mini.toml` to use |

```sh
mini build
mini build --release
mini build --strict -Werror
mini run --release -- --port 8080      # arguments after -- go to the program
mini check --message-format json
```

### `mini test`

`mini test` finds top-level `@Test fun name()` functions in `tests/` and `src/`, builds one test binary and runs every test **in its own process**, so a crash only fails that test. Assertions come from `import kotlin.test.*` (`assertEquals`, `assertTrue`, `assertFailsWith`, ...).

| Option | Meaning |
|---|---|
| `[FILTER]` | run the tests whose name contains it |
| `--list` | list the tests without building or running them |
| `--nocapture` | show the output of passing tests too |
| `--fail-fast` | stop after the first failing test |
| `--release` | build the tests optimised |

plus `-p`, `--strict`, `-Werror`, `--offline`, `--locked` and `--manifest-path` as above. Arguments after `--` are passed to every test process.

### Dependencies

```sh
mini add geometry --path ../geometry                                  # a local library
mini add --git https://github.com/me/json --tag v1.2.0               # the name is read from its mini.toml
mini remove geometry
mini update                                                           # all git dependencies
mini tree
```

`mini add` takes `--path`, `--git` with one of `--tag`, `--rev` or `--branch`, and `-p` for a workspace member. Git dependencies are cloned into a cache and pinned to a commit in `mini.lock` (commit this file). Tags and revisions never move; branches and the default branch move with `mini update`.

## `mini.toml`

A manifest has up to three sections. Unknown keys are errors (with a suggestion), and every error points at the line.

```toml
[package]
name = "hello"            # required: a-z, 0-9, _ and -, starting with a letter
version = "0.1.0"         # MAJOR.MINOR.PATCH
kind = "app"              # "app" builds an executable (needs fun main in src/), "lib" is a source package
memory = "managed"        # "managed" (reference counting) or "strict" (every allocation needs a proven owner)
description = "..."
authors = ["..."]
license = "Apache-2.0"
repository = "https://github.com/me/hello"
homepage = "https://..."

[dependencies]
geometry = { path = "../geometry" }
json = { git = "https://github.com/me/json", tag = "v1.0.0" }     # or rev = "abc1234", or branch = "main"
```

| `[package]` key | Meaning |
|---|---|
| `name` | The package name (required) |
| `version` | `MAJOR.MINOR.PATCH`, optionally with `-pre` or `+build` |
| `kind` | `app` (default) or `lib` |
| `memory` | `managed` (default) or `strict`: the memory mode of the project's own code |
| `description`, `authors`, `license`, `repository`, `homepage` | informational |

A dependency is a table with either `path` or `git`. A git dependency may pin `rev`, `tag` or `branch` (at most one). Dependencies are source packages: their code is compiled together with yours.

### Workspaces

A `mini.toml` with a `[workspace]` section and no `[package]` groups several packages. `build/` and `mini.lock` live next to the workspace manifest, and `-p` selects members:

```toml
[workspace]
members = ["app", "geometry"]
```

### Project layout

```text
hello/
  mini.toml
  mini.lock            written by mini, commit it when you have git dependencies
  src/main.kt          fun main() for an app, src/lib.kt for a library
  tests/*.kt           @Test functions
  build/debug/hello    the executable (build/release/ with --release)
```

## Environment

| Variable | Meaning |
|---|---|
| `MINI_ROOT` | the Mini checkout (the directory with `stdlib/` and `runtime/`). Set by the wrapper script |
| `MINIC` | the `minic` launcher to use instead of the one in the checkout |
| `MINI_CC`, `CC` | the C compiler; default `clang`, then `gcc` |
| `MINI_HOME` | where `mini` keeps the cache of git dependencies (default `~/.mini`) |
| `MINI_DEBUG` | print the stack trace of an internal error |

## Exit codes

`0` success, `1` a build or test failure (diagnostics are printed), `2` a command-line mistake, `101` an internal error in `mini` itself (a bug, please report it).

See also: [`minic`](./minic), the [diagnostics reference](/reference/diagnostics/), the [tutorial](/learn/).
