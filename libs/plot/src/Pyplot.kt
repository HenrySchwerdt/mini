package mini.plot

/** `plt.style.use("dark")`: the style of the figures from now on (and of the current one). */
public class StyleSelector {
    /** The available styles: `default`, `dark`, `minimal`, `seaborn`. */
    public val available: List<String> = STYLES

    public fun use(name: String) {
        currentStyle = name
        val f = plt.fig
        if (f != null) {
            f.style = name
            for (a in f.slots) if (a != null) a.style = name
        }
    }
}

/**
 * pyplot for Mini: the state-machine API of matplotlib. `plt.plot(x, y)` draws on the current axes of the current figure, which are
 * created on demand; `plt.show()` displays the figure and starts a new one.
 *
 * ```
 * plt.figure(figsize = 6.0 to 3.5)
 * plt.plot(x, y, label = "y", color = "C1", linestyle = "--", marker = "o")
 * plt.title("A title"); plt.xlabel("x"); plt.legend()
 * plt.show()
 * ```
 */
public object plt {
    internal var fig: Figure? = null
    internal var cur: Axes? = null

    public val style: StyleSelector = StyleSelector()

    private fun px(inches: Double): Int = (inches * 100.0 + 0.5).toInt()

    /** A new figure ([figsize] in inches, 100 px each; the default is 5.6 x 3.5). It becomes the current one. */
    public fun figure(figsize: Pair<Double, Double>? = null): Figure {
        val f = if (figsize == null) Figure() else Figure(px(figsize.first), px(figsize.second))
        f.style = currentStyle
        f.layout(1, 1)
        fig = f
        cur = null
        return f
    }

    public fun gcf(): Figure {
        val f = fig
        if (f != null) return f
        return figure()
    }

    /** The current axes (created when there are none). */
    public fun gca(): Axes {
        val a = cur
        if (a != null) return a
        val f = gcf()
        val ax = f.axesAt(0, 0)
        f.single = f.rows == 1 && f.cols == 1
        cur = ax
        return ax
    }

    /** A figure with a grid of axes: `val (fig, axes) = plt.subplots(2, 2)`, then `axes[0, 1].plot(...)`; one axes: `val (fig, ax) = plt.subplots()`, `ax` is `axes[0]`. */
    public fun subplots(nrows: Int = 1, ncols: Int = 1, figsize: Pair<Double, Double>? = null): Pair<Figure, AxesGrid> {
        val w = if (figsize != null) px(figsize.first) else if (ncols == 1) 520 else if (ncols == 2) 760 else 1000
        val h = if (figsize != null) px(figsize.second) else 40 + 300 * nrows
        val f = Figure(w, h)
        f.style = currentStyle
        f.layout(nrows, ncols)
        val cells = ArrayList<Axes>()
        for (r in 0 until nrows) {
            for (c in 0 until ncols) cells.add(f.axesAt(r, c))
        }
        f.single = nrows == 1 && ncols == 1
        fig = f
        cur = cells[cells.size - 1]
        return Pair(f, AxesGrid(nrows, ncols, cells))
    }

    /** The axes number [index] (1 based, row by row) of a grid of [nrows] x [ncols]; makes the grid on first use. */
    public fun subplot(nrows: Int, ncols: Int, index: Int): Axes {
        var f = fig
        if (f == null || f.rows != nrows || f.cols != ncols) {
            val w = if (ncols == 1) 520 else if (ncols == 2) 760 else 1000
            f = Figure(w, 40 + 300 * nrows)
            f.style = currentStyle
            f.layout(nrows, ncols)
            f.single = nrows == 1 && ncols == 1
            fig = f
        }
        val i = index - 1
        val a = f.axesAt(i / ncols, i % ncols)
        cur = a
        return a
    }

    /** Draws on the current axes; see [Axes.plot]. */
    public fun plot(x: Any, y: Any? = null, fmt: String = "", label: String = "", color: String = "", linestyle: String = "", marker: String = "", linewidth: Double = 2.0, alpha: Double = Double.NaN) {
        gca().plot(x, y, fmt, label, color, linestyle, marker, linewidth, alpha)
    }

    public fun scatter(x: Any, y: Any, label: String = "", color: String = "", s: Double = 36.0, alpha: Double = Double.NaN, marker: String = "o") {
        gca().scatter(x, y, label, color, s, alpha, marker)
    }

