package mini.lower

import mini.frontend.*
import mini.frontend.sema.*
import mini.frontend.sema.Arg

/* Ast2Ir: calls, operators, assignments. */

// ---- callee lookup --------------------------------------------------------------------------------------------------

fun Ast2Ir.irFunctionOf(fn: FunSym): IrSimpleFunction {
    val ex = funMap[fn]
    if (ex != null) return ex as IrSimpleFunction
    val o = fn.owner
    if (o != null) {
        irClass(o)
        val f = funMap[fn]
        if (f != null) return f as IrSimpleFunction
    }
    if (fn.kind == FunKind.LOCAL || fn.kind == FunKind.ANONYMOUS) return irLocalFunction(fn)
    // a top-level function of a file that is not yet registered
    return irFunction(fn)
}

fun Ast2Ir.irConstructorOf(fn: FunSym): IrConstructor {
    val ex = funMap[fn]
    if (ex != null) return ex as IrConstructor
    val o = fn.owner ?: fail(null, "constructor without class")
    val c = irClass(o)
    val k = funMap[fn]
    if (k != null) return k as IrConstructor
    return irConstructor(fn, c)
}

/** the function a call refers to: for members the declared or fake override function of the static receiver class */
fun Ast2Ir.functionFor(fn: FunSym, staticRecv: Type?): IrSimpleFunction {
    if (fn.kind == FunKind.FUNCTION_INVOKE) return invokeFunction(fn.invokeArity + (if (fn.invokeSuspend) 1 else 0))
    val f = irFunctionOf(fn)
    if (fn.owner != null && staticRecv != null) {
        val c = classOf(staticRecv)
        if (c != null) return findMemberIn(irClass(c), f)
    }
    return f
}

/** the member function [name] with [argc] parameters of the class of [t] (what operators like `not`, `hashCode` refer to) */
fun Ast2Ir.memberFunNamed(t: Type, name: String, argc: Int): IrSimpleFunction {
    for (m in sema.members.funsNamed(t, name)) {
        sema.ensureSig(m.fn)
        if (m.fn.params.size == argc && m.fn.decl?.receiver == null) return functionFor(m.fn, t)
    }
    fail(null, "no member function " + name + " on " + renderType(t))
}

// ---- calls ---------------------------------------------------------------------------------------------------------------

fun Ast2Ir.callExpr(e: CallExpr, node: Ast): IrExpression {
    val r = rec(node) ?: fail(node, "call without record")
    val info = r.call ?: fail(node, "call without resolution")
    return callWithInfo(e, info, irType(r.type), node)
}

fun Ast2Ir.dotExpr(e: DotExpr): IrExpression {
    val sel = e.selector
    val r = rec(e) ?: fail(e, "dot expression without record")
    if (sel is CallExpr) {
        val info = r.call ?: fail(e, "call without resolution")
        return callWithInfo(sel, info, irType(r.type), e)
    }
    val cref = unparen(e.receiver)
    if (sel is NameExpr && sel.name == "name" && cref is CallableRefExpr && r.callee == null && r.target == null) {
        // the reference is evaluated (a bound receiver has side effects), its name is a constant
        val stmts = ArrayList<IrStatement>()
        stmts.add(coerceToUnit(convert(e.receiver)))
        val str = sema.stringType
        stmts.add(IrConst(str, cref.name))
        return IrBlock(stmts, irType(str), null)
    }
    // property / enum entry / object
    val sym = r.callee
    val t = irType(r.type)
    when (sym) {
        is PropSym -> return propertyGetDot(sym, r, t, e)
        is EnumEntrySym -> {
            irClass(sym.owner)
            val en = entryMap[sym] ?: fail(e, "enum entry " + sym.name)
            return IrGetEnumValue(en, t)
        }
        else -> {
            val tg = r.target
            if (tg is ClassType && tg.cls.isObject) return IrGetObjectValue(irClass(tg.cls), irType(tg))
            val rt = r.type
            if (rt is ClassType && rt.cls.isCompanion) return IrGetObjectValue(irClass(rt.cls), irType(rt))
            fail(e, "selector " + (sel as? NameExpr)?.name)
        }
    }
}

