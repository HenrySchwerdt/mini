import kotlin.test.*
import mini.plot.*

class ApiTest {
    private fun count(s: String, needle: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }

    private val tags = listOf("svg", "g", "defs", "title", "desc", "path", "line", "rect", "circle", "ellipse", "polyline", "polygon", "text", "tspan", "lineargradient", "radialgradient", "stop", "clippath")
    private val attrs = listOf(
        "xmlns", "viewbox", "width", "height", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "transform",
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-opacity", "stroke-width", "stroke-linecap", "stroke-linejoin",
        "stroke-dasharray", "stroke-dashoffset", "stroke-miterlimit", "opacity", "font-family", "font-size", "font-weight", "font-style",
        "text-anchor", "dominant-baseline", "letter-spacing", "text-decoration", "dx", "dy", "overflow", "role", "aria-label", "aria-hidden",
        "class", "id", "offset", "stop-color", "stop-opacity", "gradientunits", "gradienttransform", "clip-path", "shape-rendering",
        "preserveaspectratio", "vector-effect",
    )

    /** Every element and attribute must be on the website's allowlist (sanitize.ts), or the figure is not inlined. */
    private fun assertInlinable(svg: String) {
        var i = 0
        while (i < svg.length) {
            if (svg[i] == '<' && i + 1 < svg.length && svg[i + 1] != '/' && svg[i + 1] != '!' && svg[i + 1] != '?') {
                var j = i + 1
                while (j < svg.length && svg[j] != ' ' && svg[j] != '>' && svg[j] != '/' && svg[j] != '\n') j++
                val tag = svg.substring(i + 1, j).lowercase()
                assertTrue(tags.contains(tag), "element " + tag)
                // attributes up to the end of the tag
                var k = j
                while (k < svg.length && svg[k] != '>') {
                    if (svg[k] == '=' && svg[k + 1] == '"') {
                        var s = k - 1
                        while (s > 0 && svg[s] != ' ') s--
                        val name = svg.substring(s + 1, k).lowercase()
                        assertTrue(attrs.contains(name), "attribute " + name + " of " + tag)
                        k += 2
                        while (svg[k] != '"') k++
                    }
                    k++
                }
            }
            i++
        }
        assertFalse(svg.contains("javascript:"))
        assertFalse(svg.contains("<style"))
    }

    private fun xs(): DoubleArray = DoubleArray(20) { i -> i.toDouble() }

    @Test
    fun pyplotStateMachine() {
        plt.clf()
        plt.figure(figsize = 6.0 to 3.0)
        plt.plot(xs(), DoubleArray(20) { i -> (i * i).toDouble() }, label = "squares", color = "C1", linestyle = "--", marker = "o")
        plt.title("Squares", "x squared")
        plt.xlabel("n")
        plt.ylabel("n^2")
        plt.legend()
        plt.grid(true)
        val svg = plt.toSvg()
        assertTrue(svg.contains("viewBox=\"0 0 600 300\""))
        assertTrue(svg.contains(">Squares</text>"))
        assertTrue(svg.contains(">x squared</text>"))
        assertTrue(svg.contains(">n^2</text>"))
        assertTrue(svg.contains("stroke=\"#f28e2b\""))
        assertTrue(svg.contains("stroke-dasharray=\"6 4\""))
        assertTrue(svg.contains("class=\"legend\""))
        assertInlinable(svg)
        plt.clf()
        // a cleared figure is empty again
        assertTrue(plt.toSvg().contains("no data"))
        plt.clf()
    }

    @Test
    fun formatStringsAndMarkers() {
        val svg = plot { plot(xs(), xs(), "r--") }.toSvg()
        assertTrue(svg.contains("stroke=\"#e15759\""))
        assertTrue(svg.contains("stroke-dasharray"))
        val dots = plot { plot(intArrayOf(1, 2, 3), intArrayOf(3, 1, 2), "go") }.toSvg()
        // markers only: no connecting path, one circle per point (plus none in a legend)
        assertEquals(0, count(dots, "<path "))
        assertEquals(3, count(dots, "<circle "))
        for (m in listOf("o", "s", "^", "v", "D", "x", "+", ".")) {
            assertInlinable(plot { plot(listOf(1, 2), listOf(2, 3), marker = m, label = "m") }.toSvg())
        }
    }

