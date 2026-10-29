
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


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
        val owner = e.owner
        if (owner is IrValueParameter && owner.kind == IrParameterKind.ExtensionReceiver && owner !in locals) unsupported(e, "receiver parameter")
        val l = localOf(owner, e)
        if (owner is IrVariable && owner.isLateinit) {
            val body = ArrayList<MirStmt>()
            val msg = MirExpr.StringConst("lateinit property " + owner.name + " has not been initialized")
            body += MirStmt.Throw(tmpOf(newBuiltin("MkUninitializedPropertyAccessException", msg), body))
            out += MirStmt.If(MirExpr.Binary(BinaryOp.IdEq, MirExpr.Local(l), MirExpr.NullConst()), body, emptyList())
        }
        if (owner in closure.boxed) {
            val rc = refClassFor(declType(owner))
            MirExpr.FieldGet(MirExpr.Local(l), rc.cName, "value", rc.valueType)
        } else MirExpr.Local(l)
    }
    is IrCall -> if (isNoWhenBranch(e)) {
        out += MirStmt.Panic("NoWhenBranchMatchedException")
        MirExpr.Unreachable(TNothing)
    } else call(e, out)
    is IrConstructorCall -> constructorCall(e, out)
    is IrGetEnumValue -> {
        val entry = e.entry
        val cls = entry.parent as IrClass
        enumGet(info(cls), enumEntries(cls).indexOf(entry))
    }
    is IrGetField -> {
        val f = e.field
        val r = e.receiver
        if (r == null) staticFieldGet(f, e) else fieldGet(operands(listOf(r), out)[0], f)
    }
    is IrStringConcatenation -> stringConcat(e, out)
    is IrFunctionExpression -> lambdaValue(e, out)
    is IrClassReference -> classReference(e, out)
    is IrGetClass -> getClassValue(e, out)
    is IrTry -> tryValue(e, out)
    is IrVararg -> varargValue(e, out)
    is IrFunctionReference -> functionReference(e, out)
    is IrPropertyReference ->
        if (e.origin == "PROPERTY_REFERENCE_FOR_DELEGATE") MirExpr.NullConst(anyNullable)
        else unsupported(e, "property reference")
    is IrThrow -> { throwStmt(e, out); MirExpr.Unreachable(TNothing) }
    is IrWhen -> whenValue(e, out)
    is IrComposite -> blockValue(e.statements, e, out)
    is IrBlock -> {
        if (e.origin == "FOR_LOOP") { forLoop(e, out); MirExpr.UnitValue }
        else blockValue(e.statements, e, out)
    }
    is IrTypeOperatorCall -> typeOperator(e, out)
    is IrGetObjectValue -> {
        val c = e.classOwner
        if (c.kotlinFqName == "kotlin.Unit") MirExpr.UnitValue
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
        IrTypeOperator.SAM_CONVERSION -> return samValue(e, out)
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
    if (from is TObject && from.cName == ANY_CNAME && to.isPrimitive) {
        return MirExpr.Unbox(MirExpr.Cast(e, TBoxed(to), MirExpr.CastMode.Unchecked), to)
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

/** The Kotlin qualified name of a MIR type, as `MkTypeInfo.name` spells it. */
internal fun typeQualifiedName(t: MirType): String? = when (t) {
    TInt -> "kotlin.Int"
    TLong -> "kotlin.Long"
    TBoolean -> "kotlin.Boolean"
    TDouble -> "kotlin.Double"
    TChar -> "kotlin.Char"
    TString, TNString -> "kotlin.String"
    is TBoxed -> typeQualifiedName(t.prim)
    is TObject -> t.qualifiedName
    else -> null
}

private fun Ir2Mir.newKClass(name: MirExpr, at: IrElement, type: MirType, out: MutableList<MirStmt>): MirExpr {
    val impl = kclassImpl ?: unsupported(at, "`::class` (the stdlib class kotlin.reflect.KClassImpl is not available)")
    val ctor = impl.declarations.filterIsInstance<IrConstructor>().firstOrNull()
    val cName = ctor?.let { functionNames[it] } ?: unsupported(at, "`::class` (KClassImpl was pruned)")
    val made = tmpOf(MirExpr.New(cName, listOf(name), typeOf(impl)), out)
    return MirExpr.Cast(made, type, MirExpr.CastMode.Unchecked)
}

/** `Foo::class`: a `KClass` carrying the qualified name of the (statically known) class. */
internal fun Ir2Mir.classReference(e: IrClassReference, out: MutableList<MirStmt>): MirExpr {
    val t = mirType(e.classType, e)
    val name = typeQualifiedName(t) ?: unsupported(e, "class reference to '${e.classType}'")
    return newKClass(MirExpr.StringConst(name), e, mirType(e.type, e), out)
}

/** `x::class`: the dynamic class of [x], from its type info name. */
internal fun Ir2Mir.getClassValue(e: IrGetClass, out: MutableList<MirStmt>): MirExpr {
    var v = value(e.argument, out)
    if (v.type.isPrimitive) v = coerceOperand(v, anyNullable, e, out)
    val name = tmpOf(MirExpr.RuntimeCall("mk_type_name", listOf(asAny(v)), TString), out)
    return newKClass(name, e, mirType(e.type, e), out)
}
