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
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements

/* Ir2Mir: calls (user functions, members, builtins, primitives, console, comparisons). */

// ---- calls ------------------------------------------------------------------------

/**
 * Lowers the arguments of a call to [callee] (receiver first, if any) converted to the declared
 * parameter types. Omitted arguments are filled by inlining the default value expression.
 */
internal fun Ir2Mir.callArgs(
    c: IrFunctionAccessExpression,
    callee: IrFunction,
    out: MutableList<MirStmt>,
    sig: IrFunction = callee,
): List<MirExpr> {
    val params = callee.parameters
    val given = c.arguments
    val idxs = params.indices.filter { given.getOrNull(it) != null }
    val vals = operands(idxs.map { given[it]!! }, out)
    val byIndex = HashMap<Int, MirExpr>()
    for ((k, i) in idxs.withIndex()) byIndex[i] = vals[k]
    val result = ArrayList<MirExpr>()
    val saved = ArrayList<Pair<IrValueParameter, MirLocal?>>()
    for ((i, p) in params.withIndex()) {
        val given1 = byIndex[i]
        val v: MirExpr
        val sigParam = sig.parameters.getOrNull(i) ?: p
        if (given1 != null) {
            val pt = mirType(sigParam.type, sigParam)
            v = coerceOperand(given1, pt, c, out)
        } else {
            val d = p.defaultValue?.expression
            v = if (d == null && p.varargElementType != null) emptyVararg(p, c, out)
            else coerceOperand(value(d ?: unsupported(c, "call with omitted argument '${p.name}' of '${callee.name}'"), out), mirType(sigParam.type, sigParam), c, out)
        }
        // make this value visible to the default expressions of later parameters
        if (idxs.size == params.size) {
            result += v
            continue
        }
        val lv: MirExpr = if (v is MirExpr.Local) v else tmpOfPlain(v, out)
        saved += p to locals[p]
        locals[p] = (lv as MirExpr.Local).local
        result += lv
    }
    for ((p, old) in saved) if (old == null) locals.remove(p) else locals[p] = old
    return result
}

/** Like [tmpOf] but only when needed for primitives (constants stay inline in the argument list). */
internal fun Ir2Mir.tmpOfPlain(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    val t = newTemp(v.type)
    out += MirStmt.Declare(t, v)
    return MirExpr.Local(t)
}

internal fun Ir2Mir.call(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val r = callInner(c, out)
    // a call that never returns normally must still be emitted when its (Nothing) value is discarded
    if (r.type == TNothing && (r is MirExpr.Call || r is MirExpr.VCall || r is MirExpr.ICall || r is MirExpr.RuntimeCall || r is MirExpr.New)) {
        out += MirStmt.Eval(r)
        return MirExpr.Unreachable(TNothing)
    }
    return r
}

