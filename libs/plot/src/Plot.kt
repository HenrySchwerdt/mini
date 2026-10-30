package mini.plot

import mini.display.displaySvg
import mini.io.writeTextFile

/**
 * Anything that can hand over its numbers as a flat `DoubleArray`, e.g. a tensor, a column of a data frame or a signal.
 * Every plot function that takes data accepts them next to `DoubleArray`, `IntArray`, `LongArray` and `List<Double>` / `List<Int>`.
 */
public interface DoubleData {
    public fun toDoubleArray(): DoubleArray
}

internal const val KIND_LINE: Int = 0
internal const val KIND_SCATTER: Int = 1
internal const val KIND_BAR: Int = 2
internal const val KIND_HIST: Int = 3
internal const val KIND_FILL: Int = 4
internal const val KIND_ERR: Int = 5
internal const val KIND_BARH: Int = 6
internal const val KIND_TEXT: Int = 7
internal const val KIND_ANNOT: Int = 8
internal const val KIND_HLINE: Int = 9
internal const val KIND_VLINE: Int = 10

/**
 * One artist. Bars: `xs` are the positions (0, 1, ...), `ys` the heights, `ys2` the bottoms. A histogram: `xs` the `n + 1` bin edges.
 * Fills: `ys2` is the second curve. Error bars: `ys2` the error. Texts: `xs[0]`, `ys[0]` is the position, `x2`/`y2` the target of an arrow.
 */
internal class Item(val kind: Int, val xs: DoubleArray, val ys: DoubleArray, val auto: Int) {
    var ys2: DoubleArray? = null
    var label: String = ""
    var color: String = ""
    var linestyle: String = "-"
    var marker: String = ""
    var lw: Double = 2.0
    var alpha: Double = Double.NaN
    var size: Double = 0.0
    var text: String = ""
    var x2: Double = Double.NaN
    var y2: Double = Double.NaN
    var anchor: String = "start"
}

/** A heatmap: [values] row by row (`rows * cols` numbers). */
internal class Heat(val rows: Int, val cols: Int, val values: DoubleArray, val cmap: String, val vmin: Double, val vmax: Double, val annot: Boolean)

internal fun num1(x: Any?): Double {
    if (x is Double) return x
    if (x is Int) return x.toDouble()
    if (x is Long) return x.toDouble()
    return Double.NaN
}

/** The numbers of [v] (a `DoubleArray`, `IntArray`, `LongArray`, list of numbers, [DoubleData] or a single number); empty for anything else. */
internal fun toData(v: Any?): DoubleArray {
    if (v is DoubleArray) return v
    if (v is IntArray) return DoubleArray(v.size) { i -> v[i].toDouble() }
    if (v is LongArray) return DoubleArray(v.size) { i -> v[i].toDouble() }
    if (v is DoubleData) return v.toDoubleArray()
    if (v is List<*>) return DoubleArray(v.size) { i -> num1(v[i]) }
    val one = num1(v)
    if (one == one) return doubleArrayOf(one)
    return DoubleArray(0)
}

/** [v] as [n] numbers: a single number is repeated. */
internal fun dataOrScalar(v: Any?, n: Int): DoubleArray {
    val d = toData(v)
    if (d.size == 1 && n != 1) return DoubleArray(n) { i -> d[0] }
    return d
}

internal fun indexRange(n: Int): DoubleArray = DoubleArray(n) { i -> i.toDouble() }

/** A matplotlib format string like `"r--"`, `"o"`, `"g^:"` split into color, marker and linestyle. */
internal class Fmt(val color: String, val marker: String, val linestyle: String)

internal fun parseFmt(f: String): Fmt {
    var color = ""
    var marker = ""
    var ls = ""
    var i = 0
    while (i < f.length) {
        val c = f[i]
        if (c == '-' && i + 1 < f.length && f[i + 1] == '-') {
            ls = "--"
            i += 2
            continue
        }
        if (c == '-' && i + 1 < f.length && f[i + 1] == '.') {
            ls = "-."
            i += 2
            continue
        }
        if (c == '-') ls = "-"
        else if (c == ':') ls = ":"
        else if (c == 'b' || c == 'g' || c == 'r' || c == 'c' || c == 'm' || c == 'y' || c == 'k' || c == 'w') color = c.toString()
        else if (c == 'o' || c == 's' || c == '^' || c == 'v' || c == 'D' || c == 'x' || c == '+' || c == '.' || c == '*') marker = c.toString()
        i++
    }
    return Fmt(color, marker, ls)
}

