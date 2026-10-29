import mini.frontend.sema.*
import java.io.File

/**
 * Compares our semantic analysis with the K2 FIR resolution: for every position (source range) at which K2 resolved an
 * expression or declaration, our resolved type and callee must be equal. See [FirOracle] for the K2 side.
 */
object SemaOracle {
    class Mismatch(val file: String, val start: Int, val end: Int, val category: String, val fir: String, val ours: String, val snippet: String, val line: Int)

    class Stat {
        var total = 0
        var matched = 0
        var missing = 0
        val byCategory = LinkedHashMap<String, IntArray>() // [total, matched]
        val mismatches = ArrayList<Mismatch>()
        fun add(cat: String, ok: Boolean) {
            total++
            if (ok) matched++
            val a = byCategory.getOrPut(cat) { IntArray(2) }
            a[0]++
            if (ok) a[1]++
        }
        fun merge(o: Stat) {
            total += o.total
            matched += o.matched
            missing += o.missing
            for ((k, v) in o.byCategory) {
                val a = byCategory.getOrPut(k) { IntArray(2) }
                a[0] += v[0]
                a[1] += v[1]
            }
            mismatches.addAll(o.mismatches)
        }
        fun pct(): Double = if (total == 0) 100.0 else matched * 100.0 / total
    }

    private val categories = mapOf(
        "FirFunctionCall" to "call",
        "FirImplicitInvokeCall" to "call",
        "FirPropertyAccessExpression" to "access",
        "FirLiteralExpression" to "literal",
        "FirStringConcatenationCall" to "string-template",
        "FirWhenExpression" to "when-if",
        "FirTryExpression" to "try",
        "FirElvisExpression" to "elvis",
        "FirSafeCallExpression" to "safe-call",
        "FirCheckNotNullCall" to "not-null-assert",
        "FirTypeOperatorCall" to "is-as",
        "FirComparisonExpression" to "comparison",
        "FirEqualityOperatorCall" to "equality",
        "FirBooleanOperatorExpression" to "boolean-op",
        "FirAnonymousFunctionExpression" to "lambda",
        "FirThisReceiverExpression" to "this",
        "FirDelegatedConstructorCall" to "ctor-call",
        "FirReturnExpression" to "return",
        "FirResolvedQualifier" to "qualifier",
        "decl:FirProperty" to "decl-property",
        "decl:FirValueParameter" to "decl-parameter",
        "decl:FirNamedFunction" to "decl-function",
    )

    fun lineOf(text: String, off: Int): Int {
        var l = 1
        var i = 0
        while (i < off && i < text.length) {
            if (text[i] == '\n') l++
            i++
        }
        return l
    }

    fun compareFile(path: String, text: String, fir: List<FirRec>, sema: Sema, f: mini.frontend.sema.FileInfo): Stat {
        val st = Stat()
        val ours = HashMap<String, Rec>()
        for (r in sema.dumpFile(f)) {
            val decl = r.kind == "PropertyDecl" || r.kind == "FunDecl" || r.kind == "Param" || r.kind == "DestructEntry" || r.kind == "LambdaParam"
            val key = "${r.start}-${r.end}-" + (if (decl) "d" else if (r.kind == "SmartCast") "s" else "e")
            if (!ours.containsKey(key)) ours[key] = r
        }
        // group FIR records per key
        val groups = LinkedHashMap<String, ArrayList<FirRec>>()
        for (r in fir) {
            val cat = categories[r.kind] ?: continue
            if (r.kind == "FirResolvedQualifier") continue // K2 specific: compared through its parent call
            val decl = r.kind.startsWith("decl:")
            val key = "${r.start}-${r.end}-" + (if (decl) "d" else "e")
            groups.getOrPut(key) { ArrayList() }.add(r)
        }
        for ((key, list) in groups) {
            val first = list[0]
            val cat = categories[first.kind]!!
            var calleeFir: String? = null
            for (r in list) if (r.callee != null && calleeFir == null) calleeFir = r.callee
            // a smart cast at this position: K2 reports the narrowed type
            val sc = fir.firstOrNull { it.start == first.start && it.end == first.end && it.kind == "FirSmartCastExpression" && !first.kind.startsWith("decl:") }
            val expectedType = sc?.type ?: first.type
            val o = ours[key]
            val os = if (sc != null) ours[key.dropLast(1) + "s"] else null
            val ot = os?.type ?: o?.type
            val ok: Boolean
            var oursText = "<missing>"
            if (o == null || ot == null) {
                ok = false
                st.missing++
            } else {
                oursText = ot + (if (o.callee != null) " => " + o.callee else "")
                ok = ot == expectedType && (calleeFir == null || o.callee == calleeFir)
            }
            st.add(cat, ok)
            if (!ok) {
                st.mismatches.add(
                    Mismatch(
                        path, first.start, first.end, cat,
                        expectedType + (if (calleeFir != null) " => $calleeFir" else ""), oursText,
                        text.substring(first.start, minOf(first.end, first.start + 50)).replace("\n", "\\n"), lineOf(text, first.start),
                    ),
                )
            }
        }
        return st
    }

    /** analyses [files] with K2 and with our analysis; compares the files of [report] */
    fun run(files: List<File>, report: List<File>): Pair<Map<String, Stat>, List<String>> {
        val reportPaths = report.map { it.absolutePath }.toSet()
        val (fir, errs) = FirOracle.analyzeCached(files, reportPaths)
        val inputs = files.map { SourceInput(it.absolutePath, it.readText()) }
        val sema = analyzeSources(inputs)
        val out = LinkedHashMap<String, Stat>()
        for (f in sema.files) {
            if (f.path !in reportPaths) continue
            val recs = fir[f.path] ?: emptyList()
            out[f.path] = compareFile(f.path, f.text, recs, sema, f)
        }
        val diag = ArrayList<String>()
        for (d in sema.diags) if (d.path in reportPaths) diag.add("${File(d.path).name}:${d.start}: ${d.message}")
        return Pair(out, errs + diag.map { "OURS " + it })
    }
}