fun Ast2Ir.propertyGetDot(sym: PropSym, r: ExprRec, t: Type, node: Ast): IrExpression {
    val cv = constantOf(sym, t)
    if (cv != null) {
        // `getC().x` with a `const val x`: the receiver expression is still evaluated (its side effects), then the constant
        val d0 = r.access?.dispatch
        if (d0 != null && d0.expr != null && unparen(d0.expr) is CallExpr) {
            val stmts = ArrayList<IrStatement>()
            stmts.add(recvValue(d0, node))
            stmts.add(cv)
            return IrBlock(stmts, cv.type, null)
        }
        return cv
    }
    val access = r.access
    val p = propertyOf(sym)
    val g = p.getter ?: fail(node, "property " + sym.name + " has no getter")
    val d = access?.dispatch
    val safe = (d != null && d.expr != null && d.safe) || (access?.ext?.safe == true)
    val inner = if (safe) irType(r.baseType ?: t) else t
    return withReceivers(access, node, g, t) { callee, c, disp, ext ->
        val cc = findMember(callee, if (d != null) d else access?.ext)
        val ir = IrCall(cc, inner)
        ir.origin = "GET_PROPERTY"
        markSuperAccess(ir, d)
        fillAccessorArgs(ir, cc, disp, ext, null)
        ir
    }
}

/** a call (function, constructor, invoke) resolved as [info]; [node] carries the safe-call receiver */
fun Ast2Ir.callWithInfo(e: CallExpr, info: CallInfo, type: Type, node: Ast): IrExpression {
    val fn = info.fn
    if (info.isInvoke) return invokeCall(e, info, type, node)
    if (fn.name == "currentContinuationIntrinsic" && fn.owner == null) {
        // the continuation of the suspend function that contains the call (`suspend val coroutineContext`)
        val cf = ctx?.fn
        val cp = cf?.parameters?.lastOrNull()
        if (cf != null && cf.isSuspend && cp != null) return getValue(cp)
    }
    if (fn.kind == FunKind.CONSTRUCTOR) return constructorCall(e, info, type, node)
    if (fn.kind == FunKind.SYNTH && fn.owner == null && info.args.size == 1) {
        // `Iface { ... }`: the SAM constructor of a fun interface
        val rt = type
        if (rt is ClassType && rt.cls.isFunInterface) return samConversion(convertArg(info.args[0], null, null), rt)
    }
    val label = fn.name
    val d = info.dispatch
    val x = info.ext
    val safeRecv: RecvUse? = if (d != null && d.expr != null && d.safe) d else if (x != null && x.expr != null && x.safe) x else null
    var staticRecv: Type? = if (d != null) recvStaticType(d) else null
    if (d != null && fn.kind != FunKind.FUNCTION_INVOKE) {
        // a smart-cast receiver whose unsmart class already has the member needs no cast
        val dex = d.expr
        if (dex != null) {
            val rr = rec(unparen(dex))
            if (rr != null && rr.smart != null) {
                val oc = classOf(rr.type)
                if (oc != null && memberFound(irClass(oc), irFunctionOf(fn))) staticRecv = rr.type
            }
        }
    }
    val callee = functionFor(fn, staticRecv)
    val inner = if (safeRecv != null) irType(info.ret ?: sema.nullableAny) else type
    if (safeRecv != null) {
        return safeCall(safeRecv, node, type) { rv ->
            val dv: IrExpression? = if (d != null) (if (d === safeRecv) rv else recvValue(d, node)) else null
            val xv: IrExpression? = if (x != null) (if (x === safeRecv) rv else recvValue(x, node)) else null
            buildCall(callee, info, inner, dv, xv, node, label, d)
        }
    }
    val dv: IrExpression? = if (d != null) recvValue(d, node) else null
    val xv: IrExpression? = if (x != null) recvValue(x, node) else null
    return buildCall(callee, info, inner, dv, xv, node, label, d)
}

/** `super.p` / `super<C>.p`: the accessor call is not virtual */
fun Ast2Ir.markSuperAccess(call: IrCall, use: RecvUse?) {
    val ux = use?.expr ?: return
    if (unparen(ux) !is SuperExpr) return
    val st = recvStaticType(use!!)
    val sc = superQualifierFor(st, call.callee)
    if (sc != null) call.superQualifier = irClass(sc)
}

/** the class `super.f()` calls into: the static type of `super`, for several supertypes the one that has the callee */
fun Ast2Ir.superQualifierFor(st: Type?, callee: IrSimpleFunction): ClassSym? {
    if (st == null) return null
    if (st is IsectType) {
        val owner = (callee.parent as? IrClass)?.sym
        if (owner != null) for (p in st.parts) {
            val pc = classOf(p) ?: continue
            if (classExtends(pc, owner, 0)) return pc
        }
    }
    return classOf(st)
}

fun Ast2Ir.recvStaticType(u: RecvUse): Type? {
    val t = u.type
    if (t != null) return t
    val ref = u.ref ?: return null
    val rc = ref.cls
    if (rc != null) return rc.thisType()
    val sc = ref.scope
    if (sc != null) return sc.type
    return null
}