    public fun bar(x: Any, height: Any? = null, label: String = "", color: String = "", width: Double = 0.8, bottom: Any? = null, alpha: Double = Double.NaN, labels: List<String> = emptyList()) {
        gca().bar(x, height, label, color, width, bottom, alpha, labels)
    }

    public fun barh(y: Any, width: Any? = null, label: String = "", color: String = "", height: Double = 0.8, alpha: Double = Double.NaN, labels: List<String> = emptyList()) {
        gca().barh(y, width, label, color, height, alpha, labels)
    }

    public fun hist(values: Any, bins: Int = 10, label: String = "", color: String = "", alpha: Double = Double.NaN, density: Boolean = false) {
        gca().hist(values, bins, label, color, alpha, density)
    }

    public fun imshow(data: Any, cmap: String = "viridis", vmin: Double = Double.NaN, vmax: Double = Double.NaN, annot: Boolean = false, rows: Int = 0, cols: Int = 0) {
        gca().imshow(data, cmap, vmin, vmax, annot, rows, cols)
    }

    public fun fill_between(x: Any, y1: Any, y2: Any? = null, label: String = "", color: String = "", alpha: Double = 0.22) {
        gca().fill_between(x, y1, y2, label, color, alpha)
    }

    public fun errorbar(x: Any, y: Any, yerr: Any? = null, label: String = "", color: String = "", marker: String = "o", linestyle: String = "", capsize: Double = 3.0, linewidth: Double = 1.6) {
        gca().errorbar(x, y, yerr, label, color, marker, linestyle, capsize, linewidth)
    }

    public fun axhline(y: Double = 0.0, label: String = "", color: String = "", linestyle: String = "--", linewidth: Double = 1.4) {
        gca().axhline(y, label, color, linestyle, linewidth)
    }

    public fun axvline(x: Double = 0.0, label: String = "", color: String = "", linestyle: String = "--", linewidth: Double = 1.4) {
        gca().axvline(x, label, color, linestyle, linewidth)
    }

    public fun text(x: Double, y: Double, s: String, fontsize: Double = 12.0, color: String = "", ha: String = "left") {
        gca().text(x, y, s, fontsize, color, ha)
    }

    public fun annotate(text: String, xy: Pair<Double, Double>, xytext: Pair<Double, Double>? = null, fontsize: Double = 12.0, color: String = "") {
        gca().annotate(text, xy, xytext, fontsize, color)
    }

    public fun title(title: String, subtitle: String = "") {
        gca().set_title(title, subtitle)
    }

    public fun xlabel(label: String) {
        gca().xlabel = label
    }

    public fun ylabel(label: String) {
        gca().ylabel = label
    }

    public fun legend(loc: String = "auto") {
        gca().legend(loc)
    }

    public fun grid(visible: Boolean = true, axis: String = "auto") {
        gca().grid(visible, axis)
    }

    public fun xlim(lo: Double, hi: Double) {
        gca().xlim(lo, hi)
    }

    public fun ylim(lo: Double, hi: Double) {
        gca().ylim(lo, hi)
    }

    public fun xscale(scale: String) {
        gca().set_xscale(scale)
    }

    public fun yscale(scale: String) {
        gca().set_yscale(scale)
    }

    public fun xticks(ticks: Any, labels: List<String> = emptyList()) {
        gca().set_xticks(ticks, labels)
    }

    public fun yticks(ticks: Any, labels: List<String> = emptyList()) {
        gca().set_yticks(ticks, labels)
    }

    /** Tick label format of the current axes: `"auto"`, `"plain"`, `"si"`, `"percent"`, `"sci"`. */
    public fun xformat(format: String) {
        gca().set_xformat(format)
    }

    public fun yformat(format: String) {
        gca().set_yformat(format)
    }

    public fun suptitle(title: String) {
        gcf().suptitle = title
    }

    /** No-op: the margins always fit the labels. */
    public fun tight_layout() {}

    /** Displays the current figure (rich output) and starts with a new one. */
    public fun show() {
        gca()
        gcf().show()
        clf()
    }

    /** Writes the current figure as SVG to [path]. */
    public fun savefig(path: String) {
        gca()
        gcf().savefig(path)
    }

    /** The current figure as SVG text. */
    public fun toSvg(): String {
        gca()
        return gcf().toSvg()
    }

    /** Clears the current figure. */
    public fun clf() {
        fig = null
        cur = null
    }

    public fun close() {
        clf()
    }
}
