package mini.plot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// SVG rendering. Text, axes and grid are `currentColor` with an opacity in the light styles, so the figure follows the text colour of
// the page (light and dark theme); series use the palette of the style. Only elements and attributes of the website's allowlist
// are used (no <style>, no scripts, no filters), so the figure is inlined in the page. Hover texts are <title> elements.

private const val FS_TICK: Double = 12.0
private const val FS_LABEL: Double = 13.0
private const val FS_LEGEND: Double = 12.5

private class Rng {
    var lo: Double = Double.POSITIVE_INFINITY
    var hi: Double = Double.NEGATIVE_INFINITY

    fun add(v: Double, log: Boolean) {
        if (isFin(v) && (!log || v > 0.0)) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
    }

    val ok: Boolean get() = lo <= hi
}

/** An axis: the visible range, the scale, and where the ticks are (value and label). */
private class Ax(val lo: Double, val hi: Double, val log: Boolean, val inv: Boolean, val tv: List<Double>, val tl: List<String>) {
    fun frac(v: Double): Double {
        val f = if (log) (log10(v) - log10(lo)) / (log10(hi) - log10(lo)) else (v - lo) / (hi - lo)
        return if (inv) 1.0 - f else f
    }
}

private fun numAxis(lo0: Double, hi0: Double, target: Int, fixedLo: Double, fixedHi: Double, tight: Boolean, log: Boolean, format: String, custom: DoubleArray?, customLabels: List<String>): Ax {
    if (log) {
        val lo = if (lo0 > 0.0 && isFin(lo0)) lo0 else 1.0
        val hi = if (hi0 > 0.0 && isFin(hi0)) hi0 else 10.0
        var a = if (isFin(fixedLo) && fixedLo > 0.0) fixedLo else 10.0.pow(floor(log10(lo) + 1.0e-9))
        var b = if (isFin(fixedHi) && fixedHi > 0.0) fixedHi else 10.0.pow(ceil(log10(hi) - 1.0e-9))
        if (b <= a) b = a * 10.0
        val vals = ArrayList<Double>()
        val labels = ArrayList<String>()
        if (custom != null) {
            for (i in 0 until custom.size) {
                vals.add(custom[i])
                labels.add(if (i < customLabels.size) customLabels[i] else logLabel(custom[i]))
            }
        } else {
            for (v in logTickValues(a, b)) {
                if (v >= a * 0.999999 && v <= b * 1.000001) {
                    vals.add(v)
                    labels.add(logLabel(v))
                }
            }
        }
        return Ax(a, b, true, false, vals, labels)
    }
    val lo1 = if (isFin(fixedLo)) fixedLo else lo0
    val hi1 = if (isFin(fixedHi)) fixedHi else hi0
    val ticks = niceTicks(lo1, hi1, target)
    var a = if (tight) lo1 else ticks.first
    var b = if (tight) hi1 else ticks.last
    if (tight && a == b) {
        a = ticks.first
        b = ticks.last
    }
    if (isFin(fixedLo)) a = fixedLo
    if (isFin(fixedHi)) b = fixedHi
    if (a >= b) b = a + 1.0
    val vals = ArrayList<Double>()
    val eps = (b - a) * 1.0e-9
    if (custom != null) {
        for (v in custom) vals.add(v)
    } else {
        for (v in ticks.values) if (v >= a - eps && v <= b + eps) vals.add(v)
    }
    val labels = ArrayList<String>()
    val auto = formatTicks(vals, ticks, format)
    for (i in 0 until vals.size) labels.add(if (custom != null && i < customLabels.size) customLabels[i] else auto[i])
    return Ax(a, b, false, false, vals, labels)
}

private fun catAxis(n: Int, labels: List<String>, targetCount: Int, inv: Boolean): Ax {
    val vals = ArrayList<Double>()
    val tl = ArrayList<String>()
    val stride = max(1, (n + targetCount - 1) / max(1, targetCount))
    var i = 0
    while (i < n) {
        vals.add(i.toDouble())
        tl.add(if (i < labels.size) labels[i] else i.toString())
        i += stride
    }
    return Ax(-0.5, n.toDouble() - 0.5, false, inv, vals, tl)
}

private fun spacingOf(xs: DoubleArray): Double {
    var best = Double.POSITIVE_INFINITY
    for (i in 1 until xs.size) {
        val d = xs[i] - xs[i - 1]
        if (d > 0.0 && d < best) best = d
    }
    return if (best == Double.POSITIVE_INFINITY) 1.0 else best
}

private fun isIndex(xs: DoubleArray): Boolean {
    for (i in 0 until xs.size) if (xs[i] != i.toDouble()) return false
    return true
}

private fun dash(style: String, lw: Double): String {
    if (style == "--") return "6 4"
    if (style == "-.") return "7 3 1.5 3"
    if (style == ":") return "1.5 3.5"
    return ""
}

private fun attr(sb: StringBuilder, name: String, v: String) {
    sb.append(' ').append(name).append("=\"").append(v).append('"')
}

private fun numAttr(sb: StringBuilder, name: String, v: Double) {
    sb.append(' ').append(name).append("=\"").append(num(v)).append('"')
}

/** A rounded-top vertical bar from [yBase] to [yTip]. */
private fun barPath(x: Double, w: Double, yBase: Double, yTip: Double, r0: Double): String {
    val len = abs(yTip - yBase)
    val r = dmin(r0, dmin(w / 2.0, len / 2.0))
    val sb = StringBuilder()
    if (r < 0.3) {
        sb.append("M").append(num(x)).append(' ').append(num(yBase)).append(" L").append(num(x)).append(' ').append(num(yTip))
        sb.append(" L").append(num(x + w)).append(' ').append(num(yTip)).append(" L").append(num(x + w)).append(' ').append(num(yBase)).append("Z")
        return sb.toString()
    }
    val up = yTip < yBase
    val sweep = if (up) "1" else "0"
    val d = if (up) r else -r
    sb.append("M").append(num(x)).append(' ').append(num(yBase))
    sb.append(" L").append(num(x)).append(' ').append(num(yTip + d))
    sb.append(" A").append(num(r)).append(' ').append(num(r)).append(" 0 0 ").append(sweep).append(' ').append(num(x + r)).append(' ').append(num(yTip))
    sb.append(" L").append(num(x + w - r)).append(' ').append(num(yTip))
    sb.append(" A").append(num(r)).append(' ').append(num(r)).append(" 0 0 ").append(sweep).append(' ').append(num(x + w)).append(' ').append(num(yTip + d))
    sb.append(" L").append(num(x + w)).append(' ').append(num(yBase)).append("Z")
    return sb.toString()
}