fun Ast2Ir.buildCall(
    callee: IrSimpleFunction,
    info: CallInfo,
    type: Type,
    dispatch: IrExpression?,
    ext: IrExpression?,
    node: Ast,
    label: String?,
    dispatchUse: RecvUse?,
): IrExpression {
    val call = IrCall(callee, type)
    call.origin = if (info.fn.kind == FunKind.FUNCTION_INVOKE && info.fn.invokeSuspend) "SUSPEND_INVOKE" else null
    // `super.f()`
    val dux = dispatchUse?.expr
    if (dispatchUse != null && dux != null && unparen(dux) is SuperExpr) {
        val st = recvStaticType(dispatchUse)
        val sc = superQualifierFor(st, callee)
        if (sc != null) call.superQualifier = irClass(sc)
    }
    fillCallArgs(call, callee, info, dispatch, ext, label)
    call.typeArguments = info.typeArgs.map { irType(it) }
    return spillForSourceOrder(call, callee, info)
}

/**
 * Named arguments are evaluated in the order they are written, not in the order of the parameters
 * (`f(b = g(), a = h())` calls `g` first). When the two orders differ, the arguments with effects (and the receivers, which come
 * first) are evaluated into temporaries in source order before the call.
 */
fun Ast2Ir.spillForSourceOrder(call: IrFunctionAccessExpression, callee: IrFunction, info: CallInfo): IrExpression {
    var any = false
    for (a in info.args) if (a.name != null) any = true
    if (!any) return call
    val srcOf = HashMap<Int, Int>()   // index in call.arguments -> position in the source
    var regular = 0
    for ((idx, p) in callee.parameters.withIndex()) {
        if (p.kind != IrParameterKind.Regular) continue
        var first = -1
        for ((i, a) in info.args.withIndex()) if (info.paramOfArg[i] == regular && a.expr != null) { first = i; break }
        if (first >= 0 && call.arguments.getOrNull(idx) != null) srcOf[idx] = first
        regular++
    }
    var last = -1
    var ordered = true
    for (idx in callee.parameters.indices) {
        val sp = srcOf[idx] ?: continue
        if (sp < last) ordered = false
        last = sp
    }
    if (ordered) return call
    fun needsSpill(e: IrExpression): Boolean = when (e) {
        is IrFunctionReference -> e.arguments.any { it != null && needsSpill(it) }   // `receiver::f`: the bound receiver is evaluated here
        is IrConst, is IrFunctionExpression, is IrPropertyReference, is IrVararg, is IrClassReference -> false
        is IrGetValue -> (e.owner as? IrVariable)?.isVar == true
        else -> true
    }
    val order = ArrayList<Int>()
    for ((idx, p) in callee.parameters.withIndex()) if (p.kind != IrParameterKind.Regular && call.arguments.getOrNull(idx) != null) order.add(idx)
    val regs = srcOf.keys.sortedBy { srcOf[it]!! }
    order.addAll(regs)
    val stmts = ArrayList<IrStatement>()
    for (idx in order) {
        val a = call.arguments[idx] ?: continue
        if (!needsSpill(a)) continue
        val t = newTemp("arg", a.type, a)
        stmts.add(t)
        call.arguments[idx] = getValue(t)
    }
    if (stmts.isEmpty()) return call
    stmts.add(call)
    return IrBlock(stmts, call.type, "ARG_ORDER")
}

fun Ast2Ir.constructorCall(e: CallExpr, info: CallInfo, type: Type, node: Ast): IrExpression {
    val k = irConstructorOf(info.fn)
    val d = info.dispatch
    if (d != null && d.expr != null && d.safe) {
        // `outer?.Inner(...)`: no construction when the outer instance is null
        val nonNull = type.withNullable(false)
        return safeCall(d, node, type) { rv ->
            val c2 = IrConstructorCall(k, nonNull)
            c2.typeArguments = info.typeArgs.map { irType(it) }
            fillCallArgs(c2, k, info, rv, null, info.fn.owner?.name ?: info.fn.name)
            spillForSourceOrder(c2, k, info)
        }
    }
    val call = IrConstructorCall(k, type)
    call.typeArguments = info.typeArgs.map { irType(it) }
    val dv: IrExpression? = if (d != null) recvValue(d, node) else null
    // inner class constructor called without an explicit outer instance: the enclosing `this`
    var outer = dv
    val cs = info.fn.owner
    if (outer == null && cs != null && cs.isInner) {
        val oc = cs.outer
        if (oc != null) outer = receiverOfClass(oc)
    }
    fillCallArgs(call, k, info, outer, null, cs?.name ?: info.fn.name)
    return spillForSourceOrder(call, k, info)
}

