# `mini`: the project tool

`mini` is to Mini what `cargo` is to Rust: it creates projects, resolves dependencies, builds, runs and tests them.
`minic` stays the bare compiler underneath (like `rustc`); `mini` calls the native compiler `minic-native` as a subprocess. `mini` itself is a native binary compiled by Mini from Kotlin sources: no JVM, no Gradle, ever.

```text
$ ./mini new hello && cd hello
$ ../mini run
   Compiling hello v0.1.0 (.)
    Finished debug build in 28.1s
     Running `build/debug/hello`
Hello, world!
$ ../mini test
   Compiling hello v0.1.0 (tests)
    Finished test build in 33.3s
     Running 2 tests (build/test/hello/runner)

test tests/greeting_test.kt::greetsTheWorld ... ok (6ms)
test tests/greeting_test.kt::greetsByName ... ok (5ms)

test result: ok. 2 passed; 0 failed; 0 ignored; finished in 11ms
```

(The first build is slow because the K2 frontend still type-checks the whole standard library on every compilation;
a rebuild without changes is instant, see [Incremental builds](#incremental-builds).)

## Installation

**Release.** `install.sh` installs `bin/mini`, `bin/minic-native`, `bin/minic`, `lib/libminirt.a`, `include/` and `stdlib/` into
`~/.mini` (needs clang or gcc; `mini doctor` checks this). `mini` finds the rest next to itself, no environment needed.

**Source checkout.** The wrapper script `./mini` in the repository root builds the tool and the compiler on first use with
`scripts/build-mini.sh` (a minute or two) into `build/native` and rebuilds them when their sources change. The compiler is
bootstrapped from a native compiler it finds (`$MINI_STAGE0`, `~/.mini`, `PATH`) or from the latest release, which it downloads
once into `build/stage0`. There is no JVM fallback: the repository builds without a JDK.

To use it from any directory, put the repository root on your `PATH`:

```sh
export PATH="$PATH:/path/to/mini"      # add to ~/.bashrc or ~/.zshrc
mini new hello && cd hello && mini run
```

Shell completion (bash, zsh and fish):

```sh
mini completions bash > ~/.local/share/bash-completion/completions/mini
mini completions zsh  > ~/.zfunc/_mini            # with ~/.zfunc in $fpath
mini completions fish > ~/.config/fish/completions/mini.fish
```

Environment variables:

| Variable | Meaning |
|---|---|
| `MINI_ROOT` | The installation: a directory with `bin/minic-native`, `lib/libminirt.a`, `include/` and `stdlib/`. Default: the directory above the running `mini`; the `./mini` wrapper sets it to `build/native`. |
| `MINIC_NATIVE` | The `minic-native` binary to use instead of `$MINI_ROOT/bin/minic-native`. |
| `MINI_STAGE0` | Used by `./mini` / `scripts/build-mini.sh`: a native compiler to build the current compiler with. |
| `MINI_HOME` | Cache of git dependencies (default `~/.mini`). |
| `MINI_CC` / `CC` | C compiler (default: clang, then gcc). |
| `NO_COLOR`, `CLICOLOR_FORCE` | Colour control, see [Output](#output). |
| `MINI_DEBUG` | Print stack traces of internal errors. |

## Project layout

```text
hello/
  mini.toml          manifest
  mini.lock          pins of git dependencies (generated, commit it)
  src/main.kt        sources (all *.kt below src/, in any subdirectory); an app has `fun main()`
  tests/*.kt         tests (@Test functions); not part of the normal build
  build/             output (debug/, release/, test/, overlay/); ignored by git
```

A **library** is a *source package*: it has no binary of its own, its sources are compiled together with the program
that uses it (and with the standard library), exactly like the stdlib. `mini build` in a library compiles it once to
verify it.

## `mini.toml`

```toml
[package]
name = "hello"          # a-z, 0-9, _ and -; starts with a letter
version = "0.1.0"       # MAJOR.MINOR.PATCH (default 0.1.0)
kind = "app"            # "app" (default) or "lib"
memory = "managed"      # default memory mode of the package: "managed" (default) or "strict"
c-sources = ["host/x.c"]  # optional: C files (relative to the package) compiled and linked into every binary that contains the package

[dependencies]
geometry = { path = "../geometry" }
json  = { git = "https://github.com/you/json", tag = "v1.2.0" }
fast  = { git = "https://github.com/you/fast", rev = "9fceb02" }
live  = { git = "https://github.com/you/live", branch = "main" }
tip   = { git = "https://github.com/you/tip" }          # the default branch

[workspace]             # optional: a repository with several packages
members = ["app", "libs/*"]
```

* The dependency key must be the name the library gives itself in its `[package]`.
* **`memory = "strict"`** makes every file of the package Strict (the ownership checker is on), as if it started with
  `@file:MemoryMode(Mode.Strict)`; a file that has its own `@file:MemoryMode(...)` keeps it. `mini build --strict` does
  the same for all of your own packages from the command line. See [memory-model.md](memory-model.md).
* Git dependencies select a commit with `tag`, `rev` or `branch` (at most one). The commit is pinned in `mini.lock`.
  A local path or `./`/`../` relative path also works as git URL.
* Only a TOML subset is supported: tables, strings, integers, booleans, arrays, inline tables, comments. Everything else
  gets an error that says so.

Malformed manifests are reported like compiler errors, with file, line, column, a caret and a hint:

```text
error: unquoted value `hello`
 --> mini.toml:2:8
  |
2 | name = hello
  |        ^^^^^ not a valid value
  = help: strings need quotes: write "hello"
```

```text
error: unknown key `knd` in [package]
 --> mini.toml:3:1
  |
3 | knd = "app"
  | ^^^ not recognised
  = help: did you mean `kind`?
```

### Workspaces

A `mini.toml` with `[workspace] members = [...]` is a workspace root (it may also have a `[package]`). Members are
directories with their own `mini.toml`; a trailing `/*` includes every subdirectory that has one.

* Commands at the workspace root apply to **all members**; `-p <name>` selects one (repeatable).
* Inside a member directory the command applies to that member, but `build/` and `mini.lock` live at the workspace root.
* Members refer to each other with ordinary path dependencies.

## Dependencies

* **Path dependencies** are used in place.
* **Git dependencies** are cloned into `$MINI_HOME/git` (default `~/.mini/git`): one mirror per URL and one immutable
  checkout per commit. `mini.lock` pins the commit of every git dependency, so builds are reproducible and need no
  network once the checkout exists. Resolution is deterministic: the lock file is sorted and rewritten only when it changes.
* `--offline` forbids network access (a missing repository is an error that says so), `--locked` fails when `mini.lock`
  would have to change (for CI).
* Two different packages with the same name, dependency cycles, apps as dependencies and name mismatches are errors that
  point at the offending line of the manifest.

## Commands

Global options (anywhere on the command line): `-q`/`--quiet`, `-v`/`--verbose` (also prints every `minic`/`git`
command), `--color auto|always|never`, `-h`/`--help`, `-V`/`--version`.
Exit codes: 0 success, 1 failure (compile error, failing test, ...), 2 usage error, 101 internal error.

### `mini new <name> [--lib] [--strict] [--no-git]`

Creates `<name>/` with `mini.toml`, `src/`, `tests/` (with a sample test), `.gitignore` and `README.md`, runs `git init`
(unless `--no-git`) and prints the next steps. An app gets `src/main.kt`, `src/greeting.kt` and a test of `greeting`;
a library gets `src/lib.kt` (in a package named after it) and a test. Refuses to touch an existing directory.

### `mini init [--name <n>] [--lib] [--strict] [--no-git]`

The same inside the current (existing) directory; the name defaults to the directory name. Existing files are never
overwritten.

### `mini build [--release] [--strict] [-Werror] [-p <pkg>]...`

Compiles the selected packages: `src/` of the package and of all its dependencies plus the standard library are passed to
`minic`. Output: `build/debug/<name>` (`-g`) or `build/release/<name>` (`-O2`) for apps. Prints one `Compiling` line per
package, then `Finished`. `-Werror` makes memory-checker warnings fail the build; `--strict` makes your own packages
Strict.

### `mini run [build options] [-- args...]`

Builds the app and runs it with the given arguments; the exit code of the program is the exit code of `mini run`. A
program killed by a signal (panic = `SIGABRT`) is reported as such. In a workspace with several apps choose one with
`-p`.

### `mini check [--strict] [-Werror] [-p <pkg>]...`

Runs the compiler frontend and the Strict-mode ownership checker, but no C compiler and no linking: this is the fast
loop while you write code. Prints the `M01xx`/`M02xx` diagnostics.

### `mini test [filter] [--list] [--nocapture] [--fail-fast] [--release] [-- args...]`

1. Finds top-level `@Test fun name()` functions and `@Test` methods of classes and objects (a class needs a no-argument
   constructor; every test gets a fresh instance; ids are `file::Class.method`) in `tests/**/*.kt` and `src/**/*.kt` of the selected packages, using the
   Mini frontend's parser. `@Ignore` skips a test. (The file with `fun main` is not part of the test build; keep testable
   code in other files, as the `mini new` template does.)
2. Generates a runner (`build/test/<pkg>/`) and builds **one** test binary containing the sources, the tests and a small
   `kotlin.test` implementation (`assertEquals`, `assertNotEquals`, `assertTrue`, `assertFalse`, `assertNull`,
   `assertNotNull`, `assertSame`, `assertFails`, `assertFailsWith<T>`, `fail`, `@Test`, `@Ignore`). Tests are
   ordinary Kotlin: `import kotlin.test.*`.
3. Runs every test in its **own process**, so a crash or panic fails only that test, and prints cargo-style results with
   timing. Output of passing tests is hidden (`--nocapture` shows it); a failing test shows its stdout and the failure message.

```text
test tests/math.kt::adds ... ok (2ms)
test tests/math.kt::fails ... FAILED (3ms)

failures:

---- tests/math.kt::fails (tests/math.kt:10) ----
stdout:
about to fail
AssertionError: math is hard. Expected <3>, actual <2>.

failures:
    tests/math.kt::fails

test result: FAILED. 1 passed; 1 failed; 0 ignored; finished in 5ms
```

`filter` selects tests whose id (`file::name`) contains it. `--list` prints the ids without building. Exit code 1 when a
test fails. The test binary is reused when nothing changed.

### `mini clean`

Deletes `build/` of the workspace root (the lock file and the git cache stay).

### `mini add [name] --path <dir> | --git <url> [--tag <t> | --rev <r> | --branch <b>] [-p <pkg>]`

Adds a dependency to `mini.toml` (in the `[dependencies]` table; existing formatting and comments are kept), resolves it
and rolls the edit back if that fails. The name may be left out; it is then read from the library's own `mini.toml`.

### `mini remove <name>`

Removes a dependency (a misspelled name gets a suggestion).

### `mini update [name...]`

Moves the `mini.lock` pins of branches and default-branch dependencies to their newest commits (all git dependencies, or
the named ones) and prints `Updating json 1a2b3c4 -> 5d6e7f8`. Tags and revisions do not move.

### `mini tree`

```text
app v0.1.0 (.)
├── geo v0.1.0 (../geo)
│   └── util v0.1.0 (../util)
└── json v1.0.0 (git https://github.com/you/json#9fceb02)
```

Repeated subtrees are marked `(*)`.

### `mini explain <code>` / `mini explain --list`

Prints the explanation of a diagnostic (`M0102`, `m102` and `102` all work) from the catalog in
[memory-model.md](memory-model.md) section 7. The texts are generated from that file at build time
(`scripts/gen-explain.py`), so they never drift.

### `mini doctor`

Checks the Mini root, `minic-native`, the C compiler (clang or gcc), git
(only for git dependencies), libuv (only for the runtime tests) and the write access to `$MINI_HOME`, and prints a fix
for everything missing. Exit code 1 if something required is missing.

### `mini completions <bash|zsh|fish>`, `mini help [command]`, `mini --version`

As expected. A mistyped command or option gets a suggestion: `error: unknown command 'bild'` / `help: did you mean 'build'?`.

## Output

* Status lines go to stderr in cargo style (`   Compiling`, `    Finished`, `     Running`), results (test results, `tree`,
  `explain`) to stdout, so `mini tree > deps.txt` works.
* Colours are on when the stream is a terminal; `NO_COLOR` turns them off, `CLICOLOR_FORCE=1` or `--color=always` on.
* `--quiet` prints only warnings, errors and results; `--verbose` also prints every subprocess command line.
* Compiler diagnostics keep the rustc-like format of `minic`, with paths rewritten to project-relative ones
  (`src/main.kt:11:15`, `../geometry/src/lib.kt:3:9`), including for files that `mini` rewrote internally.
* **`--message-format=json`** (build, run, check) prints one JSON object per line to stdout for editors and CI and no status
  lines:

  ```json
  {"reason":"compiler-message","package":"leaky","level":"error","code":"M0102","message":"allocation from `Heap` is never released","spans":[{"file":"src/main.kt","line":11,"column":15,"label":"allocated here; path: early return","primary":true}],"help":["call `buf.free()` ..."],"rendered":"error[M0102]: ..."}
  {"reason":"build-finished","package":"leaky","success":false,"millis":34423}
  ```

  `minic` itself has no JSON output yet; `mini` converts its text format (see "Follow-ups" below).

## Incremental builds

`mini build` computes a fingerprint (128-bit hash) of: the `mini` version, the compiler installation (launcher jars, stdlib
and runtime files), the flags, every `mini.toml` and every source file of the package and all its dependencies. It is stored
in `build/<profile>/.fingerprint/<package>`. If it matches and the binary exists, nothing is compiled
(`Fresh hello v0.1.0 (no changes)`). The test binary has its own fingerprint.

## How it works

```text
mini (this document)                     minic (compiler)
  mini.toml, mini.lock, git cache  ──►     minic build <all .kt files> -o build/debug/hello --stdlib ... --runtime-src ...
  dependency graph, fingerprints           minic check <file> --extra-src <others>...
  test discovery + runner generation
```

* **Strict by default (`memory = "strict"`, `--strict`).** `minic` has no default-mode flag, so for files without an explicit
  `@file:MemoryMode` `mini` compiles a copy in `build/overlay/` that starts with the annotation on the *first line* (line
  numbers do not change) and maps paths in the diagnostics back.
* **The checker only looks at one file per `minic` invocation** (the first source). `mini` therefore runs one extra
  `minic check` per further Strict file before building. This is a workaround; see the follow-ups.
* **Libraries** are source packages: `minic build` receives the sources of the program and of every library in its
  dependency graph. A library is compiled once with a generated `fun main() {}` stub to verify it.

### Architecture and self-hosting

`compiler/mini-tool` has a **pure Kotlin core** (`mini.tool`: manifest parser, dependency graph, command logic, output,
templates) behind a small `Platform` interface (file system, process spawn, environment, clock, terminal detection). There are
one implementation: `mini.tool.native.NativePlatform` (`src/NativePlatform.kt`, Mini code on top of `host/mini_host.c`, a
small POSIX layer reached through `@CSymbol`, linked through `c-sources` in `compiler/mini-tool/mini.toml`); the unit tests run on it
too. `scripts/check-purity.sh` enforces that the sources use no `java.*`, `javax.*`, `kotlin.jvm`, `System.*`, ...
The only dependency of the core is the pure-Kotlin parser of `compiler/frontend` (for `@Test` discovery and the file-level
`MemoryMode` annotation). Because the core is compiled by Mini, it avoids what the Mini stdlib lacks (`Regex`, a few `String`
helpers); `Text.kt` has the hand-written line patterns.

**Build pipeline.** `mini build` runs `minic-native -o app.c <sources> --stdlib <stdlib files>` (Kotlin to C; `--check` for the
memory checker) and then the C compiler on that file, `lib/libminirt.a` and `-l` flags from `@file:CLink`. `scripts/build-mini.sh`
builds the tool the same way: `minic-native -o mini.c <tool + frontend sources> --stdlib ...`, then `clang mini.c mini_host.c libminirt.a`.
`ExplainData.kt` (the `mini explain` texts) is generated from `docs/memory-model.md` by `scripts/gen-explain.py` and checked in,
so the native build needs no Gradle.

## Follow-ups

Deferred in v0:

* `mini install`; the K2 frontend is no longer reachable from `mini` (only the own frontend of `minic-native` is used).
* `[dev-dependencies]`, `[features]`, `subdir` for git dependencies in a monorepo, registries and versions ranges.
* Parallel compilation of several workspace members and parallel test execution; test timeouts.
* A watch mode (`mini watch check|test|run`) and `mini fmt`.
* Maven/KMP dependencies (source jars, klibs; plan section 8).

Recommended compiler-side improvements (they would make `mini` better; none of them is done by `mini` itself):

1. **Check all user files.** `minic build`/`check` run the memory checker on the first source only. A flag or rule that
   checks every non-`--extra-src` file would remove the per-file `minic check` workaround (one JVM and one K2 run per
   Strict file today).
2. **`--default-memory strict|managed`** for `minic build/check`, so that `mini` does not need the overlay copies.
3. **JSON diagnostics** (`--message-format json`) from `minic`, with the same schema as above, plus full relative paths in
   Kotlin frontend errors (they print only the file name, `main.kt:2:13: error: ...`).
4. **`#line` directives** in the generated C (and `-g`), so debuggers and sanitizer reports show Kotlin source lines.
5. **Explain texts in the compiler.** `minic --explain M0102` (or a stable resource) instead of extracting them from
   `docs/memory-model.md`.
6. **Faster frontend.** Every `minic` run type-checks the whole stdlib with K2 (about 25 s of CPU here). A cached
   stdlib (klib or serialized FIR), or a long-running `minic` server, would make `mini check` and `mini test` instant.
   Likewise, caching the compiled runtime objects would save the clang run over `runtime/src` on every build.
7. **Annotation classes in user code.** `minic` rejects `annotation class` declarations in non-stdlib sources
   ("unsupported construct"); `mini test` works around it by passing the `kotlin.test` annotations as declaration-only
   `--extra-src`.
8. **A `main` that is not unique.** Tests and libraries need a program without `fun main` (library check) or a different
   entry point (test runner); `minic build --no-main` / `--entry <fn>` would remove the generated stub and the
   "exclude the file with `main`" rule of `mini test`.
9. **Exit status of uncaught exceptions** is 1 like an assertion failure; a distinct code (or a `--test` mode that prints a
   machine-readable failure record) would let `mini test` tell a failed assertion from an unexpected exception.
