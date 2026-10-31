# Native REPL

The LLVM C API build of `minic-native` has a persistent, in-process REPL:

```sh
minic-native --backend=llvm-api --repl --stdlib $(find stdlib -name '*.kt')
```

Installed releases expose the same session as `mini repl`. It loads the installed precompiled standard library and inherits the
terminal and standard input, so expressions and declarations stay in one process without a temporary compiler subprocess per line.

Enter one top-level `val`, `var`, or `fun` declaration per line. Enter an expression on a line to evaluate and print its value; use `:do <statement>` to run a statement without printing a result. `:help` describes the commands, and `:quit` (or EOF) closes the session. The ORC JIT remains alive across inputs, so later inputs can call earlier functions and read or update earlier top-level properties. Accepted declarations are compiled once and kept in the JIT; earlier source is not replayed.

The first version accepts one complete input per line, up to 64 KiB; longer lines are discarded through their newline. `--repl` accepts no positional source files. It rejects top-level `main`, classes, imports, package declarations, and redeclarations of an accepted name. These boundaries avoid ambiguous symbol replacement and unsupported cross-module type metadata. A compile error leaves prior declarations available for the next input.

Build an API compiler with LLVM 18 using `compiler/bootstrap.sh --backend=llvm-api`. Release archives link LLVM statically; the C bootstrap seed remains plain and does not include the REPL. The C fallback (`--backend=c`) is available from the same release compiler binary.