/** a delegating constructor call (`super(...)`, `this(...)`) with the arguments [argExprs] of the declaration */
fun Ast2Ir.delegatingCall(info: CallInfo, c: IrClass, type: Type, argExprs: List<Expr>): IrExpression {
    val k = irConstructorOf(info.fn)
    val call = IrDelegatingConstructorCall(k, type)
    call.typeArguments = info.typeArgs.map { irType(it) }
    var outer: IrExpression? = null
    val cs = info.fn.owner
    if (cs != null && cs.isInner) {
        val oc = cs.outer
        if (oc != null) outer = receiverOfClass(oc)
    }
    fillCallArgs(call, k, info, outer, null, null)
    return spillForSourceOrder(call, k, info)   // named arguments are evaluated in source order
}

/** the argument list of [call] aligned with the parameters of [callee] */
fun Ast2Ir.fillCallArgs(
    call: IrFunctionAccessExpression,
    callee: IrFunction,
    info: CallInfo?,
    dispatch: IrExpression?,
    ext: IrExpression?,
    label: String?,
) {
    var regular = 0
    val subst = HashMap<TypeParamSym, Type>()
    if (info != null) {
        val tps = if (info.fn.kind == FunKind.CONSTRUCTOR) info.fn.owner!!.typeParams else info.fn.typeParams
        var i = 0
        for (tp in tps) {
            if (i < info.typeArgs.size) subst[tp] = info.typeArgs[i]
            i++
        }
    }
    for (p in callee.parameters) {
        when (p.kind) {
            IrParameterKind.DispatchReceiver -> call.arguments.add(adaptReceiver(dispatch, p))
            IrParameterKind.ExtensionReceiver -> call.arguments.add(adaptReceiver(ext, p))
            IrParameterKind.Regular -> {
                inlineArgument = callee is IrSimpleFunction && callee.isInline && !p.isNoinline
                call.arguments.add(if (info == null) null else argFor(info, regular, p, subst, label))
                inlineArgument = false
                regular++
            }
            IrParameterKind.Context -> call.arguments.add(contextArgument(p, subst))
        }
    }
}

/** The implicit context argument for [p]: the innermost context parameter in scope whose type fits. */
fun Ast2Ir.contextArgument(p: IrValueParameter, subst: Map<TypeParamSym, Type>): IrExpression? {
    val want = ops.substitute(p.type, subst)
    var c = ctx
    while (c != null) {
        for (cand in c.contexts) {
            if (want is TypeParamType || ops.isSubtype(cand.type, want)) return IrGetValue(cand, cand.type, null)
        }
        c = c.parent
    }
    return null
}

private fun Ast2Ir.argFor(info: CallInfo, j: Int, p: IrValueParameter, subst: Map<TypeParamSym, Type>, label: String?): IrExpression? {
    var first = -1
    val idxs = ArrayList<Int>()
    var i = 0
    for (a in info.args) {
        if (info.paramOfArg[i] == j) {
            if (a.expr != null) idxs.add(i)
        }
        i++
    }
    if (idxs.isEmpty()) return null
    val ve = p.varargElementType
    if (ve == null) {
        val a = info.args[idxs[0]]
        return convertArg(a, label, info.paramTypes.getOrNull(idxs[0]))
    }
    // vararg: all arguments bound to this parameter
    val elems = ArrayList<IrVarargElement>()
    var elemType: Type? = null
    for (k in idxs) {
        val a = info.args[k]
        val ex = convertArg(a, label, null)
        if (a.spread) elems.add(IrSpreadElement(ex)) else {
            elems.add(ex)
            if (elemType == null) {
                val pt = info.paramTypes.getOrNull(k)
                if (pt != null) elemType = irType(pt)
            }
        }
    }
    val et = elemType ?: irType(ops.substitute(ve, subst))
    return IrVararg(et, elems, irType(ops.substitute(p.type, subst)))
}