    @Test
    fun axesGridAndOoApi() {
        val (fig, axes) = plt.subplots(2, 2, figsize = 8.0 to 6.0)
        assertEquals(4, axes.size)
        axes[0, 0].plot(xs(), xs())
        axes[0, 0].set_title("top left")
        axes[0, 1].scatter(xs(), xs())
        axes[0, 1].set_title("top right")
        axes[1, 0].bar(listOf("a", "b"), listOf(1.0, 2.0))
        axes[1, 0].set_title("bottom left")
        axes[1, 1].hist(xs(), bins = 4)
        axes[1, 1].set_xlabel("value")
        fig.suptitle = "Four panels"
        val svg = fig.toSvg()
        assertTrue(svg.contains("viewBox=\"0 0 800 600\""))
        assertTrue(svg.contains(">top left</text>"))
        assertTrue(svg.contains(">bottom left</text>"))
        assertTrue(svg.contains(">Four panels</text>"))
        assertTrue(svg.contains(">value</text>"))
        assertInlinable(svg)
        plt.clf()
    }

    @Test
    fun axesGridIndexing() {
        val (fig, axes) = plt.subplots(1, 3)
        assertEquals(3, axes.cols)
        assertEquals(1, axes.rows)
        axes[2].plot(xs())
        axes[2].set_title("third")
        assertTrue(fig.toSvg().contains(">third</text>"))
        plt.clf()
    }

    @Test
    fun styles() {
        val dark = plot {
            style = "dark"
            line(xs(), xs(), label = "a")
        }.toSvg()
        assertTrue(dark.contains("#111827"))
        assertFalse(dark.contains("fill=\"currentColor\""))
        val light = plot { line(xs(), xs()) }.toSvg()
        assertTrue(light.contains("fill=\"currentColor\""))
        assertFalse(light.contains("#111827"))
        val minimal = plot {
            style = "minimal"
            line(xs(), xs())
        }.toSvg()
        assertFalse(minimal.contains("class=\"grid\""))
        assertTrue(plot { line(xs(), xs()) }.toSvg().contains("class=\"grid\""))
        val sea = plot {
            style = "seaborn"
            line(xs(), xs())
        }.toSvg()
        assertTrue(sea.contains("#eaeaf2"))
        assertTrue(sea.contains("stroke=\"#4c72b0\""))
        plt.style.use("dark")
        assertTrue(plt.style.available.contains("seaborn"))
        plt.clf()
        plt.plot(xs())
        assertTrue(plt.toSvg().contains("#111827"))
        plt.style.use("default")
        plt.clf()
    }

