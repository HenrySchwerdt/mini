package mini.frontend

/*
 * API stubs of precompiled libraries (docs/mlib.md): a source file with the bodies of its declarations blanked out, so
 * that a consumer can parse and analyse the declarations of a library without paying for its code. Lines and columns are
 * preserved exactly (a stripped region keeps its newlines, and the text after it keeps its column), so diagnostics and
 * debuggers point at the real library source.
 *
 * What a stub keeps is decided by [StubRules], shared by the writer (here), the semantic analysis (which does not check
 * what the stub does not keep) and the IR builder (which does not build it): a function body is kept when the function is
 * `inline` (the consumer expands it at every call site) or has an expression body without a declared return type (the
 * signature is inferred from it); a property initializer when the property is `const`, has no declared type or is
 * delegated; accessor bodies when the type of the property is inferred or the accessor is `inline`.
 */
object StubRules {
    fun keepsBody(d: FunDecl): Boolean {
        if (d.mods != null && d.mods.has("inline")) return true
        return d.expressionBody && d.returnType == null
    }

    fun keepsInitializer(d: PropertyDecl): Boolean {
        if (d.type == null || d.delegate != null) return true
        return d.mods != null && (d.mods.has("const") || d.mods.has("inline"))
    }

    fun keepsAccessors(d: PropertyDecl): Boolean {
        if (d.type == null || d.delegate != null) return true
        return d.mods != null && d.mods.has("inline")
    }
}

object StubWriter {
    /** The stub of [text]; [text] itself when it has syntax errors (the analysis then reports them in the original). */
    fun stub(text: String): String {
        val r = parseKotlinUnquoted(text)
        if (r.errors.isNotEmpty()) return text
        val cuts = ArrayList<IntArray>()   // [start, end, kind]: kind 0 = block `{}`, 1 = expression `0`
        for (d in r.file.declarations) collect(d, cuts)
        if (cuts.isEmpty()) return text
        cuts.sortBy { it[0] }
        val sb = StringBuilder(text.length)
        var pos = 0
        for (c in cuts) {
            if (c[0] < pos) continue
            sb.append(text.substring(pos, c[0]))
            blank(text, c[0], c[1], c[2] == 0, sb)
            pos = c[1]
        }
        sb.append(text.substring(pos))
        return sb.toString()
    }

    /** Appends a replacement for text[from, to) with the same newlines and the same column for what follows. */
    private fun blank(text: String, from: Int, to: Int, block: Boolean, sb: StringBuilder) {
        var lastNl = -1
        var nl = 0
        for (i in from until to) if (text[i] == '\n') { nl++; lastNl = i }
        sb.append(if (block) '{' else '0')
        if (nl == 0) {
            for (i in 0 until to - from - (if (block) 2 else 1)) sb.append(' ')
            if (block) sb.append('}')
            return
        }
        for (i in 0 until nl) sb.append('\n')
        val tail = to - lastNl - 1   // characters of the last line that belong to the replaced region
        for (i in 0 until tail - (if (block) 1 else 0)) sb.append(' ')
        if (block) sb.append('}')
    }

    private fun cut(e: Expr?, block: Boolean, cuts: ArrayList<IntArray>) {
        if (e == null) return
        // a body of length 1 (`{` alone cannot happen; `0` is already minimal) needs no replacement
        if (e.end - e.start < 2 && !block) return
        cuts.add(intArrayOf(e.start, e.end, if (block) 0 else 1))
    }

    private fun collect(d: Decl, cuts: ArrayList<IntArray>) {
        when (d) {
            is FunDecl -> if (d.body != null && !StubRules.keepsBody(d)) cut(d.body, !d.expressionBody, cuts)
            is PropertyDecl -> {
                if (!StubRules.keepsInitializer(d)) cut(d.initializer, false, cuts)
                if (!StubRules.keepsAccessors(d)) {
                    val g = d.getter
                    if (g != null && g.body != null) cut(g.body, !g.expressionBody, cuts)
                    val s = d.setter
                    if (s != null && s.body != null) cut(s.body, !s.expressionBody, cuts)
                }
            }
            is InitDecl -> cut(d.body, true, cuts)
            is SecondaryCtor -> cut(d.body, true, cuts)
            is ClassDecl -> {
                for (m in d.members) collect(m, cuts)
                for (en in d.enumEntries) {
                    val ms = en.members
                    if (ms != null) for (m in ms) collect(m, cuts)
                }
            }
            else -> {}
        }
    }
}
