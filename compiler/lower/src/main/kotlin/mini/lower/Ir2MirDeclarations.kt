
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Ir2Mir: function bodies, statements, constructors and field initialisation. */

// ---- functions --------------------------------------------------------------------

internal fun Ir2Mir.convertFunction(f: IrFunction, cName: String, qualifiedName: String, ctorOf: String?): MirFunction {
    locals = HashMap()
    localCounter = 0
    currentFunction = f
    val params = ArrayList<MirLocal>()
    if (f is IrConstructor) {
        val cls = f.parent as IrClass
        val self = MirLocal(localCounter++, "this", typeOf(cls))
        params += self
        f.parent.let { (it as IrClass).thisReceiver }?.let { locals[it] = self }
        if (cls.kind == IrClassKind.ENUM_CLASS) {
            params += MirLocal(localCounter++, "\$name", TString)
            params += MirLocal(localCounter++, "\$ordinal", TInt)
        }
    }
    for (p in f.parameters) {
        val l = MirLocal(localCounter++, if (p.kind == IrParameterKind.DispatchReceiver) "this" else p.name, mirType(p.type, p))
        locals[p] = l
        params += l
    }
    val ret = if (f is IrConstructor) TUnit else mirType(f.returnType, f)
    currentReturn = ret
    unitAsNull = false
    finallyStack = ArrayList()
    enterFunctionContext(f, params)
    val out = ArrayList<MirStmt>()
    (f.parent as? IrClass)?.takeIf { it in classInfos }?.let { enterClassMember(f, it, params, out) }
    if (f is IrSimpleFunction && f.origin == "DEFAULT_PROPERTY_ACCESSOR" && f.name.startsWith("<get-")) {
        // reading a lateinit property that was never assigned throws
        val prop = f.correspondingProperty
        val fld = prop?.backingField
        if (prop != null && prop.isLateinit && fld != null) {
            val raw = if (fld.parent is IrFile) staticFieldGet(fld, f)
            else MirExpr.FieldGet(MirExpr.Local(params[0]), fieldOwner(fld).cName, fld.name, mirType(fld.type, fld))
            val body = ArrayList<MirStmt>()
            val msg = MirExpr.StringConst("lateinit property " + prop.name + " has not been initialized")
            body += MirStmt.Throw(tmpOf(newBuiltin("MkUninitializedPropertyAccessException", msg), body))
            out += MirStmt.If(MirExpr.Binary(BinaryOp.IdEq, raw, MirExpr.NullConst()), body, emptyList())
        }
    }
    if (f is IrConstructor && (f.parent as IrClass).kind == IrClassKind.ENUM_CLASS) {
        val cn = info(f.parent as IrClass).cName
        out += MirStmt.SetField(MirExpr.Local(params[0]), cn, "\$name", TString, MirExpr.Local(params[1]))
        out += MirStmt.SetField(MirExpr.Local(params[0]), cn, "\$ordinal", TInt, MirExpr.Local(params[2]))
    }
    when (val body = f.body) {
        is IrBlockBody -> for (s in body.statements) stmt(s, out)
        is IrExpressionBody -> {
            val e = body.expression
            if (ret == TUnit) stmt(e, out) else returnValue(e, out)
        }
        else -> unsupported(f, "function body")
    }
    return MirFunction(qualifiedName, cName, params, ret, out, ctorOf, isStrictFunction(f), returnsBorrowedAnnotation(f), ownedParamIndexes(f, params), noHeapEffectsAnnotation(f))
}

internal fun Ir2Mir.newTemp(type: MirType, name: String = "tmp"): MirLocal = MirLocal(localCounter++, name, type)

/** Stores [v] (owned or borrowed) in a fresh temporary that lives until the end of the scope. */
internal fun Ir2Mir.tmpOf(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    val t = newTemp(v.type)
    out += MirStmt.Declare(t, v)
    ownedTemps += t
    return MirExpr.Local(t)
}

/** If [v] is a single-use temporary just declared by [tmpOf], takes its initialiser back (saves a retain/release pair). */
internal fun Ir2Mir.consumeOwned(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    if (v is MirExpr.Local && v.local in ownedTemps) {
        val last = out.lastOrNull()
        val init = (last as? MirStmt.Declare)?.init
        if (last is MirStmt.Declare && last.local === v.local && init != null) {
            out.removeAt(out.size - 1)
            ownedTemps -= v.local
            return init
        }
    }
    return v
}

internal fun Ir2Mir.returnValue(e: IrExpression, out: MutableList<MirStmt>) {
    var v = bindValue(e, currentReturn, out)
    if (v.type == TNothing) return
    if (finallyStack.isNotEmpty()) {
        // the value is computed before the finally blocks of the try statements being left run
        if (v !is MirExpr.IntConst && v !is MirExpr.BooleanConst && v !is MirExpr.StringConst) v = tmpOf(v, out)
        runFinalizers(0, out)
    }
    out += MirStmt.Return(v)
}

// ---- statements -------------------------------------------------------------------

