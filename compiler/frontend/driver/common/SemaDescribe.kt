package mini.frontend.driver

import mini.frontend.sema.SourceInput
import mini.frontend.sema.analyzeSources
import mini.frontend.sema.dumpText

/*
 * Driver of the B2-2 semantic analysis check: analyses a set of files together (the standard library sources are just
 * more inputs) and describes the result for the files in `report`: one line per resolved position (range, node kind,
 * type, callee) and the diagnostics. The same source runs on the JVM and, compiled by minic, natively
 * (see `native/Main.kt` and SemaSelfHostTest); both must print exactly the same text.
 */
fun describeSema(inputs: List<SourceInput>, report: Set<String>): String {
    val sema = analyzeSources(inputs)
    val sb = StringBuilder()
    for (f in sema.files) {
        if (!report.contains(f.path)) continue
        sb.append("== ").append(f.path).append('\n')
        sb.append(sema.dumpText(f))
        for (d in sema.diags) {
            if (d.path == f.path) sb.append("diag ").append(d.start).append(": ").append(d.message).append('\n')
        }
    }
    return sb.toString()
}
