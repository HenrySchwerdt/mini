package mini.backend

/**
 * Stable C identifiers for Kotlin top-level functions (documented in compiler/README.md).
 *
 * Scheme: `kt_` + segments joined by `_D`, then `_A` + one letter per parameter when the
 * function has parameters. Segments are the package parts followed by the function name.
 * Inside a segment `[A-Za-z0-9]` is kept, `_` becomes `_u`, everything else `_xHHHH`
 * (UTF-16 code unit, upper-case hex). Because an escape always has `u` or `x` after the
 * underscore, the separators `_D` and `_A` can never occur inside a segment, so the
 * mapping is injective.
 *
 * Parameter letters: I Int, J Long, Z Boolean, D Double, C Char, S String.
 *
 * `fun main()` is special: it is always `mk_main` (the runtime calls it).
 */
object Mangling {
    const val MAIN = "mk_main"

    fun function(packageName: String, name: String, params: List<MirType>): String {
        if (name == "main" && params.isEmpty()) return MAIN
        val segments = (if (packageName.isEmpty()) emptyList() else packageName.split('.')) + name
        val sb = StringBuilder("kt_")
        segments.forEachIndexed { i, s ->
            if (i > 0) sb.append("_D")
            sb.append(escape(s))
        }
        if (params.isNotEmpty()) {
            sb.append("_A")
            for (p in params) sb.append(typeLetter(p))
        }
        return sb.toString()
    }

    /** C identifier for a local variable or parameter: `v_<name>_<id>`. */
    fun local(name: String, id: Int): String = "v_" + escape(name) + "_" + id

    /**
     * C stem of a class: `kc_` + package parts and the nested class path joined by `_D`.
     * Methods are `<class stem>_M<name>_A<letters>`, constructors `<class stem>_C_A<letters>`,
     * fields `f_<name>`. `_M`, `_C`, `_D`, `_A`, `_E` never occur inside an escaped segment.
     */
    fun className(packageName: String, path: List<String>): String {
        val segments = (if (packageName.isEmpty()) emptyList() else packageName.split('.')) + path
        return "kc_" + segments.joinToString("_D") { escape(it) }
    }

    fun method(classCName: String, name: String, params: List<MirType>): String =
        classCName + "_M" + escape(name) + paramSuffix(params)

    fun constructor(classCName: String, params: List<MirType>): String = classCName + "_C" + paramSuffix(params)

    fun field(name: String): String = "f_" + escape(name)

    private fun paramSuffix(params: List<MirType>): String {
        if (params.isEmpty()) return ""
        val sb = StringBuilder("_A")
        for (p in params) sb.append(typeLetter(p))
        return sb.toString()
    }

    fun typeLetter(type: MirType): String = when (type) {
        TInt -> "I"
        TLong -> "J"
        TBoolean -> "Z"
        TDouble -> "D"
        TChar -> "C"
        TString -> "S"
        TUnit -> "V"
        TNothing -> "N"
        TNString -> "QS"
        TNull -> "QN"
        is TBoxed -> "Q" + typeLetter(type.prim)
        is TObject -> (if (type.nullable) "Q" else "") + "L" + type.cName + "_E"
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> sb.append(c)
                c == '_' -> sb.append("_u")
                else -> {
                    sb.append("_x")
                    val h = c.code.toString(16).uppercase()
                    repeat(4 - h.length) { sb.append('0') }
                    sb.append(h)
                }
            }
        }
        return sb.toString()
    }
}
