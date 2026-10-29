# Mini

A C17 bootstrap compiler for a statically typed language with Kotlin-inspired
classes and inheritance, interfaces, generics, packages/modules, annotations,
and native `Optional<T>` / `Result<Res, Err>`. Memory management is explicit.
There is no `null` literal; absent pointers use `Optional<*T>`.

Methods live in classes/interfaces, do not require `fn`, and receive `this` as an
implicit instance pointer. `object.method()` automatically borrows class values.
Classes default to `Object`; extending `AllocatableObject` enables typed heap
allocation and explicit freeing. The executable entry is `class Main { static main() }`.
Locals use `val`/`var` with inference, and semicolons are optional across lines.
Function types and lambdas support typed callbacks, implicit `it`, trailing lambda
arguments and immutable scalar captures; see [the example](examples/lambdas.mini).
The first libuv-backed `Thread`/`Mutex` APIs provide native multithreading with
typed errors and explicit cleanup; see [the parallel example](examples/threads.mini).
libuv also backs a blocking TCP `mini.net` module (`TcpListener`/`TcpConnection`);
see [the loopback example](examples/net.mini). Generics now include non-virtual
generic instance methods (`box.map<R>(f)`; see
[the example](examples/generic_methods.mini)), and the std adds `mini.math.Math`,
`mini.core.Pair`/`Triple` and `mini.core.IntRange`.
See the [implemented language reference](docs/language-v0.2.md), the
[v0.3 freeze manifest](docs/freeze-v0.3.md) and [examples](examples).

```mini
class Main {
    static answer(): Result<i32, bool> = Result.Ok(42)

    static main(): i32 {
        val answer = match Main.answer() {
            Ok(value) => value
            Err(error) => 0
        }
        print_i32(answer)
        return 0
    }
}
```

## Build and test

Install xmake, a C17 compiler and Clang. xmake resolves Unity 2.7.0 for tests
and static libuv 1.52.1 for native concurrency.
The optional direct LLVM backend additionally needs LLVM development headers,
libraries, the native target and `llvm-config`.

```sh
xmake f -c -y
xmake
xmake test
xmake run mini0 --help
xmake run mini0 build examples/kotlin.mini -o build/kotlin
```

Run `build/kotlin` (`build/kotlin.exe` on Windows). The native example prints
`42`, `2`. `xmake test` includes language regression tests and an end-to-end
CLI suite that builds/runs examples, imports std modules, tests control flow, and
emits C and LLVM IR. Clang must be available on PATH for those integration tests.
The CLI also accepts `--cc /path/to/compiler` or `MINI_CC`.

Compile/check without linking the optional LLVM C API backend:

```sh
xmake f --llvm=n
xmake
xmake test
```

The native compiler currently lowers through checked, monomorphized C17 and
invokes Clang/LLVM. It supports `check`, `build`, `emit-c`, and `emit-llvm` commands.
A GCC-compatible C17 compiler can compile emitted C; `emit-llvm` needs Clang.
The separate direct LLVM C API backend is still incomplete. It is not the working
CLI's code generator. Native macOS arm64 is verified locally; Linux/Windows code
paths exist but their test matrix has not been run here. Cross-compilation still
needs a target toolchain/runtime and is not implemented as a CLI option.

## LLVM installation selection

With `--llvm=y` (default), the build queries the installed LLVM instead of pinning
an incompatible Homebrew patch version or hardcoding an architecture. Discovery:

1. `xmake f --llvm_config=/path/to/llvm-config`.
2. `LLVM_CONFIG` (full executable path).
3. `LLVM_ROOT` (prefix containing `bin/llvm-config`, or `llvm-config.exe` on Windows).
4. `llvm-config` on PATH.
5. On macOS, `brew --prefix llvm` for keg-only installations.

Invalid explicit selections fail. Shared LLVM linkage is the default;
`xmake f --llvm_link=static` selects static libraries. Homebrew's extra dependency
library directory is discovered and applied only to LLVM consumers. Both modes
were verified with Homebrew LLVM 23.1.4. To select that Clang for generated code,
pass its executable path using `mini0 --cc` after the subcommand.

Static LLVM's C++ runtime must match the toolchain (conventional libc++ on
macOS/BSD, libstdc++ on Linux/MinGW). Windows development libraries must match the
MSVC architecture/runtime; compiler-only LLVM distributions are insufficient.
For shared LLVM on Windows its DLL directory must be on PATH when running.

## Source modules and std foundation

A package is a namespace; a module is a compilation/visibility boundary. The CLI
accepts dependency sources explicitly, with no global search paths or downloads:

```sh
xmake run mini0 build examples/stdlib.mini \
  --dep core=std/mini/core/value.mini \
  --dep collections=std/mini/collections/cell.mini \
  --dep memory=std/mini/memory/allocator.mini \
  --dep io=std/mini/io/console.mini \
  --depends collections=core -o build/stdlib
```

The example prints `42` and explicitly releases its allocation. `HeapAllocator`
returns `Result<*u8, AllocationError>`; `ByteIO` returns `Result<usize, IOError>`.
The first Kotlin-style collection slice includes
`Collection<T>`, `List<T>`, `MutableCollection<T>`, `MutableList<T>`, `RandomAccess`
and allocator-backed `ArrayList<T>` with indexed operations and typed errors.
See [std APIs and ownership](std/README.md). It is not a completed
collections/text/filesystem library or ownership system. Modules currently compile
from sources together; binary metadata and a package manager remain future work.

## VS Code / clangd

Use the recommended clangd and xmake extensions. Both use `compile_commands.json`
in the repository root. It contains local LLVM/Unity includes, C17 flags and SDK
paths, is regenerated by xmake, and is not committed.

```sh
xmake project -k compile_commands --lsp=clangd .
```

If old diagnostics remain, run **clangd: Restart language server**. The executable
is selected by your extension/user configuration; no machine-specific clangd path
is committed.

## Layout

- `bootstrap/src/frontend`: lexer, parser, AST and legacy v0.1 checker.
- `bootstrap/src/lowering`: project/module resolution, validation, concrete generic
  instances, classes/dispatch, native variants, annotations and C17 output.
- `bootstrap/src/driver`: platform process invocation and output generation.
- `bootstrap/src/backend`: optional direct LLVM backend under construction.
- `runtime`: small allocation/I/O C ABI.
- `std`: initial class-based standard-library sources.
- `tests/native.lua`: native CLI integration tests.

The [v0.1 grammar](docs/grammar.md) is retained as historical bootstrap syntax;
[the v0.2 document](docs/language-v0.2.md) describes the current language.
