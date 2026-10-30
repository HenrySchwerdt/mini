---
title: "mini.plot"
notebook: true
---

# mini.plot

`mini.plot` draws charts as **SVG**, with an API that mirrors Python's matplotlib: if you know `plt.plot(x, y)`, `plt.subplots()` and `ax.set_title(...)`, you know this. It is plain Mini (no dependencies, no C), so it runs the same natively and in your browser. `plt.show()` hands the figure to the page as [rich output](/libraries/display): it appears right below the cell that made it. Every cell on this page is runnable and verified in CI.

```kotlin
import mini.plot.*
import kotlin.math.*

val phase = DoubleArray(80) { i -> i * 0.08 }
plt.plot(phase, DoubleArray(80) { i -> sin(phase[i]) }, label = "sin(x)")
plt.plot(phase, DoubleArray(80) { i -> cos(phase[i]) }, label = "cos(x)", linestyle = "--")
plt.title("Sine and cosine", "one wave, two phases")
plt.xlabel("x (radians)")
plt.show()
```
```output
[display: image/svg+xml]
```

Charts follow the page: text, axes and grid are `currentColor`, so they read in the light and in the dark theme (try the switch in the top bar). Hover a point or a bar to see its value (SVG `<title>` tooltips, no scripts needed). The same figure as text: `plt.toSvg()`; to a file: `plt.savefig("out.svg")`.

## From matplotlib to mini.plot

| matplotlib | mini.plot |
|---|---|
| `plt.figure(figsize=(8, 4))` | `plt.figure(figsize = 8.0 to 4.0)` (inches, 100 px each; the SVG scales down to its container) |
| `plt.plot(x, y, "r--o", label="y", linewidth=2)` | `plt.plot(x, y, "r--o", label = "y", linewidth = 2.0)` |
| `plt.fill_between(x, y1, y2)` | `plt.fill_between(x, y1, y2)` |
| `fig, axes = plt.subplots(2, 2)`; `axes[0, 1]` | `val (fig, axes) = plt.subplots(2, 2)`; `axes[0, 1]` |
| `ax.set_title("t")` | `ax.set_title("t")` |
| `plt.style.use("dark_background")` | `plt.style.use("dark")` |

Method names are matplotlib's, snake_case included (`fill_between`, `set_xlabel`, `axhline`): Mini accepts them, and this way code ports line by line. Arguments are named with defaults; Python tuples are `Pair`s (`8.0 to 4.0`). Data can be a `DoubleArray`, `IntArray`, `List<Double>`, `List<Int>` or anything that implements `DoubleData`. A colour is any CSS colour, `"C0"`..`"C9"` (the palette) or one of `b g r c m y k`.

```kotlin
// a few helpers for the examples below: a deterministic generator, so every run draws the same figure
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

## Lines, markers, areas

`plot` takes `fmt` (`"r--o"`), or `color`, `linestyle` (`"-"`, `"--"`, `"-."`, `":"`), `marker` (`o s ^ v D x + .`), `linewidth` and `alpha`. `fill_between` shades the area between two curves; `axhline`/`axvline` draw reference lines; `annotate` points an arrow at a spot.

```kotlin
import mini.plot.*
import kotlin.math.*

val t = DoubleArray(60) { i -> i * 0.105 }
val y = DoubleArray(60) { i -> sin(t[i]) * exp(-t[i] * 0.2) }
val upper = DoubleArray(60) { i -> y[i] + 0.15 }
val lower = DoubleArray(60) { i -> y[i] - 0.15 }

plt.fill_between(t, lower, upper, label = "band", alpha = 0.18)
plt.plot(t, y, label = "damped sine")
plt.plot(t, DoubleArray(60) { i -> exp(-t[i] * 0.2) }, "--", label = "envelope")
plt.axhline(0.0)
plt.annotate("first peak", xy = 1.4 to 0.75, xytext = 2.6 to 0.9)
plt.title("A damped oscillation")
plt.ylabel("amplitude")
plt.show()
```
```output
[display: image/svg+xml]
```

## Scatter

`scatter(x, y, s = 36.0)`: `s` is the marker area, like in matplotlib. Dots have a faint ring so that overlapping ones stay apart, and a tooltip with their coordinates.

```kotlin
import mini.plot.*

val rng = Lcg(42)
val sx = DoubleArray(120) { rng.gauss() }
val sy = DoubleArray(120) { i -> sx[i] * 0.7 + rng.gauss() * 0.5 }
val rx = DoubleArray(60) { rng.gauss() + 2.0 }
val ry = DoubleArray(60) { i -> -rx[i] * 0.4 + rng.gauss() * 0.4 }