private fun Ast2Ir.convertArg(a: Arg, label: String?, expected: Type?): IrExpression {
    val ex = a.expr!!
    val u = unwrapPostponed(ex)
    val inl = inlineArgument
    inlineArgument = false
    if (u is LambdaExpr) lambdaInline = inl
    var own: String? = null
    var w: Expr = ex
    while (true) {
        if (w is ParenExpr) w = w.inner
        else if (w is AnnotatedExpr) w = w.expr
        else if (w is LabeledExpr) { own = w.label; w = w.expr }
        else break
    }
    val v = if (u is LambdaExpr) lambdaExpr(u, own ?: label) else convert(ex)
    // a function value (not a lambda or reference literal) of a non-suspend function type where a suspend function type is expected
    if (expected is FunType && expected.suspend && u !is LambdaExpr && u !is AnonFunExpr && u !is CallableRefExpr) {
        val vt = rec(u)?.type
        if (vt is FunType && !vt.suspend && vt.receiver == null == (expected.receiver == null)) return suspendConvertedValue(v, vt, expected)
    }
    if (expected is ClassType && expected.cls.isFunInterface && (rec(u)?.type is FunType || u is CallableRefExpr)) return samConversion(v, irType(expected))
    return v
}

fun samConversion(v: IrExpression, t: Type): IrExpression = IrTypeOperatorCall(IrTypeOperator.SAM_CONVERSION, t, v, t)

// ---- invoke ---------------------------------------------------------------------------------------------------------------

fun Ast2Ir.invokeCall(e: CallExpr, info: CallInfo, type: Type, node: Ast): IrExpression {
    val fn = info.fn
    // the function value
    var fv: IrExpression
    val v = info.invokeVar
    val p = info.invokeProp
    val iv = info.invokeValue
    val d = info.dispatch
    val staticType: Type
    if (v != null) {
        val iv2 = varMap[v] ?: fail(node, "variable " + v.name + " is not declared in the IR")
        fv = IrGetValue(iv2, iv2.type, "VARIABLE_AS_FUNCTION")
        val narrowed = info.cand?.invokeType
        if (narrowed != null) {
            val nt = irType(narrowed)
            if (fv.type.nullable && !nt.nullable) fv = IrTypeOperatorCall(IrTypeOperator.IMPLICIT_CAST, nt, fv, nt)
        }
        staticType = irType(v.type ?: sema.nullableAny)
    } else if (p != null) {
        val access = Access(if (info.recvAsFirstArg) null else d, null)
        val pr = propertyOf(p)
        val g = pr.getter ?: fail(node, "property without getter")
        val ptype = irType(sema.typeOfProp(p))
        fv = withReceivers(Access(d, null), node, g, ptype) { callee, c, disp, ext ->
            val cc = findMember(callee, d)
            val ir = IrCall(cc, ptype)
            ir.origin = "GET_PROPERTY"
            fillAccessorArgs(ir, cc, disp, ext, null)
            ir
        }
        staticType = ptype
    } else if (iv != null) {
        fv = recvValue(iv, node)
        staticType = fv.type
    } else if (d != null) {
        fv = recvValue(d, node)
        staticType = fv.type
    } else fail(node, "invoke without a function value")
    val callee: IrSimpleFunction
    if (fn.kind == FunKind.FUNCTION_INVOKE) callee = invokeFunction(fn.invokeArity + (if (fn.invokeSuspend) 1 else 0))
    else callee = functionFor(fn, staticType)
    val call = IrCall(callee, type)
    call.origin = if (fn.invokeSuspend) "SUSPEND_INVOKE" else "INVOKE"
    call.typeArguments = info.typeArgs.map { irType(it) }
    // arguments: the first one may be the receiver of a function type with receiver
    var regular = 0
    val subst = HashMap<TypeParamSym, Type>()
    for (pp in callee.parameters) {
        when (pp.kind) {
            IrParameterKind.DispatchReceiver -> call.arguments.add(if (fn.receiver == null) fv else null)
            IrParameterKind.ExtensionReceiver -> call.arguments.add(if (fn.receiver != null) adaptReceiver(fv, pp) else null)
            IrParameterKind.Regular -> {
                if (info.recvAsFirstArg && regular == 0) {
                    val recvUse = info.dispatch ?: fail(node, "invoke receiver")
                    call.arguments.add(recvValue(recvUse, node))
                } else {
                    val j = if (info.recvAsFirstArg) regular else regular
                    call.arguments.add(argFor(info, j, pp, subst, "invoke"))
                }
                regular++
            }
            else -> call.arguments.add(null)
        }
    }
    return call
}

// ---- operators --------------------------------------------------------------------------------------------------------------

