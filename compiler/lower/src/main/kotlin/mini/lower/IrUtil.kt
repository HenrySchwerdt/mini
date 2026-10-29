package mini.lower

import mini.frontend.sema.*

/* Helpers over the IR: type queries (the semantic types of the frontend are the IR types) and the tree walker. */

class IrModule(val files: ArrayList<IrFile>)

/** Global state of one lowering run: the symbol to IR declaration tables shared by the builder and the converter. */
object LowerState {
    var ctx: Ast2Ir? = null
}

// ---- types ----------------------------------------------------------------------------------------------------

fun Type.isMarkedNullable(): Boolean = this.nullable

/** The qualified name of the class a type denotes (function types: `kotlin.FunctionN`), null for type parameters. */
val Type.classFqName: String?
    get() {
        val t = this
        return when (t) {
            is ClassType -> t.cls.irFqName()
            is FunType -> (if (t.suspend) "kotlin.coroutines.SuspendFunction" else "kotlin.Function") + t.arity
            is IsectType -> if (t.parts.isNotEmpty()) t.parts[0].classFqName else null
            else -> null
        }
    }

val Type.classOrNull: IrClass?
    get() {
        val t = this
        val c = LowerState.ctx ?: return null
        return when (t) {
            is ClassType -> c.irClass(t.cls)
            is FunType -> c.functionClass(t.arity)
            is IsectType -> if (t.parts.isNotEmpty()) t.parts[0].classOrNull else null
            else -> null
        }
    }

/** The name `KFunction1` (of the reflect package) etc. of the synthetic reference classes, else the qualified name. */
fun ClassSym.irFqName(): String {
    if (fnArity >= 0) return REFLECT_PKG + "." + name
    return fqName()
}

// ---- tree walking ---------------------------------------------------------------------------------------------

/** Appends the direct children of [e] (declarations, bodies, expressions) in source order. */
fun irChildren(e: IrElement, out: MutableList<IrElement>) {
    when (e) {
        is IrFile -> for (d in e.declarations) out.add(d)
        is IrClass -> for (d in e.declarations) out.add(d)
        is IrFunction -> {
            for (p in e.parameters) out.add(p)
            val b = e.body
            if (b != null) out.add(b)
        }
        is IrValueParameter -> {
            val d = e.defaultValue
            if (d != null) out.add(d)
        }
        is IrProperty -> {
            val f = e.backingField
            if (f != null) out.add(f)
            val g = e.getter
            if (g != null) out.add(g)
            val s = e.setter
            if (s != null) out.add(s)
        }
        is IrField -> {
            val i = e.initializer
            if (i != null) out.add(i)
        }
        is IrEnumEntry -> {
            val i = e.initializerExpression
            if (i != null) out.add(i)
            val c = e.correspondingClass
            if (c != null) out.add(c)
        }
        is IrAnonymousInitializer -> out.add(e.body)
        is IrLocalDelegatedProperty -> {
            val d = e.delegate
            if (d != null) out.add(d)
            out.add(e.getter)
            val s = e.setter
            if (s != null) out.add(s)
        }
        is IrVariable -> {
            val i = e.initializer
            if (i != null) out.add(i)
        }
        is IrBlockBody -> for (s in e.statements) out.add(s)
        is IrExpressionBody -> out.add(e.expression)
        is IrGetValue, is IrConst, is IrGetEnumValue, is IrGetObjectValue, is IrClassReference, is IrInstanceInitializerCall, is IrBreak, is IrContinue -> {}
        is IrSetValue -> out.add(e.value)
        is IrFunctionAccessExpression -> for (a in e.arguments) if (a != null) out.add(a)
        is IrGetField -> {
            val r = e.receiver
            if (r != null) out.add(r)
        }
        is IrSetField -> {
            val r = e.receiver
            if (r != null) out.add(r)
            out.add(e.value)
        }
        is IrStringConcatenation -> for (a in e.arguments) out.add(a)
        is IrFunctionExpression -> out.add(e.function)
        is IrGetClass -> out.add(e.argument)
        is IrTry -> {
            out.add(e.tryResult)
            for (c in e.catches) out.add(c)
            val f = e.finallyExpression
            if (f != null) out.add(f)
        }
        is IrCatch -> {
            out.add(e.catchParameter)
            out.add(e.result)
        }
        is IrVararg -> for (x in e.elements) {
            if (x is IrSpreadElement) out.add(x.expression) else if (x is IrElement) out.add(x)
        }
        is IrSpreadElement -> out.add(e.expression)
        is IrThrow -> out.add(e.value)
        is IrBranch -> {
            out.add(e.condition)
            out.add(e.result)
        }
        is IrWhen -> for (b in e.branches) out.add(b)
        is IrContainerExpression -> for (s in e.statements) out.add(s)
        is IrTypeOperatorCall -> out.add(e.argument)
        is IrReturn -> out.add(e.value)
        is IrLoop -> {
            out.add(e.condition)
            val b = e.body
            if (b != null) out.add(b)
        }
        else -> {}
    }
}

/** Base of the tree visitors: [visit] is called for every element, [visitChildren] descends. */
abstract class IrWalker {
    abstract fun visit(e: IrElement)

    fun visitChildren(e: IrElement) {
        val l = ArrayList<IrElement>()
        irChildren(e, l)
        for (c in l) visit(c)
    }
}
