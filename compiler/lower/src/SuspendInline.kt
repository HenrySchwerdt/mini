package mini.lower

import mini.frontend.sema.*

/*
 * Inline functions whose lambdas contain suspension points (`list.forEach { delay(1) }`, `repeat(n) { yield() }`,
 * `lock.withLock { work() }`).
 *
 * [Ir2Mir] expands inline functions at the MIR level, which is too late for the state machine of [SuspendLowering]: the
 * suspension point has to be a statement of the suspend function itself. So, before the state machine is built, such a call
 * is expanded here on the IR: the call becomes a block that binds the arguments to fresh variables, followed by a deep copy
 * of the inline function's body in which
 *  - the type parameters are replaced by the type arguments of the call (reified ones work),
 *  - the invocation of a lambda parameter is replaced by a copy of the lambda's body (its parameters bound to the arguments),
 *  - `return` of the inline function and `return@label` of a lambda assign the result variable and leave through a
 *    `do { } while (false)` loop (a `return` at the end of the body is a plain assignment),
 *  - a non-local `return` of a lambda keeps returning from the enclosing suspend function.
 * The expanded code is ordinary code of the suspend function, so the state machine handles it like any other loop, `when`
 * and `try`. Calls whose lambdas contain no suspension point are left to Ir2Mir.
 */

private class CopyUnsupported(val what: String) : Exception(what)

/** Does [e] (the body of a lambda) contain a suspension point of the function it is expanded into? */
internal fun containsSuspensionPoint(e: IrElement?): Boolean {
    if (e == null) return false
    if (e is IrCall && (e.callee.isSuspend || e.origin == "SUSPEND_INVOKE")) return true
    if (e is IrClass || e is IrFunction) return false
    if (e is IrFunctionExpression) return !e.function.isSuspend && containsSuspensionPoint(e.function.body)
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) if (containsSuspensionPoint(c)) return true
    return false
}

/** does [e] return from [target] (a non-local return of a lambda)? Such a `return` must be part of the body of the state machine. */
private fun returnsFrom(e: IrElement?, target: IrFunction): Boolean {
    if (e == null) return false
    if (e is IrReturn && e.returnTarget === target) return true
    if (e is IrClass) return false
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) if (returnsFrom(c, target)) return true
    return false
}

private fun hasLocalClass(e: IrElement?): Boolean {
    if (e == null) return false
    if (e is IrClass || e is IrLocalDelegatedProperty) return true
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) if (hasLocalClass(c)) return true
    return false
}

private fun isLambdaParam(p: IrValueParameter): Boolean =
    p.kind == IrParameterKind.Regular && !p.isNoinline && !p.isCrossinline &&
        (p.type.classFqName?.startsWith("kotlin.Function") == true || p.type.classFqName?.startsWith("kotlin.coroutines.SuspendFunction") == true)

/** the body of the lambda literal [f] as it is expanded: a suspend lambda keeps its body from before the state machine was built */
private fun lambdaBody(f: IrSimpleFunction): IrBlockBody? = if (f.isSuspend) f.suspendInlineTemplate else f.body as? IrBlockBody

private fun hasSuspendLambda(e: IrElement?): Boolean {
    if (e == null) return false
    if (e is IrFunctionExpression && e.function.isSuspend) return true
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) if (hasSuspendLambda(c)) return true
    return false
}

private class RetCtx(val res: IrVariable?) {
    var loop: IrDoWhileLoop? = null
}

private class Copier(val a: Ast2Ir, val subst: Map<TypeParamSym, Type>, var parent: IrDeclarationParent, val forMemory: Boolean = false) {
    val vars = HashMap<IrValueDeclaration, IrValueDeclaration>()
    val loops = HashMap<IrLoop, IrLoop>()
    val fns = HashMap<IrFunction, IrFunction>()
    val lambdas = HashMap<IrValueParameter, IrFunctionExpression>()
    val rets = HashMap<IrFunction, RetCtx>()
    val unit: Type = a.sema.unitType
    val nothing: Type = a.sema.nothingType

    fun type(t: Type): Type = if (subst.isEmpty()) t else a.ops.substitute(t, subst)