/** a call of the operator function of [info] on [recv] with [args] (member: dispatch receiver, extension: extension receiver) */
fun Ast2Ir.callOperator(info: CallInfo, recv: IrExpression, args: List<IrExpression>, type: Type, origin: String?): IrExpression {
    val fn = info.fn
    val callee = functionFor(fn, if (fn.receiver == null) recv.type else null)
    val call = IrCall(callee, type)
    call.origin = origin
    call.typeArguments = info.typeArgs.map { irType(it) }
    var regular = 0
    for (p in callee.parameters) {
        when (p.kind) {
            IrParameterKind.DispatchReceiver -> call.arguments.add(
                if (fn.receiver == null) adaptReceiver(recv, p)
                else {
                    // a member extension used as an operator (`operator fun C.component1()` of class M): `this` of M
                    val du = info.dispatch
                    val owner = fn.owner
                    if (du != null && (du.expr != null || du.ref != null)) adaptReceiver(recvValue(du, null), p)
                    else if (owner != null) adaptReceiver(receiverOfClass(owner), p)
                    else null
                },
            )
            IrParameterKind.ExtensionReceiver -> call.arguments.add(adaptReceiver(recv, p))
            IrParameterKind.Regular -> {
                call.arguments.add(if (regular < args.size) args[regular] else null)
                regular++
            }
            // `context(h: Heap) operator fun matmul(...)`: the context argument comes from the scope like for any call
            IrParameterKind.Context -> call.arguments.add(contextArgument(p, emptyMap()))
            else -> call.arguments.add(null)
        }
    }
    return call
}

fun Ast2Ir.binaryExpr(e: BinaryExpr): IrExpression {
    val op = e.op
    when (op) {
        "=" -> return assignExpr(e)
        "+=", "-=", "*=", "/=", "%=", "@=" -> return compoundAssign(e)
        "&&" -> return andOr(e, true)
        "||" -> return andOr(e, false)
        "==", "!=", "===", "!==" -> return equality(e)
        "<", ">", "<=", ">=" -> return comparison(e)
        "in", "!in" -> return inExpr(e)
        "?:" -> return elvis(e)
    }
    val r = rec(e) ?: fail(e, "binary expression without record")
    val info = r.call ?: fail(e, "operator " + op + " is not resolved")
    val t = irType(r.type)
    if ((op == "+" || op == "-" || op == "*" || op == "/" || op == "%") && mentionsConst(e)) {
        // K2 evaluates constant expressions that involve a `const val`
        val v = evalConst(e)
        if (v != null) return IrConst(t, convertConst(v, t))
    }
    val origin = when (op) {
        "+" -> "PLUS"
        "-" -> "MINUS"
        "*" -> "MUL"
        "/" -> "DIV"
        "%" -> "PERC"
        "@" -> "MATMUL"
        ".." -> "RANGE"
        "..<" -> "RANGE_UNTIL"
        else -> null
    }
    val l = convert(e.left)
    // `x myMap { return@myMap }`: a lambda argument of an infix call is labelled with the name of the function
    val rr = if (unwrapPostponed(e.right) is LambdaExpr) convertArg(Arg(e.right, null, false), info.fn.name, info.paramTypes.getOrNull(0)) else convert(e.right)
    return callOperator(info, l, listOf(rr), t, origin)
}

fun Ast2Ir.andOr(e: BinaryExpr, and: Boolean): IrExpression {
    val l = convert(e.left)
    val r = convert(e.right)
    val branches = ArrayList<IrBranch>()
    if (and) {
        branches.add(IrBranch(l, r))
        branches.add(IrBranch(constBool(true), constBool(false)))
        return IrWhen(branches, sema.booleanType, "ANDAND")
    }
    branches.add(IrBranch(l, constBool(true)))
    branches.add(IrBranch(constBool(true), r))
    return IrWhen(branches, sema.booleanType, "OROR")
}

fun Ast2Ir.isFloating(t: Type): Boolean {
    val n = ops.makeNotNull(t).classFqName
    return n == "kotlin.Double" || n == "kotlin.Float"
}

