package mini.frontend.sema

/**
 * A source file given to the analysis. [declOnly]: an API stub of a precompiled library (docs/mlib.md): only its declarations are
 * analysed, the bodies that [mini.frontend.StubRules] says a stub does not keep are not checked.
 */
class SourceInput(val path: String, val text: String, val declOnly: Boolean = false)

/**
 * Entry point of the semantic analysis: parses, collects and checks all [inputs] together (the standard library
 * sources are just more inputs) and returns the model with the per-expression records.
 */
fun analyzeSources(inputs: List<SourceInput>): Sema {
    val s = Sema()
    for (i in inputs) s.addFile(i.path, i.text, i.declOnly)
    s.analyze()
    return s
}
