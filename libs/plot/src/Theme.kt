package mini.plot

// Built-in styles. "Chrome" (text, axes, grid) is `currentColor` with an opacity in the light styles, so the figure follows the text
// colour of the page it is shown in; the "dark" style paints its own background instead and is the same on any page.

internal class Theme(
    val name: String,
    val palette: List<String>,
    /** Background of the whole figure ("" = none, the page shows through). */
    val bg: String,
    /** Text and axes colour ("currentColor" follows the page). */
    val fg: String,
    /** Fill of the plot area ("" = none). */
    val panel: String,
    val gridColor: String,
    val gridOpacity: Double,
    /** 0 none, 1 bottom axis line, 2 bottom and left, 3 a box. */
    val axisLines: Int,
    val tickMarks: Boolean,
    /** "auto", "both", "x", "y" or "none". */
    val gridDefault: String,
    val dash: Boolean,
)

private val TABLEAU: List<String> = listOf("#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948", "#b07aa1", "#ff9da7", "#9c755f", "#bab0ac")
private val DEEP: List<String> = listOf("#4c72b0", "#dd8452", "#55a868", "#c44e52", "#8172b3", "#937860", "#da8bc3", "#8c8c8c", "#ccb974", "#64b5cd")
private val OKABE: List<String> = listOf("#2a8fd4", "#e69f00", "#009e73", "#d55e00", "#cc79a7", "#56b4e9", "#f0e442", "#999999")
private val BRIGHT: List<String> = listOf("#6ea8fe", "#ffa94d", "#69db7c", "#ff6b6b", "#b197fc", "#3bc9db", "#ffd43b", "#f783ac", "#a9e34b", "#adb5bd")

/** The names of the styles for `plt.style.use`. */
public val STYLES: List<String> = listOf("default", "dark", "minimal", "seaborn")

internal fun themeFor(name: String): Theme {
    if (name == "dark") return Theme("dark", BRIGHT, "#111827", "#d7dce4", "", "#ffffff", 0.09, 1, true, "auto", false)
    if (name == "minimal") return Theme("minimal", OKABE, "", "currentColor", "", "currentColor", 0.0, 0, false, "none", false)
    if (name == "seaborn") return Theme("seaborn", DEEP, "", "currentColor", "#eaeaf2", "#ffffff", 1.0, 0, false, "both", false)
    return Theme("default", TABLEAU, "", "currentColor", "", "currentColor", 0.1, 1, true, "auto", true)
}

/** A colour for an SVG attribute: the user's text with everything that could leave the attribute removed. */
internal fun safeColor(c: String): String {
    val sb = StringBuilder()
    for (ch in c) {
        if (ch != '"' && ch != '<' && ch != '>' && ch != '&' && ch != '\'' && ch != '\\' && ch != ';' && ch != ':') sb.append(ch)
    }
    return sb.toString().trim()
}

/** The colour for the user's [c]: `C0`..`C9` pick from the palette, `b g r c m y k w` are the matplotlib one-letter colours. */
internal fun resolveColor(c: String, th: Theme, auto: Int): String {
    if (c.isEmpty()) return th.palette[auto % th.palette.size]
    if (c.length >= 2 && c[0] == 'C' && c[1] >= '0' && c[1] <= '9') {
        var k = 0
        var i = 1
        while (i < c.length && c[i] >= '0' && c[i] <= '9') {
            k = k * 10 + (c[i] - '0')
            i++
        }
        return th.palette[k % th.palette.size]
    }
    if (c.length == 1) {
        val ch = c[0]
        if (ch == 'b') return th.palette[0]
        if (ch == 'g') return th.palette[4]
        if (ch == 'r') return th.palette[2]
        if (ch == 'c') return th.palette[3]
        if (ch == 'm') return th.palette[6]
        if (ch == 'y') return th.palette[5]
        if (ch == 'k') return th.fg
        if (ch == 'w') return "#ffffff"
    }
    if (c.startsWith("tab:")) {
        val n = c.substring(4)
        val names = listOf("blue", "orange", "red", "cyan", "green", "yellow", "purple", "pink", "brown", "gray")
        val i = names.indexOf(n)
        if (i >= 0) return th.palette[i % th.palette.size]
    }
    val s = safeColor(c)
    return if (s.isEmpty()) th.palette[auto % th.palette.size] else s
}
