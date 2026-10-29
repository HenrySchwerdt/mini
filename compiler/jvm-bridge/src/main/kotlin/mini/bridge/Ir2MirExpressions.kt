@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirField
import mini.backend.MirFunction
import mini.backend.MirItable
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TBoxed
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TNull
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.UnaryOp
import mini.backend.asNullable
import mini.backend.isOwned
import mini.backend.isPrimitive
import mini.backend.isRef
import mini.backend.nonNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements

/* Ir2Mir: expression lowering, operand ordering, type operators, constants, string templates. */

// ---- expressions ------------------------------------------------------------------

/** Free of side effects and of dependence on mutable state, so evaluation order does not matter. */
internal fun Ir2Mir.isPure(e: MirExpr): Boolean = when (e) {
    is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst, is MirExpr.CharConst,
    is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local, is MirExpr.NullConst, is MirExpr.Unreachable -> true
    is MirExpr.Binary -> isPure(e.left) && isPure(e.right)
    is MirExpr.Unary -> isPure(e.operand)
    is MirExpr.Convert -> isPure(e.operand)
    is MirExpr.StringLength -> isPure(e.operand)
    is MirExpr.Cast -> isPure(e.operand)
    is MirExpr.IsInstance -> isPure(e.operand)
    is MirExpr.Conditional -> isPure(e.cond) && isPure(e.thenValue) && isPure(e.elseValue)
    else -> false
}

/**
 * Lowers [args] left to right. If a later operand needs hoisted statements, the
 * values of earlier operands are first stored in temporaries so evaluation order is kept;
 * operands with side effects (calls, field reads) are stored in temporaries unless last.
 */
internal fun Ir2Mir.operands(args: List<IrExpression>, out: MutableList<MirStmt>): List<MirExpr> {
    val results = ArrayList<MirExpr>()
    for ((idx, a) in args.withIndex()) {
        val before = out.size
        var v = value(a, out)
        if (out.size > before) {
            var insertAt = before
            for (j in results.indices) {
                val r = results[j]
                if (r is MirExpr.IntConst || r is MirExpr.LongConst || r is MirExpr.DoubleConst ||
                    r is MirExpr.BooleanConst || r is MirExpr.CharConst || r is MirExpr.StringConst ||
                    r is MirExpr.NullConst
                ) continue
                val t = newTemp(r.type)
                out.add(insertAt++, MirStmt.Declare(t, r))
                results[j] = MirExpr.Local(t)
            }
        }
        if (idx < args.size - 1 && !isPure(v) && v.type != TUnit && v.type != TNothing) {
            val t = newTemp(v.type)
            out += MirStmt.Declare(t, v)
            v = MirExpr.Local(t)
        }
        results += v
    }
    return results
}

/** Lowers [e]; the result is never an owned expression or a field read of reference type. */
internal fun Ir2Mir.value(e: IrExpression, out: MutableList<MirStmt>): MirExpr {
    val v = valueRaw(e, out)
    return if (v.isOwned() || (v is MirExpr.FieldGet && v.type.isRef)) tmpOf(v, out) else v
}

/** Lowers [e] for storing into a location of type [to] (owned results are not wrapped in a temporary). */
internal fun Ir2Mir.bindValue(e: IrExpression, to: MirType, out: MutableList<MirStmt>): MirExpr {
    var v = value(e, out)
    if (v.type.isRef && to.isRef) v = consumeOwned(v, out)
    return convertTo(v, to, e)
}

internal fun Ir2Mir.coerceOperand(v: MirExpr, to: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val c = convertTo(v, to, at)
    return if (c.isOwned()) tmpOf(c, out) else c
}

