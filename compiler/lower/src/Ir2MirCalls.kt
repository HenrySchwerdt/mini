
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


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
            val d = defaultValueOf(callee, i, p)
            v = if (d == null && p.varargElementType != null) emptyVararg(p, c, out)
            else coerceOperand(value(d ?: unsupported(c, if (p.name == "\$completion") "suspension point ('${callee.name}') in a lambda that is not a suspend lambda, for example the lambda of an inline function: not supported yet" else "call with omitted argument '${p.name}' of '${callee.name}'"), out), mirType(sigParam.type, sigParam), c, out)
        }
        // make this value visible to the default expressions of later parameters
        if (idxs.size == params.size) {
            result += v
            continue
        }
        val lv: MirExpr = if (v is MirExpr.Local) v else tmpOfPlain(v, out)
        saved += p to locals[p]
        locals[p] = (lv as MirExpr.Local).local
        // the default value of a parameter of an inner class (its constructor or a member) may read the outer instances (`this@Outer`):
        // the dispatch argument is the instance of the nearest one (of the class itself for a member), the others are behind `$outer`
        if (i == 0 && p.kind == IrParameterKind.DispatchReceiver && callee.parent is IrClass) {
            val owner = callee.parent as IrClass
            var k: IrClass? = if (callee is IrConstructor) (if (owner.isInner) owner.parent as? IrClass else null) else (if (owner.isInner) owner else null)
            if (callee is IrConstructor && k != null) {
                // the dispatch argument is the outer instance
                val ot = k.thisReceiver
                if (ot != null) { saved += ot to locals[ot]; locals[ot] = lv.local }
            }
            var cur: MirLocal = lv.local
            while (k != null && k.isInner) {
                val oc = k.parent as? IrClass ?: break
                val l = MirLocal(localCounter++, "outer", outerType(oc))
                out += MirStmt.Declare(l, MirExpr.FieldGet(MirExpr.Local(cur), info(k).cName, "\$outer", l.type))
                val ot = oc.thisReceiver
                if (ot != null) { saved += ot to locals[ot]; locals[ot] = l }
                cur = l
                k = oc
            }
        }
        // the default of an overridden function reads the parameters of that function
        if (callee is IrSimpleFunction) for (o in allOverridden(callee)) {
            val op = o.parameters.getOrNull(i) ?: continue
            if (op === p) continue
            saved += op to locals[op]
            locals[op] = lv.local
        }
        result += lv
    }
    for ((p, old) in saved) if (old == null) locals.remove(p) else locals[p] = old
    return result
}

/** The default value expression of parameter [i] of [callee]: its own, or that of a function it overrides. */
internal fun Ir2Mir.defaultValueOf(callee: IrFunction, i: Int, p: IrValueParameter): IrExpression? {
    p.defaultValue?.expression?.let { return it }
    if (callee !is IrSimpleFunction) return null
    for (o in allOverridden(callee)) {
        if (o === callee) continue
        o.parameters.getOrNull(i)?.defaultValue?.expression?.let { return it }
    }
    return null
}

/** Like [tmpOf] but only when needed for primitives (constants stay inline in the argument list). */
internal fun Ir2Mir.tmpOfPlain(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    if (v.type == TUnit) {
        if (v !== MirExpr.UnitValue) out += MirStmt.Eval(v)
        return MirExpr.UnitValue
    }
    val t = newTemp(v.type)
    out += MirStmt.Declare(t, v)
    return MirExpr.Local(t)
}

internal fun Ir2Mir.call(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val r = callInner(c, out)
    // a call that never returns normally must still be emitted when its (Nothing) value is discarded
    if (r.type == TNothing && (r is MirExpr.Call || r is MirExpr.VCall || r is MirExpr.ICall || r is MirExpr.RuntimeCall || r is MirExpr.New || r is MirExpr.CCall || r is MirExpr.CCallIndirect)) {
        out += MirStmt.Eval(r)
        return MirExpr.Unreachable(TNothing)
    }
    return guardUnsetGetterResult(c, r, out)
}

