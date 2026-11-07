
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Ir2Mir: function bodies, statements, constructors and field initialisation. */

// ---- functions --------------------------------------------------------------------

internal fun Ir2Mir.convertFunction(f: IrFunction, cName: String, qualifiedName: String, ctorOf: String?): MirFunction {
    val failures = libFailures ?: return convertFunctionOrThrow(f, cName, qualifiedName, ctorOf)
    // building a library: a function the backend cannot lower is recorded and replaced by a stub that panics; the link reports the
    // recorded error when a program reaches the function (docs/mlib.md)
    try {
        return convertFunctionOrThrow(f, cName, qualifiedName, ctorOf)
    } catch (e: UnsupportedConstruct) {
        locals = HashMap()
        localCounter = 0
        currentFunction = f
        val params = ArrayList<MirLocal>()
        val ret: MirType
        try {
            if (f is IrConstructor) {
                params += MirLocal(localCounter++, "this", typeOf(f.parent as IrClass))
                if ((f.parent as IrClass).kind == IrClassKind.ENUM_CLASS || isEnumBody(f.parent as IrClass)) {
                    params += MirLocal(localCounter++, "\$name", TString)
                    params += MirLocal(localCounter++, "\$ordinal", TInt)
                }
            }
            for (p in f.parameters) params += MirLocal(localCounter++, if (p.kind == IrParameterKind.DispatchReceiver) "this" else p.name, mirType(p.type, p))
            ret = if (f is IrConstructor) TUnit else mirType(f.returnType, f)
        } catch (e2: UnsupportedConstruct) {
            throw e
        }
        failures[cName] = (e.message ?: "unsupported construct").replace('\n', ' ')
        return MirFunction(qualifiedName, cName, params, ret, listOf(MirStmt.Panic("unsupported construct in library code")), ctorOf)
    }
}

private fun Ir2Mir.convertFunctionOrThrow(f: IrFunction, cName: String, qualifiedName: String, ctorOf: String?): MirFunction {
    locals = HashMap()
    localCounter = 0
    currentFunction = f
    val params = ArrayList<MirLocal>()
    if (f is IrConstructor) {
        val cls = f.parent as IrClass
        val self = MirLocal(localCounter++, "this", typeOf(cls))
        params += self
        f.parent.let { (it as IrClass).thisReceiver }?.let { locals[it] = self }
        if (cls.kind == IrClassKind.ENUM_CLASS || isEnumBody(cls)) {
            params += MirLocal(localCounter++, "\$name", TString)
            params += MirLocal(localCounter++, "\$ordinal", TInt)
            enumHidden = params.subList(1, 3).toList()
        }
    }
    for (p in f.parameters) {
        val l = MirLocal(localCounter++, if (p.kind == IrParameterKind.DispatchReceiver) "this" else p.name, mirType(p.type, p).let { if (it == TNothing) anyNullable else it })
        locals[p] = l
        if (p.type.classFqName == "kotlin.Unit" && !p.type.isMarkedNullable()) unitVars += p
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
        // an initializer of the entry (an `init` block, a property of its body) may read the entry itself (`B.value`): the entry is
        // visible in the holder from the start of its construction, like `this`
        val ec = f.parent as IrClass
        if (enumRefersToOwnEntries(ec)) {
            val ci = info(ec)
            for ((i, _) in enumEntries(ec).withIndex()) {
                out += MirStmt.If(MirExpr.Binary(BinaryOp.Eq, MirExpr.Local(params[2]), MirExpr.IntConst(i)), listOf(
                    MirStmt.SetField(MirExpr.ObjectGet(holderName(ci), holderType(ci)), holderName(ci), "e$i", typeOf(ec), MirExpr.Local(params[0])),
                ), emptyList())
            }
        }
    }
    if (f is IrConstructor) {
        // the companion object of an enum class is initialized with the class, after its entries (the static initializer of Kotlin):
        // the first use of the companion creates the entries first
        val cc = f.parent as IrClass
        val ec = cc.parent as? IrClass
        if (cc.isCompanion && ec != null && ec.kind == IrClassKind.ENUM_CLASS && ec in classInfos) {
            val ei = info(ec)
            out += MirStmt.Eval(MirExpr.ObjectGet(holderName(ei), holderType(ei)))
        }
    }
    if (f is IrConstructor && f.parent is IrClass && (f.parent as IrClass).kind == IrClassKind.CLASS) {
        // creating an instance initialises the class, which runs the `init` blocks of its companion object (the objects are lazy here);
        // not for enums: their companion is initialised after the entries (it may read `values()`)
        val comp = (f.parent as IrClass).declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion }
        if (comp != null && comp in classInfos && comp.declarations.any { it is IrAnonymousInitializer }) {
            out += MirStmt.Eval(MirExpr.ObjectGet(info(comp).cName, typeOf(comp)))
        }
    }
    val tails = if (f is IrSimpleFunction && f.sym?.has("tailrec") == true && f.parameters.none { it in closure.boxed }) tailCallsOf(f) else emptySet()
    if (tails.isNotEmpty()) {
        // the body runs in a loop over copies of the parameters; a tail call assigns the copies and continues
        val copies = HashMap<IrValueParameter, MirLocal>()
        for (p in f.parameters) {
            if (p.kind != IrParameterKind.Regular) continue
            val orig = locals[p] ?: continue
            val cp = MirLocal(localCounter++, p.name, orig.type)
            out += MirStmt.Declare(cp, MirExpr.Local(orig))
            locals[p] = cp
            copies[p] = cp
        }
        val label = MirLabel(labelCounter++)
        tailLoop = TailLoop(label, copies, tails)
        val inner = ArrayList<MirStmt>()
        try { convertBody(f, ret, inner) } finally { tailLoop = null }
        if (ret == TUnit) inner += MirStmt.Break(label)
        out += MirStmt.Loop(label, inner)
    } else convertBody(f, ret, out)
    return MirFunction(qualifiedName, cName, params, ret, out, ctorOf, isStrictFunction(f), returnsBorrowedAnnotation(f), ownedParamIndexes(f, params), noHeapEffectsAnnotation(f))
}