/**
 * Builds a figure with one set of axes: `plot { line(xs, ys, label = "y"); title = "Demo" }.show()`. The block has the receiver
 * [Axes] (also called [Plot]): everything of the matplotlib-like API (`plot`, `scatter`, `bar`, `set_title`, ...) works in it.
 */
public fun plot(block: Axes.() -> Unit): Axes {
    val p = Axes()
    p.block()
    return p
}

/** The type of what [plot] builds. */
public typealias Plot = Axes

/**
 * A set of axes: the data, titles and options of one chart. The same class serves the DSL (`plot { ... }`), `plt` (pyplot, the
 * current axes) and the object-oriented API (`val (fig, ax) = plt.subplots()`; `ax.plot(...)`, `ax.set_title(...)`).
 * Method names follow matplotlib (`fill_between`, `set_xlabel`, `axhline`); `Axes` standing alone is a figure of one chart:
 * [toSvg], [show] and [save] render it.
 */
public class Axes {
    public var title: String = ""
    public var subtitle: String = ""
    public var xlabel: String = ""
    public var ylabel: String = ""

    /** Size of a figure that consists of this chart alone (in px; the SVG scales to the width of the page). */
    public var width: Int = 520
    public var height: Int = 330

    /** Show the grid lines (see [grid] for the options). */
    public var grid: Boolean = true

    /** Show a legend when at least one artist has a label (see [legend] for the position). */
    public var legend: Boolean = true

    /** Style of a figure that consists of this chart alone: `"default"`, `"dark"`, `"minimal"` or `"seaborn"`. */
    public var style: String = currentStyle

    internal var legendLoc: String = "auto"
    internal var gridAxis: String = ""
    internal val items = ArrayList<Item>()
    internal var heat: Heat? = null
    internal var barLabels: List<String> = emptyList()
    internal var barhLabels: List<String> = emptyList()
    internal var xLo: Double = Double.NaN
    internal var xHi: Double = Double.NaN
    internal var yLo: Double = Double.NaN
    internal var yHi: Double = Double.NaN
    internal var xLog: Boolean = false
    internal var yLog: Boolean = false
    internal var xTicks: DoubleArray? = null
    internal var xTickLabels: List<String> = emptyList()
    internal var yTicks: DoubleArray? = null
    internal var yTickLabels: List<String> = emptyList()
    internal var xFormat: String = "auto"
    internal var yFormat: String = "auto"
    internal var colorCount: Int = 0
    internal var figure: Figure? = null

    // ---- options ----

    /** Fixes the visible x range. */
    public fun xlim(lo: Double, hi: Double) {
        xLo = lo
        xHi = hi
    }

    /** Fixes the visible y range. */
    public fun ylim(lo: Double, hi: Double) {
        yLo = lo
        yHi = hi
    }

    public fun set_xlim(lo: Double, hi: Double) {
        xlim(lo, hi)
    }

    public fun set_ylim(lo: Double, hi: Double) {
        ylim(lo, hi)
    }

    /** Title and an optional lighter [subtitle] below it. */
    public fun set_title(title: String, subtitle: String = "") {
        this.title = title
        if (subtitle.isNotEmpty()) this.subtitle = subtitle
    }

    public fun set_xlabel(label: String) {
        xlabel = label
    }

    public fun set_ylabel(label: String) {
        ylabel = label
    }

    /** `"linear"` or `"log"`. */
    public fun set_xscale(scale: String) {
        xLog = scale == "log"
    }

    public fun set_yscale(scale: String) {
        yLog = scale == "log"
    }

    /** Tick positions (and their [labels]) of the x axis. */
    public fun set_xticks(ticks: Any, labels: List<String> = emptyList()) {
        xTicks = toData(ticks)
        xTickLabels = labels
    }

    public fun set_yticks(ticks: Any, labels: List<String> = emptyList()) {
        yTicks = toData(ticks)
        yTickLabels = labels
    }

