---
title: "mini.plot"
notebook: true
---

# mini.plot

`mini.plot` draws line plots, scatter plots, bar charts, histograms and heatmaps as **SVG**. It is plain Mini (no dependencies, no C), so it runs the same natively and in your browser. `show()` hands the figure to the page as [rich output](/libraries/display): it appears right below the cell that made it. Every cell on this page is runnable and verified in CI.

```kotlin
import mini.plot.*

val xs = DoubleArray(41) { i -> i.toDouble() * 0.25 }
val ys = DoubleArray(41) { i -> xs[i] * xs[i] }

plot {
    line(xs, ys, label = "x squared")
    title = "Squares"
    xlabel = "x"
    ylabel = "x * x"
}.show()
```
```output
[display: image/svg+xml]
```

`plot { ... }` builds a figure, `show()` displays it. The figure follows the colours of the page: text, axes and grid use `currentColor`, so it reads in the light and in the dark theme (try the switch in the top bar). The same figure as text: `.toSvg()`, and to a file: `.save("out.svg")`.

## Lines

The standard library has no `sin` or `cos` yet, so the examples define the two functions they need (a Taylor series). A cell that only declares things prints nothing.

```kotlin
fun sine(x0: Double): Double {
    val twoPi = 6.283185307179586
    var x = x0 - twoPi * (x0 / twoPi).toInt().toDouble()
    if (x > 3.141592653589793) x -= twoPi
    if (x < -3.141592653589793) x += twoPi
    var term = x
    var sum = x
    for (k in 1 until 12) {
        term = -term * x * x / ((2 * k) * (2 * k + 1)).toDouble()
        sum += term
    }
    return sum
}

fun cosine(x: Double): Double = sine(x + 1.5707963267948966)

// a small deterministic generator, so the examples draw the same figure on every run
class Lcg(var state: Long) {
    fun next(): Double {
        state = (state * 1103515245L + 12345L) % 2147483648L
        return state.toDouble() / 2147483648.0
    }
    fun gauss(): Double {
        var sum = 0.0
        for (i in 0 until 12) sum += next()
        return sum - 6.0
    }
}
```

Several series share one set of axes; each `label` appears in the legend.

```kotlin
import mini.plot.*

val x = DoubleArray(120) { i -> i.toDouble() * 0.0525 }

plot {
    line(x, DoubleArray(120) { i -> sine(x[i]) }, label = "sin")
    line(x, DoubleArray(120) { i -> cosine(x[i]) }, label = "cos")
    title = "Sine and cosine"
    xlabel = "x"
    ylabel = "y"
}.show()
```
```output
[display: image/svg+xml]
```

## Scatter

```kotlin
import mini.plot.*

val rng = Lcg(42)
val sx = DoubleArray(150) { rng.gauss() }
val sy = DoubleArray(150) { i -> sx[i] * 0.7 + rng.gauss() * 0.5 }

plot {
    scatter(sx, sy, label = "samples")
    title = "A correlated cloud"
    xlabel = "x"
    ylabel = "y"
}.show()
```
```output
[display: image/svg+xml]
```

## Bars

`bar` takes one value per category; the optional `labels` name them. Two bar series are drawn side by side.

```kotlin
import mini.plot.*

plot {
    bar(intArrayOf(12, 19, 7, 15), labels = listOf("north", "south", "east", "west"), label = "2024")
    bar(intArrayOf(9, 22, 11, 14), label = "2025")
    title = "Sales by region"
    ylabel = "units"
}.show()
```
```output
[display: image/svg+xml]
```

## Histograms

`hist(values, bins = 10)` counts the values in equally wide bins.

```kotlin
import mini.plot.*

val noise = Lcg(7)
val samples = DoubleArray(2000) { noise.gauss() }

plot {
    hist(samples, bins = 24, label = "N(0, 1)")
    title = "2000 samples"
    xlabel = "value"
    ylabel = "count"
}.show()
```
```output
[display: image/svg+xml]
```

## Heatmaps

A heatmap shows a matrix, row 0 at the top, with a colour bar. Pass an `Array<DoubleArray>`, a `List<List<Double>>`, or one flat array with the number of rows and columns.

```kotlin
import mini.plot.*

val rows = 16
val cols = 24
val field = DoubleArray(rows * cols) { k ->
    val r = (k / cols).toDouble() - 8.0
    val c = (k % cols).toDouble() - 12.0
    sine(c * 0.45) * cosine(r * 0.55) + 0.3 * sine((c + r) * 0.3)
}

plot {
    heatmap(field, rows, cols)
    title = "A 16 x 24 matrix"
}.show()
```
```output
[display: image/svg+xml]
```

## Options

| Property or function | Meaning |
|---|---|
| `title`, `xlabel`, `ylabel` | Texts (default: none). |
| `width`, `height` | Size of the SVG in pixels (default 640 x 400). The figure scales down to fit its container. |
| `grid`, `legend` | Grid lines on/off (default on). The legend shows when a series has a `label`; `legend = false` hides it. |
| `xlim(lo, hi)`, `ylim(lo, hi)` | Fix the visible range (default: the data, rounded to nice tick values). |
| `line`, `scatter`, `bar`, `hist`, `heatmap` | The series. Every one has a `label` and a `color` (any SVG colour; default: a palette that works on light and dark backgrounds). |

Data can be a `DoubleArray`, a `List<Double>` or an `IntArray`. `line(ys)` alone plots against the index. NaN and infinite values are skipped (a NaN breaks a line). Empty data draws empty axes with "no data".

## Tensors and other data

Anything that implements `DoubleData` (one function, `toDoubleArray()`) can be passed wherever an array is accepted, so a tensor library only has to implement it. A matrix is plotted with `heatmap(data, rows, cols)`, the flat row-major data of a `[rows, cols]` tensor:

```kotlin norun
// with mini.tensor (see libs/plot/README.md)
plot { heatmap(t.toDoubleArray(), t.shape[0], t.shape[1]) }.show()
```

## Use it in a project

In the browser the library is preinstalled: `import mini.plot.*` just works in every cell, in the [playground](/playground) and in the [notebook](/notebook). In a project of your own, add it as a path or git dependency:

```toml
[dependencies]
mini-plot = { path = "../mini/libs/plot" }
```

Natively, `show()` prints the display bundle to stdout; a terminal ignores it. Use `save("plot.svg")` to get a file you can open.
