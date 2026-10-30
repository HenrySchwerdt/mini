# Playground and notebook: front end and toolchain contract

This directory is the browser front end of the playground (`/playground`) and the notebook (`/notebook`),
plus the contract that the in-browser Mini toolchain has to implement. The front end is complete and works
without the toolchain: editing, highlighting, examples, sharing, notebooks and `.minb` files all work, and
Run/Check say honestly that they are not available yet. When the toolchain files appear at
`<site base>/toolchain/`, Run and Check light up without any change to this code.

Nothing here compiles anything. There is no interpreter, no server, and no precompiled sample.

## Files

| File | Role |
|---|---|
| `types.ts` | The `MiniToolchain` interface, diagnostics, manifest and both worker protocols. Start here. |
| `toolchain.ts` | Main-thread `WorkerToolchain`: probes the manifest, starts the toolchain worker, routes `load/check/compile` to it and `run` to the runner worker. |
| `runner.worker.ts`, `run-client.ts`, `wasi.ts` | Site-owned runner: one fresh worker per run, a WASI preview1 shim, limits enforced by terminating the worker. |
| `session.ts` | Page state (availability, progress) and the build-and-run pipeline used by both pages. |
| `editor.ts` | Shared CodeMirror setup: diagnostics, selections, search, keyboard bindings and Cmd/Ctrl+Enter. |
| `kotlin-editing.ts` | Four-space indentation, Kotlin/document completions, snippets and space-inserting Tab. |
| `Playground.vue`, `Notebook.vue`, `CodeCell.vue`, `RunOutput.vue`, `ToolchainStatus.vue` | UI. |
| `notebook.ts` | Notebook model, `.minb` format, splitting cells and assembling the program for a cell. |
| `share.ts` | Share links (compressed text in the URL hash). |
| `examples.ts` | The examples dropdown, fed from `website/samples`. |
| `starter.ts` | The starter notebook. |
| `../../../public/mini-sw.js` | Cache-first service worker for `toolchain/` files. |
| `../../../tests/*.test.ts` | `npm test` (node:test): WASI shim on hand-written wasm, notebook splitting/assembly, `.minb`, share links. |

## Learn notebooks

The `/learn/` pages use the same cell compiler and editor through `MiniCell.vue`.
`notebook: true` enables declaration sharing; `learnNotebook: true` adds the course
layout and opens code editors when cells approach the viewport. Markdown remains
server-rendered and has no editing controls. `learn-course.ts` is the shared chapter
order for the sidebar and previous/next links. The free-form `/notebook` keeps its
own text and structural editing controls.

Run `npm run docs:build && npm run test:learn` to check editing, reset, navigation,
mobile overflow and sidebar geometry across all documentation groups. The same browser checks also exercise real editor keystrokes in Learn, notebook and playground: indentation, matching brackets, completion/snippets, undo, comments and search. This UI test
does not require the wasm toolchain. Existing sample extraction still checks every
lesson's assembled program and documented output.

## The interface

```ts
interface MiniToolchain {
  load(onProgress: (p: LoadProgress) => void, level?: 'check' | 'full'): Promise<ToolchainInfo>
  check(files: SourceFile[]): Promise<Diagnostic[]>
  compile(files: SourceFile[], opts?: CompileOptions): Promise<CompileResult>
  run(wasm: Uint8Array, opts: RunOptions): Promise<RunResult>
}
```

The authoritative definitions, with comments, are in `types.ts`.

- **`load`** downloads and initializes. It is idempotent, and a later `full` upgrades an earlier `check`.
  - Level **`check`**: only `minic.wasm` (a few MB): parse, type check, memory checker. Enough for `check`.
  - Level **`full`**: additionally the stdlib bundle, the `clang` and `wasm-ld` wasm build, and
    `libminirt-wasm32.a`. Needed for `compile`. This is the big download, so it must report byte progress.
  - The playground loads `check` when the page opens (if a manifest exists) for live diagnostics, and `full` on the first Run.
  - It rejects with `ToolchainUnavailableError` when there is no manifest (the front end handles that before calling).
