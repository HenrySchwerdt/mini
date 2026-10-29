import mini.frontend.sema.dumpText
import org.junit.jupiter.api.Test
import java.io.File

/** Developer tool: dumps the K2 FIR resolution of the file named by MINI_FIR_PROBE (skipped otherwise); MINI_SEMA_COMPARE=1 compares with ours. */
class SemaProbeTest {
    @Test
    fun probe() {
        val path = System.getenv("MINI_FIR_PROBE") ?: return
        val f = File(path).absoluteFile
        val stdlib = File(Corpus.repoRoot, "stdlib").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        if (System.getenv("MINI_SEMA_DUMP") != null) {
            val sema = mini.frontend.sema.analyzeSources((stdlib + f).map { mini.frontend.sema.SourceInput(it.absolutePath, it.readText()) })
            val fi = sema.files.last()
            val text = f.readText()
            for (l in sema.dumpText(fi).lines()) {
                val m = Regex("^(\\d+)-(\\d+) ").find(l)
                if (m != null) println(l + "   `" + text.substring(m.groupValues[1].toInt(), minOf(m.groupValues[2].toInt(), m.groupValues[1].toInt() + 40)).replace("\n", "\\n") + "`") else println(l)
            }
            println("DIAGS: " + sema.diags.filter { it.path == f.absolutePath }.map { "${it.start}: ${it.message}" })
            return
        }
        if (System.getenv("MINI_SEMA_COMPARE") != null) {
            val (stats, errs) = SemaOracle.run(stdlib + f, listOf(f))
            println("K2/OURS DIAGNOSTICS: " + errs.take(40))
            for ((p, st) in stats) {
                println("COMPARE ${File(p).name}: ${st.matched}/${st.total} (${"%.1f".format(st.pct())}%), missing ${st.missing}, by category " + st.byCategory.map { "${it.key}=${it.value[1]}/${it.value[0]}" })
                for (m in st.mismatches.take(60)) println("MISMATCH L${m.line} ${m.start}-${m.end} [${m.category}] `${m.snippet}`\n      K2:   ${m.fir}\n      ours: ${m.ours}")
            }
            return
        }
        val (res, errs) = FirOracle.analyze(stdlib + f, setOf(f.absolutePath))
        println("K2 ERRORS: " + errs.take(20))
        val text = f.readText()
        for (r in res[f.absolutePath] ?: emptyList()) {
            val snippet = text.substring(r.start, minOf(r.end, r.start + 40)).replace("\n", "\\n")
            println("${r.start}-${r.end} ${r.kind} :: ${r.type}${if (r.callee != null) " => " + r.callee else ""} ${r.extra}   `$snippet`")
        }
    }
}