/** A rounded-end horizontal bar from [xBase] to [xTip]. */
private fun barhPath(y: Double, h: Double, xBase: Double, xTip: Double, r0: Double): String {
    val len = abs(xTip - xBase)
    val r = dmin(r0, dmin(h / 2.0, len / 2.0))
    val sb = StringBuilder()
    if (r < 0.3) {
        sb.append("M").append(num(xBase)).append(' ').append(num(y)).append(" L").append(num(xTip)).append(' ').append(num(y))
        sb.append(" L").append(num(xTip)).append(' ').append(num(y + h)).append(" L").append(num(xBase)).append(' ').append(num(y + h)).append("Z")
        return sb.toString()
    }
    val right = xTip > xBase
    val sweep = if (right) "1" else "0"
    val d = if (right) -r else r
    sb.append("M").append(num(xBase)).append(' ').append(num(y))
    sb.append(" L").append(num(xTip + d)).append(' ').append(num(y))
    sb.append(" A").append(num(r)).append(' ').append(num(r)).append(" 0 0 ").append(sweep).append(' ').append(num(xTip)).append(' ').append(num(y + r))
    sb.append(" L").append(num(xTip)).append(' ').append(num(y + h - r))
    sb.append(" A").append(num(r)).append(' ').append(num(r)).append(" 0 0 ").append(sweep).append(' ').append(num(xTip + d)).append(' ').append(num(y + h))
    sb.append(" L").append(num(xBase)).append(' ').append(num(y + h)).append("Z")
    return sb.toString()
}

private fun markerSvg(shape: String, cx: Double, cy: Double, r: Double, color: String, opacity: String, ring: String, tip: String): String {
    val sb = StringBuilder()
    val close = if (tip.isEmpty()) "/>\n" else "><title>" + xmlEscape(tip) + "</title></" + "@@" + ">\n"
    var tag = "circle"
    if (shape == "x" || shape == "+") {
        tag = "path"
        sb.append("<path d=\"")
        if (shape == "x") {
            sb.append("M").append(num(cx - r)).append(' ').append(num(cy - r)).append(" L").append(num(cx + r)).append(' ').append(num(cy + r))
            sb.append(" M").append(num(cx + r)).append(' ').append(num(cy - r)).append(" L").append(num(cx - r)).append(' ').append(num(cy + r))
        } else {
            sb.append("M").append(num(cx - r)).append(' ').append(num(cy)).append(" L").append(num(cx + r)).append(' ').append(num(cy))
            sb.append(" M").append(num(cx)).append(' ').append(num(cy - r)).append(" L").append(num(cx)).append(' ').append(num(cy + r))
        }
        sb.append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" stroke-linecap=\"round\"")
    } else if (shape == "s") {
        tag = "rect"
        sb.append("<rect x=\"").append(num(cx - r)).append("\" y=\"").append(num(cy - r)).append("\" width=\"").append(num(2.0 * r)).append("\" height=\"").append(num(2.0 * r))
        sb.append("\" rx=\"1.5\" fill=\"").append(color).append("\"").append(opacity).append(" stroke=\"").append(ring).append("\" stroke-width=\"1\" stroke-opacity=\"0.6\"")
    } else if (shape == "^" || shape == "v" || shape == "D") {
        tag = "polygon"
        val rr = r * 1.25
        sb.append("<polygon points=\"")
        if (shape == "^") sb.append(num(cx)).append(',').append(num(cy - rr)).append(' ').append(num(cx + rr)).append(',').append(num(cy + rr * 0.8)).append(' ').append(num(cx - rr)).append(',').append(num(cy + rr * 0.8))
        else if (shape == "v") sb.append(num(cx)).append(',').append(num(cy + rr)).append(' ').append(num(cx + rr)).append(',').append(num(cy - rr * 0.8)).append(' ').append(num(cx - rr)).append(',').append(num(cy - rr * 0.8))
        else sb.append(num(cx)).append(',').append(num(cy - rr)).append(' ').append(num(cx + rr)).append(',').append(num(cy)).append(' ').append(num(cx)).append(',').append(num(cy + rr)).append(' ').append(num(cx - rr)).append(',').append(num(cy))
        sb.append("\" fill=\"").append(color).append("\"").append(opacity).append(" stroke=\"").append(ring).append("\" stroke-width=\"1\" stroke-opacity=\"0.6\" stroke-linejoin=\"round\"")
    } else {
        val rr = if (shape == ".") r * 0.55 else r
        sb.append("<circle cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" r=\"").append(num(rr))
        sb.append("\" fill=\"").append(color).append("\"").append(opacity).append(" stroke=\"").append(ring).append("\" stroke-width=\"1\" stroke-opacity=\"0.6\"")
    }
    sb.append(close.replace("@@", tag))
    return sb.toString()
}

private fun tooltip(label: String, x: Double, y: Double): String {
    val xy = "x = " + shortNum(x) + ", y = " + shortNum(y)
    return if (label.isEmpty()) xy else label + "\n" + xy
}

/** Whether an item takes part in the legend. */
private fun inLegend(it: Item): Boolean = it.label.isNotEmpty() && it.kind != KIND_TEXT && it.kind != KIND_ANNOT

private fun legendSwatch(sb: StringBuilder, it: Item, color: String, x: Double, cy: Double, th: Theme) {
    if (it.kind == KIND_LINE || it.kind == KIND_HLINE || it.kind == KIND_VLINE || (it.kind == KIND_ERR && it.linestyle.isNotEmpty())) {
        sb.append("<line x1=\"").append(num(x)).append("\" y1=\"").append(num(cy)).append("\" x2=\"").append(num(x + 18.0)).append("\" y2=\"").append(num(cy))
        sb.append("\" stroke=\"").append(color).append("\" stroke-width=\"").append(num(dmin(it.lw, 3.0))).append("\" stroke-linecap=\"round\"")
        val d = dash(it.linestyle, it.lw)
        if (d.isNotEmpty()) sb.append(" stroke-dasharray=\"").append(d).append("\"")
        sb.append("/>\n")
        if (it.kind == KIND_LINE && it.marker.isNotEmpty()) {
            sb.append(markerSvg(it.marker, x + 9.0, cy, 3.2, color, "", if (th.bg.isNotEmpty()) th.bg else "#ffffff", ""))
        }
    } else if (it.kind == KIND_SCATTER || it.kind == KIND_ERR) {
        sb.append(markerSvg(if (it.marker.isEmpty()) "o" else it.marker, x + 9.0, cy, 3.6, color, "", if (th.bg.isNotEmpty()) th.bg else "#ffffff", ""))
    } else {
        sb.append("<rect x=\"").append(num(x + 2.0)).append("\" y=\"").append(num(cy - 5.0)).append("\" width=\"14\" height=\"10\" rx=\"2.5\" fill=\"").append(color).append("\"")
        if (it.kind == KIND_FILL) sb.append(" fill-opacity=\"0.5\"")
        sb.append("/>\n")
    }
}