- **`check(files)`** returns diagnostics only. `Diagnostic` has `code` (e.g. `M0102`, absent for plain
  syntax/type errors), `severity` (`error | warning | note`), `file`, 1-based `line`/`col`, exclusive
  `endLine`/`endCol` (columns in UTF-16 code units), `message`, optional `secondary` spans (each with a `label`)
  and optional `help`. The editor links `code` to `reference/diagnostics/<code>` on this site.
- **`compile(files, opts)`** returns `{ wasm?, diagnostics, mir?, llvmIr?, timings }`. `wasm` is present
  only when there are no error diagnostics: a WASI command module that exports `memory` and `_start` and
  imports only `wasi_snapshot_preview1`. `mir`/`llvmIr` are filled when `opts.emitMir`/`opts.emitLlvmIr` are
  set; the playground then shows MIR and LLVM IR tabs. `timings` are milliseconds per phase (`minic`, `clang`,
  `link`, `total` are known; others are displayed as given).
- **`run(wasm, opts)`** needs no toolchain: the site provides it (`runner.worker.ts`). `stdout`/`stderr`
  are streamed as text through `onStdout`/`onStderr`. The result is `{ exitCode, durationMs, timedOut?, outputLimitExceeded?, error? }`:
  exit code 124 on timeout, 130 when aborted, 134 on a trap (with `error`), 153 when the output limit
  (default 4 MiB) is exceeded.

All files go in as `{ name, text }`. The playground sends one file (`main.kt`), the notebook one assembled file
(`notebook.kt`, see below). The contract allows several files.

## What the toolchain worker has to do

The compiler pipeline runs inside the toolchain worker, which the toolchain build ships. Planned shape
(the front end does not depend on it):

1. `minic.wasm` (our self-hosted compiler, wasm32-wasi) runs with an **in-memory file system** that holds
   the sources and the stdlib bundle: parse, check, emit LLVM IR (and MIR text when asked). This stage also
   produces the diagnostics for `check`.
2. The wasm build of `clang` (`@yowasp/clang`, pinned) compiles the `.ll` to an object file; `wasm-ld`
   links it with `libminirt-wasm32.a`. Each tool is a WASI program run through a WASI shim with the same
   in-memory FS. (`wasi.ts` here is deliberately stdio-only. The worker needs a file-system-capable shim,
   for example `@bjorn3/browser_wasi_shim` pinned exactly.)
3. The resulting `.wasm` is returned to the page as a `Uint8Array` (transferred, not copied).

Requirements on the worker:

- Report **download progress** for every large file with `loadedBytes`/`totalBytes` (use `Content-Length` and a
  streaming `fetch` reader) and a human label, so the UI can show "Loading clang and wasm-ld (12.4 of 38 MB)".
  `fraction` (0..1) may be set when the worker knows the total for the requested level.
- Be re-entrant-safe: the page sends one request at a time, but a request may be abandoned. The page
  cancels by terminating the worker; the next `load` starts a fresh one (served from the HTTP/service worker cache).
- Never throw for ordinary compile errors: they are diagnostics. Use the `error` response only for internal failures.
- Keep `check` usable before the `full` level is loaded.

## Worker protocol (main thread to toolchain worker)

The worker is a **module worker**, `new Worker(url, { type: 'module' })`. Every request carries a numeric `id`
that every response echoes. Types are `ToolchainRequest`/`ToolchainResponse` in `types.ts`.

```
page -> worker   { id, type: 'load', level: 'check' | 'full', manifestUrl }
page -> worker   { id, type: 'check', files: SourceFile[] }
page -> worker   { id, type: 'compile', files: SourceFile[], opts: CompileOptions }

worker -> page   { id, type: 'progress', progress: LoadProgress }   // zero or more, only for 'load'
worker -> page   { id, type: 'result', value }                      // ToolchainInfo | Diagnostic[] | CompileResult
worker -> page   { id, type: 'error', message }                     // internal failure; the request failed
```

