@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.statements

/*
 * Ir2Mir: expansion of `inline` functions and of lambdas passed to them.
 *
 * A call of an `inline fun` whose IR has a body is expanded at the call site: the body is lowered
 * in the caller's function context (its parameters become fresh locals initialised with the
 * arguments). Lambda literals passed for inlinable parameters are *not* turned into objects: a call
 * `block(x)` in the body expands the lambda body in place, so
 *  - captured variables are used directly (no heap cell),
 *  - `return` inside the lambda is a non-local return from the enclosing function,
 *  - `reified` type parameters work because the type arguments are substituted (typeSubst).
 * `return` from the inline function itself assigns the result and leaves the expansion (a Loop that
 * is exited with Break). The non-inlined fallback compiles the function normally (function objects).
 */

/** An inline lambda argument bound to a parameter of the function being expanded. */
internal class InlineLambda(val fn: IrSimpleFunction)


internal fun Ir2Mir.inlineLambdaOf(e: IrExpression?): InlineLambda? = when (e) {
    is IrFunctionExpression -> InlineLambda(e.function)
    is IrGetValue -> (e.symbol.owner as? IrValueParameter)?.let { inlineLambdaParams[it] }
    is IrTypeOperatorCall -> inlineLambdaOf(e.argument)
    else -> null
}

/** Value of [v] converted to the type [t] regardless of erasure (box / unbox / retype). */
internal fun Ir2Mir.coerceFlexible(v: MirExpr, t: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    if (v.type == t || v.type == TNothing) return v
    if (t == TUnit) return v
    val x = if (v.isOwned() || (v is MirExpr.FieldGet && v.type.isRef)) tmpOf(v, out) else v
    return when {
        x.type.isPrimitive && t.isPrimitive -> coerceOperand(x, t, at, out)
        x.type.isPrimitive && t.isRef -> coerceOperand(x, t, at, out)
        x.type.isRef && t.isPrimitive -> MirExpr.Unbox(MirExpr.Cast(x, TBoxed(t), MirExpr.CastMode.Unchecked), t)
        x.type.isRef && t.isRef -> MirExpr.Cast(x, t, MirExpr.CastMode.Unchecked)
        else -> convertTo(x, t, at)
    }
}

/**
 * Expands the lambda [lam] in place with [args] as parameter values. Returns the value of the
 * lambda body converted to its declared result type (UnitValue for Unit).
 */
internal fun Ir2Mir.inlineLambdaCall(lam: InlineLambda, args: List<MirExpr>, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val fn = lam.fn
    val ret = mirType(fn.returnType, fn)
    val saved = HashMap(locals)
    try {
        for ((i, p) in fn.parameters.withIndex()) {
            val t = mirType(p.type, p)
            val v = coerceFlexible(args[i], t, at, out)
            val l = MirLocal(localCounter++, p.name.asString().ifEmpty { "p$i" }, t)
            out += MirStmt.Declare(l, v)
            locals[p] = l
        }
        return expandBody(fn, ret, at, out)
    } finally {
        locals = saved
    }
}

/** Lowers the body of [fn] (a function being expanded) into [out]; `return` leaves through a label. */
private fun Ir2Mir.expandBody(fn: IrFunction, ret: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val label = MirLabel(labelCounter++)
    val res = if (ret == TUnit || ret == TNothing) null else MirLocal(localCounter++, "inl", ret)
    if (res != null) out += MirStmt.Declare(res, null)
    returnTargets[fn] = InlineReturn(res, label, finallyStack.size)
    val body = ArrayList<MirStmt>()
    try {
        when (val b = fn.body) {
            is IrBlockBody -> for (s in b.statements) stmt(s, body)
            is IrExpressionBody -> {
                val e = b.expression
                if (res == null) stmt(e, body) else body += MirStmt.Assign(res, bindValue(e, ret, body))
            }
            else -> unsupported(fn, "inline function body")
        }
    } finally {
        returnTargets.remove(fn)
    }
    body += MirStmt.Break(label)
    out += MirStmt.Loop(label, body)
    return if (res == null) (if (ret == TNothing) MirExpr.Unreachable(TNothing) else MirExpr.UnitValue) else MirExpr.Local(res)
}

/** Expansion of a call of the inline function [callee]. */
internal fun Ir2Mir.inlineCall(c: IrCall, callee: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
    val savedLocals = HashMap(locals)
    val savedSubst = HashMap(typeSubst)
    val boundLambdas = ArrayList<IrValueParameter>()
    try {
        for ((i, tp) in callee.typeParameters.withIndex()) {
            val a = c.typeArguments.getOrNull(i)
            if (a != null) typeSubst[tp.symbol] = a
        }
        val params = callee.parameters
        for ((i, p) in params.withIndex()) {
            val arg = c.arguments.getOrNull(i)
            val lam = if (arg != null && !p.isNoinline && !p.isCrossinline) inlineLambdaOf(arg) else null
            if (lam != null) {
                inlineLambdaParams[p] = lam
                boundLambdas += p
                continue
            }
            val t = mirType(p.type, p)
            val v: MirExpr = if (arg != null) {
                coerceFlexible(value(arg, out), t, c, out)
            } else {
                val d = p.defaultValue?.expression
                if (d == null && p.varargElementType != null) emptyVararg(p, c, out)
                else coerceFlexible(value(d ?: unsupported(c, "call with omitted argument '${p.name}' of '${callee.name}'"), out), t, c, out)
            }
            val l = MirLocal(localCounter++, p.name.asString().ifEmpty { "p$i" }, t)
            out += MirStmt.Declare(l, v)
            locals[p] = l
        }
        val ret = mirType(callee.returnType, callee)
        val r = expandBody(callee, ret, c, out)
        val expected = mirType(c.type, c)
        return if (r is MirExpr.Local) coerceFlexible(r, expected, c, out) else r
    } finally {
        for (p in boundLambdas) inlineLambdaParams.remove(p)
        locals = savedLocals
        typeSubst.clear()
        typeSubst.putAll(savedSubst)
    }
}

/** Calls the function value [arg] (a lambda literal is expanded in place) with [args]. */
internal fun Ir2Mir.callFunctionArg(arg: IrExpression, args: List<MirExpr>, expected: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val lam = inlineLambdaOf(arg)
    if (lam != null) {
        val r = inlineLambdaCall(lam, args, at, out)
        return if (expected == TUnit || expected == TNothing || r.type == TUnit) r else coerceFlexible(r, expected, at, out)
    }
    val fv = value(arg, out)
    val iface = functionInterface(args.size)
    val recv = if (fv.type is TObject && (fv.type as TObject).isInterface) fv else MirExpr.Cast(fv, iface, MirExpr.CastMode.Unchecked)
    val boxed = args.map { coerceOperand(it, anyNullable, at, out) }
    val call = MirExpr.ICall(iface.cName, 0, recv, boxed, invokeArgTypes(args.size), anyNullable)
    if (expected == TUnit || expected == TNothing) {
        out += MirStmt.Eval(call)
        return MirExpr.UnitValue
    }
    return adaptResult(tmpOf(call, out), expected, at, out)
}