internal fun renderFigure(f: Figure): String {
    val th = themeFor(f.style)
    val w = if (f.width < 160) 160 else f.width
    val h = if (f.height < 100) 100 else f.height
    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h)
    sb.append("\" width=\"").append(w).append("\" height=\"").append(h)
    sb.append("\" role=\"img\" font-family=\"system-ui, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif\" font-size=\"12\" fill=\"").append(th.fg).append("\">")
    var label = f.suptitle
    if (label.isEmpty()) for (a in f.slots) if (a != null && label.isEmpty()) label = a.title
    sb.append("<title>").append(xmlEscape(if (label.isNotEmpty()) label else "Plot")).append("</title>\n")
    if (th.bg.isNotEmpty()) {
        sb.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" rx=\"10\" fill=\"").append(th.bg).append("\"/>\n")
    }
    var top = 0.0
    if (f.suptitle.isNotEmpty()) {
        sb.append("<text x=\"14\" y=\"26\" font-size=\"18\" font-weight=\"700\">").append(xmlEscape(f.suptitle)).append("</text>\n")
        top = 38.0
    }
    val cw = w.toDouble() / f.cols.toDouble()
    val ch = (h.toDouble() - top) / f.rows.toDouble()
    for (i in 0 until f.slots.size) {
        val a = f.slots[i]
        if (a == null) continue
        val r = i / f.cols
        val c = i % f.cols
        renderAxes(sb, a, c.toDouble() * cw, top + r.toDouble() * ch, cw, ch, th, f.single, "a" + i)
    }
    sb.append("</svg>\n")
    return sb.toString()
}

