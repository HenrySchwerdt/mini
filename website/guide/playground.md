# Playground and notebook

The site has an editor you can use without installing anything: the [playground](/playground) for one program and the [notebook](/notebook) for several cells.

::: warning Run is not available yet
The editors are finished, but the compiler that runs in the browser is not. Until then **Run** and **Check** are disabled on both pages and say so. Editing, highlighting, the examples, sharing and saving notebooks work. The plan is in the [roadmap](/roadmap).
:::

## What it will do

When the in-browser toolchain ships, the whole pipeline runs in your browser, with no server: the Mini compiler (compiled to WebAssembly) checks and compiles your program to a `.wasm` module, which runs in a worker with a small WASI shim. A program that runs too long, or prints too much, is stopped.

- **Check** reports diagnostics inline: a squiggle, a marker in the gutter, and a hover with the code (a link to the [diagnostics reference](/reference/diagnostics/)), the message, secondary locations and the suggested fix. It needs only the small first part of the toolchain.
- **Run** (`Ctrl/Cmd+Enter`) compiles and runs. Output, exit code and time appear next to the editor, and MIR and LLVM IR tabs show what the compiler produced (see [How it works](/internals/)).
- **Examples** lists every sample of this documentation. A sample from the [memory guide](/memory/) that is meant to be rejected says which error to expect.
- **Share** puts the source, compressed, in the URL after the `#`. It is never sent to a server.
- Every Kotlin code block in the documentation has an **Open in playground** button.

## Notebook cells

A notebook mixes Markdown cells with Kotlin code cells.

- A code cell is compiled together with the **declarations** of the cells above it: imports, `fun`, `class`, `interface`, `object`, `enum`, `typealias` and top-level `val`/`var`.
- The **statements** of the cell you run become its `main`. Statements of other cells are not run.
- Every run is its own program. Declarations carry over, values do not.
- An `@file:MemoryMode(Mode.Strict)` annotation applies from its cell on, so put a Strict section last.
- Output, exit code, time and diagnostics appear under the cell. **Run all** goes top to bottom and stops at the first failing cell.
- **Save** writes a `.minb` file (JSON with the title and the cells, no output), **Open** or drag and drop reads one back. The notebook is also kept in your browser, and **Share** makes a link.

The starter notebook is a tour of the language followed by the memory model.
