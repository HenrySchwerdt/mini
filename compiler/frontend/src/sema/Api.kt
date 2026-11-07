package mini.frontend.sema

/**
 * A source file given to the analysis. [declOnly]: an API stub of a precompiled library (docs/mlib.md): only its declarations are
 * analysed, the bodies that [mini.frontend.StubRules] says a stub does not keep are not checked. [parsed]: the tree of [text] when
 * it is already at hand (the stub cache, docs/mlib.md): the parse is skipped; it must be what the parser makes of [text] without errors.
 */
class SourceInput(val path: String, val text: String, val declOnly: Boolean = false, val parsed: mini.frontend.KtFile? = null)

/**
 * Entry point of the semantic analysis: parses, collects and checks all [inputs] together (the standard library
 * sources are just more inputs) and returns the model with the per-expression records.
 */
fun analyzeSources(inputs: List<SourceInput>): Sema {
    val s = Sema()
    for (i in inputs) s.addFile(i.path, i.text, i.declOnly, i.parsed)
    s.analyze()
    return s
}
