package mini.lower

import mini.backend.*
import mini.frontend.sema.*

/*
 * `typeOf<T>()` (kotlin.reflect): a KTypeImpl (stdlib/kotlin/reflect/KTypes.kt) made from the static type argument. The type is spelled
 * as text in the format of KType.toString of kotlin-stdlib (`kotlin.collections.Map<kotlin.String, *>?`); a type parameter that is not
 * substituted (the call is not in an inline function with a reified parameter) is spelled `@T`. The classifier, the arguments and the
 * nullability are parsed back from the text when they are asked for.
 */

/** `typeOf<T>()` with the type argument of the call (the type parameters of the inline functions being expanded are substituted). */
internal fun Ir2Mir.typeOfCall(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val impl = ktypeImpl ?: unsupported(c, "`typeOf` (the stdlib class KTypeImpl is not available)")
    val ctor = impl.declarations.filterIsInstance<IrConstructor>().firstOrNull()
    val cName = ctor?.let { functionNames[it] } ?: unsupported(c, "`typeOf` (KTypeImpl was pruned)")
    val arg = c.typeArguments[0] ?: unsupported(c, "`typeOf` without a type argument")
    val made = tmpOf(MirExpr.New(cName, listOf(MirExpr.StringConst(ktypeText(arg, c))), typeOf(impl)), out)
    return MirExpr.Cast(made, mirType(c.type, c), MirExpr.CastMode.Unchecked)
}

/** The text of the type [t0] for KTypeImpl. */
internal fun Ir2Mir.ktypeText(t0: Type, at: IrElement): String {
    var cur: Type = t0
    var nullable = cur.isMarkedNullable()
    var guard = 0
    while (cur is TypeParamType && guard < 32) {
        guard++
        val sub = typeSubst[cur.tp] ?: break
        cur = sub
        if (cur.isMarkedNullable()) nullable = true
    }
    val x = cur
    val body: String
    if (x is ClassType) {
        val sb = StringBuilder(if (x.cls.isLocal) x.cls.name else x.cls.renderName())
        if (x.args.isNotEmpty()) {
            sb.append('<')
            var first = true
            for (a in x.args) {
                if (!first) sb.append(", ")
                first = false
                val at2 = a.type
                if (at2 == null) {
                    sb.append('*')
                } else {
                    if (a.variance == Variance.IN) sb.append("in ")
                    else if (a.variance == Variance.OUT) sb.append("out ")
                    sb.append(ktypeText(at2, at))
                }
            }
            sb.append('>')
        }
        body = sb.toString()
    } else if (x is TypeParamType) {
        body = "@" + x.tp.name
    } else if (x is FunType) {
        val sb = StringBuilder("(")
        var first = true
        val recv = x.receiver
        if (recv != null) {
            sb.append(ktypeText(recv, at))
            first = false
        }
        for (p in x.params) {
            if (!first) sb.append(", ")
            first = false
            sb.append(ktypeText(p, at))
        }
        sb.append(") -> ").append(ktypeText(x.ret, at))
        body = if (nullable) "(" + sb.toString() + ")" else sb.toString()
    } else {
        unsupported(at, "typeOf of the type '" + renderType(x) + "'")
    }
    return if (nullable) body + "?" else body
}