    /** How tick labels are written: `"auto"` (1k, 2.5M from 1000 on), `"plain"`, `"si"`, `"percent"` (0.25 is 25%) or `"sci"`. */
    public fun set_xformat(format: String) {
        xFormat = format
    }

    public fun set_yformat(format: String) {
        yFormat = format
    }

    /**
     * Where the legend goes: `"auto"` (a row above the plot, or inside where no data is when it does not fit), `"top"` (the row), `"best"`
     * (inside, where it covers the least data), `"upper right"`, `"upper left"`, `"lower right"`, `"lower left"`, `"right"` (outside,
     * next to the plot) or `"none"`.
     */
    public fun legend(loc: String = "auto") {
        legend = loc != "none"
        legendLoc = loc
    }

    /** Grid lines: [axis] is `"both"`, `"x"`, `"y"` or `"auto"` (the style decides). `grid(false)` hides them. */
    public fun grid(visible: Boolean = true, axis: String = "auto") {
        grid = visible
        gridAxis = if (axis == "auto") "" else axis
    }

    private fun nextColor(): Int {
        val k = colorCount
        colorCount = k + 1
        return k
    }

    private fun add(kind: Int, xs: DoubleArray, ys: DoubleArray, label: String, color: String): Item {
        val it = Item(kind, xs, ys, if (color.isEmpty()) nextColor() else colorCount)
        it.label = label
        it.color = color
        items.add(it)
        return it
    }

    // ---- line ----

    /**
     * A line through the points ([x][i], [y][i]); with one argument the values are plotted against 0, 1, 2, ... NaN breaks the line.
     * [fmt] is a matplotlib format string (`"r--"`, `"o-"`, `"g^"`); [linestyle] one of `"-"`, `"--"`, `"-."`, `":"`, `""` (none);
     * [marker] one of `o s ^ v D x + .`. Colors: a CSS color, `"C0"`..`"C9"` (palette) or `b g r c m y k`.
     */
    public fun plot(x: Any, y: Any? = null, fmt: String = "", label: String = "", color: String = "", linestyle: String = "", marker: String = "", linewidth: Double = 2.0, alpha: Double = Double.NaN) {
        val f = parseFmt(fmt)
        val xs: DoubleArray
        val ys: DoubleArray
        if (y == null) {
            ys = toData(x)
            xs = indexRange(ys.size)
        } else {
            xs = toData(x)
            ys = toData(y)
        }
        val c = if (color.isNotEmpty()) color else f.color
        val it = add(KIND_LINE, xs, ys, label, c)
        it.marker = if (marker.isNotEmpty()) marker else f.marker
        it.linestyle = if (linestyle.isNotEmpty()) linestyle else if (f.linestyle.isNotEmpty()) f.linestyle else if (f.marker.isNotEmpty() && fmt.indexOf('-') < 0 && fmt.indexOf(':') < 0) "" else "-"
        it.lw = linewidth
        it.alpha = alpha
    }

    /** DSL name of [plot]: `line(xs, ys, label = "y")`, `line(ys)`. */
    public fun line(x: Any, y: Any? = null, label: String = "", color: String = "", linestyle: String = "-", marker: String = "", linewidth: Double = 2.0) {
        plot(x, y, label = label, color = color, linestyle = linestyle, marker = marker, linewidth = linewidth)
    }

    // ---- scatter ----

    /** Dots at the points ([x][i], [y][i]); [s] is the marker area like in matplotlib (36 = 6 px wide). */
    public fun scatter(x: Any, y: Any, label: String = "", color: String = "", s: Double = 36.0, alpha: Double = Double.NaN, marker: String = "o") {
        val it = add(KIND_SCATTER, toData(x), toData(y), label, color)
        it.size = s
        it.alpha = alpha
        it.marker = marker
    }

    // ---- bar ----