private fun renderAxes(sb: StringBuilder, a: Axes, ox: Double, oy: Double, W: Double, H: Double, th: Theme, single: Boolean, uid: String) {
    val heat = a.heat
    val fg = th.fg
    val ring = if (th.bg.isNotEmpty()) th.bg else "#ffffff"
    val xTarget = max(3, min(9, ((W - 70.0) / 70.0).toInt() + 1))
    val yTarget = max(3, min(8, ((H - 90.0) / 42.0).toInt() + 1))

    // ---- what is in the chart ----
    var anyBar = false
    var anyBarh = false
    var anyHist = false
    var anyScatter = false
    var anyLine = false
    var barCount = 0
    var barhCount = 0
    var nBarSeries = 0
    var nBarhSeries = 0
    var nHist = 0
    var stacked = false
    var allIndex = true
    var allIndexH = true
    val rx = Rng()
    val ry = Rng()
    for (it in a.items) {
        val n = min(it.xs.size, it.ys.size)
        if (it.kind == KIND_LINE || it.kind == KIND_SCATTER) {
            anyLine = anyLine || it.kind == KIND_LINE
            anyScatter = anyScatter || it.kind == KIND_SCATTER
            for (i in 0 until n) {
                rx.add(it.xs[i], a.xLog)
                ry.add(it.ys[i], a.yLog)
            }
        } else if (it.kind == KIND_FILL) {
            anyLine = true
            val y2 = it.ys2!!
            for (i in 0 until min(n, y2.size)) {
                rx.add(it.xs[i], a.xLog)
                ry.add(it.ys[i], a.yLog)
                ry.add(y2[i], a.yLog)
            }
        } else if (it.kind == KIND_ERR) {
            anyLine = true
            val e = it.ys2!!
            for (i in 0 until min(n, e.size)) {
                rx.add(it.xs[i], a.xLog)
                ry.add(it.ys[i] - e[i], a.yLog)
                ry.add(it.ys[i] + e[i], a.yLog)
            }
        } else if (it.kind == KIND_BAR) {
            anyBar = true
            nBarSeries++
            if (it.ys2 != null) stacked = true
            if (n > barCount) barCount = n
            if (!isIndex(it.xs)) allIndex = false
            val sp = spacingOf(it.xs)
            val b = it.ys2
            for (i in 0 until n) {
                val base = if (b != null && i < b.size) b[i] else 0.0
                ry.add(base + it.ys[i], a.yLog)
                ry.add(base, a.yLog)
                rx.add(it.xs[i] - sp / 2.0, a.xLog)
                rx.add(it.xs[i] + sp / 2.0, a.xLog)
            }
        } else if (it.kind == KIND_BARH) {
            anyBarh = true
            nBarhSeries++
            if (n > barhCount) barhCount = n
            if (!isIndex(it.xs)) allIndexH = false
            for (i in 0 until n) {
                rx.add(it.ys[i], a.xLog)
                rx.add(0.0, a.xLog)
                ry.add(it.xs[i] - 0.5, a.yLog)
                ry.add(it.xs[i] + 0.5, a.yLog)
            }
        } else if (it.kind == KIND_HIST) {
            anyHist = true
            nHist++
            for (v in it.xs) rx.add(v, a.xLog)
            for (v in it.ys) ry.add(v, a.yLog)
            ry.add(0.0, a.yLog)
        } else if (it.kind == KIND_HLINE) {
            ry.add(it.ys[0], a.yLog)
        } else if (it.kind == KIND_VLINE) {
            rx.add(it.xs[0], a.xLog)
        }
    }
    val empty = heat == null && !rx.ok && !ry.ok

    // ---- axes ----
    val xAx: Ax
    val yAx: Ax
    if (heat != null) {
        val cols = max(1, heat.cols)
        val rows = max(1, heat.rows)
        xAx = catAxis(cols, emptyList(), xTarget * 2, false)
        yAx = catAxis(rows, emptyList(), yTarget * 2, true)
    } else {
        var xmin = rx.lo
        var xmax = rx.hi
        var ymin = ry.lo
        var ymax = ry.hi
        if (anyBar || anyHist) {
            if (!a.yLog) {
                if (ymin > 0.0) ymin = 0.0
                if (ymax < 0.0) ymax = 0.0
                if (ymax > 0.0 && !isFin(a.yHi)) ymax += (ymax - ymin) * 0.04
            }
        }
        if (anyBarh && !a.xLog) {
            if (xmin > 0.0) xmin = 0.0
            if (xmax < 0.0) xmax = 0.0
            if (xmax > 0.0 && !isFin(a.xHi)) xmax += (xmax - xmin) * 0.04
        }
        if (ymin > ymax) {
            ymin = if (a.yLog) 1.0 else 0.0
            ymax = if (a.yLog) 10.0 else 1.0
        }
        if (xmin > xmax) {
            xmin = if (a.xLog) 1.0 else 0.0
            xmax = if (a.xLog) 10.0 else 1.0
        }
        if (anyScatter) {
            // keep the dots at the edge fully inside
            if (!a.xLog) {
                val pad = (xmax - xmin) * 0.04
                xmin -= pad
                xmax += pad
            }
            if (!a.yLog) {
                val pad = (ymax - ymin) * 0.04
                ymin -= pad
                ymax += pad
            }
        }
        val catX = anyBar && allIndex && (a.barLabels.isNotEmpty() || (!anyLine && !anyScatter && !anyHist && !anyBarh))
        val catY = anyBarh && allIndexH && !anyBar
        if (catX) {
            val cx = catAxis(barCount, a.barLabels, xTarget * 2, false)
            var lo = cx.lo
            var hi = cx.hi
            if (isFin(a.xLo)) lo = a.xLo
            if (isFin(a.xHi)) hi = a.xHi
            xAx = Ax(lo, hi, false, false, cx.tv, cx.tl)
        } else {
            val tight = (anyLine || anyHist) && !anyScatter && !anyBar && !anyBarh
            xAx = numAxis(xmin, xmax, xTarget, a.xLo, a.xHi, tight, a.xLog, a.xFormat, a.xTicks, a.xTickLabels)
        }
        if (catY) {
            val cy = catAxis(barhCount, a.barhLabels, yTarget * 2, true)
            var lo = cy.lo
            var hi = cy.hi
            if (isFin(a.yLo)) lo = a.yLo
            if (isFin(a.yHi)) hi = a.yHi
            yAx = Ax(lo, hi, false, true, cy.tv, cy.tl)
        } else {
            yAx = numAxis(ymin, ymax, yTarget, a.yLo, a.yHi, false, a.yLog, a.yFormat, a.yTicks, a.yTickLabels)
        }
    }
    // custom ticks on a category axis
    val xTv: List<Double>
    val xTl: List<String>
    val yTv: List<Double>
    val yTl: List<String>
    if (a.xTicks != null && (heat != null || (anyBar && allIndex && !anyLine))) {
        xTv = dlist(a.xTicks!!)
        xTl = if (a.xTickLabels.isNotEmpty()) a.xTickLabels else xTv.map { v -> shortNum(v) }
    } else {
        xTv = xAx.tv
        xTl = xAx.tl
    }
    if (a.yTicks != null && (heat != null || anyBarh)) {
        yTv = dlist(a.yTicks!!)
        yTl = if (a.yTickLabels.isNotEmpty()) a.yTickLabels else yTv.map { v -> shortNum(v) }
    } else {
        yTv = yAx.tv
        yTl = yAx.tl
    }

    // ---- legend entries ----
    val entries = ArrayList<Item>()
    if (a.legend && heat == null) for (it in a.items) if (inLegend(it)) entries.add(it)
    var legendW = 0.0
    var maxEntry = 0.0
    for (it in entries) {
        val tw = textWidth(it.label, FS_LEGEND)
        legendW += 26.0 + tw + 16.0
        if (tw > maxEntry) maxEntry = tw
    }

    // ---- layout ----
    var maxYLabel = 0.0
    for (l in yTl) maxYLabel = dmax(maxYLabel, textWidth(l, FS_TICK))
    val firstX = if (xTl.isNotEmpty()) textWidth(xTl[0], FS_TICK) / 2.0 else 0.0
    val lastX = if (xTl.isNotEmpty()) textWidth(xTl[xTl.size - 1], FS_TICK) / 2.0 else 0.0
    var left = dmax(maxYLabel + 14.0, firstX + 8.0)
    var right = dmax(14.0, lastX + 6.0)
    var hmin = 0.0
    var hmax = 1.0
    var cbTicks: Ticks? = null
    var cbLabels: List<String> = emptyList()
    if (heat != null) {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (v in heat.values) {
            if (isFin(v)) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        if (lo > hi) {
            lo = 0.0
            hi = 1.0
        }
        if (isFin(heat.vmin)) lo = heat.vmin
        if (isFin(heat.vmax)) hi = heat.vmax
        hmin = lo
        hmax = hi
        val t = niceTicks(lo, hi, 5)
        cbTicks = t
        val vals = ArrayList<Double>()
        for (v in t.values) if (v >= lo - 1.0e-9 * (hi - lo) && v <= hi + 1.0e-9 * (hi - lo)) vals.add(v)
        cbLabels = formatTicks(vals, t, "auto")
        var cbw = 0.0
        for (l in cbLabels) cbw = dmax(cbw, textWidth(l, FS_TICK))
        right = dmax(right, 14.0 + 12.0 + 6.0 + cbw + 8.0)
    }
    // legend: a row on top, the best corner inside, or a column on the right
    var mode = a.legendLoc
    var pwEstimate = W - left - right
    if (entries.isEmpty()) mode = "none"
    else if (mode == "auto" || mode == "top") {
        mode = if (legendW - 16.0 <= pwEstimate + 20.0) "top" else "best"
    }
    if (mode == "right") right += maxEntry + 26.0 + 22.0
    val pw = dmax(40.0, W - left - right)
    pwEstimate = pw

    // x tick labels: thin out or tilt when they do not fit
    var maxXLabel = 0.0
    for (l in xTl) maxXLabel = dmax(maxXLabel, textWidth(l, FS_TICK))
    var minGap = pw
    if (xTv.size > 1) {
        minGap = Double.POSITIVE_INFINITY
        for (i in 1 until xTv.size) {
            val g = abs(xAx.frac(xTv[i]) - xAx.frac(xTv[i - 1])) * pw
            if (g < minGap) minGap = g
        }
    }
    var rotate = false
    var xStride = 1
    if (maxXLabel + 8.0 > minGap) {
        var hasLetters = false
        for (l in xTl) for (c in l) if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetters = true
        if (hasLetters && minGap >= 14.0) rotate = true
        else xStride = ceil((maxXLabel + 8.0) / dmax(minGap, 1.0)).toInt()
    }
    val bottom = 8.0 + 20.0 + (if (rotate) maxXLabel * 0.5 + 4.0 else 0.0) + (if (a.xlabel.isNotEmpty()) 24.0 else 0.0)

    val titleSize = if (single) 17.0 else 15.0
    var cursor = 8.0
    var titleBase = 0.0
    var subBase = 0.0
    var legendY = 0.0
    var ylabBase = 0.0
    if (a.title.isNotEmpty()) {
        titleBase = cursor + titleSize * 0.95
        cursor += titleSize * 1.4
    }
    if (a.subtitle.isNotEmpty()) {
        subBase = cursor + 11.0
        cursor += 19.0
    }
    if (a.title.isNotEmpty() || a.subtitle.isNotEmpty()) cursor += 6.0
    if (mode == "top") {
        legendY = cursor + 10.0
        cursor += 28.0
    }
    if (a.ylabel.isNotEmpty()) {
        ylabBase = cursor + 10.0
        cursor += 22.0
    }
    val top = dmax(cursor, 12.0)
    val ph = dmax(30.0, H - top - bottom)

    fun sx(v: Double): Double = xAx.frac(v) * pw
    fun sy(v: Double): Double = (1.0 - yAx.frac(v)) * ph

    sb.append("<g transform=\"translate(").append(num(ox)).append(' ').append(num(oy)).append(")\">\n")

    // ---- title block ----
    if (a.title.isNotEmpty()) {
        sb.append("<text x=\"12\" y=\"").append(num(titleBase)).append("\" font-size=\"").append(num(titleSize)).append("\" font-weight=\"650\">").append(xmlEscape(a.title)).append("</text>\n")
    }
    if (a.subtitle.isNotEmpty()) {
        sb.append("<text x=\"12\" y=\"").append(num(subBase)).append("\" font-size=\"13\" fill-opacity=\"0.65\">").append(xmlEscape(a.subtitle)).append("</text>\n")
    }
    if (a.ylabel.isNotEmpty()) {
        sb.append("<text x=\"12\" y=\"").append(num(ylabBase)).append("\" font-size=\"").append(num(FS_LABEL)).append("\" fill-opacity=\"0.78\">").append(xmlEscape(a.ylabel)).append("</text>\n")
    }

    sb.append("<g transform=\"translate(").append(num(left)).append(' ').append(num(top)).append(")\">\n")
    if (th.panel.isNotEmpty()) {
        sb.append("<rect width=\"").append(num(pw)).append("\" height=\"").append(num(ph)).append("\" rx=\"3\" fill=\"").append(th.panel).append("\"/>\n")
    }

    // ---- grid ----
    var gridMode = if (a.gridAxis.isNotEmpty()) a.gridAxis else th.gridDefault
    if (gridMode == "auto") gridMode = if ((anyBar || anyHist) && !anyLine && !anyScatter) "y" else if (anyBarh && !anyLine) "x" else if (heat != null) "none" else "both"
    if (!a.grid) gridMode = "none"
    val gridStroke = "\" stroke=\"" + th.gridColor + "\" stroke-opacity=\"" + num(th.gridOpacity) + "\" shape-rendering=\"crispEdges\"/>\n"
    if (heat == null) {
        if (gridMode == "both" || gridMode == "y") {
            for (v in yTv) {
                val y = sy(v)
                sb.append("<line class=\"grid\" x1=\"0\" y1=\"").append(num(y)).append("\" x2=\"").append(num(pw)).append("\" y2=\"").append(num(y)).append(gridStroke)
            }
        }
        if (gridMode == "both" || gridMode == "x") {
            for (v in xTv) {
                val x = sx(v)
                sb.append("<line class=\"grid\" x1=\"").append(num(x)).append("\" y1=\"0\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph)).append(gridStroke)
            }
        }
        // the zero line
        if (!a.yLog && yAx.lo < 0.0 && yAx.hi > 0.0 && !anyBarh) {
            val y = sy(0.0)
            sb.append("<line x1=\"0\" y1=\"").append(num(y)).append("\" x2=\"").append(num(pw)).append("\" y2=\"").append(num(y)).append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.3\"/>\n")
        }
        if (!a.xLog && xAx.lo < 0.0 && xAx.hi > 0.0 && anyBarh) {
            val x = sx(0.0)
            sb.append("<line x1=\"").append(num(x)).append("\" y1=\"0\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph)).append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.3\"/>\n")
        }
    }

    // ---- data, clipped to the plot area by a nested <svg> (a little room so that strokes at the edge are not cut) ----
    val px = ArrayList<Double>()
    val py = ArrayList<Double>()
    sb.append("<svg x=\"-8\" y=\"-8\" width=\"").append(num(pw + 16.0)).append("\" height=\"").append(num(ph + 16.0)).append("\" overflow=\"hidden\">\n<g transform=\"translate(8 8)\">\n")
    if (heat != null) {
        val rows = max(1, heat.rows)
        val cols = max(1, heat.cols)
        val cw = pw / cols.toDouble()
        val chh = ph / rows.toDouble()
        val gap = if (cw > 16.0 && chh > 16.0) 1.5 else 0.0
        for (r in 0 until heat.rows) {
            for (c in 0 until heat.cols) {
                val idx = r * heat.cols + c
                if (idx >= heat.values.size) continue
                val v = heat.values[idx]
                if (!isFin(v)) continue
                val t = if (hmax > hmin) (v - hmin) / (hmax - hmin) else 0.5
                val col = cmapColor(heat.cmap, t)
                sb.append("<rect x=\"").append(num(c.toDouble() * cw + gap / 2.0)).append("\" y=\"").append(num(r.toDouble() * chh + gap / 2.0))
                sb.append("\" width=\"").append(num(cw - gap)).append("\" height=\"").append(num(chh - gap))
                if (gap > 0.0) sb.append("\" rx=\"2")
                sb.append("\" fill=\"").append(col).append("\"><title>")
                val rl = if (a.yTickLabels.size > r && a.yTicks != null) a.yTickLabels[r] else r.toString()
                val cl = if (a.xTickLabels.size > c && a.xTicks != null) a.xTickLabels[c] else c.toString()
                sb.append(xmlEscape(rl + ", " + cl + ": " + shortNum(v))).append("</title></rect>\n")
                if (heat.annot) {
                    val s = annotNum(v)
                    val fsz = dmin(13.0, (cw - 4.0) / (s.length.toDouble() * 0.6 + 0.01))
                    if (fsz >= 7.5 && chh >= fsz + 2.0) {
                        sb.append("<text x=\"").append(num(c.toDouble() * cw + cw / 2.0)).append("\" y=\"").append(num(r.toDouble() * chh + chh / 2.0 + fsz * 0.35))
                        sb.append("\" font-size=\"").append(num(fsz)).append("\" text-anchor=\"middle\" fill=\"").append(if (isLight(col)) "#111111" else "#ffffff").append("\">").append(xmlEscape(s)).append("</text>\n")
                    }
                }
            }
        }
    } else {
        var barIdx = 0
        var barhIdx = 0
        val groups = if (stacked) 1 else max(1, nBarSeries)
        val groupsH = max(1, nBarhSeries)
        for (it in a.items) {
            val n = min(it.xs.size, it.ys.size)
            val color = if (it.color.isEmpty() && it.auto < 0) fg else resolveColor(it.color, th, it.auto)
            val alphaAttr = if (it.alpha == it.alpha && it.alpha < 1.0) " stroke-opacity=\"" + num(it.alpha) + "\"" else ""
            if (it.kind == KIND_LINE) {
                val path = StringBuilder()
                var pen = false
                var lastX = 0.0
                var lastY = 0.0
                for (i in 0 until n) {
                    val xv = it.xs[i]
                    val yv = it.ys[i]
                    if (!isFin(xv) || !isFin(yv) || (a.xLog && xv <= 0.0) || (a.yLog && yv <= 0.0)) {
                        pen = false
                        continue
                    }
                    val x = sx(xv)
                    val y = sy(yv)
                    path.append(if (pen) "L" else "M").append(num(x)).append(' ').append(num(y)).append(' ')
                    if (pen) {
                        px.add((x + lastX) / 2.0)
                        py.add((y + lastY) / 2.0)
                    }
                    px.add(x)
                    py.add(y)
                    lastX = x
                    lastY = y
                    pen = true
                }
                if (path.isNotEmpty() && it.linestyle.isNotEmpty()) {
                    sb.append("<path d=\"").append(path.toString().trim()).append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(num(it.lw))
                    sb.append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"").append(alphaAttr)
                    val d = dash(it.linestyle, it.lw)
                    if (d.isNotEmpty()) sb.append(" stroke-dasharray=\"").append(d).append("\"")
                    sb.append("/>\n")
                }
                val showHit = n <= 150
                if (it.marker.isNotEmpty() || showHit) {
                    for (i in 0 until n) {
                        val xv = it.xs[i]
                        val yv = it.ys[i]
                        if (!isFin(xv) || !isFin(yv) || (a.xLog && xv <= 0.0) || (a.yLog && yv <= 0.0)) continue
                        if (it.marker.isNotEmpty()) {
                            sb.append(markerSvg(it.marker, sx(xv), sy(yv), 3.6, color, "", ring, tooltip(it.label, xv, yv)))
                        } else {
                            sb.append("<circle cx=\"").append(num(sx(xv))).append("\" cy=\"").append(num(sy(yv))).append("\" r=\"7\" fill=\"").append(color).append("\" fill-opacity=\"0\"><title>")
                            sb.append(xmlEscape(tooltip(it.label, xv, yv))).append("</title></circle>\n")
                        }
                    }
                }
            } else if (it.kind == KIND_SCATTER) {
                val r = dmax(1.5, sqrt(it.size) / 2.0)
                val op = " fill-opacity=\"" + num(if (it.alpha == it.alpha) it.alpha else 0.85) + "\""
                val tips = n <= 2000
                for (i in 0 until n) {
                    val xv = it.xs[i]
                    val yv = it.ys[i]
                    if (!isFin(xv) || !isFin(yv) || (a.xLog && xv <= 0.0) || (a.yLog && yv <= 0.0)) continue
                    val x = sx(xv)
                    val y = sy(yv)
                    px.add(x)
                    py.add(y)
                    sb.append(markerSvg(it.marker, x, y, r, color, op, ring, if (tips) tooltip(it.label, xv, yv) else ""))
                }
            } else if (it.kind == KIND_FILL) {
                val y2 = it.ys2!!
                val m = min(n, y2.size)
                val d = StringBuilder()
                var started = false
                for (i in 0 until m) {
                    if (!isFin(it.xs[i]) || !isFin(it.ys[i])) continue
                    d.append(if (started) "L" else "M").append(num(sx(it.xs[i]))).append(' ').append(num(sy(it.ys[i]))).append(' ')
                    started = true
                }
                var i = m - 1
                while (i >= 0) {
                    if (isFin(it.xs[i]) && isFin(y2[i])) d.append("L").append(num(sx(it.xs[i]))).append(' ').append(num(sy(y2[i]))).append(' ')
                    i--
                }
                if (started) {
                    sb.append("<path d=\"").append(d.toString().trim()).append(" Z\" fill=\"").append(color).append("\" fill-opacity=\"").append(num(if (it.alpha == it.alpha) it.alpha else 0.22)).append("\" stroke=\"none\"")
                    sb.append(if (it.label.isNotEmpty()) "><title>" + xmlEscape(it.label) + "</title></path>\n" else "/>\n")
                }
            } else if (it.kind == KIND_ERR) {
                val e = it.ys2!!
                val cap = it.size
                val lw = num(it.lw)
                if (it.linestyle.isNotEmpty()) {
                    val path = StringBuilder()
                    var pen = false
                    for (i in 0 until n) {
                        if (!isFin(it.xs[i]) || !isFin(it.ys[i])) {
                            pen = false
                            continue
                        }
                        path.append(if (pen) "L" else "M").append(num(sx(it.xs[i]))).append(' ').append(num(sy(it.ys[i]))).append(' ')
                        pen = true
                    }
                    sb.append("<path d=\"").append(path.toString().trim()).append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(lw).append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"")
                    val d = dash(it.linestyle, it.lw)
                    if (d.isNotEmpty()) sb.append(" stroke-dasharray=\"").append(d).append("\"")
                    sb.append("/>\n")
                }
                for (i in 0 until min(n, e.size)) {
                    if (!isFin(it.xs[i]) || !isFin(it.ys[i]) || !isFin(e[i])) continue
                    val x = sx(it.xs[i])
                    val y0 = sy(it.ys[i] - e[i])
                    val y1 = sy(it.ys[i] + e[i])
                    px.add(x)
                    py.add(sy(it.ys[i]))
                    sb.append("<path d=\"M").append(num(x)).append(' ').append(num(y0)).append(" L").append(num(x)).append(' ').append(num(y1))
                    sb.append(" M").append(num(x - cap)).append(' ').append(num(y0)).append(" L").append(num(x + cap)).append(' ').append(num(y0))
                    sb.append(" M").append(num(x - cap)).append(' ').append(num(y1)).append(" L").append(num(x + cap)).append(' ').append(num(y1))
                    sb.append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(lw).append("\" stroke-linecap=\"round\"/>\n")
                    if (it.marker.isNotEmpty()) {
                        sb.append(markerSvg(it.marker, x, sy(it.ys[i]), 3.8, color, "", ring, tooltip(it.label, it.xs[i], it.ys[i]) + " ± " + shortNum(e[i])))
                    }
                }
            } else if (it.kind == KIND_BAR) {
                val sp = spacingOf(it.xs)
                val widthData = it.size * sp
                val slot = widthData / groups.toDouble()
                val slotIdx = if (stacked) 0 else barIdx
                val b = it.ys2
                val gapPx = if (groups > 1) 2.0 else 0.0
                val op = if (it.alpha == it.alpha && it.alpha < 1.0) " fill-opacity=\"" + num(it.alpha) + "\"" else ""
                for (i in 0 until n) {
                    val h = it.ys[i]
                    if (!isFin(h)) continue
                    var base = if (b != null && i < b.size) b[i] else 0.0
                    if (a.yLog && base <= 0.0) base = yAx.lo
                    if (!a.yLog) {
                        if (base < yAx.lo) base = yAx.lo
                        if (base > yAx.hi) base = yAx.hi
                    }
                    val tip = (if (b != null && i < b.size) b[i] else 0.0) + h
                    if (a.yLog && tip <= 0.0) continue
                    val left0 = it.xs[i] - widthData / 2.0 + slot * slotIdx.toDouble()
                    val x0 = sx(left0)
                    val bw = sx(left0 + slot) - x0
                    val wpx = dmax(1.0, if (bw > 8.0) bw - gapPx else bw)
                    val xx = x0 + (bw - wpx) / 2.0
                    val yb = sy(base)
                    val yt = sy(tip)
                    px.add(xx)
                    py.add(yt)
                    px.add(xx + wpx / 2.0)
                    py.add(yt)
                    px.add(xx + wpx)
                    py.add(yt)
                    val cat = if (a.barLabels.size > i) a.barLabels[i] else shortNum(it.xs[i])
                    val tt = (if (it.label.isNotEmpty()) it.label + "\n" else "") + cat + ": " + shortNum(h)
                    sb.append("<path d=\"").append(barPath(xx, wpx, yb, yt, if (stacked) 0.0 else 4.0)).append("\" fill=\"").append(color).append("\"").append(op).append("><title>").append(xmlEscape(tt)).append("</title></path>\n")
                }
                barIdx++
            } else if (it.kind == KIND_BARH) {
                val slot = it.size / groupsH.toDouble()
                val op = if (it.alpha == it.alpha && it.alpha < 1.0) " fill-opacity=\"" + num(it.alpha) + "\"" else ""
                for (i in 0 until n) {
                    val v = it.ys[i]
                    if (!isFin(v) || (a.xLog && v <= 0.0)) continue
                    val top0 = it.xs[i] - it.size / 2.0 + slot * barhIdx.toDouble()
                    val ya = sy(top0)
                    val yb = sy(top0 + slot)
                    val hpx = dmax(1.0, abs(yb - ya) - (if (groupsH > 1) 2.0 else 0.0))
                    val yy = dmin(ya, yb) + (abs(yb - ya) - hpx) / 2.0
                    val xb = if (a.xLog) 0.0 else sx(if (xAx.lo > 0.0) xAx.lo else if (xAx.hi < 0.0) xAx.hi else 0.0)
                    val xt = sx(v)
                    px.add(xt)
                    py.add(yy + hpx / 2.0)
                    val cat = if (a.barhLabels.size > i) a.barhLabels[i] else shortNum(it.xs[i])
                    val tt = (if (it.label.isNotEmpty()) it.label + "\n" else "") + cat + ": " + shortNum(v)
                    sb.append("<path d=\"").append(barhPath(yy, hpx, xb, xt, 4.0)).append("\" fill=\"").append(color).append("\"").append(op).append("><title>").append(xmlEscape(tt)).append("</title></path>\n")
                }
                barhIdx++
            } else if (it.kind == KIND_HIST) {
                val op = " fill-opacity=\"" + num(if (it.alpha == it.alpha) it.alpha else if (nHist > 1) 0.6 else 0.9) + "\""
                val y0 = sy(if (a.yLog) yAx.lo else 0.0)
                for (i in 0 until it.ys.size) {
                    if (i + 1 >= it.xs.size) break
                    if (a.yLog && it.ys[i] <= 0.0) continue
                    val x = sx(it.xs[i])
                    val bw = sx(it.xs[i + 1]) - x
                    val y = sy(it.ys[i])
                    px.add(x + bw / 2.0)
                    py.add(y)
                    val tt = (if (it.label.isNotEmpty()) it.label + "\n" else "") + shortNum(it.xs[i]) + " to " + shortNum(it.xs[i + 1]) + ": " + shortNum(it.ys[i])
                    sb.append("<path d=\"").append(barPath(x + 0.5, dmax(0.5, bw - 1.0), y0, y, 3.0)).append("\" fill=\"").append(color).append("\"").append(op).append("><title>").append(xmlEscape(tt)).append("</title></path>\n")
                }
            } else if (it.kind == KIND_HLINE || it.kind == KIND_VLINE) {
                val c2 = if (it.color.isEmpty()) fg else color
                val op = if (it.color.isEmpty()) " stroke-opacity=\"0.55\"" else ""
                val hz = it.kind == KIND_HLINE
                val v = if (hz) it.ys[0] else it.xs[0]
                if (isFin(v) && !((if (hz) a.yLog else a.xLog) && v <= 0.0)) {
                    if (hz) {
                        val y = sy(v)
                        sb.append("<line x1=\"0\" y1=\"").append(num(y)).append("\" x2=\"").append(num(pw)).append("\" y2=\"").append(num(y))
                    } else {
                        val x = sx(v)
                        sb.append("<line x1=\"").append(num(x)).append("\" y1=\"0\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph))
                    }
                    sb.append("\" stroke=\"").append(c2).append("\" stroke-width=\"").append(num(it.lw)).append("\"").append(op)
                    val d = dash(it.linestyle, it.lw)
                    if (d.isNotEmpty()) sb.append(" stroke-dasharray=\"").append(d).append("\"")
                    sb.append(" stroke-linecap=\"round\"/>\n")
                }
            }
        }
    }
    sb.append("</g>\n</svg>\n")

    // ---- annotations (not clipped) ----
    for (it in a.items) {
        if (it.kind != KIND_TEXT && it.kind != KIND_ANNOT) continue
        val x = sx(it.xs[0])
        val y = sy(it.ys[0])
        val col = if (it.color.isEmpty()) fg else resolveColor(it.color, th, 0)
        if (it.kind == KIND_ANNOT && it.marker == "arrow") {
            val tx = sx(it.x2)
            val ty = sy(it.y2)
            val sx0 = x + (if (it.anchor == "start") -3.0 else if (it.anchor == "end") 3.0 else 0.0)
            val sy0 = y - it.size * 0.35
            var dx = tx - sx0
            var dy = ty - sy0
            val len = sqrt(dx * dx + dy * dy)
            if (len > 8.0) {
                dx /= len
                dy /= len
                val ex = tx - dx * 3.0
                val ey = ty - dy * 3.0
                sb.append("<line x1=\"").append(num(sx0)).append("\" y1=\"").append(num(sy0)).append("\" x2=\"").append(num(ex - dx * 5.0)).append("\" y2=\"").append(num(ey - dy * 5.0))
                sb.append("\" stroke=\"").append(col).append("\" stroke-opacity=\"0.7\" stroke-width=\"1.4\" stroke-linecap=\"round\"/>\n")
                val bx = ex - dx * 7.0
                val by = ey - dy * 7.0
                sb.append("<polygon points=\"").append(num(ex)).append(',').append(num(ey)).append(' ').append(num(bx - dy * 3.2)).append(',').append(num(by + dx * 3.2)).append(' ')
                sb.append(num(bx + dy * 3.2)).append(',').append(num(by - dx * 3.2)).append("\" fill=\"").append(col).append("\" fill-opacity=\"0.75\"/>\n")
            }
        }
        sb.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" font-size=\"").append(num(it.size)).append("\" text-anchor=\"").append(it.anchor).append("\" fill=\"").append(col).append("\">").append(xmlEscape(it.text)).append("</text>\n")
    }

    // ---- axis lines, ticks, tick labels ----
    if (th.axisLines >= 3) {
        sb.append("<rect width=\"").append(num(pw)).append("\" height=\"").append(num(ph)).append("\" fill=\"none\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.4\"/>\n")
    } else {
        if (th.axisLines >= 1) sb.append("<line x1=\"0\" y1=\"").append(num(ph)).append("\" x2=\"").append(num(pw)).append("\" y2=\"").append(num(ph)).append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.4\" shape-rendering=\"crispEdges\"/>\n")
        if (th.axisLines >= 2) sb.append("<line x1=\"0\" y1=\"0\" x2=\"0\" y2=\"").append(num(ph)).append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.4\" shape-rendering=\"crispEdges\"/>\n")
    }
    for (i in 0 until yTv.size) {
        val y = sy(yTv[i])
        if (y < -1.0 || y > ph + 1.0) continue
        sb.append("<text class=\"tick\" x=\"-8\" y=\"").append(num(y + 4.0)).append("\" text-anchor=\"end\" fill-opacity=\"0.72\">").append(xmlEscape(yTl[i])).append("</text>\n")
    }
    for (i in 0 until xTv.size) {
        val x = sx(xTv[i])
        if (x < -1.0 || x > pw + 1.0) continue
        if (th.tickMarks && th.axisLines >= 1) {
            sb.append("<line x1=\"").append(num(x)).append("\" y1=\"").append(num(ph)).append("\" x2=\"").append(num(x)).append("\" y2=\"").append(num(ph + 4.0)).append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.4\" shape-rendering=\"crispEdges\"/>\n")
        }
        if (i % xStride != 0) continue
        if (rotate) {
            sb.append("<text class=\"tick\" x=\"").append(num(x + 4.0)).append("\" y=\"").append(num(ph + 16.0)).append("\" text-anchor=\"end\" fill-opacity=\"0.72\" transform=\"rotate(-30 ").append(num(x + 4.0)).append(' ').append(num(ph + 16.0)).append(")\">")
        } else {
            sb.append("<text class=\"tick\" x=\"").append(num(x)).append("\" y=\"").append(num(ph + 17.0)).append("\" text-anchor=\"middle\" fill-opacity=\"0.72\">")
        }
        sb.append(xmlEscape(xTl[i])).append("</text>\n")
    }
    if (a.xlabel.isNotEmpty()) {
        sb.append("<text x=\"").append(num(pw / 2.0)).append("\" y=\"").append(num(ph + bottom - 8.0)).append("\" font-size=\"").append(num(FS_LABEL)).append("\" text-anchor=\"middle\" fill-opacity=\"0.78\">").append(xmlEscape(a.xlabel)).append("</text>\n")
    }

    if (empty) {
        sb.append("<text x=\"").append(num(pw / 2.0)).append("\" y=\"").append(num(ph / 2.0)).append("\" text-anchor=\"middle\" fill-opacity=\"0.6\">no data</text>\n")
    }

    // ---- colour bar ----
    if (heat != null) {
        val gid = uid + "cb"
        val stops = cmapGradient(heat.cmap)
        sb.append("<defs><linearGradient id=\"").append(gid).append("\" x1=\"0\" y1=\"1\" x2=\"0\" y2=\"0\">")
        for (i in 0 until stops.size) {
            sb.append("<stop offset=\"").append(num(i.toDouble() / (stops.size - 1).toDouble())).append("\" stop-color=\"").append(stops[i]).append("\"/>")
        }
        sb.append("</linearGradient></defs>\n")
        val bx = pw + 14.0
        sb.append("<rect x=\"").append(num(bx)).append("\" y=\"0\" width=\"12\" height=\"").append(num(ph)).append("\" rx=\"3\" fill=\"url(#").append(gid).append(")\"/>\n")
        val t = cbTicks
        if (t != null) {
            var k = 0
            for (v in t.values) {
                if (v < hmin - 1.0e-9 * (hmax - hmin) || v > hmax + 1.0e-9 * (hmax - hmin)) continue
                val y = if (hmax > hmin) ph - (v - hmin) / (hmax - hmin) * ph else ph / 2.0
                sb.append("<text class=\"tick\" x=\"").append(num(bx + 18.0)).append("\" y=\"").append(num(y + 4.0)).append("\" fill-opacity=\"0.72\">").append(xmlEscape(cbLabels[k])).append("</text>\n")
                k++
            }
        }
    }

    // ---- legend ----
    if (mode != "none") {
        val lh = entries.size.toDouble() * 20.0 + 10.0
        val lw = maxEntry + 26.0 + 14.0
        if (mode == "top") {
            var x = 2.0
            sb.append("<g class=\"legend\">\n")
            for (it in entries) {
                val color = if (it.color.isEmpty() && it.auto < 0) fg else resolveColor(it.color, th, it.auto)
                legendSwatch(sb, it, color, x, legendY - top, th)
                sb.append("<text x=\"").append(num(x + 24.0)).append("\" y=\"").append(num(legendY - top + 4.5)).append("\" font-size=\"").append(num(FS_LEGEND)).append("\">").append(xmlEscape(it.label)).append("</text>\n")
                x += 26.0 + textWidth(it.label, FS_LEGEND) + 16.0
            }
            sb.append("</g>\n")
        } else {
            var bx: Double
            var by: Double
            if (mode == "right") {
                bx = pw + 16.0
                by = 0.0
            } else {
                // the corner with the least data under it
                var bestScore = 1000000
                bx = pw - lw - 8.0
                by = 8.0
                val corners = if (mode == "upper right") 0 else if (mode == "upper left") 1 else if (mode == "lower right") 2 else if (mode == "lower left") 3 else -1
                for (k in 0 until 4) {
                    if (corners >= 0 && k != corners) continue
                    val cx = if (k == 0 || k == 2) pw - lw - 8.0 else 8.0
                    val cy = if (k == 0 || k == 1) 8.0 else ph - lh - 8.0
                    var score = 0
                    for (j in 0 until px.size) {
                        if (px[j] >= cx - 6.0 && px[j] <= cx + lw + 6.0 && py[j] >= cy - 6.0 && py[j] <= cy + lh + 6.0) score++
                    }
                    if (score < bestScore) {
                        bestScore = score
                        bx = cx
                        by = cy
                    }
                }
            }
            sb.append("<g class=\"legend\"><rect x=\"").append(num(bx)).append("\" y=\"").append(num(by)).append("\" width=\"").append(num(lw)).append("\" height=\"").append(num(lh))
            sb.append("\" rx=\"6\" fill=\"").append(if (th.bg.isNotEmpty()) th.bg else "#ffffff").append("\" fill-opacity=\"").append(if (th.bg.isNotEmpty()) "0.85" else "0.12").append("\" stroke=\"").append(fg).append("\" stroke-opacity=\"0.18\"/>\n")
            var row = 0
            for (it in entries) {
                val color = if (it.color.isEmpty() && it.auto < 0) fg else resolveColor(it.color, th, it.auto)
                val cy = by + 5.0 + 10.0 + row.toDouble() * 20.0
                legendSwatch(sb, it, color, bx + 8.0, cy, th)
                sb.append("<text x=\"").append(num(bx + 32.0)).append("\" y=\"").append(num(cy + 4.5)).append("\" font-size=\"").append(num(FS_LEGEND)).append("\">").append(xmlEscape(it.label)).append("</text>\n")
                row++
            }
            sb.append("</g>\n")
        }
    }
    sb.append("</g>\n</g>\n")
}
