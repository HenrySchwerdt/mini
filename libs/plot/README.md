# mini-plot

SVG plots in plain Mini: line, scatter, bar, histogram, heatmap. No dependencies (it uses `mini.display` of the standard library).

```kotlin
import mini.plot.*

plot {
    line(xs, ys, label = "y")
    scatter(px, py, label = "points")
    title = "Demo"; xlabel = "x"; ylabel = "y"
}.show()          // rich output in the notebook / playground; .toSvg() for the text; .save("out.svg") for a file
```

Docs with live examples: `website/libraries/plot.md`. Programs: `examples/`.

* Data: `DoubleArray`, `List<Double>`, `IntArray`, or anything implementing `DoubleData` (`toDoubleArray()`).
* Colours: axes, text and grid are `currentColor`, so the figure follows the page theme; series use a fixed palette.
* Tick math (`niceTicks`, `niceNumber`, `fixed`, `scientific`) is local: it needs no `kotlin.math`.

## Plotting a tensor

mini.tensor is independent of this library. Either implement `DoubleData` on the tensor type, or pass its data:

```kotlin
plot { line(t.toDoubleArray(), label = "signal") }.show()                       // 1-D tensor
plot { heatmap(m.toDoubleArray(), m.shape[0], m.shape[1]) }.show()              // [rows, cols], row-major
```

## Use

`[dependencies] mini-plot = { path = "../mini/libs/plot" }`. In the website's browser toolchain the library is preinstalled
(`scripts/wasm/pack-libs.mjs` packs `libs/*/src` into `libs.json`; the worker compiles it with programs that import it).
Tests: `mini test`.
