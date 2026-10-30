package mini.display

/*
 * Rich output for notebooks and the playground. A program can hand a MIME bundle to the host that renders its output by
 * printing one escape sequence on stdout:
 *
 *     ESC ] 5379 ; mini-display ; <mime> ; <base64 of the UTF-8 data> BEL
 *
 * (ESC = 0x1B, BEL = 0x07; the receiver also accepts the string terminator ESC \ instead of BEL.) OSC is the terminal's
 * "operating system command"; terminals ignore the ones they do not know, so a plain terminal shows nothing. There is no
 * newline in the sequence: the text around it is untouched. The full specification is docs/display.md.
 */

/** Introducer of a display bundle: `ESC ] 5379 ; mini-display ;`. */
public const val DISPLAY_PREFIX: String = "\u001B]5379;mini-display;"

/** Terminator of a display bundle (BEL). */
public const val DISPLAY_SUFFIX: String = "\u0007"

public const val MIME_SVG: String = "image/svg+xml"
public const val MIME_HTML: String = "text/html"
public const val MIME_MARKDOWN: String = "text/markdown"
public const val MIME_TABLE: String = "application/vnd.mini.table+json"

/** The bytes to print for [data] of type [mime], as one string. */
public fun displayBundle(mime: String, data: String): String =
    DISPLAY_PREFIX + mime + ";" + base64OfUtf8(data) + DISPLAY_SUFFIX

/** Sends [data] of type [mime] to the host: prints one display bundle (no newline). */
public fun display(mime: String, data: String) {
    print(displayBundle(mime, data))
}

/** Shows an SVG document. */
public fun displaySvg(svg: String) {
    display(MIME_SVG, svg)
}

/** Shows HTML (the host renders it sandboxed, without scripts). */
public fun displayHtml(html: String) {
    display(MIME_HTML, html)
}

/** Shows Markdown. */
public fun displayMarkdown(markdown: String) {
    display(MIME_MARKDOWN, markdown)
}

/** Shows a table: [header] are the column titles, [rows] the cells (each row should have as many cells as the header). */
public fun displayTable(header: List<String>, rows: List<List<String>>) {
    val sb = StringBuilder()
    sb.append("{\"columns\":[")
    var i = 0
    while (i < header.size) {
        if (i > 0) sb.append(',')
        appendJsonString(sb, header[i])
        i++
    }
    sb.append("],\"rows\":[")
    var r = 0
    while (r < rows.size) {
        if (r > 0) sb.append(',')
        sb.append('[')
        val row = rows[r]
        var c = 0
        while (c < row.size) {
            if (c > 0) sb.append(',')
            appendJsonString(sb, row[c])
            c++
        }
        sb.append(']')
        r++
    }
    sb.append("]}")
    display(MIME_TABLE, sb.toString())
}

private fun appendJsonString(sb: StringBuilder, s: String) {
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '"') sb.append("\\\"")
        else if (ch == '\\') sb.append("\\\\")
        else if (ch == '\n') sb.append("\\n")
        else if (ch == '\r') sb.append("\\r")
        else if (ch == '\t') sb.append("\\t")
        else if (ch.code < 32) {
            sb.append("\\u00")
            sb.append(hexDigit(ch.code / 16))
            sb.append(hexDigit(ch.code % 16))
        } else sb.append(ch)
        i++
    }
    sb.append('"')
}

private fun hexDigit(n: Int): Char = if (n < 10) ('0'.code + n).toChar() else ('a'.code + n - 10).toChar()

private const val B64: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard Base64 (with padding) of the UTF-8 encoding of [text]. */
public fun base64OfUtf8(text: String): String {
    // UTF-8 bytes first (surrogate pairs become 4-byte sequences)
    val bytes = ArrayList<Int>()
    var i = 0
    while (i < text.length) {
        var cp = text[i].code
        i++
        if (cp in 0xD800..0xDBFF && i < text.length) {
            val lo = text[i].code
            if (lo in 0xDC00..0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) shl 10) + (lo - 0xDC00)
                i++
            }
        }
        if (cp < 0x80) bytes.add(cp)
        else if (cp < 0x800) {
            bytes.add(0xC0 or (cp shr 6))
            bytes.add(0x80 or (cp and 0x3F))
        } else if (cp < 0x10000) {
            bytes.add(0xE0 or (cp shr 12))
            bytes.add(0x80 or ((cp shr 6) and 0x3F))
            bytes.add(0x80 or (cp and 0x3F))
        } else {
            bytes.add(0xF0 or (cp shr 18))
            bytes.add(0x80 or ((cp shr 12) and 0x3F))
            bytes.add(0x80 or ((cp shr 6) and 0x3F))
            bytes.add(0x80 or (cp and 0x3F))
        }
    }
    val out = StringBuilder()
    var k = 0
    while (k < bytes.size) {
        val b0 = bytes[k]
        val b1 = if (k + 1 < bytes.size) bytes[k + 1] else 0
        val b2 = if (k + 2 < bytes.size) bytes[k + 2] else 0
        out.append(B64[b0 shr 2])
        out.append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
        out.append(if (k + 1 < bytes.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
        out.append(if (k + 2 < bytes.size) B64[b2 and 63] else '=')
        k += 3
    }
    return out.toString()
}