    /**
     * Vertical bars. `bar(values)` puts them at 0, 1, 2, ...; `bar(categories, heights)` takes a `List<String>` of category names
     * (`bar(values, labels = names)` too). Several bar series stand side by side; with [bottom] (the sums of the series below)
     * they are stacked. [width] is the width of a group of bars (0..1).
     */
    public fun bar(x: Any, height: Any? = null, label: String = "", color: String = "", width: Double = 0.8, bottom: Any? = null, alpha: Double = Double.NaN, labels: List<String> = emptyList()) {
        var cats = labels
        var xs: DoubleArray
        var hs: DoubleArray
        if (height == null) {
            hs = toData(x)
            xs = indexRange(hs.size)
        } else if (height is List<*> && height.size > 0 && height[0] is String) {
            // bar(values, listOf("a", "b")): the old order
            hs = toData(x)
            xs = indexRange(hs.size)
            cats = ArrayList<String>()
            for (e in height) cats.add(e.toString())
        } else if (x is List<*> && x.size > 0 && x[0] is String) {
            cats = ArrayList<String>()
            for (e in x) cats.add(e.toString())
            hs = toData(height)
            xs = indexRange(hs.size)
        } else {
            xs = toData(x)
            hs = toData(height)
        }
        val it = add(KIND_BAR, xs, hs, label, color)
        it.size = width
        it.alpha = alpha
        if (bottom != null) it.ys2 = dataOrScalar(bottom, hs.size)
        if (cats.isNotEmpty()) barLabels = cats
    }

    /** Horizontal bars: `barh(categories, widths)`; the first category is at the top. */
    public fun barh(y: Any, width: Any? = null, label: String = "", color: String = "", height: Double = 0.8, alpha: Double = Double.NaN, labels: List<String> = emptyList()) {
        var cats = labels
        val xs: DoubleArray
        val ws: DoubleArray
        if (width == null) {
            ws = toData(y)
            xs = indexRange(ws.size)
        } else if (y is List<*> && y.size > 0 && y[0] is String) {
            cats = ArrayList<String>()
            for (e in y) cats.add(e.toString())
            ws = toData(width)
            xs = indexRange(ws.size)
        } else {
            xs = toData(y)
            ws = toData(width)
        }
        val it = add(KIND_BARH, xs, ws, label, color)
        it.size = height
        it.alpha = alpha
        if (cats.isNotEmpty()) barhLabels = cats
    }

    // ---- histogram ----

    /**
     * A histogram of [values] with [bins] equally wide bins between the smallest and the largest finite value;
     * [density] makes the area 1. Several histograms overlap (semi-transparent).
     */
    public fun hist(values: Any, bins: Int = 10, label: String = "", color: String = "", alpha: Double = Double.NaN, density: Boolean = false) {
        val data = toData(values)
        val n = if (bins < 1) 1 else bins
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var count = 0
        for (v in data) {
            if (isFin(v)) {
                if (v < lo) lo = v
                if (v > hi) hi = v
                count++
            }
        }
        if (lo > hi) {
            lo = 0.0
            hi = 1.0
        }
        if (lo == hi) {
            lo -= 0.5
            hi += 0.5
        }
        val edges = DoubleArray(n + 1) { i -> lo + (hi - lo) * i.toDouble() / n.toDouble() }
        val counts = DoubleArray(n)
        for (v in data) {
            if (isFin(v)) {
                var k = ((v - lo) / (hi - lo) * n.toDouble()).toInt()
                if (k >= n) k = n - 1
                if (k < 0) k = 0
                counts[k] = counts[k] + 1.0
            }
        }
        if (density && count > 0) {
            val w = (hi - lo) / n.toDouble()
            for (i in 0 until n) counts[i] = counts[i] / (count.toDouble() * w)
        }
        val it = add(KIND_HIST, edges, counts, label, color)
        it.alpha = alpha
    }

    // ---- areas, error bars, lines, text ----

    /** The area between the curves [y1] and [y2] (a number or data; default 0), semi-transparent. */
    public fun fill_between(x: Any, y1: Any, y2: Any? = null, label: String = "", color: String = "", alpha: Double = 0.22) {
        val xs = toData(x)
        val a = dataOrScalar(y1, xs.size)
        // without a colour the area takes the colour of the line drawn last (or of the next one), and does not use up a palette colour
        val it = Item(KIND_FILL, xs, a, if (colorCount > 0) colorCount - 1 else 0)
        it.label = label
        it.color = color
        items.add(it)
        it.ys2 = if (y2 == null) DoubleArray(xs.size) { i -> 0.0 } else dataOrScalar(y2, xs.size)
        it.alpha = alpha
    }