private fun Ir2Mir.convertBody(f: IrFunction, ret: MirType, out: ArrayList<MirStmt>) {
    when (val body = f.body) {
        is IrBlockBody -> for (s in body.statements) stmt(s, out)
        is IrExpressionBody -> {
            val e = body.expression
            if (ret == TUnit) stmt(e, out) else returnValue(e, out)
        }
        else -> unsupported(f, "function body")
    }
}

internal fun Ir2Mir.newTemp(type: MirType, name: String = "tmp"): MirLocal = MirLocal(localCounter++, name, type)

/** Stores [v] (owned or borrowed) in a fresh temporary that lives until the end of the scope. */
internal fun Ir2Mir.tmpOf(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    // a Unit / Nothing value has no storage (`val x = unitCall()`, a Unit component of a data class): it runs for its effect
    if (v.type == TUnit) {
        if (v !== MirExpr.UnitValue) out += MirStmt.Eval(v)
        return MirExpr.UnitValue
    }
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
        if (v !is MirExpr.IntConst && v !is MirExpr.BooleanConst && v !is MirExpr.StringConst) {
            val rl = MirLocal(localCounter++, "ret", v.type)
            finallyStack[0].decls += MirStmt.Declare(rl, null)
            out += MirStmt.Assign(rl, v)
            v = MirExpr.Local(rl)
        }
        val rv = v
        leaveThrough(0, out) { it += MirStmt.Return(rv) }
        return
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
            if ((t == TUnit || t == TNothing) && s !in closure.boxed) {
                // a variable of type Unit (or Nothing: the initializer never completes) has no storage: the initializer runs
                // for its effect, reads are the Unit value
                s.initializer?.let { stmt(it, out) }
                unitVars += s
                return
            }
            if (t == TUnit || t == TNothing) unsupported(s, "variable '${s.name}' of type $t")
            if (s.name.endsWith("_safe_receiver")) s.initializer?.let { markUnguarded(it) }
            val init0 = s.initializer?.let { bindValue(it, t, out) }
            // an initializer of type Nothing (`val x: Int = null!!`) never completes: the variable is declared without a value
            val init = if (init0 != null && init0.type == TNothing) null else init0
            if (s in closure.boxed) {
                // captured `var`: lives in a heap cell shared with the closures
                val rc = refClassFor(t)
                val l = MirLocal(localCounter++, s.name, rc.type)
                locals[s] = l
                val cell = if (init != null && init.isOwned()) tmpOf(init, out) else init
                out += MirStmt.Declare(l, MirExpr.New(rc.ctor, listOf(cell ?: zeroOf(t)), rc.type))
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
        is IrBreak -> { val l = labelOf(s.loop, s); leaveThrough(loopFinallyDepth[s.loop] ?: 0, out) { it += MirStmt.Break(l) } }
        is IrContinue -> { val l = labelOf(s.loop, s); leaveThrough(loopFinallyDepth[s.loop] ?: 0, out) { it += MirStmt.Continue(l) } }
        is IrThrow -> throwStmt(s, out)
        is IrTry -> discard(tryValue(s, out, true), out)
        is IrReturn -> returnStmt(s, out)
        is IrSetValue -> if (s.owner in unitVars) stmt(s.value, out) else {
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
        is IrCall -> if (isNoWhenBranch(s)) out += MirStmt.Panic("NoWhenBranchMatchedException") else call(s, out).let { out += MirStmt.Eval(erasedChecks[it] ?: it) } // (a discarded result is not checked: the JVM casts where a value is used)
        is IrExpression -> discard(value(s, out), out)
        else -> unsupported(s, describe(s))
    }
}

internal fun Ir2Mir.discard(v: MirExpr, out: MutableList<MirStmt>) {
    if (v is MirExpr.Local || v === MirExpr.UnitValue) return
    out += MirStmt.Eval(erasedChecks[v] ?: v)
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
        if (rt == null || s.value.type.classFqName == "kotlin.Nothing") stmt(s.value, out)
        else {
            val bv = bindValue(s.value, rt.type, out)
            // an erased generic result (`R` of `let`) whose argument is a Nothing lambda (`let { throw ... }`)
            out += if (bv.type == TNothing) MirStmt.Eval(bv) else MirStmt.Assign(rt, bv)
        }
        leaveThrough(r.finallyDepth, out) { it += MirStmt.Break(r.label) }
        return
    }
    val tl = tailLoop
    if (tl != null && target === f && finallyStack.isEmpty() && hasTailCall(s.value, tl.calls)) {
        returnWithTail(s.value, tl, out)
        return
    }
    if (target !== f) unsupported(s, "return from a lambda or nested function (target ${(target as? IrFunction)?.name}, in ${f.name}, inline stack ${returnTargets.keys.map { it.name }})")
    if (currentReturn == TUnit) {
        stmt(s.value, out)
        val un = unitAsNull
        leaveThrough(0, out) { it += MirStmt.Return(if (un) MirExpr.NullConst(anyNullable) else null) }
    } else {
        returnValue(s.value, out)
    }
}