plt.scatter(sx, sy, label = "group A")
plt.scatter(rx, ry, label = "group B", marker = "s")
plt.title("Two clouds")
plt.xlabel("x")
plt.ylabel("y")
plt.show()
```
```output
[display: image/svg+xml]
```

## Bars

`bar(categories, heights)` takes a `List<String>` of category names. Several bar series stand side by side; give `bottom` (the running sum) to stack them. `barh` draws horizontal bars, the first category at the top.

```kotlin
import mini.plot.*

val regions = listOf("North", "South", "East", "West")
plt.bar(regions, listOf(12500.0, 19200.0, 7400.0, 15100.0), label = "2024")
plt.bar(regions, listOf(9100.0, 22300.0, 11000.0, 14200.0), label = "2025")
plt.title("Sales by region", "units sold")
plt.show()

val base = listOf(30.0, 45.0, 28.0, 52.0)
val extra = listOf(12.0, 8.0, 20.0, 5.0)
plt.bar(regions, base, label = "core")
plt.bar(regions, extra, label = "new", bottom = base)
plt.title("Stacked")
plt.show()

plt.barh(listOf("Kotlin", "Rust", "Go", "Zig"), listOf(8.5, 7.2, 6.1, 3.9), color = "C4")
plt.title("Horizontal bars")
plt.show()
```
```output
[display: image/svg+xml]
[display: image/svg+xml]
[display: image/svg+xml]
```

## Histograms

`hist(values, bins = 10)` counts the values in equally wide bins; `density = true` normalises the area to 1. Several histograms overlap, semi-transparent.

```kotlin
import mini.plot.*

val noise = Lcg(7)
val a = DoubleArray(1500) { noise.gauss() }
val b = DoubleArray(1500) { noise.gauss() * 0.6 + 1.5 }

plt.hist(a, bins = 28, label = "N(0, 1)")
plt.hist(b, bins = 28, label = "N(1.5, 0.6)")
plt.title("Two distributions")
plt.xlabel("value")
plt.ylabel("count")
plt.show()
```
```output
[display: image/svg+xml]
```

## Heatmaps

`imshow` shows a matrix, row 0 at the top, with a colour bar. Colour maps: `viridis` (default), `magma`, `plasma`, `coolwarm`, `gray`. `annot = true` writes the values into the cells. Pass an `Array<DoubleArray>`, a `List<List<Double>>`, or flat data with `rows` and `cols` (a tensor of shape `[rows, cols]`).

```kotlin
import mini.plot.*
import kotlin.math.*

val corr = Array(6) { r -> DoubleArray(6) { c -> if (r == c) 1.0 else cos((r - c) * 0.9) * 0.8 } }
plt.imshow(corr, cmap = "coolwarm", vmin = -1.0, vmax = 1.0, annot = true)
plt.xticks(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), listOf("a", "b", "c", "d", "e", "f"))
plt.yticks(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), listOf("a", "b", "c", "d", "e", "f"))
plt.title("Correlations")
plt.show()

val field = DoubleArray(20 * 30) { k ->
    val r = (k / 30).toDouble() - 10.0
    val c = (k % 30).toDouble() - 15.0
    sin(c * 0.4) * cos(r * 0.5) + 0.3 * sin((c + r) * 0.3)
}
plt.imshow(field, cmap = "magma", rows = 20, cols = 30)
plt.title("A 20 x 30 field")
plt.show()
```
```output
[display: image/svg+xml]
[display: image/svg+xml]
```

## Error bars, log scales, tick formats

`errorbar(x, y, yerr = ...)` (a number or one error per point). `xscale("log")` / `yscale("log")` put ticks at the powers of ten. Tick labels are written compactly from 1000 on (`1k`, `2.5M`); `xformat`/`yformat` choose `"plain"`, `"si"`, `"percent"` (0.25 is 25%) or `"sci"`.

```kotlin
import mini.plot.*
import kotlin.math.*

val n = DoubleArray(8) { i -> 2.0.pow(i.toDouble() + 3.0) }
val time = DoubleArray(8) { i -> n[i] * n[i] * 0.4 }
plt.errorbar(n, time, yerr = DoubleArray(8) { i -> time[i] * 0.15 }, label = "measured")
plt.plot(n, DoubleArray(8) { i -> n[i] * n[i] * 0.4 }, "--", label = "n squared", color = "C1")
plt.xscale("log")
plt.yscale("log")
plt.xlabel("input size")
plt.ylabel("time (ms)")
plt.title("Quadratic growth")
plt.show()