internal fun Ir2Mir.callInner(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val owner = c.symbol.owner
    val name = owner.name.asString()
    val parent = owner.parent

    lateinitIsInitialized(c, name, if (parent is IrClass) parent.kotlinFqName.asString() else (parent as? IrPackageFragment)?.packageFqName?.asString() ?: "", out)?.let { return it }
    // natively implemented function (@CSymbol, docs/stdlib.md)
    Reachability.cSymbol(owner)?.let { return cSymbolCall(c, it, out) }
    memoryApiCall(c, owner, out)?.let { return it }

    if (isFunctionInvoke(owner)) return invokeFunctionValue(c, out)
    // inline functions with a body (user code, or stdlib code compiled from source) are expanded in place
    if (owner.isInline && owner.body != null && (functionNames[owner] != null || parent !is IrClass)) return inlineCall(c, owner, out)

    // lambda-lifted local function
    liftedFns[owner]?.let { l ->
        val args = liftedArgs(l, c) + callArgs(c, owner, out)
        return adaptResult(MirExpr.Call(l.cName, args, mirType(owner.returnType, owner)), mirType(c.type, c), c, out)
    }
    // user function of this module (top-level)
    functionNames[owner]?.let { cName ->
        if (parent !is IrClass) {
            val args = callArgs(c, owner, out)
            return adaptResult(MirExpr.Call(cName, args, mirType(owner.returnType, owner)), mirType(c.type, c), c, out)
        }
    }
    if (parent is IrClass && parent.kotlinFqName.asString() in RANGES && name == "contains" && c.arguments.size == 2 &&
        progressionOrNull(c.arguments[0]!!)?.let { it.step == null && it.ascending } == true
    ) return rangeContains(c, c.arguments.map { it!! }, out)
    if (parent is IrClass && name == "rangeTo" && c.arguments.size == 2) rangeObject(c, parent, out)?.let { return it }
    if (parent is IrClass && parent.kotlinFqName.asString() == "kotlin.Comparable" && name == "compareTo") return comparableCompareTo(c, out)
    if (parent is IrClass && isEnumSpecial(parent, owner)) return enumSpecialCall(c, parent, owner, out)
    if (parent is IrClass && (parent in classInfos || isAny(parent) || builtinOf(parent) != null)) return memberCall(c, owner, out)
    // fake overrides of Any members declared in user classes have the user class as parent
    val ownerName = when (parent) {
        is IrClass -> parent.kotlinFqName.asString()
        is IrPackageFragment -> parent.packageFqName.asString()
        else -> unsupported(c, "call of '$name'")
    }
    if (parent is IrClass && isEnumEntriesList(c, parent)) return arrayMember(c, name, arrayKindOfFq("kotlin.Array")!!, out)
    if (parent is IrClass) arrayKindOfFq(ownerName)?.let { return arrayMember(c, name, it, out) }
    arrayFunction(c, name, ownerName, out)?.let { return it }
    val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$ownerName.$name'") }

    return when {
        ownerName == "kotlin" && name == "<get-code>" && args.size == 1 ->
            convertTo(operands(args, out)[0], TInt, c)
        ownerName == "kotlin" && name == "plus" && args.size == 2 && args[0].type.classFqName?.asString() == "kotlin.String" -> {
            val v = operands(args, out)
            concat(toStringExpr(v[0], c, out), toStringExpr(v[1], c, out), out)
        }
        ownerName == "kotlin" && name == "toString" && args.size == 1 -> anyMember(0, operands(args, out), c, out)
        ownerName == "kotlin" && name == "hashCode" && args.size == 1 -> anyMember(2, operands(args, out), c, out)
        ownerName == "kotlin.io" && (name == "println" || name == "print") -> consoleCall(c, name, args, out)
        ownerName == "kotlin.internal.ir" -> irBuiltin(c, name, args, out)
        parent is IrClass && ownerName in RANGES && name == "contains" -> rangeContains(c, args, out)
        (ownerName == "kotlin.String" || ownerName == "kotlin.CharSequence") && name == "<get-length>" -> MirExpr.StringLength(operands(args, out)[0])
        parent is IrClass && ownerName == "kotlin.CharSequence" -> primitiveMember(c, "kotlin.String", name, args, out)
        parent is IrClass && ownerName in PRIMITIVES -> primitiveMember(c, ownerName, name, args, out)
        else -> unsupported(c, "call to '${if (parent is IrClass) "$ownerName.$name" else "$ownerName.$name"}'")
    }
}

/** `toString` / `equals` / `hashCode` on any reference through the virtual runtime helpers. */
internal fun Ir2Mir.anyMember(slot: Int, v: List<MirExpr>, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val recv = asAny(boxIfPrim(v[0], at, out))
    return when (slot) {
        0 -> MirExpr.RuntimeCall("mk_to_string", listOf(recv), TString)
        1 -> MirExpr.RuntimeCall("mk_equals", listOf(recv, asAny(boxIfPrim(v[1], at, out))), TBoolean)
        else -> MirExpr.RuntimeCall("mk_hash_code", listOf(recv), TInt)
    }
}

