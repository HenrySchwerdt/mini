# Playground and notebook

The site has an editor you can use without installing anything: the [playground](/playground) for one program and the [notebook](/notebook) for several cells.

The compiler runs in your browser as WebAssembly. It starts loading in the background when any page of the site opens (a small badge in the corner shows the progress; it is skipped with Save-Data or on a slow connection and then loads on the first Run), and your browser caches it, so later visits start instantly. The whole pipeline runs locally with no server: the Mini compiler checks and compiles your program to a `.wasm` module, which runs in a worker with a small WASI shim. A program that runs too long (10 s in cells, 10 to 15 s in the editors) or prints too much is stopped.

## Code cells in the documentation

Every complete Kotlin example in the documentation is a live cell with a **Run** button and its output right below it, and every example for the memory checker has a **Check** button. **Edit** makes it editable, **Reset** restores it, **Playground** opens it in the full editor. The [tutorial](/learn/) is built from such cells, which share declarations from top to bottom like a notebook. Examples that cannot run in a browser (C interop, fragments) are plain code.

- **Check** reports diagnostics inline: a squiggle, a marker in the gutter, and a hover with the code (a link to the [diagnostics reference](/reference/diagnostics/)), the message, secondary locations and the suggested fix. It needs only the small first part of the toolchain.
- **Run** (`Ctrl/Cmd+Enter`) compiles and runs. Output, exit code and time appear next to the editor, and MIR and LLVM IR tabs show what the compiler produced (see [How it works](/internals/)).
- **Examples** lists every sample of this documentation. A sample from the [memory guide](/memory/) that is meant to be rejected says which error to expect.
- **Share** puts the source, compressed, in the URL after the `#`. It is never sent to a server.
- Kotlin code blocks that are not runnable cells have an **Open in playground** button.

## Notebook cells

A notebook mixes Markdown cells with Kotlin code cells.

- A code cell is compiled together with the **declarations** of the cells above it: imports, `fun`, `class`, `interface`, `object`, `enum`, `typealias` and top-level `val`/`var`.
- The **statements** of the cell you run become its `main`. Statements of other cells are not run.
- Every run is its own program. Declarations carry over, values do not.
- An `@file:MemoryMode(Mode.Strict)` annotation applies from its cell on, so put a Strict section last.
- Output, exit code, time and diagnostics appear under the cell. **Run all** goes top to bottom and stops at the first failing cell.
- **Save** writes a `.minb` file (JSON with the title and the cells, no output), **Open** or drag and drop reads one back. The notebook is also kept in your browser, and **Share** makes a link.

The starter notebook is a tour of the language followed by the memory model.