val share = doubleArrayOf(0.12, 0.31, 0.27, 0.45, 0.62, 0.71)
plt.plot(doubleArrayOf(2020.0, 2021.0, 2022.0, 2023.0, 2024.0, 2025.0), share, marker = "o")
plt.yformat("percent")
plt.xformat("plain")
plt.title("Adoption")
plt.show()
```
```output
[display: image/svg+xml]
[display: image/svg+xml]
```

## Subplots and the object-oriented API

`plt.subplots(nrows, ncols, figsize)` returns the figure and its axes: `axes[i]` counts row by row, `axes[row, col]` addresses a cell. Every `plt.` function exists as a method of `Axes`, with the matplotlib names `set_title`, `set_xlabel`, `set_ylabel`, `set_xlim`, `set_ylim`, `set_xscale`, `set_xticks`, `legend`, `grid`. Margins are computed from the labels, so `tight_layout()` is a no-op that ports code unchanged.

```kotlin
import mini.plot.*
import kotlin.math.*

val (fig, axes) = plt.subplots(1, 3, figsize = 9.0 to 3.2)
val u = DoubleArray(50) { i -> i * 0.13 }
axes[0].plot(u, DoubleArray(50) { i -> sin(u[i]) })
axes[0].set_title("line")
axes[1].bar(listOf("a", "b", "c"), listOf(3.0, 7.0, 5.0))
axes[1].set_title("bars")
axes[2].scatter(u, DoubleArray(50) { i -> cos(u[i] * 2.0) + 0.2 * sin(i * 7.0) })
axes[2].set_title("scatter")
fig.suptitle = "Three panels"
fig.show()
```
```output
[display: image/svg+xml]
```

## Styles

`plt.style.use(name)` applies to the figures that follow: `"default"` (soft grid, no frame), `"minimal"` (nothing but data and labels), `"seaborn"` (grey panel with white grid) and `"dark"` (a dark card that looks the same on any page). Only `"dark"` paints a background; the others take the colours of the page.

```kotlin
import mini.plot.*
import kotlin.math.*

val v = DoubleArray(60) { i -> i * 0.1 }
for (style in listOf("seaborn", "minimal", "dark")) {
    plt.style.use(style)
    plt.plot(v, DoubleArray(60) { i -> sin(v[i]) }, label = "sin")
    plt.plot(v, DoubleArray(60) { i -> sin(v[i] * 1.5) * 0.7 }, label = "faster")
    plt.title("Style: " + style)
    plt.show()
}
plt.style.use("default")
```
```output
[display: image/svg+xml]
[display: image/svg+xml]
[display: image/svg+xml]
```

## Legends and layout

The legend sits in a row above the plot when it fits (`legend("top")`, the default `"auto"`), else inside where it covers the least data. Choose with `legend("best")`, `"upper left"`, `"lower right"`, `"right"` (outside) or `"none"`. Titles are left-aligned with an optional subtitle, axis labels sit above the axes, and the margins grow with the width of the tick labels. Long category names tilt by themselves.

## The `plot { }` DSL

The earlier block style keeps working, and has the whole matplotlib API as well:

```kotlin
import mini.plot.*

val xs = DoubleArray(41) { i -> i * 0.25 }
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

`plot { }` builds an `Axes` (also called `Plot`): `line`, `scatter`, `bar`, `hist`, `heatmap`, `fill_between`, ... plus the properties `title`, `subtitle`, `xlabel`, `ylabel`, `width`, `height`, `grid`, `legend` and `style`. `.show()`, `.toSvg()` and `.save(path)` output it.

## Gallery

A small dashboard: four chart types in one figure, in the dark style (a card that looks the same on any page).

