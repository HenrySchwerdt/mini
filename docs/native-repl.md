# Native REPL

The LLVM C API build of `minic-native` has a persistent, in-process REPL:

```sh
minic-native --backend=llvm-api --repl --stdlib $(find stdlib -name '*.kt')
```

Enter one top-level `val`, `var`, or `fun` declaration per line. Enter an expression on a line to evaluate and print its value; use `:do <statement>` to run a statement without printing a result. `:help` describes the commands, and `:quit` (or EOF) closes the session. The ORC JIT remains alive across inputs, so later inputs can call earlier functions and read or update earlier top-level properties. Accepted declarations are compiled once and kept in the JIT; earlier source is not replayed.

The first version accepts one complete input per line, up to 64 KiB; longer lines are discarded through their newline. `--repl` accepts no positional source files. It rejects top-level `main`, classes, imports, package declarations, and redeclarations of an accepted name. These boundaries avoid ambiguous symbol replacement and unsupported cross-module type metadata. A compile error leaves prior declarations available for the next input.

Build the API compiler with the LLVM C API as described in [plan-kotlin.md](plan-kotlin.md#10g-toolchain-without-clang-llvm-as-the-primary-backend-static-libuv-jit-and-scripts-after-b3); the plain `--backend=c` compiler does not include the REPL.