internal fun Ir2Mir.labelOf(loop: IrLoop, at: IrElement): MirLabel =
    loopLabels[loop] ?: unsupported(at, "jump to a loop outside the current function")

internal fun Ir2Mir.localOf(owner: Any, at: IrElement): MirLocal =
    locals[owner] ?: unsupported(at, "reference to a variable that is not a local of the current function" + ((owner as? IrValueDeclaration)?.let { " ('${it.name}')" } ?: ""))

// ---- classes: constructors and fields -------------------------------------------------------

internal fun Ir2Mir.fieldOwner(f: IrField): ClassInfo = info(f.parent as? IrClass ?: unsupported(f, "top-level field"))

internal fun Ir2Mir.fieldGet(recv: MirExpr, f: IrField): MirExpr =
    if (recv.type is TInline) retag(recv, mirType(f.type, f)) else MirExpr.FieldGet(recv, fieldOwner(f).cName, f.name, mirType(f.type, f))

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

private fun isDefaultInit(e: IrExpression, t: MirType): Boolean {
    if (e is IrConst) return isDefaultValue(e.value, t)
    // `0.toByte()`, `0.toChar()`: a conversion of a zero constant
    if (e is IrCall && e.arguments.size == 1) {
        val a = e.arguments[0] as? IrConst ?: return false
        val v = a.value
        val zero = (v is Int && v == 0) || (v is Long && v == 0L) || (v is Double && v.toRawBits() == 0L)
        if (!zero) return false
        return when (e.callee.name) {
            "toByte" -> t === TByte
            "toShort" -> t === TShort
            "toChar" -> t === TChar
            "toInt" -> t === TInt
            "toLong" -> t === TLong
            "toFloat" -> t === TFloat
            "toDouble" -> t === TDouble
            else -> false
        }
    }
    return false
}