    private fun <T : IrElement> at(n: T, o: IrElement): T {
        n.startOffset = o.startOffset
        return n
    }

    private fun lamOf(e: IrExpression?): IrFunctionExpression? = when (e) {
        is IrGetValue -> (e.owner as? IrValueParameter)?.let { lambdas[it] }
        is IrTypeOperatorCall -> lamOf(e.argument)
        else -> null
    }

    fun stmt(s: IrStatement, tail: IrFunction?): IrStatement = when (s) {
        is IrVariable -> variable(s)
        is IrSimpleFunction -> function(s)
        is IrExpression -> expr(s, tail)
        else -> throw CopyUnsupported(s.toString())
    }

    fun variable(v: IrVariable): IrVariable {
        val n = IrVariable(v.name, type(v.type), v.isVar)
        at(n, v)
        n.isLateinit = v.isLateinit
        n.deferredInit = v.deferredInit
        n.sym = v.sym
        n.typeAnnotations = v.typeAnnotations
        n.origin = v.origin
        n.annotations = v.annotations
        n.parent = parent
        vars[v] = n
        val i = v.initializer
        if (i != null) n.initializer = expr(i, null)
        return n
    }

    fun function(f: IrSimpleFunction): IrSimpleFunction {
        val nf = IrSimpleFunction(f.name)
        at(nf, f)
        fns[f] = nf
        nf.origin = f.origin
        nf.annotations = f.annotations
        nf.visibility = f.visibility
        nf.modality = f.modality
        nf.isInline = f.isInline
        nf.isSuspend = f.isSuspend
        nf.isOperator = f.isOperator
        nf.sym = f.sym
        nf.cSymbolName = f.cSymbolName
        nf.returnTypeAnnotations = f.returnTypeAnnotations
        nf.typeParameters = f.typeParameters
        nf.parent = parent
        for (p in f.parameters) {
            val np = IrValueParameter(p.name, type(p.type), p.kind, p.index)
            np.parent = nf
            np.varargElementType = p.varargElementType?.let { type(it) }
            np.isNoinline = p.isNoinline
            np.isCrossinline = p.isCrossinline
            np.isHidden = p.isHidden
            np.typeAnnotations = p.typeAnnotations
            vars[p] = np
            nf.parameters.add(np)
        }
        for ((i, p) in f.parameters.withIndex()) {
            val d = p.defaultValue
            if (d != null) nf.parameters[i].defaultValue = IrExpressionBody(expr(d.expression, null))
        }
        nf.returnType = type(f.returnType)
        nf.origReturnType = f.origReturnType?.let { type(it) }
        val saved = parent
        parent = nf
        try {
            // for the ownership checker a nested suspend lambda is copied as written (before its state machine was built)
            when (val b = if (forMemory) f.memoryBody ?: f.body else f.body) {
                is IrBlockBody -> nf.body = IrBlockBody(ArrayList(b.statements.map { stmt(it, null) }))
                is IrExpressionBody -> nf.body = IrExpressionBody(expr(b.expression, null))
                else -> {}
            }
        } finally {
            parent = saved
        }
        return nf
    }

    private fun fill(n: IrFunctionAccessExpression, e: IrFunctionAccessExpression) {
        for (x in e.arguments) n.arguments.add(if (x == null) null else expr(x, null))
        n.typeArguments = e.typeArguments.map { if (it == null) null else type(it) }
        n.origin = e.origin
    }

    private fun tailType(t: Type, tail: IrFunction?): Type = if (tail != null && a.isNothing(t)) unit else type(t)