internal fun Ir2Mir.callInner(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val owner = c.callee
    val name = owner.name
    val parent = owner.parent

    lateinitIsInitialized(c, name, if (parent is IrClass) parent.kotlinFqName else (parent as? IrFile)?.packageFqName ?: "", out)?.let { return it }
    // natively implemented function (@CSymbol, docs/stdlib.md)
    Reachability.cSymbol(owner)?.let { return cSymbolCall(c, it, out) }
    // C interop: external functions of C, the kotlinx.cinterop intrinsics, @CStruct fields
    interopCall(c, owner, out)?.let { return it }
    // enumValues<E>() / enumValueOf<E>(name) / enumEntries<E>(): the generated values / valueOf / entries of E
    if (parent is IrFile && parent.packageFqName == "kotlin" && owner.isInline && c.typeArguments.size == 1 &&
        (name == "enumValues" || name == "enumValueOf" || name == "enumEntries")
    ) {
        val ec = substituted(c.typeArguments[0] ?: unsupported(c, "$name without a type argument")).classOrNull
        if (ec != null && ec.kind == IrClassKind.ENUM_CLASS && ec in classInfos) {
            return enumSpecialCall(c, ec, when (name) { "enumValues" -> "values"; "enumValueOf" -> "valueOf"; else -> "<get-entries>" }, out)
        }
        unsupported(c, "$name of a type that is not an enum class")
    }
    memoryApiCall(c, owner, out)?.let { return it }
    // `arrayOf` / `emptyArray` are made here, not called: the array records the element type of the call (docs/memory-model.md, "Reified object arrays")
    if (parent is IrFile && parent.packageFqName == "kotlin" && (name == "arrayOf" || name == "emptyArray")) arrayFunction(c, name, "kotlin", out)?.let { return it }

    if (parent is IrClass && parent.kotlinFqName == "kotlin.Nothing") {
        // a member called on a value of type Nothing (`null?.toString()`, `p.toString()` with `p: Nothing`): the receiver never completes
        for (a in c.arguments) if (a != null) stmt(a, out)
        return MirExpr.Unreachable(TNothing)
    }
    if (isFunctionInvoke(owner)) return invokeFunctionValue(c, out)
    // inline functions with a body (user code, or stdlib code compiled from source) are expanded in place
    if (owner.isInline && owner.body != null && (functionNames[owner] != null || parent !is IrClass)) return inlineCall(c, owner, out)
    // an inline member inherited through a fake override (`B().baz<T>()` where `A` declares `inline fun <reified T> baz()`): expand the real one
    if (owner.isInline && owner.isFakeOverride && owner.body == null) {
        val real = resolveFake(owner)
        if (real !== owner && real.isInline && real.body != null && functionNames[real] != null && real.typeParameters.any { it.reified }) return inlineCall(c, real, out)
    }

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
    if (parent is IrClass && parent.kotlinFqName in RANGES && name == "contains" && c.arguments.size == 2 &&
        progressionOrNull(c.arguments[0]!!)?.let { it.step == null && it.ascending } == true
    ) return rangeContains(c, c.arguments.map { it!! }, out)
    if (parent is IrClass && name == "rangeTo" && c.arguments.size == 2) rangeObject(c, parent, out)?.let { return it }
    if (parent is IrClass && parent.kotlinFqName == "kotlin.Comparable" && name == "compareTo") return comparableCompareTo(c, out)
    if (parent is IrClass && parent.kotlinFqName == "kotlin.Number" && (name == "toString" || name == "equals" || name == "hashCode") && c.superQualifier == null) {
        // the Any members seen through Number (fake overrides of the class)
        val vals = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
        return anyMember(if (name == "toString") 0 else if (name == "equals") 1 else 2, vals, c, out)
    }
    if (parent is IrClass && parent.kotlinFqName == "kotlin.Number" && c.arguments.size == 1 && c.superQualifier != null && name == "toChar") {
        // `super.toChar()` of a class that extends Number: toInt().toChar()
        val recv = operands(listOf(c.arguments[0]!!), out)[0]
        return MirExpr.RuntimeCall("mk_number_to_char_default", listOf(asAny(recv)), TChar)
    }
    if (parent is IrClass && parent.kotlinFqName == "kotlin.Number" && c.arguments.size == 1) {
        val kind = when (name) {
            "toInt" -> TInt; "toLong" -> TLong; "toDouble" -> TDouble; "toFloat" -> TFloat
            "toByte" -> TByte; "toShort" -> TShort; "toChar" -> TChar
            else -> null
        }
        if (kind != null) {
            // a receiver of type T (reified, substituted by Long in an inline expansion) is a primitive here
            val recv = boxIfPrim(operands(listOf(c.arguments[0]!!), out)[0], c, out)
            return MirExpr.RuntimeCall("mk_checked_number_to_" + kind.displayName.lowercase(), listOf(asAny(recv)), kind)
        }
    }
    if (parent is IrClass && isEnumSpecial(parent, owner)) return enumSpecialCall(c, parent, owner.name, out)
    if (parent is IrClass && parent.kotlinFqName == "kotlin.Unit") unitMember(c, name, out)?.let { return it }
    if (parent is IrClass && parent.kotlinFqName == "kotlin.CharSequence" && (name == "toString" || name == "equals" || name == "hashCode")) {
        // the Any members seen through CharSequence (fake overrides of the interface)
        val vals = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
        return anyMember(if (name == "toString") 0 else if (name == "equals") 1 else 2, vals, c, out)
    }
    if (parent is IrClass && parent.kotlinFqName == "kotlin.CharSequence" && (name == "<get-length>" || name == "get" || name == "subSequence")) return charSequenceMember(c, name, out)
    if (parent is IrClass && (parent in classInfos || isAny(parent) || builtinOf(parent) != null || parent.kotlinFqName == "kotlin.Enum")) return memberCall(c, owner, out)
    // fake overrides of Any members declared in user classes have the user class as parent
    val ownerName = when (parent) {
        is IrClass -> parent.kotlinFqName
        is IrFile -> parent.packageFqName
        else -> unsupported(c, "call of '$name'")
    }
    if (parent is IrClass) arrayKindOfFq(ownerName)?.let { return arrayMember(c, name, it, out) }
    arrayFunction(c, name, ownerName, out)?.let { return it }
    val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$ownerName.$name'") }

    return when {
        ownerName == "kotlin" && name == "<get-code>" && args.size == 1 ->
            convertTo(operands(args, out)[0], TInt, c)
        ownerName == "kotlin" && name == "plus" && args.size == 2 && args[0].type.classFqName == "kotlin.String" -> {
            val v = operands(args, out)
            concat(toStringExpr(v[0], c, out), toStringExpr(v[1], c, out), out)
        }
        ownerName == "kotlin" && name == "toString" && args.size == 1 -> anyMember(0, operands(args, out), c, out)
        ownerName == "kotlin" && name == "hashCode" && args.size == 1 -> anyMember(2, operands(args, out), c, out)
        ownerName == "kotlin.io" && (name == "println" || name == "print") -> consoleCall(c, name, args, out)
        ownerName == "kotlin.internal.ir" -> irBuiltin(c, name, args, out)
        parent is IrClass && ownerName in RANGES && name == "contains" -> rangeContains(c, args, out)
        ownerName == "kotlin.String" && name == "<get-length>" -> MirExpr.StringLength(operands(args, out)[0])
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
        if (rp.kotlinFqName == "kotlin.Enum" && c.superQualifier == null) {
            val vals = operands(c.arguments.map { it ?: unsupported(c, "enum member") }, out)
            val recv = vals[0]
            // a receiver of the static type `Enum<*>` / `T : Enum<T>`: every enum class starts with the same hidden fields, so any one of them gives the layout
            val rc = (recv.type as? TObject)?.cName
            val enumNames = classInfos.values.filter { it.ir.kind == IrClassKind.ENUM_CLASS }.map { it.cName }
            val owner = (if (rc != null && rc in enumNames) rc else enumNames.firstOrNull()) ?: unsupported(c, "enum member on a non-enum receiver")
            return when (real.name) {
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
    val prop = real.correspondingProperty
    if (c.superQualifier == null && real.origin == "DEFAULT_PROPERTY_ACCESSOR" &&
        real.modality == IrModality.FINAL && prop?.backingField != null && !isAny(real.parent as IrClass) && !prop.isLateinit
    ) {
        val field = prop.backingField!!
        val args = callArgs(c, f, out)
        if (real.parameters.size == 1) return adaptResult(if (c in unguardedReads) fieldGet(args[0], field) else guardUnsetField(fieldGet(args[0], field), field), mirType(c.type, c), c, out)
        // the setter of a fake override in `class B : A<Int>()` takes an Int, the field (of `A<T>`) holds an Any
        val ft = mirType(field.type, field)
        out += MirStmt.SetField(args[0], fieldOwner(field).cName, field.name, ft, coerceFlexible(args[1], ft, c, out))
        return MirExpr.UnitValue
    }

    val d: Dispatch = if (c.superQualifier != null) {
        val t = resolveFake(f)
        if (parentClass(t)?.let { isAny(it) } == true) Dispatch.AnyMember(anyMember(topClass(f) ?: f) ?: unsupported(c, "super call of '${f.name}'"))
        else Dispatch.Direct(t)
    } else dispatchOfValue(f)

    if (d is Dispatch.AnyMember) {
        val vals = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
        if (vals[0].type is TInline && c.superQualifier == null) {
            val rest = vals.drop(1).map { coerceOperand(it, anyNullable, c, out) }
            valueClassAnyMember(vals[0], d.slot, rest, c)?.let { return it }
        }
        val recv = asAny(if (vals[0].type is TInline) coerceOperand(vals[0], anyNullable, c, out) else vals[0])
        val viaSuper = c.superQualifier != null
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
    val d: Dispatch = if (superQualified) Dispatch.Direct(resolveFake(f)) else dispatchOfValue(f)
    if (d is Dispatch.AnyMember) {
        if (args[0].type is TInline) {
            valueClassAnyMember(args[0], d.slot, args.drop(1).map { coerceOperand(it, anyNullable, at, out) }, at)?.let { return it }
        }
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
    val fq = parent.kotlinFqName
    if (fq != "kotlin.Int" && fq != "kotlin.Long" && fq != "kotlin.Char" && fq != "kotlin.Byte" && fq != "kotlin.Short") return null
    val cls = c.type.classOrNull ?: return null
    if (cls.kotlinFqName !in RANGES) return null
    val ctor = cls.constructors.firstOrNull() ?: return null
    val cName = functionNames[ctor] ?: unsupported(c, "range value '${cls.name}' (the stdlib class is not available)")
    val vals = operands(c.arguments.map { it!! }, out)
    val et = if (cls.name == "IntRange") TInt else if (cls.name == "LongRange") TLong else TChar
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
    val v = operands(args, out)[0].let { if (it.type == TUnit) unitAsRef(it, out) else it }
    if (name == "print") {
        val fn = when (v.type) {
            TString -> "mk_print_string"
            TInt -> "mk_print_int"
            TLong -> "mk_print_long"
            TDouble -> "mk_print_double"
            TBoolean -> "mk_print_boolean"
            TChar -> "mk_print_char"
            TFloat -> "mk_print_float"
            else -> null
        }
        if (v.type == TByte || v.type == TShort) return MirExpr.RuntimeCall("mk_print_int", listOf(MirExpr.Convert(v, TInt)), TUnit)
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
        TFloat -> MirExpr.RuntimeCall("mk_println_float", listOf(v), TUnit)
        TByte, TShort -> MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Convert(v, TInt)), TUnit)
        TNothing -> v // the argument never completes
        else -> if (v.type.isRef || v.type is TInline) MirExpr.RuntimeCall("mk_println_string", listOf(toStringExpr(v, c, out)), TUnit)
        else unsupported(c, "println of a value of type ${v.type}")
    }
}

internal fun Ir2Mir.irBuiltin(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    if (name == "CHECK_NOT_NULL") {
        val v = operands(args, out)
        if (v.size != 1) unsupported(c, "compiler intrinsic '$name'")
        val t = v[0].type
        // `null!!` has type Nothing: always throws
        if (t === TNull) { out += MirStmt.Eval(MirExpr.NotNull(v[0])); return MirExpr.Unreachable(TNothing) }
        return if (t is TBoxed && t.prim is TInline) unboxVc(v[0], t.prim, MirExpr.CastMode.Unchecked) else if (t is TBoxed) MirExpr.Unbox(v[0], t.prim) else if (t.isRef || t is TPtr) MirExpr.NotNull(v[0]) else v[0]
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
    if ((op == BinaryOp.Eq || op == BinaryOp.IdEq) && args.size == 2 && args.any { it.type.classFqName == "kotlin.Unit" && !it.type.nullable }) {
        // `foo() == Unit`: the Unit value is the Unit object (the call runs first)
        val nv = ArrayList<MirExpr>()
        for (a in args) {
            if (a.type.classFqName == "kotlin.Unit" && !a.type.nullable) {
                if (a !is IrGetObjectValue) stmt(a, out)
                nv += unitInstance()
            } else nv += tmpOfPlain(value(a, out), out)
        }
        return refEquality(op, nv[0], nv[1], c, out)
    }
    val v = operands(args, out)
    if (v.size != 2) unsupported(c, "compiler intrinsic '$name'")
    val ta = v[0].type
    val tb = v[1].type
    if (ta == TNothing || tb == TNothing) return MirExpr.Unreachable(TBoolean)
    if (ta == TUnit) unsupported(c, "comparison of $ta")
    if (op == BinaryOp.Eq && ta is TInline && ta == tb && ta.rep.repr != TDouble && ta.rep.repr != TFloat && hasSyntheticEquals(ta)) {
        return MirExpr.Binary(BinaryOp.Eq, retag(v[0], ta.rep.repr), retag(v[1], ta.rep.repr))
    }
    if (op == BinaryOp.Eq && ta is TInline && ta == tb) {
        valueClassAnyMember(v[0], 1, listOf(coerceOperand(v[1], anyNullable, c, out)), c)?.let { return it }
    }
    // ProperIeee754Comparisons: `a == b` with static types Double / Double? (Float): numbers compare as numbers (-0.0 == 0.0, NaN != NaN), null equals only null
    if (name == "ieee754equals" && (ta is TBoxed || tb is TBoxed)) {
        fun fp(t: MirType): MirType? = (if (t is TBoxed) t.prim else t).takeIf { it == TDouble || it == TFloat }
        val pa = fp(ta)
        val pb = fp(tb)
        if (pa != null && pa == pb) {
            val a = if (v[0] is MirExpr.Local || !ta.isRef) v[0] else tmpOfPlain(v[0], out)
            val b = if (v[1] is MirExpr.Local || !tb.isRef) v[1] else tmpOfPlain(v[1], out)
            fun isNull(x: MirExpr): MirExpr = MirExpr.Binary(BinaryOp.IdEq, x, MirExpr.NullConst())
            fun notNull(x: MirExpr): MirExpr = MirExpr.Unary(UnaryOp.Not, isNull(x))
            fun value(x: MirExpr): MirExpr = if (x.type is TBoxed) MirExpr.Unbox(x, pa) else x
            val bothNonNull = listOfNotNull(if (ta is TBoxed) notNull(a) else null, if (tb is TBoxed) notNull(b) else null)
                .reduce { x, y -> MirExpr.Binary(BinaryOp.AndAnd, x, y) }
            val sameValue = MirExpr.Binary(BinaryOp.AndAnd, bothNonNull, MirExpr.Binary(BinaryOp.Eq, value(a), value(b)))
            return if (ta is TBoxed && tb is TBoxed) MirExpr.Binary(BinaryOp.OrOr, MirExpr.Binary(BinaryOp.AndAnd, isNull(a), isNull(b)), sameValue) else sameValue
        }
    }
    // `a == b` of two values whose static type is a type parameter (substituted by Double / Float in an inline expansion): `equals`, not IEEE 754
    if (op == BinaryOp.Eq && name == "EQEQ" && ta == tb && (ta == TDouble || ta == TFloat) &&
        args.any { val fq = it.type.classFqName; fq != "kotlin.Double" && fq != "kotlin.Float" }) {
        return refEquality(op, boxIfPrim(v[0], c, out), boxIfPrim(v[1], c, out), c, out)
    }
    if (op == BinaryOp.Eq || op == BinaryOp.IdEq) {
        if (ta.isRef || tb.isRef) return refEquality(op, v[0], v[1], c, out)
    }
    if (ta is TPtr && tb is TPtr) return MirExpr.Binary(if (op == BinaryOp.IdEq) BinaryOp.Eq else op, v[0], v[1])
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
        if (other.type is TPtr) return MirExpr.Binary(BinaryOp.IdEq, other, MirExpr.NullConst(TPtr(true)))
        if (!other.type.isRef) return MirExpr.BooleanConst(false)
        return MirExpr.Binary(BinaryOp.IdEq, other, MirExpr.NullConst())
    }
    if (op == BinaryOp.IdEq) {
        return MirExpr.Binary(BinaryOp.IdEq, boxIfPrim(a, at, out), boxIfPrim(b, at, out))
    }
    // String and String? operands: mk_string_equals accepts null (equal only to null), no type dispatch
    if ((ta == TString || ta == TNString) && (tb == TString || tb == TNString)) return MirExpr.Binary(BinaryOp.Eq, a, b)
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
    val v0 = operands(args, out)
    // an operand typed by a type parameter with a primitive bound (`T : Int`) is a boxed value at run time: unbox it
    val v = if (v0.any { !it.type.isPrimitive && it.type.isRef && it.type !is TInline }) v0.mapIndexed { i, e ->
        val p = c.callee.parameters.getOrNull(i)
        val pt = if (p != null && e.type.isRef && e.type !is TInline && e.type != TString) mirType(p.type, p) else null
        if (pt != null && pt.isPrimitive && pt != TUnit && pt != TString) convertTo(e, pt, c) else e
    } else v0
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
            "subSequence" -> MirExpr.RuntimeCall("mk_string_substring", listOf(recv, v[1], v[2]), TString)
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
        "toByte" -> return convertTo(recv, TByte, c)
        "toShort" -> return convertTo(recv, TShort, c)
        "toFloat" -> return convertTo(recv, TFloat, c)
        "<get-code>" -> return convertTo(recv, TInt, c)
        "hashCode" -> return when (recv.type) {
            TInt -> recv
            TByte, TShort -> MirExpr.Convert(recv, TInt)
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
            "compareTo" -> MirExpr.Conditional(
                MirExpr.Binary(BinaryOp.Eq, recv, v[1]), MirExpr.IntConst(0),
                MirExpr.Conditional(recv, MirExpr.IntConst(1), MirExpr.IntConst(-1)),
            )
            "and" -> MirExpr.Binary(BinaryOp.And, recv, v[1])
            "or" -> MirExpr.Binary(BinaryOp.Or, recv, v[1])
            "xor" -> MirExpr.Binary(BinaryOp.Xor, recv, v[1])
            else -> unsupported(c, "call to 'kotlin.Boolean.$name'")
        }
    }

    // unary numeric
    if (v.size == 1) {
        val numeric = if (recv.type == TChar || recv.type == TByte || recv.type == TShort) MirExpr.Convert(recv, TInt) else recv
        return when (name) {
            "unaryMinus" -> MirExpr.Unary(UnaryOp.Neg, numeric)
            "unaryPlus" -> numeric
            "inv" -> convertTo(MirExpr.Unary(UnaryOp.Inv, numeric), recv.type, c)
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
        // Byte.compareTo(Byte) and Short.compareTo(Short) are `this - other` (like Kotlin/JVM)
        if ((recv.type == TByte && v[1].type == TByte) || (recv.type == TShort && v[1].type == TShort)) {
            return MirExpr.Binary(BinaryOp.Sub, a, b)
        }
        // Double/Float.compareTo is the total order of Double.compare: -0.0 < 0.0, NaN is the greatest and equal to itself
        if (t == TDouble || t == TFloat) {
            return MirExpr.RuntimeCall("mk_double_compare", listOf(convertTo(a, TDouble, c), convertTo(b, TDouble, c)), TInt)
        }
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

internal fun Ir2Mir.widenChar(e: MirExpr): MirExpr = if (e.type == TChar || e.type == TByte || e.type == TShort) MirExpr.Convert(e, TInt) else e

internal fun Ir2Mir.incDec(e: MirExpr, op: BinaryOp): MirExpr = when (e.type) {
    TInt -> MirExpr.Binary(op, e, MirExpr.IntConst(1))
    TLong -> MirExpr.Binary(op, e, MirExpr.LongConst(1))
    TDouble -> MirExpr.Binary(op, e, MirExpr.DoubleConst(1.0))
    TFloat -> MirExpr.Binary(op, e, MirExpr.Convert(MirExpr.DoubleConst(1.0), TFloat))
    else -> throw IllegalStateException("inc/dec on ${e.type}")
}

internal fun Ir2Mir.widest(a: MirType, b: MirType, at: IrElement): MirType {
    fun rank(t: MirType) = when (t) {
        TInt, TChar, TByte, TShort -> 1; TLong -> 2; TFloat -> 3; TDouble -> 4
        else -> unsupported(at, "arithmetic on $t")
    }
    return if (rank(a) >= rank(b)) (if (a == TChar || a == TByte || a == TShort) TInt else a) else (if (b == TChar || b == TByte || b == TShort) TInt else b)
}


/** `Comparable.compareTo` on a type-parameter-typed receiver: boxed primitives, strings or the Comparable itable. */
internal fun Ir2Mir.comparableCompareTo(c: IrCall, out: MutableList<MirStmt>): MirExpr {
    val v = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
    val a = asAny(boxIfPrim(v[0], c, out))
    val b = asAny(boxIfPrim(v[1], c, out))
    val cmp = classInfos.keys.firstOrNull { it.kotlinFqName == "kotlin.Comparable" }
    return MirExpr.RuntimeCall("mk_compare_to", listOf(a, b, MirExpr.TypeInfoOf(cmp?.let { typeOf(it) })), TInt)
}

/** `values()`, `valueOf(String)` and `entries` of an enum class (generated functions, see Ir2MirClasses). */
internal fun Ir2Mir.enumSpecialCall(c: IrCall, enumClass: IrClass, fname: String, out: MutableList<MirStmt>): MirExpr {
    val inf = info(enumClass)
    val arrType = enumArrayType(inf)
    return when (fname) {
        "values" -> MirExpr.Call(inf.cName + "_values", emptyList(), arrType)
        "<get-entries>" -> {
            val impl = entriesImpl ?: unsupported(c, "`entries` (the stdlib class EnumEntriesImpl is not available)")
            val ctor = impl.declarations.filterIsInstance<IrConstructor>().firstOrNull()
            val cName = ctor?.let { functionNames[it] } ?: unsupported(c, "`entries` (EnumEntriesImpl was pruned)")
            val arr = tmpOf(MirExpr.Call(inf.cName + "_values", emptyList(), arrType), out)
            val made = tmpOf(MirExpr.New(cName, listOf(arr), typeOf(impl)), out)
            MirExpr.Cast(made, mirType(c.type, c), MirExpr.CastMode.Unchecked)
        }
        else -> {
            val a = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
            MirExpr.Call(inf.cName + "_valueOf", a, typeOf(enumClass))
        }
    }
}

/** `Unit.toString()`, `Unit.equals(x)`, `Unit.hashCode()` (the Unit object is a generated singleton, see unitInstance). */
internal fun Ir2Mir.unitMember(c: IrCall, name: String, out: MutableList<MirStmt>): MirExpr? = when (name) {
    "toString" -> MirExpr.StringConst("kotlin.Unit")
    "hashCode" -> MirExpr.IntConst(0)
    "equals" -> {
        val other = c.arguments.getOrNull(1)?.let { operands(listOf(it), out)[0] }
        if (other == null) null else MirExpr.Binary(BinaryOp.IdEq, unitInstance(), asAny(boxIfPrim(other, c, out)))
    }
    else -> null
}

/** The `CharSequence` interface compiled as a user interface (when some class implements it), or null. */
internal fun Ir2Mir.charSequenceInterface(): MirExpr {
    val cs = classInfos.keys.firstOrNull { it.kotlinFqName == "kotlin.CharSequence" }
    return MirExpr.TypeInfoOf(cs?.let { typeOf(it) })
}

/** `length`, `get` and `subSequence` of a `CharSequence` receiver (a String, or an object that implements the interface): mk_cs_*. */
internal fun Ir2Mir.charSequenceMember(c: IrCall, name: String, out: MutableList<MirStmt>): MirExpr {
    val v = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
    val recv = v[0]
    if (recv.type == TString) {
        // a statically known String keeps the direct paths
        return when (name) {
            "<get-length>" -> MirExpr.StringLength(recv)
            "get" -> MirExpr.RuntimeCall("mk_string_char_at", listOf(recv, v[1]), TChar)
            else -> MirExpr.RuntimeCall("mk_string_substring", listOf(recv, v[1], v[2]), TString)
        }
    }
    val a = asAny(recv)
    val ti = charSequenceInterface()
    return when (name) {
        "<get-length>" -> MirExpr.RuntimeCall("mk_cs_length", listOf(a, ti), TInt)
        "get" -> MirExpr.RuntimeCall("mk_cs_get", listOf(a, v[1], ti), TChar)
        else -> MirExpr.RuntimeCall("mk_cs_sub_sequence", listOf(a, v[1], v[2], ti), anyType)
    }
}
