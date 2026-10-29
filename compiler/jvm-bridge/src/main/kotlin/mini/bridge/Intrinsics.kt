@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*

/*
 * STOPGAP: stdlib functions the bridge understands directly.
 *
 * K2 resolves against the JVM stdlib jar, so `let`, `repeat`, `error`, `require`, ... have no IR
 * bodies. Until the stdlib-from-source mechanism lands (then these functions have bodies and are
 * expanded by Ir2MirInline like any user `inline fun`), the few small ones needed by ordinary
 * programs are special-cased here. This file is deliberately self-contained so it can be deleted:
 * Ir2MirCalls only calls [intrinsicCall] and falls back to "unsupported call" when it returns null.
 */

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

private fun Ir2Mir.single(arg: IrExpression, out: MutableList<MirStmt>): MirExpr = operands(listOf(arg), out)[0]

internal fun Ir2Mir.intrinsicCall(c: IrCall, f: IrSimpleFunction, pkg: String, out: MutableList<MirStmt>): MirExpr? {
    if (f.parent is IrClass) return null
    val name = f.name.asString()
    val a = c.arguments.map { it ?: return null }
    val expected = mirType(c.type, c)
    if (pkg == "kotlin") {
        when (name) {
            "let" -> if (a.size == 2) return callFunctionArg(a[1], listOf(single(a[0], out)), expected, c, out)
            "also", "apply" -> if (a.size == 2) {
                val recv = single(a[0], out)
                callFunctionArg(a[1], listOf(recv), TUnit, c, out)
                return recv
            }
            "run" -> {
                if (a.size == 1) return callFunctionArg(a[0], emptyList(), expected, c, out)
                if (a.size == 2) return callFunctionArg(a[1], listOf(single(a[0], out)), expected, c, out)
            }
            "with" -> if (a.size == 2) return callFunctionArg(a[1], listOf(single(a[0], out)), expected, c, out)
            "takeIf", "takeUnless" -> if (a.size == 2) {
                val recv = single(a[0], out)
                val keep = callFunctionArg(a[1], listOf(recv), TBoolean, c, out)
                val rt = expected
                val tmp = newTemp(rt)
                out += MirStmt.Declare(tmp, null)
                val cond = if (name == "takeIf") keep else MirExpr.Unary(UnaryOp.Not, keep)
                val body = ArrayList<MirStmt>()
                body += MirStmt.Assign(tmp, coerceFlexible(recv, rt, c, body))
                out += MirStmt.If(cond, body, emptyList())
                return MirExpr.Local(tmp)
            }
            "repeat" -> if (a.size == 2) {
                val times = single(a[0], out)
                val label = MirLabel(labelCounter++)
                val i = MirLocal(localCounter++, "i", TInt)
                val body = ArrayList<MirStmt>()
                callFunctionArg(a[1], listOf(MirExpr.Local(i)), TUnit, c, body)
                out += MirStmt.ForRange(label, i, MirExpr.IntConst(0), times, null, true, false, body)
                return MirExpr.UnitValue
            }
        }
    }
    if (pkg == "kotlin" && name == "<get-isInitialized>" && a.size == 1) {
        // lateinit: `this::x.isInitialized` / `::x.isInitialized`
        val ref = a[0] as? IrPropertyReference ?: unsupported(c, "isInitialized on something other than a property reference")
        val field = ref.symbol.owner.backingField ?: unsupported(c, "isInitialized without backing field")
        val recvIr = ref.arguments.firstOrNull { it != null }
        val raw = if (recvIr == null) staticFieldGet(field, c)
        else fieldGet(operands(listOf(recvIr), out)[0], field)
        return MirExpr.Binary(BinaryOp.IdNe, raw, MirExpr.NullConst())
    }
    if (pkg == "kotlin.comparisons" && (name == "maxOf" || name == "minOf") && a.size == 2) {
        val v = operands(a, out)
        val t = v[0].type
        if (t == TInt || t == TLong || t == TDouble || t == TChar) {
            val x = if (v[0] is MirExpr.Local) v[0] else tmpOfPlain(v[0], out)
            val y = if (v[1] is MirExpr.Local) v[1] else tmpOfPlain(v[1], out)
            return MirExpr.Conditional(MirExpr.Binary(if (name == "maxOf") BinaryOp.Ge else BinaryOp.Le, x, y), x, y, t)
        }
    }
    return intrinsicThrowing(c, f, pkg, name, a, expected, out)
}

/** `throw <exception of runtime class [cName] with [message]>`. */
private fun Ir2Mir.throwNew(cName: String, message: MirExpr, out: MutableList<MirStmt>) {
    val ex = tmpOf(newBuiltin(cName, message), out)
    out += MirStmt.Throw(ex)
}

/** The string of a lazy message lambda `() -> Any`. */
private fun Ir2Mir.lazyMessage(arg: IrExpression, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val v = callFunctionArg(arg, emptyList(), anyNullable, at, out)
    return toStringExpr(v, at, out)
}

/** Throwing stdlib helpers (error, require, check, TODO, requireNotNull ...). */
internal fun Ir2Mir.intrinsicThrowing(
    c: IrCall, f: IrSimpleFunction, pkg: String, name: String, a: List<IrExpression>, expected: MirType, out: MutableList<MirStmt>,
): MirExpr? {
    if (pkg != "kotlin") return null
    when (name) {
        "error" -> if (a.size == 1) {
            val m = single(a[0], out)
            throwNew("MkIllegalStateException", toStringExpr(coerceFlexible(m, anyNullable, c, out), c, out), out)
            return MirExpr.Unreachable(TNothing)
        }
        "TODO" -> {
            val msg = if (a.isEmpty()) MirExpr.StringConst("An operation is not implemented.")
            else concat(MirExpr.StringConst("An operation is not implemented: "), toStringExpr(single(a[0], out), c, out), out)
            throwNew("MkNotImplementedError", msg, out)
            return MirExpr.Unreachable(TNothing)
        }
        "require", "check" -> if (a.size == 1 || a.size == 2) {
            val cond = single(a[0], out)
            val body = ArrayList<MirStmt>()
            val msg = if (a.size == 2) lazyMessage(a[1], c, body) else MirExpr.StringConst(if (name == "require") "Failed requirement." else "Check failed.")
            throwNew(if (name == "require") "MkIllegalArgumentException" else "MkIllegalStateException", msg, body)
            out += MirStmt.If(MirExpr.Unary(UnaryOp.Not, cond), body, emptyList())
            return MirExpr.UnitValue
        }
        "requireNotNull", "checkNotNull" -> if (a.size == 1 || a.size == 2) {
            val v0 = single(a[0], out)
            val v = if (v0 is MirExpr.Local || v0 is MirExpr.NullConst) v0 else tmpOf(coerceFlexible(v0, v0.type, c, out), out)
            val body = ArrayList<MirStmt>()
            val msg = if (a.size == 2) lazyMessage(a[1], c, body) else MirExpr.StringConst("Required value was null.")
            throwNew(if (name == "requireNotNull") "MkIllegalArgumentException" else "MkIllegalStateException", msg, body)
            if (v.type.isPrimitive) return v
            out += MirStmt.If(MirExpr.Binary(BinaryOp.IdEq, v, MirExpr.NullConst()), body, emptyList())
            return coerceFlexible(v, expected, c, out)
        }
    }
    return null
}
