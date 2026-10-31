---
title: "Rich output (mini.display)"
notebook: true
---

# Rich output: `mini.display`

A program can show more than text. `mini.display` is part of the standard library: it prints one escape sequence on stdout, a **display bundle** with a MIME type and the data, and the page that runs the program (the notebook, the playground, the code cells of this site) renders it below the text output. Plain text output is unchanged.

```kotlin
import mini.display.*

println("Before")
displaySvg("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 40" width="120" height="40">
  <rect x="2" y="2" width="116" height="36" rx="8" fill="none" stroke="currentColor" stroke-width="2"/>
  <text x="60" y="26" text-anchor="middle" font-family="sans-serif" font-size="16" fill="currentColor">Mini</text>
</svg>""")
println("After")
```
```output
Before
[display: image/svg+xml]
After
```

The documented output shows a bundle as a line `[display: <type>]`.

## What can be shown

| Function | MIME type | Shown as |
|---|---|---|
| `displaySvg(svg)` | `image/svg+xml` | The drawing itself. Plain SVG (shapes, paths, text, gradients) is inlined into the page, so `currentColor` follows the theme. SVG that uses anything else (scripts, `<style>`, links, images) is shown as an `<img>`, where it cannot run anything. |
| `displayHtml(html)` | `text/html` | A sandboxed frame: no scripts, no network, no access to the page. |
| `displayTable(header, rows)` | `application/vnd.mini.table+json` | A table. |
| `displayMarkdown(text)` | `text/markdown` | Preformatted text (no Markdown rendering yet). |
| `display(mime, data)` | any | Types the page does not know are listed as a note. |

```kotlin
import mini.display.*

displayTable(
    listOf("language", "year"),
    listOf(listOf("Kotlin", "2011"), listOf("Mini", "2025")),
)
displayHtml("<h3>HTML</h3><p>Rendered in a <b>sandboxed</b> frame, <i>without</i> scripts.</p><script>alert(1)</script>")
```
```output
[display: application/vnd.mini.table+json]
[display: text/html]
```

## Interactive model output

The [digit lab](/libraries/digits) uses `display("application/vnd.mini.digit-classifier+json", json)` to show a drawing pad after training. The payload contains a version, 640 weights and 10 biases for a 64 → 10 classifier. The host validates these parameters and uses them to score your drawing locally. No scripts from the output run in the page.

## The protocol

```text
ESC ] 5379 ; mini-display ; <mime> ; <base64 of the UTF-8 data> BEL
```

It is an OSC ("operating system command") sequence, so a terminal that does not know it ignores it and shows nothing; a program run with `mini run` therefore prints just its text. The data is Base64, so it never contains the terminator and survives line buffering and pipes. The full specification, including how to consume it from your own host, is in [docs/display.md](https://github.com/HenrySchwerdt/mini/blob/main/docs/display.md). The plotting library [mini.plot](/libraries/plot) is built on it.
