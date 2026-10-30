package mini.plot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// SVG rendering. Colours that belong to the "chrome" (text, axes, grid) are `currentColor` with an opacity, so the figure
// follows the text colour of the page it is shown in, in a light and in a dark theme. Series use a fixed palette.

private fun isFinite(v: Double): Boolean = v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY

private fun textWidth(s: String, size: Double): Double = s.length.toDouble() * size * 0.56

/** An axis: the visible range and where the ticks are (value and label). */
private class Axis(val lo: Double, val hi: Double, val tickValues: List<Double>, val tickLabels: List<String>)

private fun niceAxis(lo: Double, hi: Double, target: Int, fixedLo: Double, fixedHi: Double, tight: Boolean): Axis {
    val ticks = niceTicks(lo, hi, target)
    var a = if (tight) lo else ticks.first
    var b = if (tight) hi else ticks.last
    if (tight && a == b) {
        a = ticks.first
        b = ticks.last
    }
    if (isFinite(fixedLo)) a = fixedLo
    if (isFinite(fixedHi)) b = fixedHi
    if (a >= b) b = a + 1.0
    val vals = ArrayList<Double>()
    val labels = ArrayList<String>()
    val eps = (b - a) * 1.0e-9
    for (v in ticks.values) {
        if (v >= a - eps && v <= b + eps) {
            vals.add(v)
            labels.add(tickLabel(v, ticks))
        }
    }
    return Axis(a, b, vals, labels)
}

private fun hex2(n: Int): String {
    val d = "0123456789abcdef"
    val v = if (n < 0) 0 else if (n > 255) 255 else n
    return d[v / 16].toString() + d[v % 16].toString()
}

// viridis, 5 stops
private val HEAT_R: IntArray = intArrayOf(68, 59, 33, 94, 253)
private val HEAT_G: IntArray = intArrayOf(1, 82, 145, 201, 231)
private val HEAT_B: IntArray = intArrayOf(84, 139, 140, 98, 37)

internal fun heatColor(t0: Double): String {
    val t = if (t0 < 0.0) 0.0 else if (t0 > 1.0) 1.0 else t0
    val x = t * 4.0
    var i = x.toInt()
    if (i > 3) i = 3
    val f = x - i.toDouble()
    val r = HEAT_R[i].toDouble() + (HEAT_R[i + 1] - HEAT_R[i]).toDouble() * f
    val g = HEAT_G[i].toDouble() + (HEAT_G[i + 1] - HEAT_G[i]).toDouble() * f
    val b = HEAT_B[i].toDouble() + (HEAT_B[i + 1] - HEAT_B[i]).toDouble() * f
    return "#" + hex2((r + 0.5).toInt()) + hex2((g + 0.5).toInt()) + hex2((b + 0.5).toInt())
}