private fun isDefaultValue(v: Any?, t: MirType): Boolean = when (v) {
    null -> t === TString || t === TNString || t is TObject || t is TBoxed
    is Int -> v == 0 && (t === TInt || t === TByte || t === TShort)
    is Long -> v == 0L && t === TLong
    is Boolean -> !v && t === TBoolean
    is Double -> v.toRawBits() == 0L && (t === TDouble || t === TFloat)
    is Char -> v.code == 0 && t === TChar
    else -> false
}

internal fun Ir2Mir.initField(self: MirExpr, f: IrField, out: MutableList<MirStmt>) {
    val e = f.initializer?.expression ?: return
    val ft = mirType(f.type, f)
    // an initializer that stores the default value is dropped (as on the JVM): a superclass constructor may have set the field already
    if (isDefaultInit(e, ft)) return
    out += MirStmt.SetField(self, fieldOwner(f).cName, f.name, ft, bindValue(e, ft, out))
}

internal fun Ir2Mir.delegatingCall(c: IrDelegatingConstructorCall, out: MutableList<MirStmt>) {
    val k = c.callee
    val cls = k.parent as IrClass
    if (isAny(cls) || cls.kotlinFqName == "kotlin.Number") return
    // an enum class with secondary constructors only: the call of `Enum()` has nothing to do (name and ordinal are set by the creation code)
    if (cls.kotlinFqName == "kotlin.Enum" && builtinOf(cls) == null) return
    if (builtinOf(cls) != null) { builtinSuperInit(c, k, out); return }
    val cName = functionNames[k] ?: unsupported(c, "call of constructor of '${cls.kotlinFqName}'")
    val self = MirExpr.Local(locals.values.first { it.name == "this" && it.id == 0 })
    val args = callArgs(c, k, out)
    val hidden = if (cls.kind == IrClassKind.ENUM_CLASS) enumHidden.map { MirExpr.Local(it) } else emptyList()
    out += MirStmt.Eval(MirExpr.Call(cName, listOf<MirExpr>(self) + hidden + args + captureArgsFor(cls, c), TUnit))
}

internal fun Ir2Mir.constructorCall(c: IrConstructorCall, out: MutableList<MirStmt>): MirExpr {
    val k = c.callee
    val cls = k.parent as IrClass
    builtinOf(cls)?.let { return builtinNew(c, cls, it, out) }
    if (inlineRepOf(cls) != null) return valueClassNew(c, k, cls, out)
    if (cls.kotlinFqName == "kotlin.Any") return MirExpr.RuntimeCall("mk_any_new", emptyList(), anyType)
    if (cls.kotlinFqName == "kotlin.String" && c.arguments.none { it != null }) return MirExpr.StringConst("")   // `String()`
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

/** Does the enum class [c] (its entries, their bodies, its initializers) read one of its own entries (`B.x` inside the declaration of `B`)? */
internal fun Ir2Mir.enumRefersToOwnEntries(c: IrClass): Boolean {
    var found = false
    val w = object : IrWalker() {
        override fun visit(e: IrElement) {
            if (found) return
            if (e is IrGetEnumValue && e.entry.parent === c) { found = true; return }
            visitChildren(e)
        }
    }
    for (d in c.declarations) {
        if (d is IrEnumEntry) {
            val ce = d.correspondingClass
            if (ce != null) w.visit(ce)
        } else if (d is IrClass) continue
        else if (d !is IrSimpleFunction) w.visit(d)
    }
    return found
}

/** The reads that produce the value of [e], an initializer of a safe-call receiver (`z?.b?.c`: the inner `z?.b` is a block of a `when`): see [Ir2Mir.unguardedReads]. */
internal fun Ir2Mir.markUnguarded(e: IrExpression, depth: Int = 0) {
    if (depth > 8) return
    when (e) {
        is IrGetField, is IrCall -> unguardedReads.add(e)
        is IrBlock -> if (e.origin == "SAFE_CALL") (e.statements.lastOrNull() as? IrExpression)?.let { markUnguarded(it, depth + 1) }
        is IrWhen -> for (b in e.branches) markUnguarded(b.result, depth + 1)
        is IrTypeOperatorCall -> markUnguarded(e.argument, depth + 1)
        else -> {}
    }
}
