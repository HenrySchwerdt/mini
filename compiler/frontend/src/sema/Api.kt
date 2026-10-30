package mini.frontend.sema

/** A source file given to the analysis. */
class SourceInput(val path: String, val text: String)

/**
 * Entry point of the semantic analysis: parses, collects and checks all [inputs] together (the standard library
 * sources are just more inputs) and returns the model with the per-expression records.
 */
fun analyzeSources(inputs: List<SourceInput>): Sema {
    val s = Sema()
    for (i in inputs) s.addFile(i.path, i.text)
    s.analyze()
    return s
}