internal fun Ir2Mir.memberCall(c: IrCall, f: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
    // direct access to the backing field for final default accessors
    val real = resolveFake(f)
    (real.parent as? IrClass)?.let { rp ->
        if (builtinOf(rp) != null) builtinMember(c, real, out)?.let { return it }
        if (rp.kotlinFqName.asString() == "kotlin.Enum" && c.superQualifierSymbol == null) {
            val vals = operands(c.arguments.map { it ?: unsupported(c, "enum member") }, out)
            val recv = vals[0]
            val owner = (recv.type as? TObject)?.cName ?: unsupported(c, "enum member on a non-enum receiver")
            return when (real.name.asString()) {
                "<get-name>" -> MirExpr.FieldGet(recv, owner, "\$name", TString)
                "<get-ordinal>" -> MirExpr.FieldGet(recv, owner, "\$ordinal", TInt)
                "toString" -> MirExpr.RuntimeCall("mk_to_string", listOf(asAny(recv)), TString)
                "hashCode" -> MirExpr.RuntimeCall("mk_hash_code", listOf(asAny(recv)), TInt)
                "equals" -> MirExpr.RuntimeCall("mk_equals", listOf(asAny(recv), asAny(vals[1])), TBoolean)
                "compareTo" -> MirExpr.Binary(
                    BinaryOp.Sub, MirExpr.FieldGet(recv, owner, "\$ordinal", TInt),
                    MirExpr.FieldGet(vals[1], owner, "\$ordinal", TInt),
                )
                else -> unsupported(c, "call to 'kotlin.Enum.${real.name}'")
            }
        }
    }
    val prop = real.correspondingPropertySymbol?.owner
    if (c.superQualifierSymbol == null && real.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
        real.modality == Modality.FINAL && prop?.backingField != null && !isAny(real.parent as IrClass) && !prop.isLateinit
    ) {
        val field = prop.backingField!!
        val args = callArgs(c, f, out)
        if (real.parameters.size == 1) return adaptResult(fieldGet(args[0], field), mirType(c.type, c), c, out)
        out += MirStmt.SetField(args[0], fieldOwner(field).cName, field.name.asString(), mirType(field.type, field), args[1])
        return MirExpr.UnitValue
    }

    val d: Dispatch = if (c.superQualifierSymbol != null) {
        val t = resolveFake(f)
        if (parentClass(t)?.let { isAny(it) } == true) Dispatch.AnyMember(anyMember(topClass(f) ?: f) ?: unsupported(c, "super call of '${f.name}'"))
        else Dispatch.Direct(t)
    } else dispatchOf(f)

    if (d is Dispatch.AnyMember) {
        val vals = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
        val recv = asAny(vals[0])
        val viaSuper = c.superQualifierSymbol != null
        return when (d.slot) {
            0 -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_to_string" else "mk_to_string", listOf(recv), TString)
            1 -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_equals" else "mk_equals", listOf(recv, asAny(boxIfPrim(vals[1], c, out))), TBoolean)
            else -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_hash_code" else "mk_hash_code", listOf(recv), TInt)
        }
    }

    val sig = signatureOf(d, f)
    val args = callArgs(c, f, out, sig)
    return adaptResult(dispatchWithArgs(d, f, args, c), mirType(c.type, c), c, out)
}

/** The function whose (erased) signature the call is compiled against: the vtable / itable root for dynamic calls. */
internal fun Ir2Mir.signatureOf(d: Dispatch, f: IrSimpleFunction): IrSimpleFunction = when (d) {
    is Dispatch.Direct -> d.target
    is Dispatch.Virtual -> topClass(f) ?: f
    is Dispatch.Interface -> interfaceRoot(f)
    is Dispatch.AnyMember -> f
}

