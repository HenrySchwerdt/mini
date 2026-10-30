import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * B2-2 oracle: our resolution and type checking against K2's FIR on the target corpus (stdlib, compiler sources,
 * test programs). Writes `build/reports/sema-oracle.txt` (per file and overall agreement, per expression kind, the
 * first mismatches) and compares every file with the checked-in baseline
 * `src/test/resources/sema-oracle-baseline.txt`: agreement may not drop. Run with MINI_SEMA_UPDATE=1 to rewrite the
 * baseline; MINI_SEMA_AREAS=testdata,stdlib,backend,native-driver,frontend,selfhost selects areas (default all).
 */
class SemaOracleTest {
    private val root = Corpus.repoRoot

    private fun kt(dir: String): List<File> =
        File(root, dir).walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" }.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private val stdlib: List<File> by lazy { kt("stdlib") }

    private class Area(val name: String, val runs: List<Pair<List<File>, List<File>>>) // (inputs, files to report)

    private fun areas(): List<Area> {
        val out = ArrayList<Area>()
        val td = kt("compiler/testdata").filter { it.parentFile.name == "testdata" && !File(it.path.removeSuffix(".kt") + ".cerr").exists() && Corpus.isK2Parsable(it) }
        out.add(Area("testdata", td.map { Pair(stdlib + it, listOf(it)) }))
        out.add(Area("stdlib", listOf(Pair(stdlib, stdlib))))
        val backend = kt("compiler/backend/src")
        val nativeDriver = kt("compiler/native-driver/src")
        val frontend = kt("compiler/frontend/src")
        out.add(Area("backend", listOf(Pair(stdlib + backend, backend))))
        out.add(Area("native-driver", listOf(Pair(stdlib + backend + nativeDriver, nativeDriver))))
        out.add(Area("frontend", listOf(Pair(stdlib + frontend, frontend))))
        val ex = kt("examples")
        out.add(Area("examples", ex.map { Pair(stdlib + it, listOf(it)) }))
        val mem = kt("compiler/testdata/memory") + kt("compiler/testdata/memory-extra")
        out.add(Area("memory", mem.filter { !File(it.path.removeSuffix(".kt") + ".cerr").exists() }.map { Pair(stdlib + it, listOf(it)) }))
        val selfhost = kt("tools/k2-oracle/selfhost")
        out.add(Area("selfhost", listOf(Pair(stdlib + backend + nativeDriver + selfhost, selfhost))))
        return out
    }

    private fun readBaseline(): Map<String, Pair<Int, Int>> {
        val f = File(System.getProperty("mini.semaBaseline") ?: return emptyMap())
        if (!f.isFile) return emptyMap()
        val m = HashMap<String, Pair<Int, Int>>()
        for (l in f.readLines()) {
            if (l.isBlank() || l.startsWith("#")) continue
            val p = l.split(' ')
            m[p[0]] = Pair(p[1].toInt(), p[2].toInt())
        }
        return m
    }

    @Test
    fun semaAgreesWithK2() {
        val wanted = (System.getenv("MINI_SEMA_AREAS") ?: "testdata,stdlib,backend,native-driver,frontend,selfhost").split(',').toSet()
        val baseline = readBaseline()
        val update = System.getenv("MINI_SEMA_UPDATE") != null
        val report = StringBuilder()
        val all = StringBuilder()
        val allDiags = StringBuilder()
        var totalDiags = 0
        val newBaseline = StringBuilder("# file matched total (positions where K2 resolved a type; see SemaOracleTest)\n")
        val regressions = ArrayList<String>()
        val overall = SemaOracle.Stat()
        val perKind = SemaOracle.Stat()
        val topMismatch = HashMap<String, Int>()
        for (area in areas()) {
            if (area.name !in wanted) continue
            val areaStat = SemaOracle.Stat()
            var areaDiags = 0
            var areaUnsupported = 0
            report.append("== area ${area.name}\n")
            for ((inputs, reported) in area.runs) {
                val (stats, diags) = SemaOracle.run(inputs, reported)
                for ((path, st) in stats) {
                    val rel = File(path).relativeTo(root).path
                    areaStat.merge(st)
                    report.append(String.format("  %-70s %5d/%-5d %6.2f%%\n", rel, st.matched, st.total, st.pct()))
                    newBaseline.append("$rel ${st.matched} ${st.total}\n")
                    val b = baseline[rel]
                    if (b != null && st.matched < b.first) regressions.add("$rel: ${st.matched}/${st.total} (baseline ${b.first}/${b.second})")
                    for (m in st.mismatches) {
                        val k = m.category + ": " + m.fir.substringBefore(" =>").take(40)
                        topMismatch[k] = (topMismatch[k] ?: 0) + 1
                    }
                    for (m in st.mismatches) all.append("$rel L${m.line} [${m.category}] `${m.snippet}`\n    K2:   ${m.fir}\n    ours: ${m.ours}\n")
                    if (st.mismatches.isNotEmpty()) {
                        report.append("      first mismatches:\n")
                        for (m in st.mismatches.take(5)) report.append("        L${m.line} [${m.category}] `${m.snippet}`\n          K2:   ${m.fir}\n          ours: ${m.ours}\n")
                    }
                }
                val ours = diags.filter { it.startsWith("OURS") }
                val unsupported = ours.filter { it.contains("unsupported") }
                areaDiags += ours.size
                totalDiags += ours.size
                areaUnsupported += unsupported.size
                if (ours.isNotEmpty()) {
                    allDiags.append("== ${area.name}\n")
                    for (d in ours) allDiags.append("  $d\n")
                }
            }
            report.append(String.format("  AREA %s: %d/%d = %.2f%%\n", area.name, areaStat.matched, areaStat.total, areaStat.pct()))
            report.append("  our diagnostics on code K2 accepts: $areaDiags (unsupported constructs: $areaUnsupported)\n")
            report.append("  by expression kind: " + areaStat.byCategory.entries.joinToString(", ") { "${it.key} ${it.value[1]}/${it.value[0]}" } + "\n")
            overall.merge(areaStat)
        }
        report.append(String.format("OVERALL: %d/%d = %.2f%%\n", overall.matched, overall.total, overall.pct()))
        report.append("BY EXPRESSION KIND: " + overall.byCategory.entries.joinToString(", ") { String.format("%s %d/%d (%.1f%%)", it.key, it.value[1], it.value[0], it.value[1] * 100.0 / it.value[0]) } + "\n")
        report.append("TOP MISMATCH CATEGORIES:\n")
        for ((k, v) in topMismatch.entries.sortedByDescending { it.value }.take(25)) report.append("  $v  $k\n")
        val out = File(System.getProperty("mini.reports") ?: "build/reports")
        out.mkdirs()
        File(out, "sema-oracle.txt").writeText(report.toString())
        File(out, "sema-oracle-mismatches.txt").writeText(all.toString())
        File(out, "sema-oracle-diagnostics.txt").writeText(allDiags.toString())
        println(report.toString().lines().filter { it.startsWith("  AREA") || it.startsWith("OVERALL") }.joinToString("\n"))
        if (update) {
            val bf = System.getProperty("mini.semaBaseline")
            if (bf != null) File(bf).writeText(newBaseline.toString())
        } else {
            assertTrue(regressions.isEmpty(), "agreement regressed:\n" + regressions.joinToString("\n"))
            assertTrue(totalDiags == 0, "our analysis reported $totalDiags diagnostics on code that K2 accepts, see sema-oracle-diagnostics.txt")
        }
    }
}