    /** Points with vertical error bars [yerr] (a number or data): `errorbar(x, y, yerr = 0.2)`. */
    public fun errorbar(x: Any, y: Any, yerr: Any? = null, label: String = "", color: String = "", marker: String = "o", linestyle: String = "", capsize: Double = 3.0, linewidth: Double = 1.6) {
        val xs = toData(x)
        val ys = toData(y)
        val it = add(KIND_ERR, xs, ys, label, color)
        it.ys2 = if (yerr == null) DoubleArray(ys.size) { i -> 0.0 } else dataOrScalar(yerr, ys.size)
        it.marker = marker
        it.linestyle = linestyle
        it.size = capsize
        it.lw = linewidth
    }

    /** A horizontal reference line at [y] across the plot (dashed by default). */
    public fun axhline(y: Double = 0.0, label: String = "", color: String = "", linestyle: String = "--", linewidth: Double = 1.4) {
        val it = Item(KIND_HLINE, doubleArrayOf(0.0), doubleArrayOf(y), -1)
        it.label = label
        it.color = color
        it.linestyle = linestyle
        it.lw = linewidth
        items.add(it)
    }

    /** A vertical reference line at [x]. */
    public fun axvline(x: Double = 0.0, label: String = "", color: String = "", linestyle: String = "--", linewidth: Double = 1.4) {
        val it = Item(KIND_VLINE, doubleArrayOf(x), doubleArrayOf(0.0), -1)
        it.label = label
        it.color = color
        it.linestyle = linestyle
        it.lw = linewidth
        items.add(it)
    }

    /** Text at the data position ([x], [y]); [ha] is `"left"`, `"center"` or `"right"`. */
    public fun text(x: Double, y: Double, s: String, fontsize: Double = 12.0, color: String = "", ha: String = "left") {
        val it = Item(KIND_TEXT, doubleArrayOf(x), doubleArrayOf(y), -1)
        it.text = s
        it.color = color
        it.size = fontsize
        it.anchor = if (ha == "center") "middle" else if (ha == "right") "end" else "start"
        items.add(it)
    }

    /** A note [text] with an arrow to the data point [xy]; the text sits at [xytext] (default: at the point). */
    public fun annotate(text: String, xy: Pair<Double, Double>, xytext: Pair<Double, Double>? = null, fontsize: Double = 12.0, color: String = "") {
        val tx = if (xytext == null) xy.first else xytext.first
        val ty = if (xytext == null) xy.second else xytext.second
        val it = Item(KIND_ANNOT, doubleArrayOf(tx), doubleArrayOf(ty), -1)
        it.text = text
        it.color = color
        it.size = fontsize
        it.x2 = xy.first
        it.y2 = xy.second
        it.anchor = if (xytext == null) "middle" else if (tx > xy.first) "start" else if (tx < xy.first) "end" else "middle"
        it.marker = if (xytext == null) "" else "arrow"
        items.add(it)
    }

    // ---- images ----

    /**
     * A heatmap of a matrix: an `Array<DoubleArray>`, a `List<List<Double>>`, or flat data with [rows] x [cols] (row after row;
     * a tensor of shape [rows, cols] fits). [cmap]: `"viridis"`, `"magma"`, `"plasma"`, `"coolwarm"`, `"gray"`. [annot] writes the values into the cells.
     */
    public fun imshow(data: Any, cmap: String = "viridis", vmin: Double = Double.NaN, vmax: Double = Double.NaN, annot: Boolean = false, rows: Int = 0, cols: Int = 0) {
        if (data is Array<*>) {
            val m = ArrayList<DoubleArray>()
            for (r in data) m.add(toData(r))
            setHeat(m, cmap, vmin, vmax, annot)
        } else if (data is List<*> && data.size > 0 && (data[0] is List<*> || data[0] is DoubleArray)) {
            val m = ArrayList<DoubleArray>()
            for (r in data) m.add(toData(r))
            setHeat(m, cmap, vmin, vmax, annot)
        } else {
            val flat = toData(data)
            val c = if (cols > 0) cols else if (rows > 0) (flat.size + rows - 1) / rows else flat.size
            val r = if (rows > 0) rows else if (c > 0) (flat.size + c - 1) / c else 0
            heat = Heat(r, c, flat, cmap, vmin, vmax, annot)
        }
    }