internal fun Ir2Mir.dispatchWithArgs(d: Dispatch, f: IrSimpleFunction, args: List<MirExpr>, at: IrElement): MirExpr {
    val sig = signatureOf(d, f)
    val rt = mirType(sig.returnType, sig)
    val ptypes = sig.parameters.drop(1).map { mirType(it.type, it) }
    return when (d) {
        is Dispatch.Direct -> {
            val cName = functionNames[d.target] ?: unsupported(at, "call of '${d.target.name}'")
            if (!hasBody(d.target)) unsupported(at, "call of abstract function '${f.name}'")
            MirExpr.Call(cName, args, rt)
        }
        is Dispatch.Virtual -> MirExpr.VCall(d.slot, args[0], args.drop(1), ptypes, rt)
        is Dispatch.Interface -> MirExpr.ICall(d.cName, d.index, args[0], args.drop(1), ptypes, rt)
        is Dispatch.AnyMember -> error("unreachable")
    }
}

/** Dispatch of a member call whose arguments are already lowered (function references). */
internal fun Ir2Mir.memberDispatch(f: IrSimpleFunction, superQualified: Boolean, args: List<MirExpr>, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val d: Dispatch = if (superQualified) Dispatch.Direct(resolveFake(f)) else dispatchOf(f)
    if (d is Dispatch.AnyMember) {
        val recv = asAny(boxIfPrim(args[0], at, out))
        return when (d.slot) {
            0 -> MirExpr.RuntimeCall("mk_to_string", listOf(recv), TString)
            1 -> MirExpr.RuntimeCall("mk_equals", listOf(recv, asAny(boxIfPrim(args[1], at, out))), TBoolean)
            else -> MirExpr.RuntimeCall("mk_hash_code", listOf(recv), TInt)
        }
    }
    val sig = signatureOf(d, f)
    val coerced = args.mapIndexed { i, a -> if (i == 0) a else coerceOperand(a, mirType(sig.parameters[i].type, sig.parameters[i]), at, out) }
    return dispatchWithArgs(d, f, coerced, at)
}

/** `a..b` used as a value (not a `for` / `in` operand): `IntRange(a, b)` of the stdlib. */
internal fun Ir2Mir.rangeObject(c: IrCall, parent: IrClass, out: MutableList<MirStmt>): MirExpr? {
    val fq = parent.kotlinFqName.asString()
    if (fq != "kotlin.Int" && fq != "kotlin.Long" && fq != "kotlin.Char") return null
    val cls = c.type.classOrNull?.owner ?: return null
    if (cls.kotlinFqName.asString() !in RANGES) return null
    val ctor = cls.constructors.firstOrNull() ?: return null
    val cName = functionNames[ctor] ?: unsupported(c, "range value '${cls.name}' (the stdlib class is not available)")
    val vals = operands(c.arguments.map { it!! }, out)
    val et = if (cls.name.asString() == "IntRange") TInt else if (cls.name.asString() == "LongRange") TLong else TChar
    return MirExpr.New(cName, vals.map { convertTo(it, et, c) }, typeOf(cls))
}

/** `x in a..b` / `x in a until b` (the range is built in place; no range object exists). */
internal fun Ir2Mir.rangeContains(c: IrCall, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    if (args.size != 2) unsupported(c, "range contains")
    val p = progression(args[0])
    if (p.step != null || !p.ascending) unsupported(c, "`in` on a progression with step or direction")
    val vals = operands(listOf(p.start, p.end, args[1]), out)
    val t = widest(widest(vals[0].type, vals[1].type, c), vals[2].type, c)
    val parts = vals.map { v ->
        val x = convertTo(widenCharIf(v, t), t, c)
        if (x is MirExpr.IntConst || x is MirExpr.LongConst || x is MirExpr.CharConst || x is MirExpr.Local) x
        else {
            val tmp = newTemp(x.type)
            out += MirStmt.Declare(tmp, x)
            MirExpr.Local(tmp)
        }
    }
    val lo = MirExpr.Binary(BinaryOp.Ge, parts[2], parts[0])
    val hi = MirExpr.Binary(if (p.inclusive) BinaryOp.Le else BinaryOp.Lt, parts[2], parts[1])
    return MirExpr.Binary(BinaryOp.AndAnd, lo, hi)
}

