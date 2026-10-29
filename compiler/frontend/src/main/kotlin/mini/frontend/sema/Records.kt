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
    var declRec: ExprRec? = null
    /** B2-3: what the lowering needs besides type and callee (resolution details of calls, operators and accesses) */
    var call: CallInfo? = null
    var access: Access? = null
    var extra: Any? = null
    /** the type of a safe-called member before it became nullable */
    var baseType: Type? = null
}

/** the parameters of an analysed lambda, in order (`it`, named parameters, `<destruct>` placeholders) */
class LambdaInfo(val params: List<VarSym>)

/** An implicit receiver: `this` of a class, an object / companion value, or a receiver of an extension function / lambda. */
class RecvRef(val cls: ClassSym?, val scope: ReceiverScope?, val isObject: Boolean) {
    /** the type of the receiver after smart casts (`this` of a class inside `if (this is X)`), null when unchanged */
    var smart: Type? = null
}

/** How a receiver is provided: an explicit expression (possibly safe-called) or an implicit receiver. */
class RecvUse(val expr: Expr?, val safe: Boolean, val ref: RecvRef?, val type: Type?)

/** the operator functions of a delegated property: `getValue`, `setValue` (var), `provideDelegate` */
class DelegateInfo(val delegateType: Type, val getValue: CallInfo?, val setValue: CallInfo?)

/** `a op= b`: whether `opAssign` was used (else `a = a op b`), the `set` of an indexed target */
class CompoundInfo(val assignOp: Boolean, val set: CallInfo?)

/** The resolution of a `for` loop: the iterator protocol functions. */
class ForInfo(val iterator: CallInfo?, val hasNext: CallInfo?, val next: CallInfo?, val elemType: Type)

/** The receivers of a property or function access. */
class Access(var dispatch: RecvUse?, var ext: RecvUse?)

/**
 * The resolution of a call: callee, the final type arguments of its own type parameters, the arguments in source order
 * with their parameter binding, the receivers.
 */
class CallInfo(val fn: FunSym, val typeArgs: List<Type>, val args: List<Arg>, val paramOfArg: IntArray) {
    var ret: Type? = null
    var paramTypes: List<Type?> = emptyList()
    var dispatch: RecvUse? = null
    var ext: RecvUse? = null
    /** `v(args)` / `a.v(args)` on a value of function type or with an `invoke` operator: the value (a name, property or expression) */
    var invokeValue: RecvUse? = null
    var invokeProp: PropSym? = null
    var invokeVar: VarSym? = null
    /** the super type of `super.f()` / `super<T>.f()` */
    var superType: Type? = null
    var cand: Candidate? = null
    var isInvoke = false
    /** the receiver is the first argument of an `invoke` of a function type with receiver */
    var recvAsFirstArg = false
}

class Records {
    val map = HashMap<Ast, ExprRec>()
    val order = ArrayList<Ast>()
    /** B2-3: the variable a `when (val x = e)` subject declares */
    val subjectVars = HashMap<Ast, VarSym>()

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
            is VarSym -> {
                if (sym.isField) return "<local>.field"
                return (if (sym.isParam) "param " else "local ") + sym.name + "@" + sym.declStart
            }
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
                    if (o.isAnonymous) sb.append("<anonymous>.") else if (o.isLocal) sb.append("<local>.") else sb.append(o.renderName()).append('.')
                    sb.append(sym.name)
                    if (o.isLocal || o.isAnonymous) sb.append('@').append(sym.start)
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
                if (o != null && (o.isLocal || o.isAnonymous) && sym.receiver == null) return "local " + sym.name + "@" + sym.start
                val base = if (o != null) (if (o.isAnonymous) "<anonymous>." else if (o.isLocal) "<local>." else o.renderName() + ".") + sym.name else {
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