    fun expr(e: IrExpression, tail: IrFunction?): IrExpression {
        val r: IrExpression = when (e) {
            is IrConst -> IrConst(type(e.type), e.value)
            is IrGetValue -> {
                val lam = (e.owner as? IrValueParameter)?.let { lambdas[it] }
                if (lam != null) lambdaExpression(lam) else IrGetValue(vars[e.owner] ?: e.owner, type(e.type), e.origin)
            }
            is IrSetValue -> IrSetValue(vars[e.owner] ?: e.owner, expr(e.value, null), type(e.type), e.origin)
            is IrCall -> call(e)
            is IrConstructorCall -> IrConstructorCall(e.callee, type(e.type)).also { fill(it, e) }
            is IrDelegatingConstructorCall, is IrEnumConstructorCall, is IrInstanceInitializerCall -> throw CopyUnsupported("constructor call")
            is IrGetEnumValue -> IrGetEnumValue(e.entry, type(e.type))
            is IrGetObjectValue -> IrGetObjectValue(e.classOwner, type(e.type))
            is IrGetField -> IrGetField(e.field, e.receiver?.let { expr(it, null) }, type(e.type))
            is IrSetField -> IrSetField(e.field, e.receiver?.let { expr(it, null) }, expr(e.value, null), type(e.type))
            is IrStringConcatenation -> IrStringConcatenation(ArrayList(e.arguments.map { expr(it, null) }), type(e.type))
            is IrFunctionExpression -> IrFunctionExpression(function(e.function), type(e.type), e.origin)
            is IrFunctionReference -> IrFunctionReference((fns[e.target] ?: e.target), type(e.type)).also { fill(it, e) }
            is IrPropertyReference -> IrPropertyReference(e.property, type(e.type)).also { fill(it, e) }
            is IrClassReference -> IrClassReference(type(e.classType), type(e.type))
            is IrGetClass -> IrGetClass(expr(e.argument, null), type(e.type))
            is IrTry -> {
                val cs = ArrayList<IrCatch>()
                val t = expr(e.tryResult, tail)
                for (c in e.catches) {
                    val p = variable(c.catchParameter)
                    cs.add(at(IrCatch(p, expr(c.result, tail)), c))
                }
                IrTry(t, cs, e.finallyExpression?.let { expr(it, null) }, tailType(e.type, tail))
            }
            is IrVararg -> {
                val els = ArrayList<IrVarargElement>()
                for (x in e.elements) {
                    if (x is IrSpreadElement) els.add(at(IrSpreadElement(expr(x.expression, null)), x)) else els.add(expr(x as IrExpression, null))
                }
                IrVararg(type(e.varargElementType), els, type(e.type))
            }
            is IrThrow -> IrThrow(expr(e.value, null), type(e.type))
            is IrWhen -> {
                val bs = ArrayList<IrBranch>()
                for (b in e.branches) bs.add(at(IrBranch(expr(b.condition, null), expr(b.result, tail)), b))
                IrWhen(bs, tailType(e.type, tail), e.origin)
            }
            is IrContainerExpression -> {
                val ss = ArrayList<IrStatement>()
                var i = 0
                for (s in e.statements) {
                    ss.add(stmt(s, if (i == e.statements.size - 1) tail else null))
                    i++
                }
                if (e is IrComposite) IrComposite(ss, tailType(e.type, tail), e.origin) else IrBlock(ss, tailType(e.type, tail), e.origin)
            }
            is IrTypeOperatorCall -> IrTypeOperatorCall(e.operator, type(e.type), expr(e.argument, null), type(e.typeOperand)).also { it.isSmart = e.isSmart }
            is IrReturn -> ret(e, tail)
            is IrLoop -> {
                val n: IrLoop = if (e is IrWhileLoop) IrWhileLoop(type(e.type)) else IrDoWhileLoop(type(e.type))
                loops[e] = n
                n.label = e.label
                n.origin = e.origin
                n.condition = expr(e.condition, null)
                n.body = e.body?.let { expr(it, null) }
                n
            }
            is IrBreak -> IrBreak(loops[e.loop] ?: e.loop, type(e.type))
            is IrContinue -> IrContinue(loops[e.loop] ?: e.loop, type(e.type))
            else -> throw CopyUnsupported(e.toString())
        }
        return at(r, e)
    }

    private fun call(e: IrCall): IrExpression {
        val callee = e.callee
        if (callee.name == "invoke" && e.arguments.isNotEmpty()) {
            val p = callee.parent
            if (p is IrClass && p.kotlinFqName.startsWith("kotlin.Function")) {
                val lam = lamOf(e.arguments[0])
                if (lam != null) return invoke(e, lam.function)
            }
        }
        val n = IrCall((fns[callee] as? IrSimpleFunction) ?: callee, type(e.type))
        n.superQualifier = e.superQualifier
        fill(n, e)
        return n
    }