internal fun Ir2Mir.valueRaw(e: IrExpression, out: MutableList<MirStmt>): MirExpr = when (e) {
    is IrConst -> constant(e)
    is IrGetValue -> {
        val owner = e.symbol.owner
        MirExpr.Local(localOf(owner, e))
    }
    is IrCall -> if (isNoWhenBranch(e)) {
        out += MirStmt.Panic("NoWhenBranchMatchedException")
        MirExpr.Unreachable(TNothing)
    } else call(e, out)
    is IrConstructorCall -> constructorCall(e, out)
    is IrGetEnumValue -> {
        val entry = e.symbol.owner
        val cls = entry.parent as IrClass
        enumGet(info(cls), enumEntries(cls).indexOf(entry))
    }
    is IrGetField -> {
        val f = e.symbol.owner
        val r = e.receiver ?: unsupported(e, "static field")
        fieldGet(operands(listOf(r), out)[0], f)
    }
    is IrStringConcatenation -> stringConcat(e, out)
    is IrWhen -> whenValue(e, out)
    is IrComposite -> blockValue(e.statements, e, out)
    is IrBlock -> {
        if (e.origin?.debugName == "FOR_LOOP") { forLoop(e, out); MirExpr.UnitValue }
        else blockValue(e.statements, e, out)
    }
    is IrTypeOperatorCall -> typeOperator(e, out)
    is IrGetObjectValue -> {
        val c = e.symbol.owner
        if (c.kotlinFqName.asString() == "kotlin.Unit") MirExpr.UnitValue
        else if (c in classInfos) MirExpr.ObjectGet(info(c).cName, typeOf(c))
        else unsupported(e, "object reference '${c.kotlinFqName}'")
    }
    is IrSetValue, is IrSetField, is IrWhileLoop, is IrDoWhileLoop, is IrInstanceInitializerCall, is IrDelegatingConstructorCall ->
        { stmt(e, out); MirExpr.UnitValue }
    is IrReturn, is IrBreak, is IrContinue -> { stmt(e, out); MirExpr.Unreachable(TNothing) }
    else -> unsupported(e, describe(e))
}

internal fun Ir2Mir.typeOperator(e: IrTypeOperatorCall, out: MutableList<MirStmt>): MirExpr {
    when (e.operator) {
        IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> { stmt(e.argument, out); return MirExpr.UnitValue }
        IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF -> {
            var v = value(e.argument, out)
            if (v.type.isPrimitive) v = coerceOperand(v, anyNullable, e, out)
            val target = mirType(e.typeOperand, e)
            val test: MirExpr = MirExpr.IsInstance(v, if (target.isPrimitive) TBoxed(target) else target.nonNull())
            return if (e.operator == IrTypeOperator.INSTANCEOF) test else MirExpr.Unary(UnaryOp.Not, test)
        }
        IrTypeOperator.CAST, IrTypeOperator.SAFE_CAST -> {
            var v = value(e.argument, out)
            val target = mirType(e.type, e)
            val safe = e.operator == IrTypeOperator.SAFE_CAST
            if (v.type.isPrimitive) v = coerceOperand(v, anyNullable, e, out)
            if (target.isPrimitive) {
                return MirExpr.Unbox(MirExpr.Cast(v, TBoxed(target), MirExpr.CastMode.Checked), target)
            }
            return MirExpr.Cast(v, target, if (safe) MirExpr.CastMode.Safe else MirExpr.CastMode.Checked)
        }
        IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> {
            val v = value(e.argument, out)
            val target = mirType(e.type, e)
            if (v.type == target || v.type == TNothing) return v
            if (v.type.isRef && target.isRef) return MirExpr.Cast(v, target, MirExpr.CastMode.Unchecked)
            if (v.type.isRef && v.type !is TBoxed && target.isPrimitive) {
                return MirExpr.Unbox(MirExpr.Cast(v, TBoxed(target), MirExpr.CastMode.Unchecked), target)
            }
            return coerceOperand(v, target, e, out)
        }
        else -> unsupported(e, "type operator ${e.operator}")
    }
}

internal fun Ir2Mir.blockValue(statements: List<IrStatement>, at: IrExpression, out: MutableList<MirStmt>): MirExpr {
    if (statements.isEmpty()) return MirExpr.UnitValue
    for (s in statements.dropLast(1)) stmt(s, out)
    val last = statements.last()
    return if (last is IrExpression) value(last, out) else { stmt(last, out); MirExpr.UnitValue }
}