internal fun Ir2Mir.widenCharIf(e: MirExpr, t: MirType): MirExpr = if (e.type == TChar && t != TChar) MirExpr.Convert(e, TInt) else e

internal fun Ir2Mir.consoleCall(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    if (args.isEmpty()) {
        if (name == "println") return MirExpr.RuntimeCall("mk_println_empty", emptyList(), TUnit)
        unsupported(c, "print() without argument")
    }
    if (args.size != 1) unsupported(c, "call to '$name'")
    val v = operands(args, out)[0]
    if (name == "print") {
        val fn = when (v.type) {
            TString -> "mk_print_string"
            TInt -> "mk_print_int"
            TLong -> "mk_print_long"
            TDouble -> "mk_print_double"
            TBoolean -> "mk_print_boolean"
            TChar -> "mk_print_char"
            else -> null
        }
        if (fn != null) return MirExpr.RuntimeCall(fn, listOf(v), TUnit)
        return MirExpr.RuntimeCall("mk_print_string", listOf(toStringExpr(v, c, out)), TUnit)
    }
    return when (v.type) {
        TString -> MirExpr.RuntimeCall("mk_println_string", listOf(v), TUnit)
        TInt -> MirExpr.RuntimeCall("mk_println_int", listOf(v), TUnit)
        TLong -> MirExpr.RuntimeCall("mk_println_long", listOf(v), TUnit)
        TDouble -> MirExpr.RuntimeCall("mk_println_double", listOf(v), TUnit)
        TBoolean -> MirExpr.RuntimeCall("mk_println_boolean", listOf(v), TUnit)
        TChar -> MirExpr.RuntimeCall("mk_println_char", listOf(v), TUnit)
        else -> if (v.type.isRef) MirExpr.RuntimeCall("mk_println_string", listOf(toStringExpr(v, c, out)), TUnit)
        else unsupported(c, "println of a value of type ${v.type}")
    }
}

internal fun Ir2Mir.irBuiltin(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    if (name == "CHECK_NOT_NULL") {
        val v = operands(args, out)
        if (v.size != 1) unsupported(c, "compiler intrinsic '$name'")
        val t = v[0].type
        return if (t is TBoxed) MirExpr.Unbox(v[0], t.prim) else if (t.isRef) MirExpr.NotNull(v[0]) else v[0]
    }
    val op = when (name) {
        "less" -> BinaryOp.Lt
        "lessOrEqual" -> BinaryOp.Le
        "greater" -> BinaryOp.Gt
        "greaterOrEqual" -> BinaryOp.Ge
        "EQEQ", "ieee754equals" -> BinaryOp.Eq
        "EQEQEQ" -> BinaryOp.IdEq
        else -> unsupported(c, "compiler intrinsic '$name'")
    }
    val v = operands(args, out)
    if (v.size != 2) unsupported(c, "compiler intrinsic '$name'")
    val ta = v[0].type
    val tb = v[1].type
    if (ta == TUnit || ta == TNothing) unsupported(c, "comparison of $ta")
    if (op == BinaryOp.Eq || op == BinaryOp.IdEq) {
        if (ta.isRef || tb.isRef) return refEquality(op, v[0], v[1], c, out)
    }
    if (ta != tb) unsupported(c, "comparison of $ta with $tb")
    if (ta == TString && op != BinaryOp.Eq) unsupported(c, "ordering comparison of strings")
    return MirExpr.Binary(if (op == BinaryOp.IdEq) BinaryOp.Eq else op, v[0], v[1])
}