fun Ast2Ir.equality(e: BinaryExpr): IrExpression {
    val ident = e.op == "===" || e.op == "!=="
    // ProperIeee754Comparisons: operands smart cast to Double / Float (`x is Double && y is Double && x == y`) compare as
    // numbers (-0.0 == 0.0, NaN != NaN), not with equals()
    var smartIeee = false
    if (!ident && (rec(unparen(e.left))?.smart != null || rec(unparen(e.right))?.smart != null)) {
        val lt = rec(unparen(e.left))?.let { it.smart ?: it.type }
        val rt = rec(unparen(e.right))?.let { it.smart ?: it.type }
        smartIeee = lt != null && rt != null && isFloating(lt) && isFloating(rt)
    }
    var l = if (ident) convert(e.left) else if (smartIeee) convert(e.left) else convertEqOperand(e.left)
    var r = if (ident) convert(e.right) else if (smartIeee) convert(e.right) else convertEqOperand(e.right)
    if (smartIeee && l.type.classFqName != r.type.classFqName) {
        l = convertNumber(l, "kotlin.Double")
        r = convertNumber(r, "kotlin.Double")
    }
    if (!ident && !l.type.nullable && !r.type.nullable && isSmallNumber(l.type.classFqName) && isSmallNumber(r.type.classFqName)) {
        // Byte and Short are compared as Int (K2 does the same)
        l = convertNumber(l, "kotlin.Int")
        r = convertNumber(r, "kotlin.Int")
    }
    val neg = e.op == "!=" || e.op == "!=="
    val name = if (ident) "EQEQEQ" else if (isFloating(l.type) && isFloating(r.type)) "ieee754equals" else "EQEQ"
    val eq = IrCall(builtinFun(name), sema.booleanType)
    eq.origin = if (neg) (if (ident) "EXCLEQEQ" else "EXCLEQ") else (if (ident) "EQEQEQ" else "EQEQ")
    eq.arguments.add(l)
    eq.arguments.add(r)
    if (!neg) return eq
    val notFn = memberFunNamed(sema.booleanType, "not", 0)
    val n = IrCall(notFn, sema.booleanType)
    n.origin = if (ident) "EXCLEQEQ" else "EXCLEQ"
    n.arguments.add(eq)
    return n
}

fun Ast2Ir.comparison(e: BinaryExpr): IrExpression {
    val r = rec(e) ?: fail(e, "comparison without record")
    val l = convert(e.left)
    val rr = convert(e.right)
    val fname = when (e.op) {
        "<" -> "less"
        ">" -> "greater"
        "<=" -> "lessOrEqual"
        else -> "greaterOrEqual"
    }
    val origin = when (e.op) {
        "<" -> "LT"
        ">" -> "GT"
        "<=" -> "LTEQ"
        else -> "GTEQ"
    }
    val lt = ops.makeNotNull(l.type)
    val rt = ops.makeNotNull(rr.type)
    val ln = lt.classFqName
    val rn = rt.classFqName
    if (!l.type.nullable && !rr.type.nullable && isPrimitiveName(ln) && isPrimitiveName(rn)) {
        var a = l
        var b = rr
        if (ln != rn || isSmallNumber(ln)) {
            // the narrower operand is converted to the wider type: `x.toLong()`; Byte and Short compare as Int
            var wide = widerNumber(ln!!, rn!!)
            if (isSmallNumber(wide)) wide = "kotlin.Int"
            if (ln != wide) a = convertNumber(a, wide)
            if (rn != wide) b = convertNumber(b, wide)
        }
        val c = IrCall(builtinFun(fname), sema.booleanType)
        c.origin = origin
        c.arguments.add(a)
        c.arguments.add(b)
        return c
    }
    val info = r.call ?: fail(e, "compareTo is not resolved")
    val cmp = callOperator(info, l, listOf(rr), sema.intType, origin)
    val c = IrCall(builtinFun(fname), sema.booleanType)
    c.origin = origin
    c.arguments.add(cmp)
    c.arguments.add(constInt(0))
    return c
}

fun isSmallNumber(n: String?): Boolean = n == "kotlin.Byte" || n == "kotlin.Short"

fun numberRank(n: String): Int = when (n) {
    "kotlin.Byte", "kotlin.Short" -> 0
    "kotlin.Int" -> 1
    "kotlin.Long" -> 2
    "kotlin.Float" -> 3
    else -> 4
}

fun widerNumber(a: String, b: String): String = if (numberRank(a) >= numberRank(b)) a else b

fun Ast2Ir.convertNumber(e: IrExpression, to: String): IrExpression {
    val name = when (to) {
        "kotlin.Long" -> "toLong"
        "kotlin.Float" -> "toFloat"
        "kotlin.Double" -> "toDouble"
        else -> "toInt"
    }
    val call = IrCall(memberFunNamed(e.type, name, 0), sema.classTypeOf(to))
    call.arguments.add(e)
    return call
}

fun isPrimitiveName(n: String?): Boolean =
    n == "kotlin.Int" || n == "kotlin.Long" || n == "kotlin.Double" || n == "kotlin.Float" || n == "kotlin.Byte" || n == "kotlin.Short"