internal fun renderSvg(p: Plot): String {
    val w = if (p.width < 160) 160 else p.width
    val h = if (p.height < 100) 100 else p.height
    val wd = w.toDouble()
    val hd = h.toDouble()
    val heat = p.heat
    val fs = 12.0

    // ---- axes ----
    var empty = false
    var catX = false
    val xAxisProbe: Axis
    val yAxisProbe: Axis
    val plotWProbe = wd - 100.0
    val plotHProbe = hd - 80.0
    val xTarget = max(3, (plotWProbe / 80.0).toInt() + 1)
    val yTarget = max(3, (plotHProbe / 45.0).toInt() + 1)
    if (heat != null) {
        val cols = max(1, heat.cols)
        val rows = max(1, heat.rows)
        xAxisProbe = Axis(0.0, cols.toDouble(), emptyList(), emptyList())
        yAxisProbe = Axis(0.0, rows.toDouble(), emptyList(), emptyList())
    } else {
        var xmin = Double.POSITIVE_INFINITY
        var xmax = Double.NEGATIVE_INFINITY
        var ymin = Double.POSITIVE_INFINITY
        var ymax = Double.NEGATIVE_INFINITY
        var anyBar = false
        var anyHist = false
        var anyXY = false
        var anyScatter = false
        var barCount = 0
        for (s in p.series) {
            if (s.kind == KIND_BAR) {
                anyBar = true
                if (s.ys.size > barCount) barCount = s.ys.size
            }
            if (s.kind == KIND_HIST) anyHist = true
            if (s.kind == KIND_LINE || s.kind == KIND_SCATTER) anyXY = true
            if (s.kind == KIND_SCATTER) anyScatter = true
            val n = min(s.xs.size, s.ys.size)
            for (i in 0 until n) {
                val x = s.xs[i]
                val y = s.ys[i]
                if (isFinite(x) && (s.kind == KIND_LINE || s.kind == KIND_SCATTER || s.kind == KIND_HIST)) {
                    if (x < xmin) xmin = x
                    if (x > xmax) xmax = x
                }
                if (isFinite(y) && (s.kind == KIND_LINE || s.kind == KIND_SCATTER || s.kind == KIND_BAR || s.kind == KIND_HIST)) {
                    if (y < ymin) ymin = y
                    if (y > ymax) ymax = y
                }
            }
            if (s.kind == KIND_HIST && s.xs.size > 0) {
                // the histogram has one more edge than values
                val last = s.xs[s.xs.size - 1]
                if (isFinite(last) && last > xmax) xmax = last
            }
        }
        if (anyBar || anyHist) {
            if (ymin > 0.0) ymin = 0.0
            if (ymax < 0.0) ymax = 0.0
            if (ymax > 0.0 && p.yHi != p.yHi) ymax += (ymax - ymin) * 0.06
        }
        if (ymin > ymax) {
            empty = true
            ymin = 0.0
            ymax = 1.0
        }
        if (xmin > xmax) {
            xmin = 0.0
            xmax = 1.0
        }
        if (anyScatter) {
            // keep the dots at the edge fully inside
            val padX = (xmax - xmin) * 0.04
            val padY = (ymax - ymin) * 0.04
            xmin -= padX
            xmax += padX
            ymin -= padY
            ymax += padY
        }
        if (anyBar && !anyXY && !anyHist) {
            catX = true
            val lo = -0.5
            val hi = barCount.toDouble() - 0.5
            val vals = ArrayList<Double>()
            val labels = ArrayList<String>()
            val stride = max(1, (barCount + xTarget * 2 - 1) / (xTarget * 2))
            var i = 0
            while (i < barCount) {
                vals.add(i.toDouble())
                labels.add(if (i < p.barLabels.size) p.barLabels[i] else i.toString())
                i += stride
            }
            xAxisProbe = Axis(if (isFinite(p.xLo)) p.xLo else lo, if (isFinite(p.xHi)) p.xHi else hi, vals, labels)
        } else {
            if (anyBar) {
                // bars next to lines or histograms: position by index, no category labels
                val hi = barCount.toDouble() - 0.5
                if (-0.5 < xmin) xmin = -0.5
                if (hi > xmax) xmax = hi
            }
            xAxisProbe = niceAxis(xmin, xmax, xTarget, p.xLo, p.xHi, anyHist && !anyXY && !anyBar)
        }
        yAxisProbe = niceAxis(ymin, ymax, yTarget, p.yLo, p.yHi, false)
    }
    val xAxis = xAxisProbe
    val yAxis = yAxisProbe

    // ---- layout ----
    var maxLabel = 0.0
    if (heat != null) {
        maxLabel = textWidth((max(1, heat.rows) - 1).toString(), fs)
    } else {
        for (l in yAxis.tickLabels) maxLabel = dmax(maxLabel, textWidth(l, fs))
    }
    val left = 14.0 + (if (p.ylabel.isNotEmpty()) 18.0 else 0.0) + maxLabel + 8.0
    val top = if (p.title.isNotEmpty()) 34.0 else 14.0
    val right = if (heat != null) 74.0 else 16.0
    val bottom = 30.0 + (if (p.xlabel.isNotEmpty()) 20.0 else 0.0)
    val pw = dmax(40.0, wd - left - right)
    val ph = dmax(30.0, hd - top - bottom)

    val xr = xAxis.hi - xAxis.lo
    val yr = yAxis.hi - yAxis.lo
    fun sx(v: Double): Double = (v - xAxis.lo) / xr * pw
    fun sy(v: Double): Double = ph - (v - yAxis.lo) / yr * ph

    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h)
    sb.append("\" width=\"").append(w).append("\" height=\"").append(h)
    sb.append("\" role=\"img\" font-family=\"system-ui, -apple-system, Segoe UI, Helvetica, Arial, sans-serif\" font-size=\"12\" fill=\"currentColor\">")
    val label = if (p.title.isNotEmpty()) p.title else "Plot"
    sb.append("<title>").append(xmlEscape(label)).append("</title>\n")
    if (p.title.isNotEmpty()) {
        sb.append("<text x=\"").append(num(wd / 2.0)).append("\" y=\"21\" text-anchor=\"middle\" font-size=\"15\" font-weight=\"600\">")
        sb.append(xmlEscape(p.title)).append("</text>\n")
    }
    sb.append("<g transform=\"translate(").append(num(left)).append(' ').append(num(top)).append(")\">\n")

    // grid, ticks and labels
    val gridOpacity = "0.14"
    if (heat == null) {
        if (p.grid) {
            for (v in yAxis.tickValues) {
                val y = sy(v)
                sb.append("<line class=\"grid\" x1=\"0\" y1=\"").append(num(y)).append("\" x2=\"").append(num(pw)).append("\" y2=\"").append(num(y))
                sb.append("\" stroke=\"currentColor\" stroke-opacity=\"").append(gridOpacity).append("\"/>\n")
            }
            if (!catX) {
                for (v in xAxis.tickValues) {
                    val x = sx(v)
                    sb.append("<line class=\"grid\" x1=\"").append(num(x)).append("\" y1=\"0\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph))
                    sb.append("\" stroke=\"currentColor\" stroke-opacity=\"").append(gridOpacity).append("\"/>\n")
                }
            }
        }
        for (i in 0 until yAxis.tickValues.size) {
            val y = sy(yAxis.tickValues[i])
            sb.append("<text class=\"tick\" x=\"-8\" y=\"").append(num(y + 4.0)).append("\" text-anchor=\"end\">").append(xmlEscape(yAxis.tickLabels[i])).append("</text>\n")
        }
        for (i in 0 until xAxis.tickValues.size) {
            val x = sx(xAxis.tickValues[i])
            sb.append("<line x1=\"").append(num(x)).append("\" y1=\"").append(num(ph)).append("\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph + 4.0))
            sb.append("\" stroke=\"currentColor\" stroke-opacity=\"0.6\"/>\n")
            sb.append("<text class=\"tick\" x=\"").append(num(x)).append("\" y=\"").append(num(ph + 18.0)).append("\" text-anchor=\"middle\">").append(xmlEscape(xAxis.tickLabels[i])).append("</text>\n")
        }
    }

    // data, clipped to the plot area by a nested <svg>
    sb.append("<svg x=\"0\" y=\"0\" width=\"").append(num(pw)).append("\" height=\"").append(num(ph)).append("\" overflow=\"hidden\">\n")
    if (heat != null) {
        val rows = max(1, heat.rows)
        val cols = max(1, heat.cols)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (v in heat.values) {
            if (isFinite(v)) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        val cw = pw / cols.toDouble()
        val ch = ph / rows.toDouble()
        for (r in 0 until heat.rows) {
            for (c in 0 until heat.cols) {
                val idx = r * heat.cols + c
                if (idx >= heat.values.size) continue
                val v = heat.values[idx]
                if (!isFinite(v)) continue
                val t = if (hi > lo) (v - lo) / (hi - lo) else 0.5
                sb.append("<rect x=\"").append(num(c.toDouble() * cw)).append("\" y=\"").append(num(r.toDouble() * ch))
                sb.append("\" width=\"").append(num(cw + 0.4)).append("\" height=\"").append(num(ch + 0.4)).append("\" fill=\"").append(heatColor(t)).append("\"><title>")
                sb.append("[").append(r).append(", ").append(c).append("] = ").append(num(v)).append("</title></rect>\n")
            }
        }
    } else {
        var barSeries = 0
        var barIndex = 0
        for (s in p.series) if (s.kind == KIND_BAR) barSeries++
        for (s in p.series) {
            val n = min(s.xs.size, s.ys.size)
            if (s.kind == KIND_LINE) {
                val path = StringBuilder()
                var pen = false
                for (i in 0 until n) {
                    if (!isFinite(s.xs[i]) || !isFinite(s.ys[i])) {
                        pen = false
                        continue
                    }
                    path.append(if (pen) "L" else "M").append(num(sx(s.xs[i]))).append(' ').append(num(sy(s.ys[i]))).append(' ')
                    pen = true
                }
                if (path.isNotEmpty()) {
                    sb.append("<path d=\"").append(path.toString().trim()).append("\" fill=\"none\" stroke=\"").append(s.color)
                    sb.append("\" stroke-width=\"2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>\n")
                }
            } else if (s.kind == KIND_SCATTER) {
                for (i in 0 until n) {
                    if (!isFinite(s.xs[i]) || !isFinite(s.ys[i])) continue
                    sb.append("<circle cx=\"").append(num(sx(s.xs[i]))).append("\" cy=\"").append(num(sy(s.ys[i]))).append("\" r=\"3.2\" fill=\"").append(s.color).append("\" fill-opacity=\"0.8\"/>\n")
                }
            } else if (s.kind == KIND_BAR) {
                val slot = 0.8 / barSeries.toDouble()
                val y0 = sy(0.0)
                for (i in 0 until n) {
                    if (!isFinite(s.ys[i])) continue
                    val left0 = i.toDouble() - 0.4 + slot * barIndex.toDouble()
                    val x = sx(left0)
                    val bw = sx(left0 + slot) - x
                    val y = sy(s.ys[i])
                    val top0 = dmin(y, y0)
                    sb.append("<rect x=\"").append(num(x)).append("\" y=\"").append(num(top0)).append("\" width=\"").append(num(dmax(0.5, bw - 1.0))).append("\" height=\"").append(num(abs(y - y0)))
                    sb.append("\" fill=\"").append(s.color).append("\" fill-opacity=\"0.9\"/>\n")
                }
                barIndex++
            } else if (s.kind == KIND_HIST) {
                val y0 = sy(0.0)
                for (i in 0 until s.ys.size) {
                    if (i + 1 >= s.xs.size) break
                    val x = sx(s.xs[i])
                    val bw = sx(s.xs[i + 1]) - x
                    val y = sy(s.ys[i])
                    sb.append("<rect x=\"").append(num(x + 0.5)).append("\" y=\"").append(num(dmin(y, y0))).append("\" width=\"").append(num(dmax(0.5, bw - 1.0))).append("\" height=\"").append(num(abs(y - y0)))
                    sb.append("\" fill=\"").append(s.color).append("\" fill-opacity=\"0.85\"/>\n")
                }
            }
        }
    }
    sb.append("</svg>\n")

    // frame
    sb.append("<rect x=\"0\" y=\"0\" width=\"").append(num(pw)).append("\" height=\"").append(num(ph)).append("\" fill=\"none\" stroke=\"currentColor\" stroke-opacity=\"0.5\"/>\n")

    if (heat != null) {
        // index labels at the cell centres, thinned out
        val cols = max(1, heat.cols)
        val rows = max(1, heat.rows)
        val xs = max(1, (cols + xTarget * 2 - 1) / (xTarget * 2))
        val ys = max(1, (rows + yTarget * 2 - 1) / (yTarget * 2))
        var c = 0
        while (c < cols) {
            val x = (c.toDouble() + 0.5) * pw / cols.toDouble()
            sb.append("<text class=\"tick\" x=\"").append(num(x)).append("\" y=\"").append(num(ph + 18.0)).append("\" text-anchor=\"middle\">").append(c).append("</text>\n")
            c += xs
        }
        var r = 0
        while (r < rows) {
            val y = (r.toDouble() + 0.5) * ph / rows.toDouble()
            sb.append("<text class=\"tick\" x=\"-8\" y=\"").append(num(y + 4.0)).append("\" text-anchor=\"end\">").append(r).append("</text>\n")
            r += ys
        }
        // colour bar
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (v in heat.values) {
            if (isFinite(v)) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        if (lo > hi) {
            lo = 0.0
            hi = 1.0
        }
        val bx = pw + 14.0
        val steps = 32
        for (i in 0 until steps) {
            val t = 1.0 - (i.toDouble() + 0.5) / steps.toDouble()
            sb.append("<rect x=\"").append(num(bx)).append("\" y=\"").append(num(ph * i.toDouble() / steps.toDouble())).append("\" width=\"12\" height=\"").append(num(ph / steps.toDouble() + 0.4))
            sb.append("\" fill=\"").append(heatColor(t)).append("\"/>\n")
        }
        val ticks = niceTicks(lo, hi, 4)
        for (i in 0 until ticks.values.size) {
            val v = ticks.values[i]
            if (v < lo || v > hi) continue
            val y = if (hi > lo) ph - (v - lo) / (hi - lo) * ph else ph / 2.0
            sb.append("<text class=\"tick\" x=\"").append(num(bx + 17.0)).append("\" y=\"").append(num(y + 4.0)).append("\">").append(xmlEscape(tickLabel(v, ticks))).append("</text>\n")
        }
    }

    if (empty && heat == null) {
        sb.append("<text x=\"").append(num(pw / 2.0)).append("\" y=\"").append(num(ph / 2.0)).append("\" text-anchor=\"middle\" fill-opacity=\"0.6\">no data</text>\n")
    }

    // legend
    if (p.legend && heat == null) {
        var entries = 0
        var widest = 0.0
        for (s in p.series) {
            if (s.label.isNotEmpty()) {
                entries++
                widest = dmax(widest, textWidth(s.label, fs))
            }
        }
        if (entries > 0) {
            val bw = widest + 38.0
            val bh = entries.toDouble() * 18.0 + 8.0
            val bx = pw - bw - 8.0
            sb.append("<g class=\"legend\"><rect x=\"").append(num(bx)).append("\" y=\"8\" width=\"").append(num(bw)).append("\" height=\"").append(num(bh))
            sb.append("\" rx=\"4\" fill=\"currentColor\" fill-opacity=\"0.05\" stroke=\"currentColor\" stroke-opacity=\"0.3\"/>\n")
            var row = 0
            for (s in p.series) {
                if (s.label.isEmpty()) continue
                val cy = 8.0 + 4.0 + 9.0 + row.toDouble() * 18.0
                if (s.kind == KIND_LINE) {
                    sb.append("<line x1=\"").append(num(bx + 8.0)).append("\" y1=\"").append(num(cy)).append("\" x2=\"").append(num(bx + 26.0)).append("\" y2=\"").append(num(cy))
                    sb.append("\" stroke=\"").append(s.color).append("\" stroke-width=\"2\" stroke-linecap=\"round\"/>\n")
                } else if (s.kind == KIND_SCATTER) {
                    sb.append("<circle cx=\"").append(num(bx + 17.0)).append("\" cy=\"").append(num(cy)).append("\" r=\"3.2\" fill=\"").append(s.color).append("\"/>\n")
                } else {
                    sb.append("<rect x=\"").append(num(bx + 10.0)).append("\" y=\"").append(num(cy - 5.0)).append("\" width=\"14\" height=\"10\" fill=\"").append(s.color).append("\"/>\n")
                }
                sb.append("<text x=\"").append(num(bx + 32.0)).append("\" y=\"").append(num(cy + 4.0)).append("\">").append(xmlEscape(s.label)).append("</text>\n")
                row++
            }
            sb.append("</g>\n")
        }
    }

    // axis labels
    if (p.xlabel.isNotEmpty()) {
        sb.append("<text x=\"").append(num(pw / 2.0)).append("\" y=\"").append(num(ph + 40.0)).append("\" text-anchor=\"middle\">").append(xmlEscape(p.xlabel)).append("</text>\n")
    }
    if (p.ylabel.isNotEmpty()) {
        val cx = -maxLabel - 20.0
        sb.append("<text x=\"").append(num(cx)).append("\" y=\"").append(num(ph / 2.0)).append("\" text-anchor=\"middle\" transform=\"rotate(-90 ")
        sb.append(num(cx)).append(' ').append(num(ph / 2.0)).append(")\">").append(xmlEscape(p.ylabel)).append("</text>\n")
    }
    sb.append("</g>\n</svg>\n")
    return sb.toString()
}