internal fun Ir2Mir.refEquality(op: BinaryOp, a: MirExpr, b: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val ta = a.type
    val tb = b.type
    if (ta === TNull && tb === TNull) return MirExpr.BooleanConst(true)
    if (ta === TNull || tb === TNull) {
        val other = if (ta === TNull) b else a
        if (!other.type.isRef) return MirExpr.BooleanConst(false)
        return MirExpr.Binary(BinaryOp.IdEq, other, MirExpr.NullConst())
    }
    if (op == BinaryOp.IdEq) {
        return MirExpr.Binary(BinaryOp.IdEq, boxIfPrim(a, at, out), boxIfPrim(b, at, out))
    }
    if (ta == TString && tb == TString) return MirExpr.Binary(BinaryOp.Eq, a, b)
    return MirExpr.RuntimeCall("mk_equals", listOf(asAny(boxIfPrim(a, at, out)), asAny(boxIfPrim(b, at, out))), TBoolean)
}

internal fun Ir2Mir.arithmeticOp(name: String): BinaryOp? = when (name) {
    "plus" -> BinaryOp.Add; "minus" -> BinaryOp.Sub; "times" -> BinaryOp.Mul
    "div" -> BinaryOp.Div; "rem" -> BinaryOp.Rem
    "and" -> BinaryOp.And; "or" -> BinaryOp.Or; "xor" -> BinaryOp.Xor
    "shl" -> BinaryOp.Shl; "shr" -> BinaryOp.Shr; "ushr" -> BinaryOp.Ushr
    else -> null
}

internal fun Ir2Mir.primitiveMember(
    c: IrCall,
    cls: String,
    name: String,
    args: List<IrExpression>,
    out: MutableList<MirStmt>,
): MirExpr {
    val v = operands(args, out)
    val recv = v[0]
    val resultType = mirType(c.type, c)

    // String members
    if (cls == "kotlin.String") {
        return when (name) {
            "plus" -> concat(recv, toStringExpr(v[1], c, out), out)
            "toString" -> recv
            "hashCode" -> MirExpr.RuntimeCall("mk_string_hash", listOf(recv), TInt)
            "equals" -> anyMember(1, v, c, out)
            "get" -> MirExpr.RuntimeCall("mk_string_get_char", listOf(recv, v[1]), TChar)
            else -> unsupported(c, "call to 'kotlin.String.$name'")
        }
    }

    // conversions and toString
    when (name) {
        "toString" -> return toStringExpr(recv, c, out)
        "toInt" -> return convertTo(recv, TInt, c)
        "toLong" -> return convertTo(recv, TLong, c)
        "toDouble" -> return convertTo(recv, TDouble, c)
        "toChar" -> return convertTo(recv, TChar, c)
        "<get-code>" -> return convertTo(recv, TInt, c)
        "hashCode" -> return when (recv.type) {
            TInt -> recv
            TChar -> MirExpr.Convert(recv, TInt)
            TBoolean -> MirExpr.Conditional(recv, MirExpr.IntConst(1231), MirExpr.IntConst(1237))
            TLong -> MirExpr.Convert(
                MirExpr.Binary(BinaryOp.Xor, recv, MirExpr.Binary(BinaryOp.Ushr, recv, MirExpr.IntConst(32))), TInt,
            )
            else -> anyMember(2, v, c, out)
        }
        "equals" -> if (v.size == 2) return anyMember(1, v, c, out)
    }

    if (cls == "kotlin.Boolean") {
        return when (name) {
            "not" -> MirExpr.Unary(UnaryOp.Not, recv)
            "and" -> MirExpr.Binary(BinaryOp.And, recv, v[1])
            "or" -> MirExpr.Binary(BinaryOp.Or, recv, v[1])
            "xor" -> MirExpr.Binary(BinaryOp.Xor, recv, v[1])
            else -> unsupported(c, "call to 'kotlin.Boolean.$name'")
        }
    }

    // unary numeric
    if (v.size == 1) {
        val numeric = if (recv.type == TChar) MirExpr.Convert(recv, TInt) else recv
        return when (name) {
            "unaryMinus" -> MirExpr.Unary(UnaryOp.Neg, numeric)
            "unaryPlus" -> numeric
            "inv" -> MirExpr.Unary(UnaryOp.Inv, recv)
            "inc" -> convertTo(incDec(numeric, BinaryOp.Add), recv.type, c)
            "dec" -> convertTo(incDec(numeric, BinaryOp.Sub), recv.type, c)
            else -> unsupported(c, "call to '$cls.$name'")
        }
    }

    // compareTo
    if (name == "compareTo") {
        val t = widest(recv.type, v[1].type, c)
        val a = convertTo(widenChar(recv), t, c)
        val b = convertTo(widenChar(v[1]), t, c)
        // (a > b) - (a < b) with Int result
        return MirExpr.Binary(
            BinaryOp.Sub,
            convertTo(MirExpr.Conditional(MirExpr.Binary(BinaryOp.Gt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)), TInt, c),
            MirExpr.Conditional(MirExpr.Binary(BinaryOp.Lt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)),
        )
    }

    // binary arithmetic / bit operations: compute in the wider type
    val op = arithmeticOp(name) ?: unsupported(c, "call to '$cls.$name'")
    if (op.isShift) {
        return MirExpr.Binary(op, recv, v[1])
    }
    if (recv.type == TChar || v[1].type == TChar) {
        // Char + Int -> Char, Char - Int -> Char, Char - Char -> Int
        val t = TInt
        val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
        return convertTo(r, resultType, c)
    }
    val t = widest(recv.type, v[1].type, c)
    val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
    return convertTo(r, resultType, c)
}

