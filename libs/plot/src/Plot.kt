package mini.plot

import mini.display.displaySvg
import mini.io.writeTextFile

/**
 * Anything that can hand over its numbers as a flat `DoubleArray`, e.g. a tensor, a column of a data frame or a signal.
 * `plot { line(x, y) }` accepts them next to `DoubleArray`, `List<Double>` and `IntArray`.
 */
public interface DoubleData {
    public fun toDoubleArray(): DoubleArray
}

internal const val KIND_LINE: Int = 0
internal const val KIND_SCATTER: Int = 1
internal const val KIND_BAR: Int = 2
internal const val KIND_HIST: Int = 3

/** One data series. For bars `xs` are the category positions (0, 1, 2, ...), for a histogram the `n + 1` bin edges. */
internal class Series(val kind: Int, val xs: DoubleArray, val ys: DoubleArray, val label: String, val color: String)

/** A heatmap: [values] row by row (`rows * cols` numbers). */
internal class Heat(val rows: Int, val cols: Int, val values: DoubleArray)

internal fun toDoubles(v: IntArray): DoubleArray = DoubleArray(v.size) { i -> v[i].toDouble() }

internal fun toDoubles(v: List<Double>): DoubleArray = DoubleArray(v.size) { i -> v[i] }

internal fun indexRange(n: Int): DoubleArray = DoubleArray(n) { i -> i.toDouble() }

/** Builds a plot: `plot { line(xs, ys, label = "y"); title = "Demo" }.show()`. */
public fun plot(block: Plot.() -> Unit): Plot {
    val p = Plot()
    p.block()
    return p
}

/**
 * A figure: series, titles and options. Create it with [plot], then [show] it (rich output in a notebook, the playground
 * and the docs), turn it into text with [toSvg] or write it to a file with [save].
 */
public class Plot {
    public var title: String = ""
    public var xlabel: String = ""
    public var ylabel: String = ""
    public var width: Int = 640
    public var height: Int = 400

    /** Show the grid lines. */
    public var grid: Boolean = true

    /** Show a legend when at least one series has a label. */
    public var legend: Boolean = true

    internal val series = ArrayList<Series>()
    internal var heat: Heat? = null
    internal var barLabels: List<String> = emptyList()
    internal var xLo: Double = Double.NaN
    internal var xHi: Double = Double.NaN
    internal var yLo: Double = Double.NaN
    internal var yHi: Double = Double.NaN

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

    private fun colorFor(color: String): String = if (color.isNotEmpty()) color else paletteColor(series.size)

    // ---- line ----

    /** A polyline through the points ([xs][i], [ys][i]); NaN values break the line. */
    public fun line(xs: DoubleArray, ys: DoubleArray, label: String = "", color: String = "") {
        series.add(Series(KIND_LINE, xs, ys, label, colorFor(color)))
    }

    public fun line(xs: List<Double>, ys: List<Double>, label: String = "", color: String = "") {
        line(toDoubles(xs), toDoubles(ys), label, color)
    }

    public fun line(xs: IntArray, ys: IntArray, label: String = "", color: String = "") {
        line(toDoubles(xs), toDoubles(ys), label, color)
    }

    public fun line(xs: DoubleData, ys: DoubleData, label: String = "", color: String = "") {
        line(xs.toDoubleArray(), ys.toDoubleArray(), label, color)
    }

    /** A line of [ys] against their indices 0, 1, 2, ... */
    public fun line(ys: DoubleArray, label: String = "", color: String = "") {
        line(indexRange(ys.size), ys, label, color)
    }

    public fun line(ys: List<Double>, label: String = "", color: String = "") {
        line(toDoubles(ys), label, color)
    }

    public fun line(ys: IntArray, label: String = "", color: String = "") {
        line(toDoubles(ys), label, color)
    }

    public fun line(ys: DoubleData, label: String = "", color: String = "") {
        line(ys.toDoubleArray(), label, color)
    }

    // ---- scatter ----

    /** Dots at the points ([xs][i], [ys][i]). */
    public fun scatter(xs: DoubleArray, ys: DoubleArray, label: String = "", color: String = "") {
        series.add(Series(KIND_SCATTER, xs, ys, label, colorFor(color)))
    }

    public fun scatter(xs: List<Double>, ys: List<Double>, label: String = "", color: String = "") {
        scatter(toDoubles(xs), toDoubles(ys), label, color)
    }

    public fun scatter(xs: IntArray, ys: IntArray, label: String = "", color: String = "") {
        scatter(toDoubles(xs), toDoubles(ys), label, color)
    }

