import kotlin.test.*
import mini.plot.*
import mini.display.*

class PlotTest {
    private fun count(s: String, needle: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }

    @Test
    fun emptyPlotIsAValidSvg() {
        val svg = plot { title = "Nothing" }.toSvg()
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertTrue(svg.contains("no data"))
        assertTrue(svg.contains(">Nothing</text>"))
    }

    @Test
    fun sizeOptionsAreUsed() {
        val svg = plot {
            width = 300
            height = 200
            line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        }.toSvg()
        assertTrue(svg.contains("viewBox=\"0 0 300 200\""))
        assertTrue(svg.contains("width=\"300\" height=\"200\""))
    }

    @Test
    fun lineHasPathTitleLabelsAndLegend() {
        val svg = plot {
            line(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 4.0), label = "square")
            line(listOf(0.0, 1.0, 2.0), listOf(1.0, 1.0, 1.0), label = "one")
            title = "Two lines"
            xlabel = "x axis"
            ylabel = "y axis"
        }.toSvg()
        assertEquals(2, count(svg, "<path "))
        assertTrue(svg.contains(">Two lines</text>"))
        assertTrue(svg.contains(">x axis</text>"))
        assertTrue(svg.contains("rotate(-90"))
        assertTrue(svg.contains(">square</text>"))
        assertTrue(svg.contains(">one</text>"))
        assertTrue(svg.contains("class=\"legend\""))
        assertTrue(svg.contains("stroke=\"#4c8bf5\""))
        assertTrue(svg.contains("stroke=\"#f28e2b\""))
        assertTrue(svg.contains("currentColor"))
    }

    @Test
    fun legendIsOmittedWithoutLabels() {
        val svg = plot { line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0)) }.toSvg()
        assertFalse(svg.contains("class=\"legend\""))
        val off = plot {
            legend = false
            line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), label = "a")
        }.toSvg()
        assertFalse(off.contains("class=\"legend\""))
    }

    @Test
    fun theLinePathPassesThroughTheData() {
        val svg = plot {
            width = 200
            height = 120
            line(intArrayOf(0, 10), intArrayOf(0, 10))
        }.toSvg()
        assertEquals(1, count(svg, "<path "))
        assertTrue(svg.contains("d=\"M0 "))
        assertTrue(svg.contains(" L"))
    }

    @Test
    fun nanBreaksTheLine() {
        val svg = plot {
            line(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(0.0, Double.NaN, 2.0, 3.0))
        }.toSvg()
        val d = svg.substring(svg.indexOf("d=\"") + 3, svg.indexOf("\"", svg.indexOf("d=\"") + 3))
        assertEquals(2, count(d, "M"))
    }

    @Test
    fun scatterDrawsOneCirclePerFinitePoint() {
        val svg = plot {
            scatter(doubleArrayOf(1.0, 2.0, 3.0, Double.NaN), doubleArrayOf(1.0, 4.0, 9.0, 1.0), label = "pts")
        }.toSvg()
        // three dots, plus the legend marker
        assertEquals(4, count(svg, "<circle "))
    }

    @Test
    fun barsHaveCategoryLabels() {
        val svg = plot {
            bar(intArrayOf(3, 5, 2), labels = listOf("a", "b", "c"))
        }.toSvg()
        assertTrue(svg.contains(">a</text>"))
        assertTrue(svg.contains(">c</text>"))
        // 3 bars + the frame and the plot area has no other rects
        assertEquals(3 + 1, count(svg, "<rect "))
    }

    @Test
    fun histogramCountsAllValues() {
        val data = DoubleArray(100) { i -> (i % 10).toDouble() }
        val svg = plot { hist(data, bins = 5) }.toSvg()
        assertEquals(5 + 1, count(svg, "<rect "))
        val one = plot { hist(doubleArrayOf(2.0, 2.0, 2.0), bins = 3) }.toSvg()
        assertTrue(one.contains("<rect "))
    }

    @Test
    fun heatmapDrawsACellPerValue() {
        val m = arrayOf(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0))
        val svg = plot { heatmap(m) }.toSvg()
        // 6 cells + 32 colour bar steps + the frame
        assertEquals(6 + 32 + 1, count(svg, "<rect "))
        assertTrue(svg.contains("#440154"))
        assertTrue(svg.contains("#fde725"))
    }

    @Test
    fun degenerateDataDoesNotBreak() {
        val flat = plot { line(doubleArrayOf(1.0, 1.0), doubleArrayOf(5.0, 5.0)) }.toSvg()
        assertTrue(flat.contains("<path "))
        val one = plot { scatter(doubleArrayOf(2.0), doubleArrayOf(3.0)) }.toSvg()
        assertTrue(one.contains("<circle "))
        val none = plot { line(DoubleArray(0), DoubleArray(0)) }.toSvg()
        assertTrue(none.contains("no data"))
        val mismatched = plot { line(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(1.0)) }.toSvg()
        assertTrue(mismatched.contains("<path "))
        val allNan = plot { line(doubleArrayOf(Double.NaN), doubleArrayOf(Double.NaN)) }.toSvg()
        assertTrue(allNan.contains("no data"))
        val emptyHeat = plot { heatmap(DoubleArray(0), 0, 0) }.toSvg()
        assertTrue(emptyHeat.startsWith("<svg"))
    }

    @Test
    fun textIsEscaped() {
        val svg = plot {
            title = "a < b & \"c\""
            line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), label = "<script>")
        }.toSvg()
        assertTrue(svg.contains("a &lt; b &amp; &quot;c&quot;"))
        assertFalse(svg.contains("<script>"))
    }

    @Test
    fun axisLimitsAreRespected() {
        val svg = plot {
            line(doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 100.0))
            ylim(0.0, 50.0)
            xlim(0.0, 5.0)
        }.toSvg()
        assertTrue(svg.contains(">50</text>"))
        assertFalse(svg.contains(">100</text>"))
    }

    @Test
    fun anyDoubleDataCanBePlotted() {
        class Col(val v: DoubleArray) : DoubleData {
            override fun toDoubleArray(): DoubleArray = v
        }
        val svg = plot { line(Col(doubleArrayOf(1.0, 2.0)), Col(doubleArrayOf(3.0, 4.0)), label = "col") }.toSvg()
        assertTrue(svg.contains("<path "))
    }

    @Test
    fun showUsesTheDisplayProtocol() {
        val bundle = displayBundle(MIME_SVG, plot { line(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0)) }.toSvg())
        assertTrue(bundle.startsWith("\u001B]5379;mini-display;image/svg+xml;"))
        assertTrue(bundle.endsWith("\u0007"))
    }
}

class DisplayTest {
    @Test
    fun base64() {
        assertEquals("", base64OfUtf8(""))
        assertEquals("Zg==", base64OfUtf8("f"))
        assertEquals("Zm8=", base64OfUtf8("fo"))
        assertEquals("Zm9v", base64OfUtf8("foo"))
        assertEquals("w6k=", base64OfUtf8("é"))
        assertEquals("4pyT", base64OfUtf8("✓"))
    }
}
