package mini.frontend.driver

import mini.frontend.CstLeaf
import mini.frontend.CstNode
import mini.frontend.Outline
import mini.frontend.Tk
import mini.frontend.parseKotlin
import mini.frontend.printCst

/*
 * Driver of the B2 frontend check: parses a file with the pure-Kotlin frontend and describes the result as text
 * (errors, lossless check, counters and the declaration outline). The same source runs on the JVM (compiled by the real
 * Kotlin compiler, called from the tests) and natively (compiled by minic together with the frontend, see
 * `Main.kt`); both must print exactly the same text.
 */

private fun countNodes(n: CstNode): Int {
    var c = 1
    for (k in n.children) {
        if (k is CstNode) c += countNodes(k)
    }
    return c
}

/** Everything the driver prints for one file. */
fun describeFile(path: String, text: String): String {
    val r = parseKotlin(text)
    val sb = StringBuilder()
    sb.append("== ").append(path).append('\n')
    sb.append("errors: ").append(r.errors.size).append('\n')
    for (e in r.errors) sb.append("  ").append(e.line).append(':').append(e.col).append(": ").append(e.message).append('\n')
    var sig = 0
    for (t in r.tokens) {
        if (t.kind != Tk.WS && t.kind != Tk.EOF && t.kind != Tk.LINE_COMMENT && t.kind != Tk.BLOCK_COMMENT && t.kind != Tk.KDOC) sig++
    }
    sb.append("tokens: ").append(r.tokens.size).append(" (").append(sig).append(" significant)\n")
    sb.append("cst nodes: ").append(countNodes(r.root)).append('\n')
    val printed = printCst(r.root, r.tokens, text)
    sb.append("lossless: ").append(if (printed == text) "ok" else "FAILED").append('\n')
    val o = Outline()
    val lines = o.file(r.file)
    sb.append("declarations: ").append(o.decls).append(", expressions: ").append(o.exprs).append(", types: ").append(o.types).append('\n')
    for (l in lines) sb.append(l).append('\n')
    return sb.toString()
}

/** Number of lines of a text (for the throughput figures). */
fun countLines(text: String): Int {
    var n = 0
    var i = 0
    while (i < text.length) {
        if (text[i] == '\n') n++
        i++
    }
    return n
}