    @Test
    fun logScale() {
        val svg = plot {
            plot(doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0), doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0))
            set_yscale("log")
            set_xscale("log")
        }.toSvg()
        assertTrue(svg.contains(">10</text>"))
        assertTrue(svg.contains(">100</text>"))
        assertTrue(svg.contains(">1k</text>"))
        assertTrue(svg.contains(">10k</text>"))
        // zero and negative values are dropped, not drawn at infinity
        val bad = plot {
            plot(doubleArrayOf(0.0, 1.0, 10.0), doubleArrayOf(-1.0, 1.0, 10.0))
            set_yscale("log")
        }.toSvg()
        assertFalse(bad.contains("Infinity"))
        assertFalse(bad.contains("NaN"))
    }

    @Test
    fun tickLabelFormats() {
        assertEquals("1.5k", siLabel(1500.0, 500.0))
        assertEquals("2M", siLabel(2000000.0, 1000000.0))
        assertEquals("2.5M", siLabel(2500000.0, 500000.0))
        assertEquals("0", siLabel(0.0, 1000.0))
        assertEquals("500", siLabel(500.0, 500.0))
        assertEquals("-3k", siLabel(-3000.0, 1000.0))
        assertEquals("25%", percentLabel(0.25, 0.25))
        assertEquals("12.5%", percentLabel(0.125, 0.025))
        val t = niceTicks(0.0, 50000.0, 6)
        val labels = formatTicksForTest(t)
        assertEquals("0", labels[0])
        assertTrue(labels.contains("10k"))
        val pct = plot {
            plot(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
            set_yformat("percent")
        }.toSvg()
        assertTrue(pct.contains(">40%</text>"))
        assertTrue(pct.contains(">100%</text>"))
    }

    private fun formatTicksForTest(t: Ticks): List<String> {
        val out = ArrayList<String>()
        for (v in t.values) out.add(siLabel(v, t.step))
        return out
    }

    @Test
    fun barsStackedGroupedAndHorizontal() {
        val grouped = plot {
            bar(listOf("a", "b", "c"), listOf(1.0, 2.0, 3.0), label = "x")
            bar(listOf("a", "b", "c"), listOf(2.0, 1.0, 1.0), label = "y")
        }.toSvg()
        assertEquals(6, count(grouped, " 0 0 1 ") / 2)
        val stacked = plot {
            bar(listOf("a", "b"), listOf(1.0, 2.0), label = "x")
            bar(listOf("a", "b"), listOf(2.0, 1.0), label = "y", bottom = listOf(1.0, 2.0))
        }.toSvg()
        // stacked bars have square corners (no arcs) and the top reaches 3
        assertEquals(0, count(stacked, " 0 0 1 "))
        assertTrue(stacked.contains(">3</text>") || stacked.contains(">3.5</text>"))
        val h = plot { barh(listOf("alpha", "beta"), listOf(3.0, 5.0)) }.toSvg()
        assertTrue(h.contains(">alpha</text>"))
        assertTrue(h.contains(": 5</title>"))
        assertInlinable(grouped)
        assertInlinable(stacked)
        assertInlinable(h)
    }

    @Test
    fun areasErrorBarsLinesAndText() {
        val svg = plot {
            fill_between(xs(), DoubleArray(20) { i -> i * 0.5 }, DoubleArray(20) { i -> i * 1.5 }, label = "band")
            errorbar(xs(), xs(), yerr = 1.0, label = "e", capsize = 4.0)
            axhline(5.0, label = "limit")
            axvline(3.0)
            text(2.0, 3.0, "note <b>", fontsize = 14.0)
            annotate("peak", xy = 10.0 to 10.0, xytext = 14.0 to 4.0)
        }.toSvg()
        assertTrue(svg.contains("fill-opacity=\"0.22\""))
        assertTrue(svg.contains("note &lt;b&gt;"))
        assertTrue(svg.contains(">peak</text>"))
        assertTrue(svg.contains(">limit</text>"))
        assertTrue(svg.contains("<polygon "))
        assertInlinable(svg)
    }

    @Test
    fun heatmapColorMapsAndAnnotations() {
        val m = arrayOf(doubleArrayOf(0.0, 0.5), doubleArrayOf(0.25, 1.0))
        for (c in listOf("viridis", "magma", "plasma", "coolwarm", "gray")) {
            val svg = plot { imshow(m, cmap = c, annot = true) }.toSvg()
            assertTrue(svg.contains("<linearGradient"))
            assertTrue(svg.contains(">0.25</text>"))
            assertInlinable(svg)
        }
        assertTrue(plot { imshow(m, cmap = "magma") }.toSvg().contains("#000004"))
        assertTrue(plot { imshow(m, cmap = "coolwarm") }.toSvg().contains("#b40426"))
        // flat data with a shape, and ticks with labels
        val flat = plot {
            imshow(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), rows = 2, cols = 3)
            set_xticks(doubleArrayOf(0.0, 1.0, 2.0), listOf("a", "b", "c"))
        }.toSvg()
        assertEquals(6 + 1, count(flat, "<rect "))
        assertTrue(flat.contains(">b</text>"))
    }

    @Test
    fun hoverTitlesShowValues() {
        val svg = plot {
            scatter(doubleArrayOf(1.5), doubleArrayOf(2.25), label = "pt")
            bar(listOf("a"), listOf(7.0))
        }.toSvg()
        assertTrue(svg.contains("<title>pt\nx = 1.5, y = 2.25</title>"))
        assertTrue(svg.contains("<title>a: 7</title>"))
        val line = plot { line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 3.0), label = "l") }.toSvg()
        assertTrue(line.contains("x = 1, y = 3"))
    }

    @Test
    fun legendPlacement() {
        val data = plot {
            line(xs(), xs(), label = "rising")
            legend("best")
        }.toSvg()
        // the line fills the diagonal; the legend box sits in a free corner (upper left or lower right)
        assertTrue(data.contains("class=\"legend\"><rect"))
        val top = plot { line(xs(), xs(), label = "rising") }.toSvg()
        assertTrue(top.contains("<g class=\"legend\">\n<line"))
        val none = plot {
            line(xs(), xs(), label = "rising")
            legend("none")
        }.toSvg()
        assertFalse(none.contains("class=\"legend\""))
        val ur = plot {
            line(xs(), xs(), label = "rising")
            legend("lower right")
        }.toSvg()
        val ul = plot {
            line(xs(), xs(), label = "rising")
            legend("upper left")
        }.toSvg()
        assertTrue(ur != ul)
        val outside = plot {
            line(xs(), xs(), label = "rising")
            legend("right")
        }.toSvg()
        assertTrue(outside.contains("class=\"legend\"><rect"))
    }

    @Test
    fun layoutAdaptsToLabels() {
        val shortLabels = plot { bar(listOf("a", "b", "c"), listOf(1.0, 2.0, 3.0)) }.toSvg()
        assertFalse(shortLabels.contains("rotate(-30"))
        val names = listOf("Engineering", "Marketing", "Operations", "Customer support", "Legal and compliance", "Research", "Facilities")
        val long = plot { bar(names, DoubleArray(7) { i -> (i + 1).toDouble() }) }.toSvg()
        assertTrue(long.contains("rotate(-30"))
        // wider tick labels push the plot area to the right
        val narrow = plot { line(xs(), xs()) }.toSvg()
        val wide = plot { line(xs(), DoubleArray(20) { i -> i * 123456.0 }); set_yformat("plain") }.toSvg()
        assertTrue(leftOf(wide) > leftOf(narrow))
    }

    private fun leftOf(svg: String): Double {
        val key = "<g transform=\"translate("
        val first = svg.indexOf(key)
        val second = svg.indexOf(key, first + 1)
        val start = second + key.length
        val end = svg.indexOf(' ', start)
        return svg.substring(start, end).toDouble()
    }

    @Test
    fun everythingStaysOnTheAllowlist() {
        val svg = plot {
            title = "All of it"
            subtitle = "sub"
            xlabel = "x"
            ylabel = "y"
            plot(xs(), xs(), "r--o", label = "a")
            scatter(xs(), xs(), label = "b")
            fill_between(xs(), xs(), 0.0)
            errorbar(xs(), xs(), 1.0)
            axhline(3.0)
            text(1.0, 1.0, "t")
            annotate("n", xy = 2.0 to 2.0, xytext = 5.0 to 8.0)
        }.toSvg()
        assertInlinable(svg)
        assertInlinable(plot { hist(xs(), bins = 5, density = true) }.toSvg())
        for (st in listOf("default", "dark", "minimal", "seaborn")) {
            assertInlinable(plot { style = st; line(xs(), xs(), label = "z"); bar(xs()) }.toSvg())
        }
    }

    @Test
    fun colorsAreResolvedAndEscaped() {
        val svg = plot { plot(xs(), xs(), color = "\"><script>x") }.toSvg()
        assertFalse(svg.contains("<script"))
        assertTrue(plot { plot(xs(), xs(), color = "tab:red") }.toSvg().contains("#e15759"))
        assertTrue(plot { plot(xs(), xs(), color = "#123456") }.toSvg().contains("#123456"))
    }

    @Test
    fun dslAndMatplotlibNamesMix() {
        val svg = plot {
            line(xs(), xs(), label = "dsl")
            plot(xs(), xs(), label = "mpl")
            set_title("T")
            set_xlim(0.0, 10.0)
            grid(false)
        }.toSvg()
        assertTrue(svg.contains(">T</text>"))
        assertFalse(svg.contains("class=\"grid\""))
        assertTrue(svg.contains(">dsl</text>"))
        assertTrue(svg.contains(">mpl</text>"))
    }
}