internal fun Ir2Mir.stmt(s: IrStatement, out: MutableList<MirStmt>) {
    val before = out.size
    stmtBody(s, out)
    if (lineDirectives && s.startOffset >= 0 && out.size > before && !currentFile.isStdlib && out[before].srcLine == 0) {
        out[before].srcLine = currentFile.line(s.startOffset)
        out[before].srcFile = currentFile.path
    }
}

private fun Ir2Mir.stmtBody(s: IrStatement, out: MutableList<MirStmt>) {
    when (s) {
        is IrVariable -> {
            val t = mirType(s.type, s)
            if (t == TUnit || t == TNothing) unsupported(s, "variable '${s.name}' of type $t")
            val init = s.initializer?.let { bindValue(it, t, out) }
            if (s in closure.boxed) {
                // captured `var`: lives in a heap cell shared with the closures
                val rc = refClassFor(t)
                val l = MirLocal(localCounter++, s.name, rc.type)
                locals[s] = l
                out += MirStmt.Declare(l, MirExpr.New(rc.ctor, listOf(init ?: zeroOf(t)), rc.type))
            } else {
                val l = MirLocal(localCounter++, s.name, t)
                locals[s] = l
                out += MirStmt.Declare(l, init)
            }
        }
        is IrComposite -> for (x in s.statements) stmt(x, out)
        is IrBlock -> {
            if (s.origin == "FOR_LOOP") forLoop(s, out) else for (x in s.statements) stmt(x, out)
        }
        is IrWhen -> whenStmt(s, out)
        is IrWhileLoop -> whileLoop(s, out)
        is IrDoWhileLoop -> doWhileLoop(s, out)
        is IrBreak -> { runFinalizers(loopFinallyDepth[s.loop] ?: 0, out); out += MirStmt.Break(labelOf(s.loop, s)) }
        is IrContinue -> { runFinalizers(loopFinallyDepth[s.loop] ?: 0, out); out += MirStmt.Continue(labelOf(s.loop, s)) }
        is IrThrow -> throwStmt(s, out)
        is IrTry -> discard(tryValue(s, out, true), out)
        is IrReturn -> returnStmt(s, out)
        is IrSetValue -> {
            val owner = s.owner
            val l = localOf(owner, s)
            if (owner in closure.boxed) {
                val rc = refClassFor(declType(owner))
                out += MirStmt.SetField(MirExpr.Local(l), rc.cName, "value", rc.valueType, bindValue(s.value, rc.valueType, out))
            } else out += MirStmt.Assign(l, bindValue(s.value, l.type, out))
        }
        is IrSimpleFunction -> liftLocalFunction(s)
        is IrLocalDelegatedProperty -> {
            s.delegate?.let { stmt(it, out) }
            liftLocalFunction(s.getter)
            s.setter?.let { liftLocalFunction(it) }
        }
        is IrClass -> {} // local class: registered up front, compiled with the other classes
        is IrSetField -> setField(s, out)
        is IrInstanceInitializerCall -> instanceInitializer(s, out)
        is IrDelegatingConstructorCall -> delegatingCall(s, out)
        is IrEnumConstructorCall -> {}
        is IrTypeOperatorCall -> {
            if (s.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) stmt(s.argument, out)
            else discard(value(s, out), out)
        }
        is IrGetObjectValue -> {
            if (s.classOwner.kotlinFqName != "kotlin.Unit") discard(value(s, out), out)
        }
        is IrCall -> if (isNoWhenBranch(s)) out += MirStmt.Panic("NoWhenBranchMatchedException") else out += MirStmt.Eval(call(s, out))
        is IrExpression -> discard(value(s, out), out)
        else -> unsupported(s, describe(s))
    }
}

internal fun Ir2Mir.discard(v: MirExpr, out: MutableList<MirStmt>) {
    if (v is MirExpr.Local || v === MirExpr.UnitValue) return
    out += MirStmt.Eval(v)
}

/** The synthetic `else` branch of an exhaustive `when` without `else`. */
internal fun Ir2Mir.isNoWhenBranch(c: IrCall): Boolean {
    val o = c.callee
    return o.name == "noWhenBranchMatchedException" &&
        (o.parent as? IrFile)?.packageFqName == "kotlin.internal.ir"
}

internal fun Ir2Mir.returnStmt(s: IrReturn, out: MutableList<MirStmt>) {
    val f = currentFunction!!
    val target = s.returnTarget
    returnTargets[target]?.let { r ->
        // return from an inline function / lambda expanded in place: store the result and leave the expansion
        val rt = r.result
        if (rt == null) stmt(s.value, out)
        else out += MirStmt.Assign(rt, bindValue(s.value, rt.type, out))
        runFinalizers(r.finallyDepth, out)
        out += MirStmt.Break(r.label)
        return
    }
    if (target !== f) unsupported(s, "return from a lambda or nested function (target ${(target as? IrFunction)?.name}, in ${f.name}, inline stack ${returnTargets.keys.map { it.name }})")
    if (currentReturn == TUnit) {
        stmt(s.value, out)
        runFinalizers(0, out)
        out += MirStmt.Return(if (unitAsNull) MirExpr.NullConst(anyNullable) else null)
    } else {
        returnValue(s.value, out)
    }
}