    /** a copy of the lambda literal for a use of its parameter other than an invocation */
    private fun lambdaExpression(lam: IrFunctionExpression): IrExpression {
        val c = Copier(a, emptyMap(), parent)
        return c.expr(lam, null)
    }

    /** the invocation `f(args)` of the lambda parameter [f] becomes a block with a copy of the lambda's body */
    private fun invoke(e: IrCall, lam: IrSimpleFunction): IrExpression {
        val b = lambdaBody(lam) ?: throw CopyUnsupported("lambda body")
        val lc = Copier(a, emptyMap(), parent)
        val out = ArrayList<IrStatement>()
        val ps = lam.parameters
        if (e.arguments.size != ps.size + 1) throw CopyUnsupported("lambda arity")
        for ((i, p) in ps.withIndex()) {
            if (lam.isSuspend && i == ps.size - 1) continue // the continuation
            val v = IrVariable(p.name.ifEmpty { "p$i" }, p.type, false)
            v.parent = parent
            v.initializer = expr(e.arguments[i + 1]!!, null)
            lc.vars[p] = v
            out.add(v)
        }
        val resT = type(e.type)
        val result = lc.returnable(lam, b.statements, resT, out)
        out.add(result)
        return IrBlock(out, resT, null)
    }

    /**
     * Copies [src] (the body of [target], an inline function or a lambda) into [out]; `return`s of [target] assign the result
     * variable. Returns the expression that reads the result.
     */
    fun returnable(target: IrFunction, src: List<IrStatement>, resT: Type, out: ArrayList<IrStatement>): IrExpression {
        val unitLike = a.isUnit(resT) || a.isNothing(resT)
        var res: IrVariable? = null
        if (!unitLike) {
            res = IrVariable("\$inlined", resT, true)
            res.parent = parent
            out.add(res)
        }
        val r = RetCtx(res)
        rets[target] = r
        val copied = ArrayList<IrStatement>()
        for ((i, s) in src.withIndex()) copied.add(stmt(s, if (i == src.size - 1) target else null))
        rets.remove(target)
        val loop = r.loop
        if (loop != null) {
            loop.body = IrBlock(copied, unit, null)
            loop.condition = a.constBool(false)
            out.add(loop)
        } else out.addAll(copied)
        return if (res != null) IrGetValue(res, resT, null) else a.unitValue()
    }

    private fun ret(e: IrReturn, tail: IrFunction?): IrExpression {
        val r = rets[e.returnTarget]
        if (r == null) return IrReturn(fns[e.returnTarget] ?: e.returnTarget, expr(e.value, null), type(e.type))
        val v = expr(e.value, null)
        val res = r.res
        val assign: IrExpression = if (res != null) IrSetValue(res, v, unit, null) else v
        if (tail === e.returnTarget) return assign
        var loop = r.loop
        if (loop == null) {
            loop = IrDoWhileLoop(unit)
            loop.origin = "DO_WHILE_LOOP"
            r.loop = loop
        }
        val ss = ArrayList<IrStatement>()
        ss.add(assign)
        ss.add(IrBreak(loop, nothing))
        return IrBlock(ss, nothing, null)
    }
}

private class SuspendInliner(val a: Ast2Ir, val fn: IrFunction) {
    var budget = 500

    fun run(body: IrBlockBody) {
        mapChildren(body) { transform(it) }
    }

    fun transform(e: IrExpression): IrExpression {
        if (e is IrCall) {
            val x = tryExpand(e)
            if (x != null) return transform(x)
        }
        mapChildren(e) { transform(it) }
        return e
    }

    private fun tryExpand(c: IrCall): IrExpression? {
        val callee = c.callee
        if (!callee.isInline || callee.stub || !callee.hasBody) return null
        val template = if (callee.isSuspend) callee.suspendInlineTemplate ?: return null else null
        var need = false
        for ((i, p) in callee.parameters.withIndex()) {
            val arg = c.arguments.getOrNull(i)
            if (arg is IrFunctionExpression && isLambdaParam(p)) {
                val lf = arg.function
                if (lf.isSuspend) {
                    if (lf.suspendInlineTemplate != null) need = true
                } else if (containsSuspensionPoint(lf.body) || returnsFrom(lf.body, fn)) need = true
            }
        }
        if (!need) return null
        if (budget-- <= 0) return null
        val body = template ?: callee.body as? IrBlockBody ?: return null
        if (hasLocalClass(body) || hasSuspendLambda(body)) return null
        for (arg in c.arguments) if (arg != null && (hasLocalClass(arg))) return null
        return try {
            expand(c, callee, body)
        } catch (x: CopyUnsupported) {
            null
        }
    }