`CompileResult.wasm` should be sent in a `postMessage` transfer list. The worker resolves file URLs relative
to `manifestUrl`. `run` is not part of this protocol (the site's runner owns it, see `RunRequest`/`RunEvent` in `types.ts`).

## Deployment layout: `<site base>/toolchain/`

The toolchain build writes into `website/public/toolchain/` (not committed; the Pages workflow produces it)
so that VitePress copies it to `/mini/toolchain/`:

```
toolchain/manifest.json     tiny, fetched with cache: 'no-cache'
toolchain/<worker>.js       module worker named by manifest.worker
toolchain/...               minic.wasm, stdlib bundle, clang/wasm-ld wasm, libminirt-wasm32.a
```

```jsonc
{
  "version": "2026.11.0",                       // changes whenever any file changes
  "worker": "mini-toolchain.worker.js",
  "files": [ { "path": "minic.wasm", "bytes": 3400000, "stage": "check" },
             { "path": "clang.wasm", "bytes": 38000000, "stage": "full" } ],
  "capabilities": { "check": true, "compile": true, "mir": true, "llvmIr": true }
}
```

The page treats "no valid manifest" (404 or not JSON; a dev server answers unknown URLs with HTML) as
"toolchain not available" and shows "Run is not available yet" with a link to the roadmap. There is no other switch.

## Caching plan (implemented)

- Large files should be immutable per version: name them with a content hash or put them in a
  per-version directory. GitHub Pages sends `cache-control: max-age=600`, so do not rely on HTTP caching alone.
- `public/mini-sw.js` is registered (by `toolchain.ts`, only when a manifest exists, and not on `localhost`) as
  `<base>/mini-sw.js?v=<manifest.version>` with scope `<base>/`. It answers GETs under `/toolchain/` cache-first
  from the cache `mini-toolchain-<version>`, except `manifest.json` (always fetched, so a new version is noticed).
  On activation, caches of other versions are deleted. Range requests are not cached.
- First visit downloads once; later visits and other pages work from the cache and offline. A new version
  means a new `?v=`, so the browser installs the new worker and the old cache is dropped.
- Nothing from `toolchain/` is requested before the playground or notebook page is open: the editor and
  runner are separate chunks (`defineAsyncComponent`), other pages only get the small "Open in playground" button.

## Notebook semantics

The notebook page documents this under "How cells run". Cells are Markdown or Kotlin code.

- A code cell is compiled together with the **declarations** of the cells above it and itself: `@file:` annotations, `import`s
  (de-duplicated), `fun`, `class`, `interface`, `object`, `enum`, `typealias`, and top-level `val`/`var`
  (the splitter in `notebook.ts` decides by the first keyword after annotations and modifiers; `val (a, b) = ...`
  and `object : T {}` are statements; a `context(...)` prefix belongs to the declaration after it).
- The remaining top-level **statements of the cell being run** become the body of a generated `fun main() { ... }`.
  Statements of other cells are not part of the program. A `fun main` declared in an earlier cell is dropped; a
  cell that declares its own `main` and has no statements keeps it.
- Every run is a separate program (compile, then run in a fresh worker). Values do not survive between runs;
  declarations do, and top-level properties are initialized again on every run.
- An error in an earlier declaration shows up in every later run. Redefinition is a normal "conflicting declaration" error.
  `@file:MemoryMode(Mode.Strict)` applies to every cell from the one that has it on.
- Diagnostics are mapped back to cell and line through a per-line origin table (`assembleProgram`/`mapDiagnostics`);
  generated lines (`fun main() {` and its closing brace) map to the running cell.
- **Run all** runs the code cells top to bottom and stops at the first failure.
- Each cell's output is that run's stdout/stderr (in order), the exit code and time, and its problems.

Splitting is a lightweight lexer (strings, raw strings, templates, comments, brackets, continuation lines), not
a parser. It is conservative: when a line is ambiguous, wrap the code in an explicit function.

### `.minb`

```json
{ "format": "minb", "version": 1, "title": "A tour of Mini",
  "cells": [ { "type": "markdown", "source": "# Hi" }, { "type": "code", "source": "println(1)" } ] }
```

No outputs are stored. Unknown versions and malformed cells are rejected with a message. The notebook is also kept in
`localStorage` (a per-browser convenience) and can be shared as a link: `#c=` + compressed `.minb` JSON.

## Share links

`#c=<payload>`: `z` + base64url(deflate-raw(UTF-8)) using `CompressionStream`, or `p` + base64url(UTF-8) where that is missing.
The fragment is never sent to a server. The playground link carries the source, the notebook link the `.minb` JSON.

## Open in playground

`../open-in-playground.ts` adds a button to every Kotlin code block of the docs (in the browser, after route changes). It
builds a share link from the block's text and navigates to `/playground`.

## Integration checklist (when the toolchain exists)

1. Produce `manifest.json`, the worker and the files into `website/public/toolchain/` in the Pages build (a build step on `main`;
   keep it out of pull request runs).
2. Implement the worker protocol above against `types.ts`. A quick manual conformance test: open `/playground`, the status
   banner must go from "Loading the checker" to idle, the leak example must show M0102 with the hover, Run must print.
3. Check that the produced modules need only `wasi_snapshot_preview1` functions the shim in `wasi.ts` implements (stdio, args,
   environ, clocks, random, `proc_exit`, `poll_oneoff` for clocks); everything else answers `ENOSYS`. If programs need a file system,
   extend `wasi.ts` (it is tested under `npm test`).
4. Decide the time limit defaults (`Playground.vue`: 10 s selectable, `Notebook.vue`: 15 s) and whether `check` should load on page open.
5. Cross-origin isolation is not required (no `SharedArrayBuffer` needed; `poll_oneoff` falls back to a busy wait where `Atomics.wait` is not allowed).

## Cells in the documentation, preload

- `../../mini-cells.ts` (markdown-it plugin) wraps every runnable Kotlin fence in `<MiniCell>`; `../snippets.ts` holds the fence syntax
  (`norun`, `run`, `check=M0102`, `fresh`, `chain`, `exercise`, `exit=N`, a following `output` fence) and `programFor`, which assembles
  the program of a cell (notebook semantics on pages with `notebook: true`). `MiniCell.vue` runs it through `session.ts`.
- `preload.ts` starts loading the toolchain after the page `load` and an idle moment (skipped for Save-Data and 2g/3g); `ToolchainBadge.vue`
  shows progress. `ensureToolchain` and `exclusive` in `session.ts` serialize loads and compile/check requests.
- CI: `scripts/extract-snippets.mjs` with `scripts/check-samples.sh` (native compiler) and `scripts/run-snippets-wasm.mjs` (in-browser toolchain, in node).

## Development

```
cd website
npm test            # node:test, no browser needed (WASI shim with wabt, notebook, share links)
npm run typecheck   # tsc over the .ts files
npm run docs:dev
```

### HTTP datasets and interactive model output

A notebook code cell can declare `// %dataset https://example.org/data.csv` on its own line.
On an explicit Run, the host downloads the UTF-8 text (CORS must allow it), decompresses `.gz` files,
and compiles a generated source module exposing `mini.notebook.datasetText(): String` alongside the cell.
The closest directive at or above a code cell supplies its input, until a later directive replaces it. Markdown cells are ignored. Downloads omit credentials, time out after 30 seconds, can be stopped,
and are limited to 2 MiB after decompression. The dataset is fetched again on each run, not saved in `.minb`.
Cells using datasets have a 120-second execution budget; other cells retain 15 seconds.
This is a notebook host facility, not a general Mini HTTP client; it requires no new compiler intrinsic.

`application/vnd.mini.digit-classifier+json` is a data-only rich output for a 64 → 10 affine softmax model:
`{ "version": 1, "weights": [640 finite numbers with magnitude ≤ 1e6, output-major], "bias": [10 finite numbers with magnitude ≤ 1e6] }`.
The host validates the payload and renders a mouse/touch drawing pad, reduces its alpha channel to 8×8 values in [0,1],
and evaluates `softmax(x @ weight.T + bias)` locally. No scripts from output are executed. This fast inference path
uses the emitted Mini-trained parameters in JavaScript; training, evaluation and plotting run in Mini.
The widget is a snapshot of one run, not a persistent Mini process. See `/libraries/digits` for the editable experiment.

The digit lab uses short declaration cells and a final executable cell. Its **Run experiment** button runs the last code cell, which assembles all earlier declarations; **Run all** retains the usual cell-by-cell behavior. The second preset version uses `mini-notebook-digits-v2` storage, leaving the original saved notebook untouched.