internal fun Ir2Mir.constant(c: IrConst): MirExpr = when (val v = c.value) {
    is Int -> MirExpr.IntConst(v)
    is Long -> MirExpr.LongConst(v)
    is Double -> MirExpr.DoubleConst(v)
    is Boolean -> MirExpr.BooleanConst(v)
    is Char -> MirExpr.CharConst(v.code)
    is String -> MirExpr.StringConst(v)
    null -> MirExpr.NullConst()
    else -> unsupported(c, "constant of type ${v::class.simpleName}")
}

internal fun Ir2Mir.convertTo(e: MirExpr, to: MirType, at: IrElement): MirExpr {
    if (e.type == to || e.type == TNothing) return e
    val from = e.type
    if (from.isPrimitive && to.isPrimitive) {
        val ok = (from == TInt || from == TLong || from == TChar || from == TDouble) &&
            (to == TInt || to == TLong || to == TChar || to == TDouble)
        if (!ok) unsupported(at, "conversion from $from to $to")
        return MirExpr.Convert(e, to)
    }
    if (from === TNull && to.isRef) return MirExpr.NullConst(to)
    if (from.isPrimitive && to.isRef) {
        if (to == TBoxed(from) || (to is TObject && to.cName == ANY_CNAME)) return MirExpr.Box(e, to)
        unsupported(at, "conversion from $from to $to")
    }
    if (from is TBoxed && to.isPrimitive) {
        if (from.prim == to) return MirExpr.Unbox(e, to)
        unsupported(at, "conversion from $from to $to")
    }
    if (from.isRef && to.isRef) return e // C-level retyping is done by the emitter
    unsupported(at, "conversion from $from to $to")
}

internal fun Ir2Mir.asAny(e: MirExpr): MirExpr =
    if (e.type is TObject && (e.type as TObject).cName == ANY_CNAME) e else MirExpr.Cast(e, anyNullable, MirExpr.CastMode.Unchecked)

internal fun Ir2Mir.boxIfPrim(e: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr =
    if (e.type.isPrimitive) coerceOperand(e, TBoxed(e.type), at, out) else e

/** String conversion; the result is a String local, constant or temporary (never owned inline). */
internal fun Ir2Mir.toStringExpr(e: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val call = when (e.type) {
        TString -> return e
        TInt -> MirExpr.RuntimeCall("mk_string_from_int", listOf(e), TString)
        TLong -> MirExpr.RuntimeCall("mk_string_from_long", listOf(e), TString)
        TDouble -> MirExpr.RuntimeCall("mk_string_from_double", listOf(e), TString)
        TBoolean -> MirExpr.RuntimeCall("mk_string_from_boolean", listOf(e), TString)
        TChar -> MirExpr.RuntimeCall("mk_string_from_char", listOf(e), TString)
        else -> if (e.type.isRef) MirExpr.RuntimeCall("mk_to_string", listOf(asAny(e)), TString)
        else unsupported(at, "string conversion of a value of type ${e.type}")
    }
    return tmpOf(call, out)
}

internal fun Ir2Mir.concat(a: MirExpr, b: MirExpr, out: MutableList<MirStmt>): MirExpr {
    if (a is MirExpr.StringConst && b is MirExpr.StringConst) return MirExpr.StringConst(a.value + b.value)
    return tmpOf(MirExpr.RuntimeCall("mk_string_concat", listOf(a, b), TString), out)
}

internal fun Ir2Mir.stringConcat(e: IrStringConcatenation, out: MutableList<MirStmt>): MirExpr {
    val vals = operands(e.arguments, out)
    var acc: MirExpr? = null
    for (v in vals) {
        val s = toStringExpr(v, e, out)
        acc = if (acc == null) s else concat(acc, s, out)
    }
    return acc ?: MirExpr.StringConst("")
}
