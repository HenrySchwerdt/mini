# mini-plot

SVG charts in plain Mini with a matplotlib-like API: lines, scatter, bars, histograms, heatmaps, areas, error bars, subplots,
log scales, four styles. No dependencies (it uses `mini.display` and `kotlin.math` of the standard library).

```kotlin
import mini.plot.*

plt.figure(figsize = 6.0 to 3.5)
plt.plot(xs, ys, "r--o", label = "y", linewidth = 2.0)
plt.scatter(px, py, label = "points")
plt.title("Demo", "with a subtitle"); plt.xlabel("x"); plt.ylabel("y")
plt.show()        // rich output in the notebook / playground; plt.toSvg() for the text; plt.savefig("out.svg") for a file

val (fig, axes) = plt.subplots(1, 2)          // object-oriented API
axes[0].bar(listOf("a", "b"), listOf(3.0, 5.0)); axes[0].set_title("bars")
fig.show()

plot { line(xs, ys, label = "y"); title = "Demo" }.show()    // the block DSL, same Axes class
```

Docs with live examples and a gallery: `website/libraries/plot.md`.

* Names follow matplotlib, snake_case included (`fill_between`, `set_xlabel`, `axhline`): one name per function, no camelCase twins.
  Python tuples are `Pair`s (`figsize = 8.0 to 4.0`, `xy = 1.0 to 2.0`).
* Data: `DoubleArray`, `IntArray`, `LongArray`, `List<Double>`, `List<Int>`, or anything implementing `DoubleData`
  (`toDoubleArray()`); data parameters are typed `Any`.
* Files: `Plot.kt` (`Axes`, `Figure`, DSL), `Pyplot.kt` (`plt`), `Render.kt` (layout and SVG), `Theme.kt` (styles, palettes),
  `Format.kt` (tick labels, colour maps, text widths), `Ticks.kt` (nice ticks).
* The SVG uses only elements and attributes of the website's inline allowlist (`sanitize.ts`), so it is inlined and follows
  the page theme (`currentColor`); the `dark` style paints its own background. Hover texts are `<title>` elements.

## Plotting a tensor

mini.tensor is independent of this library. Either implement `DoubleData` on the tensor type, or pass its data:

```kotlin
plt.plot(t.toDoubleArray(), label = "signal"); plt.show()                       // 1-D tensor
plt.imshow(m.toDoubleArray(), rows = m.shape[0], cols = m.shape[1]); plt.show() // [rows, cols], row-major
```

## Use

`[dependencies] mini-plot = { path = "../mini/libs/plot" }`. In the website's browser toolchain the library is preinstalled
(`scripts/wasm/pack-libs.mjs` packs `libs/*/src` into `libs.json`; the worker compiles it with programs that import it).
Tests: `mini test`.