internal fun Ir2Mir.widenChar(e: MirExpr): MirExpr = if (e.type == TChar) MirExpr.Convert(e, TInt) else e

internal fun Ir2Mir.incDec(e: MirExpr, op: BinaryOp): MirExpr = when (e.type) {
    TInt -> MirExpr.Binary(op, e, MirExpr.IntConst(1))
    TLong -> MirExpr.Binary(op, e, MirExpr.LongConst(1))
    TDouble -> MirExpr.Binary(op, e, MirExpr.DoubleConst(1.0))
    else -> throw IllegalStateException("inc/dec on ${e.type}")
}

internal fun Ir2Mir.widest(a: MirType, b: MirType, at: IrElement): MirType {
    fun rank(t: MirType) = when (t) {
        TInt, TChar -> 1; TLong -> 2; TDouble -> 3
        else -> unsupported(at, "arithmetic on $t")
    }
    return if (rank(a) >= rank(b)) (if (a == TChar) TInt else a) else b
}


/** `Comparable.compareTo` on a type-parameter-typed receiver: boxed primitives, strings or the Comparable itable. */
internal fun Ir2Mir.comparableCompareTo(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val v = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
    val a = asAny(boxIfPrim(v[0], c, out))
    val b = asAny(boxIfPrim(v[1], c, out))
    val cmp = classInfos.keys.firstOrNull { it.kotlinFqName.asString() == "kotlin.Comparable" }
    return MirExpr.RuntimeCall("mk_compare_to", listOf(a, b, MirExpr.TypeInfoOf(cmp?.let { typeOf(it) })), TInt)
}

/** `get` / `size` of `Enum.entries` (a list in Kotlin; here the same array as `values()`). */
internal fun Ir2Mir.isEnumEntriesList(c: IrCall, parent: IrClass): Boolean {
    val n = c.symbol.owner.name.asString()
    if (n != "get" && n != "<get-size>") return false
    return c.arguments.getOrNull(0)?.type?.classFqName?.asString() == "kotlin.enums.EnumEntries"
}

/** `values()`, `valueOf(String)` and `entries` of an enum class (generated functions, see Ir2MirClasses). */
internal fun Ir2Mir.enumSpecialCall(c: IrCall, enumClass: IrClass, f: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
    val inf = info(enumClass)
    val arrType = arrayType(arrayKindOfFq("kotlin.Array")!!)
    return when (f.name.asString()) {
        "values", "<get-entries>" -> MirExpr.Call(inf.cName + "_values", emptyList(), arrType)
        else -> {
            val a = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
            MirExpr.Call(inf.cName + "_valueOf", a, typeOf(enumClass))
        }
    }
}
