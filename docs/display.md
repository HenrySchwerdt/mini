# Rich output: the display protocol

A program can hand a typed value (a drawing, a table, HTML) to whatever renders its output, by printing one escape
sequence on stdout. `mini.display` (standard library) produces it, the website notebook, playground and docs cells consume it.

```text
ESC ] 5379 ; mini-display ; <mime> ; <base64> BEL
0x1B 0x5D ...                                  0x07
```

* `<mime>`: a MIME type, e.g. `image/svg+xml`. No `;` in it.
* `<base64>`: standard Base64 (with padding) of the UTF-8 data.
* The terminator is BEL; consumers also accept the string terminator `ESC \`.
* There is **no newline** in the sequence. The text around it is untouched, so a bundle can sit in the middle of a line.
* It is an OSC sequence ("operating system command"). Terminals ignore OSC numbers they do not know, so `mini run` in a
  terminal shows only the text of the program. Base64 keeps the payload free of ESC/BEL/newlines, so it survives line
  buffering, pipes and files. Programs read their stdout back unchanged: strip the sequences with the regex
  `\x1b\]5379;mini-display;[^;]*;[A-Za-z0-9+/=]*(\x07|\x1b\\)`.
* A consumer must work across arbitrary chunk boundaries (the website's parser is `display.ts`, tested at every split).
  A sequence that never terminates, or whose payload is not valid Base64/UTF-8, is shown as text / ignored.

## Types the website renders

| MIME | Rendering |
|---|---|
| `image/svg+xml` | Inlined if every element and attribute is on a strict allowlist (`sanitize.ts`; then `currentColor` follows the theme), otherwise `<img src="data:...">` (inert). |
| `text/html` | `<iframe sandbox="" srcdoc=...>` with a CSP of `default-src 'none'`: no scripts, no network, no same-origin access. |
| `application/vnd.mini.table+json` | `{"columns": ["a", "b"], "rows": [["1", "2"]]}` as a table. |
| `text/markdown`, `text/plain` | Preformatted text. |
| anything else | A note that names the type. |

## API (`mini.display`)

`display(mime, data)`, `displaySvg`, `displayHtml`, `displayMarkdown`, `displayTable(header, rows)`, and the pure
`displayBundle(mime, data): String` / `base64OfUtf8(text)`.

## Native programs

`mini run` does not interpret bundles: the sequence goes to stdout and the terminal ignores it. Libraries that produce
files offer them directly (`plt.savefig("plot.svg")`). To extract bundles from a captured stdout, use the regex above.

## In CI

Documented output of a docs cell shows a bundle as a line `[display: <mime>]`. `website/scripts/normalize-display.mjs`
(native samples) and `run-snippets-wasm.mjs` (browser toolchain) apply that replacement and check that SVG and table
payloads are well formed.