    private fun expand(c: IrCall, callee: IrSimpleFunction, body: IrBlockBody): IrExpression {
        val subst = HashMap<TypeParamSym, Type>()
        for ((i, tp) in callee.typeParameters.withIndex()) {
            val t = c.typeArguments.getOrNull(i)
            if (t != null) subst[tp] = t
        }
        val cp = Copier(a, subst, fn)
        val out = ArrayList<IrStatement>()
        val cls = callee.parent as? IrClass
        for ((i, p) in callee.parameters.withIndex()) {
            val arg = c.arguments.getOrNull(i)
            if (arg is IrFunctionExpression && isLambdaParam(p) && lambdaBody(arg.function) != null) {
                cp.lambdas[p] = arg
                continue
            }
            if (callee.isSuspend && i == callee.parameters.size - 1) continue // the continuation: the suspension points of the copy get that of the caller
            val v = IrVariable(p.name.ifEmpty { "p$i" }, cp.type(p.type), false)
            v.parent = fn
            if (arg != null) v.initializer = arg
            else {
                val d = p.defaultValue
                val vet = p.varargElementType
                if (d != null) v.initializer = cp.expr(d.expression, null)
                else if (vet != null) v.initializer = IrVararg(cp.type(vet), ArrayList(), v.type)
                else throw CopyUnsupported("omitted argument")
            }
            cp.vars[p] = v
            val tr = cls?.thisReceiver
            if (p.kind == IrParameterKind.DispatchReceiver && tr != null) cp.vars[tr] = v
            out.add(v)
        }
        val resT = c.type
        val result = cp.returnable(callee, body.statements, resT, out)
        out.add(result)
        val blk = IrBlock(out, resT, null)
        blk.startOffset = c.startOffset
        return blk
    }
}

/** Expands the calls of inline functions with suspension points in their lambdas in the statements of [body]. */
internal fun Ast2Ir.expandSuspendingInlineCalls(fn: IrFunction, body: IrBlockBody) {
    if (!containsSuspensionPoint(body)) return
    SuspendInliner(this, fn).run(body)
}

/** Does [e] declare a local function, class or delegated property? (the checker's closure analysis knows only the original declarations) */
private fun declaresLocal(e: IrElement): Boolean {
    if (e is IrClass || e is IrLocalDelegatedProperty || e is IrSimpleFunction) return true
    val ch = ArrayList<IrElement>()
    if (e is IrFunctionExpression) {
        // the lambda itself is fine, what it declares is not
        val b = (e.function.memoryBody ?: e.function.body) ?: return false
        ch.add(b)
    } else irChildren(e, ch)
    for (c in ch) if (declaresLocal(c)) return true
    return false
}

/**
 * A copy of the body of the suspend function [fn] as it was written, for the ownership checker ([IrFunction.memoryBody]); null when it declares
 * local functions or classes (the checker then analyses the state machine, which is safe but rejects owners that live across a suspension point).
 */
internal fun Ast2Ir.snapshotForMemory(fn: IrFunction, body: IrBlockBody): IrBlockBody? {
    for (s in body.statements) if (declaresLocal(s)) return null
    return try {
        val c = Copier(this, emptyMap(), fn, true)
        IrBlockBody(ArrayList(body.statements.map { c.stmt(it, null) }))
    } catch (x: Exception) {
        null
    }
}

/** A copy of [body] (of [fn]) that later changes of the original do not affect. */
internal fun Ast2Ir.snapshotBody(fn: IrFunction, body: IrBlockBody): IrBlockBody? {
    return try {
        val c = Copier(this, emptyMap(), fn)
        IrBlockBody(ArrayList(body.statements.map { c.stmt(it, null) }))
    } catch (x: CopyUnsupported) {
        null
    }
}