    public fun scatter(xs: DoubleData, ys: DoubleData, label: String = "", color: String = "") {
        scatter(xs.toDoubleArray(), ys.toDoubleArray(), label, color)
    }

    // ---- bar ----

    /**
     * Vertical bars, one per value, at the positions 0, 1, 2, ...; [labels] names the categories.
     * Several bar series are drawn side by side.
     */
    public fun bar(values: DoubleArray, labels: List<String> = emptyList(), label: String = "", color: String = "") {
        series.add(Series(KIND_BAR, indexRange(values.size), values, label, colorFor(color)))
        if (labels.isNotEmpty()) barLabels = labels
    }

    public fun bar(values: List<Double>, labels: List<String> = emptyList(), label: String = "", color: String = "") {
        bar(toDoubles(values), labels, label, color)
    }

    public fun bar(values: IntArray, labels: List<String> = emptyList(), label: String = "", color: String = "") {
        bar(toDoubles(values), labels, label, color)
    }

    public fun bar(values: DoubleData, labels: List<String> = emptyList(), label: String = "", color: String = "") {
        bar(values.toDoubleArray(), labels, label, color)
    }

    // ---- histogram ----

    /** A histogram of [values] with [bins] equally wide bins between the smallest and the largest finite value. */
    public fun hist(values: DoubleArray, bins: Int = 10, label: String = "", color: String = "") {
        val n = if (bins < 1) 1 else bins
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY) {
                if (v < lo) lo = v
                if (v > hi) hi = v
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
        for (v in values) {
            if (v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY) {
                var k = ((v - lo) / (hi - lo) * n.toDouble()).toInt()
                if (k >= n) k = n - 1
                if (k < 0) k = 0
                counts[k] = counts[k] + 1.0
            }
        }
        series.add(Series(KIND_HIST, edges, counts, label, colorFor(color)))
    }

    public fun hist(values: List<Double>, bins: Int = 10, label: String = "", color: String = "") {
        hist(toDoubles(values), bins, label, color)
    }

    public fun hist(values: IntArray, bins: Int = 10, label: String = "", color: String = "") {
        hist(toDoubles(values), bins, label, color)
    }

    public fun hist(values: DoubleData, bins: Int = 10, label: String = "", color: String = "") {
        hist(values.toDoubleArray(), bins, label, color)
    }

    // ---- heatmap ----

    /** A heatmap of a matrix given as one array, row after row (`data.size` should be `rows * cols`). A tensor of shape [rows, cols] fits. */
    public fun heatmap(data: DoubleArray, rows: Int, cols: Int) {
        heat = Heat(rows, cols, data)
    }

    public fun heatmap(data: DoubleData, rows: Int, cols: Int) {
        heatmap(data.toDoubleArray(), rows, cols)
    }

    /** A heatmap of a matrix (an array of rows). */
    public fun heatmap(matrix: Array<DoubleArray>) {
        val rows = matrix.size
        var cols = 0
        for (r in matrix) if (r.size > cols) cols = r.size
        val flat = DoubleArray(rows * cols) { i -> Double.NaN }
        for (r in 0 until rows) {
            for (c in 0 until matrix[r].size) flat[r * cols + c] = matrix[r][c]
        }
        heat = Heat(rows, cols, flat)
    }

    /** A heatmap of a list of rows. */
    public fun heatmap(matrix: List<List<Double>>) {
        val rows = matrix.size
        var cols = 0
        for (r in matrix) if (r.size > cols) cols = r.size
        val flat = DoubleArray(rows * cols) { i -> Double.NaN }
        for (r in 0 until rows) {
            for (c in 0 until matrix[r].size) flat[r * cols + c] = matrix[r][c]
        }
        heat = Heat(rows, cols, flat)
    }

    // ---- output ----

    /** The figure as an SVG document. */
    public fun toSvg(): String = renderSvg(this)

    /** Sends the figure to the host as rich output (see `mini.display`): it appears below the cell in the notebook and the playground. */
    public fun show() {
        displaySvg(toSvg())
    }

    /** Writes the SVG to the file [path]. */
    public fun save(path: String) {
        writeTextFile(path, toSvg())
    }
}

/** The colour of series number [i]: a palette that reads on light and on dark backgrounds. */
internal fun paletteColor(i: Int): String {
    val k = i % 8
    return if (k == 0) "#4c8bf5"
    else if (k == 1) "#f28e2b"
    else if (k == 2) "#3cb371"
    else if (k == 3) "#e15759"
    else if (k == 4) "#a77bd6"
    else if (k == 5) "#17b8c9"
    else if (k == 6) "#e0b400"
    else "#e377b2"
}
