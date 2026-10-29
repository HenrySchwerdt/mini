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
    if (fn.kind == FunKind.FUNCTION_INVOKE) return invokeFunction(fn.invokeArity)
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
            fail(e, "selector " + (sel as? NameExpr)?.name)
        }
    }
}

fun Ast2Ir.propertyGetDot(sym: PropSym, r: ExprRec, t: Type, node: Ast): IrExpression {
    val cv = constantOf(sym, t)
    if (cv != null) return cv
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
        fillAccessorArgs(ir, cc, disp, ext, null)
        ir
    }
}

/** a call (function, constructor, invoke) resolved as [info]; [node] carries the safe-call receiver */
fun Ast2Ir.callWithInfo(e: CallExpr, info: CallInfo, type: Type, node: Ast): IrExpression {
    val fn = info.fn
    if (info.isInvoke) return invokeCall(e, info, type, node)
    if (fn.kind == FunKind.CONSTRUCTOR) return constructorCall(e, info, type, node)
    val label = fn.name
    val d = info.dispatch
    val x = info.ext
    val safeRecv: RecvUse? = if (d != null && d.expr != null && d.safe) d else if (x != null && x.expr != null && x.safe) x else null
    val staticRecv: Type? = if (d != null) recvStaticType(d) else null
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
    call.origin = null
    // `super.f()`
    val dux = dispatchUse?.expr
    if (dispatchUse != null && dux != null && unparen(dux) is SuperExpr) {
        val st = recvStaticType(dispatchUse)
        val sc = if (st != null) classOf(st) else null
        if (sc != null) call.superQualifier = irClass(sc)
    }
    fillCallArgs(call, callee, info, dispatch, ext, label)
    call.typeArguments = info.typeArgs.map { irType(it) }
    return call
}

fun Ast2Ir.constructorCall(e: CallExpr, info: CallInfo, type: Type, node: Ast): IrExpression {
    val k = irConstructorOf(info.fn)
    val call = IrConstructorCall(k, type)
    call.typeArguments = info.typeArgs.map { irType(it) }
    val d = info.dispatch
    val dv: IrExpression? = if (d != null) recvValue(d, node) else null
    // inner class constructor called without an explicit outer instance: the enclosing `this`
    var outer = dv
    val cs = info.fn.owner
    if (outer == null && cs != null && cs.isInner) {
        val oc = cs.outer
        if (oc != null) outer = implicitReceiver(RecvRef(oc, null, false), node)
    }
    fillCallArgs(call, k, info, outer, null, info.fn.name)
    return call
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
        if (oc != null) outer = implicitReceiver(RecvRef(oc, null, false), null)
    }
    fillCallArgs(call, k, info, outer, null, null)
    return call
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
                call.arguments.add(if (info == null) null else argFor(info, regular, p, subst, label))
                regular++
            }
            else -> call.arguments.add(null)
        }
    }
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
    if (u is LambdaExpr) return lambdaExpr(u, label)
    return convert(ex)
}

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
    if (fn.kind == FunKind.FUNCTION_INVOKE) callee = invokeFunction(fn.invokeArity)
    else callee = functionFor(fn, staticType)
    val call = IrCall(callee, type)
    call.origin = "INVOKE"
    call.typeArguments = info.typeArgs.map { irType(it) }
    // arguments: the first one may be the receiver of a function type with receiver
    var regular = 0
    val subst = HashMap<TypeParamSym, Type>()
    for (pp in callee.parameters) {
        when (pp.kind) {
            IrParameterKind.DispatchReceiver -> call.arguments.add(fv)
            IrParameterKind.ExtensionReceiver -> call.arguments.add(null)
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
            IrParameterKind.DispatchReceiver -> call.arguments.add(if (fn.receiver == null) adaptReceiver(recv, p) else null)
            IrParameterKind.ExtensionReceiver -> call.arguments.add(adaptReceiver(recv, p))
            IrParameterKind.Regular -> {
                call.arguments.add(if (regular < args.size) args[regular] else null)
                regular++
            }
            else -> call.arguments.add(null)
        }
    }
    return call
}

fun Ast2Ir.binaryExpr(e: BinaryExpr): IrExpression {
    val op = e.op
    when (op) {
        "=" -> return assignExpr(e)
        "+=", "-=", "*=", "/=", "%=" -> return compoundAssign(e)
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
    val origin = when (op) {
        "+" -> "PLUS"
        "-" -> "MINUS"
        "*" -> "MUL"
        "/" -> "DIV"
        "%" -> "PERC"
        ".." -> "RANGE"
        "..<" -> "RANGE_UNTIL"
        else -> null
    }
    val l = convert(e.left)
    val rr = convert(e.right)
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
    val l = convert(e.left)
    val r = convert(e.right)
    val ident = e.op == "===" || e.op == "!=="
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
        if (ln != rn) {
            // the narrower operand is converted to the wider type: `x.toLong()`
            val wide = widerNumber(ln!!, rn!!)
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

fun numberRank(n: String): Int = when (n) {
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
    n == "kotlin.Int" || n == "kotlin.Long" || n == "kotlin.Double" || n == "kotlin.Float"

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
            return callOperator(info, v, emptyList(), sema.booleanType, null)
        }
        "-", "+" -> {
            val operand = e.expr
            if (e.op == "-" && operand is LiteralExpr && operand.kind == LitKind.INT) {
                // `-<integer literal>` is a literal
                val t = irType(r.type)
                var text = operand.text
                if (text.endsWith("L")) text = text.substring(0, text.length - 1)
                val v = parseIntLiteral(text) ?: fail(e, "integer literal")
                return intConst(t, -v)
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