    private fun setHeat(m: List<DoubleArray>, cmap: String, vmin: Double, vmax: Double, annot: Boolean) {
        val rows = m.size
        var cols = 0
        for (r in m) if (r.size > cols) cols = r.size
        val flat = DoubleArray(rows * cols) { i -> Double.NaN }
        for (r in 0 until rows) {
            for (c in 0 until m[r].size) flat[r * cols + c] = m[r][c]
        }
        heat = Heat(rows, cols, flat, cmap, vmin, vmax, annot)
    }

    /** A heatmap of a matrix given as one array, row after row. */
    public fun heatmap(data: DoubleArray, rows: Int, cols: Int, cmap: String = "viridis", annot: Boolean = false) {
        imshow(data, cmap = cmap, annot = annot, rows = rows, cols = cols)
    }

    public fun heatmap(data: DoubleData, rows: Int, cols: Int, cmap: String = "viridis", annot: Boolean = false) {
        imshow(data.toDoubleArray(), cmap = cmap, annot = annot, rows = rows, cols = cols)
    }

    /** A heatmap of a matrix (an array of rows). */
    public fun heatmap(matrix: Array<DoubleArray>, cmap: String = "viridis", annot: Boolean = false) {
        imshow(matrix, cmap = cmap, annot = annot)
    }

    /** A heatmap of a list of rows. */
    public fun heatmap(matrix: List<List<Double>>, cmap: String = "viridis", annot: Boolean = false) {
        imshow(matrix, cmap = cmap, annot = annot)
    }

    // ---- output ----

    private fun owner(): Figure {
        val f = figure
        if (f != null) return f
        val nf = Figure(width, height)
        nf.style = style
        nf.slots.add(this)
        nf.single = true
        return nf
    }

    /** The figure as an SVG document. */
    public fun toSvg(): String = owner().toSvg()

    /** Sends the figure to the host as rich output (see `mini.display`): it appears below the cell in the notebook and the playground. */
    public fun show() {
        displaySvg(toSvg())
    }

    /** Writes the SVG to the file [path]. */
    public fun save(path: String) {
        writeTextFile(path, toSvg())
    }
}

internal var currentStyle: String = "default"

/**
 * A figure: a grid of [Axes] and an optional overall title. `plt.figure()` and `plt.subplots()` make them.
 * [show] sends it to the notebook, [toSvg] gives the text, [savefig] writes a file.
 */
public class Figure(public var width: Int = 520, public var height: Int = 330) {
    internal var slots: ArrayList<Axes?> = ArrayList<Axes?>()
    internal var rows: Int = 1
    internal var cols: Int = 1
    internal var single: Boolean = false

    /** An overall title above all axes. */
    public var suptitle: String = ""

    /** `"default"`, `"dark"`, `"minimal"` or `"seaborn"`. */
    public var style: String = currentStyle

    /** Margins are computed from the labels' sizes, so this is a no-op kept for matplotlib code. */
    public fun tight_layout() {}

    internal fun layout(r: Int, c: Int) {
        rows = r
        cols = c
        slots = ArrayList<Axes?>()
        for (i in 0 until r * c) slots.add(null)
    }

    /** The axes at row [row], column [col] (0 based) of the grid. */
    public fun axesAt(row: Int, col: Int): Axes {
        val i = row * cols + col
        val existing = slots[i]
        if (existing != null) return existing
        val a = Axes()
        a.style = style
        a.figure = this
        slots[i] = a
        return a
    }

    public fun toSvg(): String = renderFigure(this)

    public fun show() {
        displaySvg(toSvg())
    }

    public fun savefig(path: String) {
        writeTextFile(path, toSvg())
    }
}

/** The axes of `plt.subplots(nrows, ncols)`: `axes[i]` counts row by row, `axes[row, col]` addresses a cell. */
public class AxesGrid internal constructor(public val rows: Int, public val cols: Int, private val cells: List<Axes>) {
    public val size: Int get() = cells.size

    public operator fun get(i: Int): Axes = cells[i]

    public operator fun get(row: Int, col: Int): Axes = cells[row * cols + col]
}