internal fun Ir2Mir.labelOf(loop: IrLoop, at: IrElement): MirLabel =
    loopLabels[loop] ?: unsupported(at, "jump to a loop outside the current function")

internal fun Ir2Mir.localOf(owner: Any, at: IrElement): MirLocal =
    locals[owner] ?: unsupported(at, "reference to a variable that is not a local of the current function")

// ---- classes: constructors and fields -------------------------------------------------------

internal fun Ir2Mir.fieldOwner(f: IrField): ClassInfo = info(f.parent as? IrClass ?: unsupported(f, "top-level field"))

internal fun Ir2Mir.fieldGet(recv: MirExpr, f: IrField): MirExpr =
    MirExpr.FieldGet(recv, fieldOwner(f).cName, f.name, mirType(f.type, f))

internal fun Ir2Mir.setField(s: IrSetField, out: MutableList<MirStmt>) {
    val f = s.field
    val r = s.receiver ?: run { staticFieldSet(f, s.value, s, out); return }
    val ops = operands(listOf(r), out)
    val ft = mirType(f.type, f)
    out += MirStmt.SetField(ops[0], fieldOwner(f).cName, f.name, ft, bindValue(s.value, ft, out))
}

internal fun Ir2Mir.instanceInitializer(s: IrInstanceInitializerCall, out: MutableList<MirStmt>) {
    val cls = s.classOwner
    val self = MirExpr.Local(localOf(cls.thisReceiver ?: unsupported(s, "class without this"), s))
    for (d in cls.declarations) {
        when (d) {
            is IrProperty -> d.backingField?.let { initField(self, it, out) }
            is IrField -> initField(self, d, out)
            is IrAnonymousInitializer -> for (x in d.body.statements) stmt(x, out)
            else -> {}
        }
    }
}

internal fun Ir2Mir.initField(self: MirExpr, f: IrField, out: MutableList<MirStmt>) {
    val e = f.initializer?.expression ?: return
    val ft = mirType(f.type, f)
    out += MirStmt.SetField(self, fieldOwner(f).cName, f.name, ft, bindValue(e, ft, out))
}

internal fun Ir2Mir.delegatingCall(c: IrDelegatingConstructorCall, out: MutableList<MirStmt>) {
    val k = c.callee
    val cls = k.parent as IrClass
    if (isAny(cls)) return
    if (builtinOf(cls) != null) { builtinSuperInit(c, k, out); return }
    val cName = functionNames[k] ?: unsupported(c, "call of constructor of '${cls.kotlinFqName}'")
    val self = MirExpr.Local(locals.values.first { it.name == "this" && it.id == 0 })
    val args = callArgs(c, k, out)
    out += MirStmt.Eval(MirExpr.Call(cName, listOf<MirExpr>(self) + args + captureArgsFor(cls, c), TUnit))
}

internal fun Ir2Mir.constructorCall(c: IrConstructorCall, out: MutableList<MirStmt>): MirExpr {
    val k = c.callee
    val cls = k.parent as IrClass
    builtinOf(cls)?.let { return builtinNew(c, cls, it, out) }
    if (cls.kotlinFqName == "kotlin.Any") return MirExpr.RuntimeCall("mk_any_new", emptyList(), anyType)
    arrayKindOfFq(cls.kotlinFqName)?.let { return arrayConstructor(c, it, out) }
    val cName = functionNames[k] ?: unsupported(c, "construction of '${cls.kotlinFqName}'")
    if (cls.kind != IrClassKind.CLASS) unsupported(c, "construction of ${classKind(cls)} '${cls.name}'")
    if (cls.modality == IrModality.ABSTRACT || cls.modality == IrModality.SEALED) unsupported(c, "construction of abstract class '${cls.name}'")
    val args = callArgs(c, k, out) + captureArgsFor(cls, c)
    if (cls.kotlinFqName == "mini.memory.Pool") return poolNew(c, cName, cls, args, out)
    val (alloc, auto) = resolveAllocation(cls)
    return MirExpr.New(cName, args, typeOf(cls), alloc, auto)
}

/**
 * `Pool<T>(capacity)`: a pool needs the block size of `T`, which only the construction site knows; the pool object is
 * created as usual and its native handle is set right after.
 */
private fun Ir2Mir.poolNew(c: IrConstructorCall, ctor: String, cls: IrClass, args: List<MirExpr>, out: MutableList<MirStmt>): MirExpr {
    val ta = c.typeArguments.firstOrNull()?.classOrNull
    if (ta == null || ta !in classInfos) unsupported(c, "Pool of a type that is not a class")
    val obj = tmpOf(MirExpr.New(ctor, args, typeOf(cls)), out)
    out += MirStmt.SetField(
        obj, info(cls).cName, "handle", TLong,
        MirExpr.RuntimeCall("mk_pool_new_h", listOf(MirExpr.TypeInfoOf(typeOf(ta)), args[0]), TLong),
    )
    return obj
}