fun Ast2Ir.inExpr(e: BinaryExpr): IrExpression {
    val r = rec(e) ?: fail(e, "in without record")
    val info = r.call ?: fail(e, "contains is not resolved")
    val l = convert(e.left)
    val rr = convert(e.right)
    val neg = e.op == "!in"
    val c = callOperator(info, rr, listOf(l), sema.booleanType, if (neg) "NOT_IN" else "IN")
    if (!neg) return c
    val n = IrCall(memberFunNamed(sema.booleanType, "not", 0), sema.booleanType)
    n.origin = "NOT_IN"
    n.arguments.add(c)
    return n
}

fun Ast2Ir.elvis(e: BinaryExpr): IrExpression {
    val r = rec(e) ?: fail(e, "elvis without record")
    val t = irType(r.type)
    val lhs = convert(e.left)
    val tmp = newTemp("elvis_lhs", lhs.type, lhs)
    val rhs = convert(e.right)
    val branches = ArrayList<IrBranch>()
    branches.add(IrBranch(eqNull(getValue(tmp)), rhs))
    branches.add(IrBranch(constBool(true), getValue(tmp)))
    val w = IrWhen(branches, t, null)
    val stmts = ArrayList<IrStatement>()
    stmts.add(tmp)
    stmts.add(w)
    return IrBlock(stmts, t, "ELVIS")
}

fun Ast2Ir.prefixExpr(e: PrefixExpr): IrExpression {
    val r = rec(e) ?: fail(e, "prefix expression without record")
    when (e.op) {
        "!" -> {
            val info = r.call ?: fail(e, "not is not resolved")
            val v = convert(e.expr)
            return callOperator(info, v, emptyList(), irType(r.type), null)   // (`operator fun A.not(): A`)
        }
        "-", "+" -> {
            val operand = e.expr
            if (e.op == "-" && operand is LiteralExpr && operand.kind == LitKind.INT) {
                // `-<integer literal>` is a literal (unless an unsuffixed literal is too big for Int: that stays a negation)
                val t = irType(r.type)
                var text = operand.text
                val suffixed = text.endsWith("L")
                if (suffixed) text = text.substring(0, text.length - 1)
                val v = parseIntLiteral(text) ?: fail(e, "integer literal")
                // (`-2147483648` is the Int MIN_VALUE: the sema types the negation of the literal as an Int that fits)
                if (v <= 2147483647L || t.classFqName == "kotlin.Long" || -v >= -2147483648L) return intConst(t, -v)
            }
            val info = r.call ?: fail(e, "unary operator is not resolved")
            val v = convert(e.expr)
            return callOperator(info, v, emptyList(), irType(r.type), if (e.op == "-") "UMINUS" else "UPLUS")
        }
        "++", "--" -> return incDec(e.expr, e.op == "++", true, e, r)
    }
    fail(e, "prefix " + e.op)
}

fun Ast2Ir.postfixExpr(e: PostfixExpr): IrExpression {
    val r = rec(e) ?: fail(e, "postfix expression without record")
    if (e.op == "!!") {
        val v = convert(e.expr)
        val nn = irType(ops.makeNotNull(v.type))
        val c = IrCall(builtinFun("CHECK_NOT_NULL"), nn)
        c.origin = "EXCLEXCL"
        c.typeArguments = listOf(nn)
        c.arguments.add(v)
        return c
    }
    return incDec(e.expr, e.op == "++", false, e, r)
}

fun Ast2Ir.indexExpr(e: IndexExpr): IrExpression {
    val r = rec(e) ?: fail(e, "index expression without record")
    val info = r.call ?: fail(e, "get is not resolved")
    val recv = convert(e.receiver)
    val args = ArrayList<IrExpression>()
    for (i in e.indices) args.add(convert(i))
    return callOperator(info, recv, args, irType(r.type), "GET_ARRAY_ELEMENT")
}

/** the innermost receiver in scope (class `this`, extension or lambda receiver) whose type is the class [owner] */
fun Ast2Ir.receiverOfClass(owner: ClassSym): IrExpression {
    var c = ctx
    while (c != null) {
        for (i in c.recvs.indices.reversed()) {
            val r = c.recvs[i]
            val rc = r.cls ?: (r.param.type as? ClassType)?.cls
            if (rc != null && classExtends(rc, owner, 0)) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
        }
        c = c.parent
    }
    return implicitReceiver(RecvRef(owner, null, false), null)
}

/** whether [c] is [base] or inherits from it */
fun Ast2Ir.classExtends(c: ClassSym, base: ClassSym, depth: Int): Boolean {
    if (c === base) return true
    if (depth > 24) return false
    sema.ensureSupertypes(c)
    for (t in c.supertypes) {
        val sc = (t as? ClassType)?.cls ?: continue
        if (classExtends(sc, base, depth + 1)) return true
    }
    return false
}