```kotlin
import mini.plot.*
import kotlin.math.*

plt.style.use("dark")
val (board, ax) = plt.subplots(2, 2, figsize = 9.0 to 6.4)
val days = DoubleArray(30) { i -> i.toDouble() }
val visits = DoubleArray(30) { i -> 1200.0 + i * 35.0 + 260.0 * sin(i * 0.9) }
val signups = DoubleArray(30) { i -> 90.0 + i * 2.5 + 22.0 * cos(i * 0.7) }
ax[0, 0].fill_between(days, visits, 0.0, alpha = 0.15)
ax[0, 0].plot(days, visits)
ax[0, 0].set_title("Visits", "last 30 days")
ax[0, 1].bar(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), listOf(420.0, 380.0, 510.0, 470.0, 640.0))
ax[0, 1].set_title("Orders per weekday")
val gen = Lcg(11)
val spend = DoubleArray(90) { 20.0 + 60.0 * gen.next() }
val revenue = DoubleArray(90) { i -> spend[i] * 2.2 + gen.gauss() * 18.0 }
ax[1, 0].scatter(spend, revenue, alpha = 0.7)
ax[1, 0].set_title("Revenue vs. spend")
ax[1, 0].set_xlabel("spend")
ax[1, 1].hist(DoubleArray(600) { 40.0 + 12.0 * gen.gauss() }, bins = 18, color = "C2")
ax[1, 1].set_title("Order value")
ax[1, 1].set_yformat("plain")
board.suptitle = "Shop overview"
board.show()
plt.style.use("default")
```
```output
[display: image/svg+xml]
```

### A training curve

mini.tensor and mini.plot work together (both are preinstalled in the browser): train the XOR network of the tensor examples and plot its loss on a log scale.

```kotlin
import mini.plot.*
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.Adam

manualSeed(7)
val inputs = tensor(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0))
val targets = tensor(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(0.0))
val model = Sequential(Linear(2, 8), Tanh(), Linear(8, 1), Sigmoid())
val lossFn = MSELoss()
val opt = Adam(model.parameters(), lr = 0.05)

val losses = DoubleArray(300)
for (epoch in 0 until 300) {
    opt.zeroGrad()
    val loss = lossFn(model(inputs), targets)
    loss.backward()
    opt.step()
    losses[epoch] = loss.item()
}

plt.plot(losses, label = "MSE loss")
plt.yscale("log")
plt.title("XOR training", "a 2-8-1 network, Adam, lr 0.05")
plt.xlabel("epoch")
plt.show()
```
```output
[display: image/svg+xml]
```

## Reference

| `plt` / `Axes` | Meaning |
|---|---|
| `plot(x, y, fmt, label, color, linestyle, marker, linewidth, alpha)` | Line; `plot(y)` plots against the index. NaN breaks the line. |
| `scatter(x, y, label, color, s, alpha, marker)` | Dots. |
| `bar(x, height, label, color, width, bottom)`, `barh(...)` | Bars; `x` may be a `List<String>`. |
| `hist(values, bins, label, color, alpha, density)` | Histogram. |
| `imshow(data, cmap, vmin, vmax, annot, rows, cols)` | Heatmap with colour bar. |
| `fill_between(x, y1, y2, label, color, alpha)` | Shaded area. |
| `errorbar(x, y, yerr, label, color, marker, capsize)` | Error bars. |
| `axhline(y)`, `axvline(x)`, `text(x, y, s)`, `annotate(text, xy, xytext)` | Reference lines and notes. |
| `title(t, subtitle)`, `xlabel`, `ylabel`, `legend(loc)`, `grid(visible, axis)` | Decoration. |
| `xlim`, `ylim`, `xscale`, `yscale`, `xticks(positions, labels)`, `yticks`, `xformat`, `yformat` | Axes. |
| `figure(figsize)`, `subplots(nrows, ncols, figsize)`, `subplot(r, c, i)`, `suptitle`, `gca()`, `gcf()` | Figures and axes. |
| `show()`, `savefig(path)`, `toSvg()`, `clf()`, `style.use(name)` | Output and state. |

Empty or non-finite data draws empty axes with "no data". `Figure` and `Axes` (from `plt.subplots`) have `show()`, `toSvg()` and `savefig(path)`.

## Tensors and other data

Anything that implements `DoubleData` (one function, `toDoubleArray()`) can be passed wherever an array is accepted, so a tensor library only has to implement it. A matrix is plotted with `imshow(data, rows = r, cols = c)`:

```kotlin norun
// with mini.tensor (see libs/plot/README.md)
plt.imshow(t.toDoubleArray(), rows = t.shape[0], cols = t.shape[1])
plt.show()
```

## Use it in a project

In the browser the library is preinstalled: `import mini.plot.*` just works in every cell, in the [playground](/playground) and in the [notebook](/notebook). In a project of your own, add it as a path or git dependency:

```toml
[dependencies]
mini-plot = { path = "../mini/libs/plot" }
```

Natively, `show()` prints the display bundle to stdout; a terminal ignores it. Use `savefig("plot.svg")` to get a file you can open.
