package mini.frontend.sema

import mini.frontend.*

/** What the checker found out about one AST node: its type and, for references and calls, the resolved callee. */
class ExprRec(var type: Type, var callee: Sym?) {
    var calleeText: String? = null
    /** the type after smart casts (null = same as [type]) */
    var smart: Type? = null
    var kind: String = ""
    /** for `is`/`as`/when-is: the resolved target type */
    var target: Type? = null
    var thisKey: String? = null
}

class Records {
    val map = HashMap<Ast, ExprRec>()
    val order = ArrayList<Ast>()

    fun put(node: Ast, type: Type, callee: Sym?): ExprRec {
        var r = map[node]
        if (r == null) {
            r = ExprRec(type, callee)
            map[node] = r
            order.add(node)
        } else {
            r.type = type
            r.callee = callee
        }
        return r
    }

    fun get(node: Ast): ExprRec? = map[node]
}

/** One line of the semantic dump: a source range with the type and callee found for it. */
class Rec(val start: Int, val end: Int, val kind: String, val type: String, val callee: String?)

fun renderCallee(s: Sema, sym: Sym?, text: String?): String? {
    if (text != null) return text
    if (sym == null) return null
    return Rendering(s).callee(sym)
}

class Rendering(private val sema: Sema) {
    fun sigParams(f: FunSym): String {
        sema.ensureSig(f)
        val sb = StringBuilder("(")
        val r = f.receiver
        if (r != null) sb.append(renderType(r)).append('.')
        var first = true
        for (p in f.params) {
            if (!first) sb.append(',')
            first = false
            if (p.isVararg) sb.append("vararg ")
            val t = p.type
            sb.append(if (t != null) renderType(t) else "?")
        }
        return sb.append(')').toString()
    }

    fun callee(sym: Sym): String {
        when (sym) {
            is VarSym -> return (if (sym.isParam) "param " else "local ") + sym.name + "@" + sym.declStart
            is FunSym -> {
                if (sym.kind == FunKind.FUNCTION_INVOKE) {
                    val n = sym.invokeArity
                    val sb = StringBuilder(if (sym.invokeSuspend) "kotlin.coroutines.SuspendFunction" else "kotlin.Function")
                    sb.append(n).append(".invoke(")
                    var i = 1
                    while (i <= n) {
                        if (i > 1) sb.append(',')
                        sb.append('P').append(i)
                        i++
                    }
                    return sb.append(')').toString()
                }
                val o = sym.owner
                val sb = StringBuilder()
                if (sym.kind == FunKind.CONSTRUCTOR && o != null) {
                    sb.append(o.renderName()).append(".<init>")
                } else if (o != null) {
                    sb.append(o.renderName()).append('.').append(sym.name)
                    if (o.isLocal) sb.append('@').append(sym.start)
                } else if (sym.kind == FunKind.LOCAL) {
                    sb.append("<local>.").append(sym.name).append('@').append(sym.start)
                } else {
                    val pk = sym.file?.pkg ?: ""
                    if (pk.isNotEmpty()) sb.append(pk).append('.')
                    sb.append(sym.name)
                }
                sb.append(sigParams(sym))
                return sb.toString()
            }
            is PropSym -> {
                sema.ensurePropSig(sym)
                if (sym.isLocal) return "local " + sym.name + "@" + sym.start
                val o = sym.owner
                val base = if (o != null) o.renderName() + "." + sym.name else {
                    val pk = sym.file?.pkg ?: ""
                    if (pk.isNotEmpty()) pk + "." + sym.name else sym.name
                }
                val r = sym.receiver
                return if (r != null) renderType(r) + "." + base else base
            }
            is EnumEntrySym -> return sym.owner.renderName() + "." + sym.name
            is ClassSym -> return "class " + sym.renderName()
            is TypeAliasSym -> return "alias " + sym.name
            is TypeParamSym -> return "tp " + sym.name
        }
    }
}
