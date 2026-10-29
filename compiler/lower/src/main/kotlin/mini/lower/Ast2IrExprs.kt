package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/* Ast2Ir: expressions. The shapes follow what K2's fir2ir produces (see the dumps in docs/b23-ir.md). */

fun Ast2Ir.rec(n: Ast): ExprRec? = sema.records.get(n)

fun Ast2Ir.typeOf(n: Ast): Type {
    val r = rec(n) ?: fail(n, "no type recorded for " + n::class.simpleName)
    return irType(r.type)
}

fun Ast2Ir.unparen(e: Expr): Expr {
    var x = e
    while (true) {
        if (x is ParenExpr) x = x.inner
        else if (x is AnnotatedExpr) x = x.expr
        else break
    }
    return x
}

// ---- entry ----------------------------------------------------------------------------------------------------------

fun Ast2Ir.convert(e: Expr): IrExpression {
    if (e is ParenExpr) return convert(e.inner)
    if (e is AnnotatedExpr) return convert(e.expr)
    var ir = convertRaw(e)
    val r = rec(e)
    val sm = r?.smart
    if (sm != null) {
        val st = irType(sm)
        if (!typeEquals(st, ir.type)) ir = IrTypeOperatorCall(IrTypeOperator.IMPLICIT_CAST, st, ir, st)
    }
    return ir
}

private fun Ast2Ir.convertRaw(e: Expr): IrExpression {
    when (e) {
        is LiteralExpr -> return literal(e)
        is StringExpr -> return stringExpr(e)
        is NameExpr -> return nameExpr(e, e)
        is ThisExpr -> return thisExpr(e)
        is BinaryExpr -> return binaryExpr(e)
        is PrefixExpr -> return prefixExpr(e)
        is PostfixExpr -> return postfixExpr(e)
        is IsExpr -> return isExpr(e)
        is AsExpr -> return asExpr(e)
        is CallExpr -> return callExpr(e, e)
        is DotExpr -> return dotExpr(e)
        is IndexExpr -> return indexExpr(e)
        is IfExpr -> return ifExpr(e)
        is WhenExpr -> return whenExpr(e)
        is BlockExpr -> return blockExpr(e)
        is WhileExpr -> return whileExpr(e, null)
        is DoWhileExpr -> return doWhileExpr(e, null)
        is ForExpr -> return forExpr(e, null)
        is LabeledExpr -> return labeledExpr(e)
        is ReturnExpr -> return returnExpr(e)
        is BreakExpr -> return breakExpr(e)
        is ContinueExpr -> return continueExpr(e)
        is ThrowExpr -> return throwExpr(e)
        is TryExpr -> return tryExpr(e)
        is LambdaExpr -> return lambdaExpr(e, null)
        is AnonFunExpr -> return anonFunExpr(e)
        is ObjectLiteral -> return objectLiteral(e)
        is CallableRefExpr -> return callableRef(e)
        else -> fail(e, "expression " + e::class.simpleName)
    }
}

// ---- literals --------------------------------------------------------------------------------------------------------

fun unescapeChar(text: String, start: Int, out: StringBuilder): Int {
    // text[start] == '\\'; returns the index after the escape
    val c = text[start + 1]
    when (c) {
        'n' -> { out.append('\n'); return start + 2 }
        't' -> { out.append('\t'); return start + 2 }
        'r' -> { out.append('\r'); return start + 2 }
        'b' -> { out.append('\b'); return start + 2 }
        '\\' -> { out.append('\\'); return start + 2 }
        '\'' -> { out.append('\''); return start + 2 }
        '"' -> { out.append('"'); return start + 2 }
        '$' -> { out.append('$'); return start + 2 }
        'u' -> {
            var v = 0
            var i = start + 2
            while (i < start + 6 && i < text.length) {
                val ch = text[i]
                val d = if (ch >= '0' && ch <= '9') ch - '0' else if (ch >= 'a' && ch <= 'f') ch - 'a' + 10 else if (ch >= 'A' && ch <= 'F') ch - 'A' + 10 else 0
                v = v * 16 + d
                i++
            }
            out.append(v.toChar())
            return start + 6
        }
        else -> { out.append(c); return start + 2 }
    }
}

fun unescape(text: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length) i = unescapeChar(text, i, sb) else {
            sb.append(text[i])
            i++
        }
    }
    return sb.toString()
}

fun Ast2Ir.literal(e: LiteralExpr): IrExpression {
    val t = typeOf(e)
    when (e.kind) {
        LitKind.TRUE -> return IrConst(t, true)
        LitKind.FALSE -> return IrConst(t, false)
        LitKind.NULL -> return IrConst(sema.nullableNothing, null)
        LitKind.CHAR -> {
            val body = e.text.substring(1, e.text.length - 1)
            val s = unescape(body)
            return IrConst(t, s[0])
        }
        LitKind.REAL -> {
            var text = e.text.replace("_", "")
            val last = text[text.length - 1]
            if (last == 'f' || last == 'F') fail(e, "Float literal")
            return IrConst(t, text.toDouble())
        }
        LitKind.INT -> {
            var text = e.text
            if (text.endsWith("L")) text = text.substring(0, text.length - 1)
            val v = parseIntLiteral(text) ?: fail(e, "integer literal " + e.text)
            return intConst(t, v)
        }
    }
}

fun Ast2Ir.intConst(t: Type, v: Long): IrExpression {
    val n = t.classFqName
    if (n == "kotlin.Long") return IrConst(t, v)
    return IrConst(t, v.toInt())
}

fun Ast2Ir.stringExpr(e: StringExpr): IrExpression {
    var template = false
    for (p in e.parts) if (p is StrShortRef || p is StrLongExpr) template = true
    if (!template) {
        val sb = StringBuilder()
        for (p in e.parts) appendStringPart(sb, p, e.raw)
        return IrConst(sema.stringType, sb.toString())
    }
    val args = ArrayList<IrExpression>()
    val run = StringBuilder()
    var hasRun = false
    for (p in e.parts) {
        when (p) {
            is StrText, is StrEscape -> {
                appendStringPart(run, p, e.raw)
                hasRun = true
            }
            is StrShortRef -> {
                if (hasRun) {
                    args.add(IrConst(sema.stringType, run.toString()))
                    run.setLength(0)
                    hasRun = false
                }
                if (p.name == "this") args.add(thisExprAt(p, null)) else args.add(nameExpr(NameExpr(p.name), p))
            }
            is StrLongExpr -> {
                if (hasRun) {
                    args.add(IrConst(sema.stringType, run.toString()))
                    run.setLength(0)
                    hasRun = false
                }
                args.add(convert(p.expr))
            }
        }
    }
    if (hasRun) args.add(IrConst(sema.stringType, run.toString()))
    return IrStringConcatenation(args, sema.stringType)
}

private fun appendStringPart(sb: StringBuilder, p: StrPart, raw: Boolean) {
    when (p) {
        is StrText -> sb.append(p.text)
        is StrEscape -> if (raw) sb.append(p.text) else sb.append(unescape(p.text))
        else -> {}
    }
}

// ---- names, this -------------------------------------------------------------------------------------------------------

/** the dispatch / extension receiver parameter of the enclosing context that [ref] denotes */
fun Ast2Ir.implicitReceiver(ref: RecvRef, at: Ast?): IrExpression {
    if (ref.isObject) {
        val rcs = ref.cls!!
        val c = irClass(rcs)
        return IrGetObjectValue(c, irType(rcs.thisType()))
    }
    var c = ctx
    while (c != null) {
        for (i in c.recvs.indices.reversed()) {
            val r = c.recvs[i]
            val sc = ref.scope
            if (ref.cls != null && r.cls === ref.cls) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
            if (sc != null) {
                if (sc.lambda != null && r.lambda === sc.lambda) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
                if (sc.fn != null && r.fn === sc.fn) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
                if (sc.prop != null && r.prop === sc.prop) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
            }
        }
        c = c.parent
    }
    fail(at, "implicit receiver not found")
}

fun Ast2Ir.thisExpr(e: ThisExpr): IrExpression = thisExprAt(e, e.label)

fun Ast2Ir.thisExprAt(node: Ast, label: String?): IrExpression {
    val r = rec(node) ?: fail(node, "this without record")
    // the receiver the keyword denotes: innermost class / lambda receiver (or the labelled one)
    val t = irType(r.type)
    var c = ctx
    while (c != null) {
        for (i in c.recvs.indices.reversed()) {
            val re = c.recvs[i]
            if (label == null) {
                if (re.cls != null && re.cls.isCompanion && false) continue
                return IrGetValue(re.param, re.param.type, null)
            }
            if (re.cls != null && re.cls.name == label) return IrGetValue(re.param, re.param.type, null)
            if (re.fn != null && re.fn.name == label) return IrGetValue(re.param, re.param.type, null)
            if (re.lambda != null && labelOfLambda(re.lambda) == label) return IrGetValue(re.param, re.param.type, null)
            if (re.prop != null && re.prop.name == label) return IrGetValue(re.param, re.param.type, null)
        }
        c = c.parent
    }
    fail(node, "this not found")
}

fun Ast2Ir.labelOfLambda(l: LambdaExpr): String? = lambdaLabels[l]

fun Ast2Ir.nameExpr(e: NameExpr, node: Ast): IrExpression {
    val r = rec(node) ?: fail(node, "unresolved name " + e.name)
    val sym = r.callee
    val t = irType(r.type)
    when (sym) {
        is VarSym -> {
            if (sym.isField) return fieldAccess(node)
            val v = varMap[sym] ?: fail(node, "variable " + sym.name + " is not declared in the IR")
            return IrGetValue(v, v.type, null)
        }
        is PropSym -> return propertyGet(sym, r.access, t, node)
        is EnumEntrySym -> {
            val c = irClass(sym.owner)
            val en = entryMap[sym] ?: fail(node, "enum entry " + sym.name)
            return IrGetEnumValue(en, t)
        }
        else -> {
            val tg = r.target
            if (tg is ClassType && tg.cls.isObject) return IrGetObjectValue(irClass(tg.cls), irType(tg))
            fail(node, "name " + e.name + " is not a value")
        }
    }
}

/** `field` inside an accessor */
fun Ast2Ir.fieldAccess(node: Ast): IrExpression {
    val f = currentAccessorField() ?: fail(node, "field outside of an accessor")
    val recv = currentThisOf(f)
    return IrGetField(f, recv, f.type)
}

fun Ast2Ir.currentAccessorField(): IrField? {
    var c = ctx
    while (c != null) {
        val fn = c.fn
        if (fn is IrSimpleFunction) {
            val p = fn.correspondingProperty
            if (p != null) return p.backingField
        }
        c = c.parent
    }
    return null
}

fun Ast2Ir.currentThisOf(f: IrField): IrExpression? {
    if (f.isStatic) return null
    val fn = ctx?.fn ?: return null
    for (p in fn.parameters) if (p.kind == IrParameterKind.DispatchReceiver) return IrGetValue(p, p.type, null)
    return null
}

// ---- property access -----------------------------------------------------------------------------------------------------

fun Ast2Ir.recvClassOf(u: RecvUse?): IrClass? {
    if (u == null) return null
    val t = u.type
    if (t != null) {
        val c = classOf(t) ?: return null
        return irClass(c)
    }
    val ref = u.ref ?: return null
    val rc = ref.cls
    if (rc != null) return irClass(rc)
    val sc = ref.scope
    if (sc != null) {
        val c = classOf(sc.type) ?: return null
        return irClass(c)
    }
    return null
}

/** the value of a receiver use: an explicit expression or an implicit receiver (safe calls are handled by the callers) */
fun Ast2Ir.recvValue(u: RecvUse, at: Ast?): IrExpression {
    val ex = u.expr
    if (ex != null) return convert(ex)
    return implicitReceiver(u.ref!!, at)
}

fun Ast2Ir.propertyOf(sym: PropSym): IrProperty {
    val ex = propMap[sym]
    if (ex != null) return ex
    val o = sym.owner
    if (o != null) {
        irClass(o)
        val p = propMap[sym]
        if (p != null) return p
    }
    fail(null, "property " + sym.name + " is not declared in the IR")
}

fun Ast2Ir.accessorCall(f: IrSimpleFunction, access: Access?, recvClassHint: IrClass?, type: Type, origin: String?, node: Ast?): IrCall {
    val call = IrCall(f, type)
    call.origin = origin
    return call
}

fun Ast2Ir.propertyGet(sym: PropSym, access: Access?, type: Type, node: Ast): IrExpression {
    val p = propertyOf(sym)
    val g = p.getter ?: fail(node, "property " + sym.name + " has no getter")
    return withReceivers(access, node, g, type) { callee, call, disp, ext ->
        val cc = findMember(callee, access?.dispatch ?: access?.ext)
        val ir = IrCall(cc, type)
        ir.origin = "GET_PROPERTY"
        fillAccessorArgs(ir, cc, disp, ext, null)
        ir
    }
}

fun Ast2Ir.findMember(f: IrSimpleFunction, recv: RecvUse?): IrSimpleFunction {
    val rc = recvClassOf(recv) ?: return f
    return findMemberIn(rc, f)
}

fun Ast2Ir.fillAccessorArgs(call: IrCall, callee: IrFunction, disp: IrExpression?, ext: IrExpression?, value: IrExpression?) {
    for (p in callee.parameters) {
        when (p.kind) {
            IrParameterKind.DispatchReceiver -> call.arguments.add(disp)
            IrParameterKind.ExtensionReceiver -> call.arguments.add(ext)
            IrParameterKind.Regular -> call.arguments.add(value)
            else -> call.arguments.add(null)
        }
    }
}

/**
 * Builds the receivers of [access] (explicit expressions, implicit receivers; a safe-call receiver wraps the result in
 * the `SAFE_CALL` block) and calls [build] with the callee, the dispatch and the extension receiver values.
 */
fun Ast2Ir.withReceivers(
    access: Access?,
    node: Ast,
    callee: IrSimpleFunction,
    resultType: Type,
    build: (IrSimpleFunction, IrCall?, IrExpression?, IrExpression?) -> IrExpression,
): IrExpression {
    val d = access?.dispatch
    val x = access?.ext
    if (d != null && d.expr != null && d.safe) {
        return safeCall(d, node, resultType) { rv -> build(callee, null, rv, if (x != null) recvValue(x, node) else null) }
    }
    if (x != null && x.expr != null && x.safe) {
        return safeCall(x, node, resultType) { rv -> build(callee, null, if (d != null) recvValue(d, node) else null, rv) }
    }
    val dv = if (d != null) recvValue(d, node) else null
    val xv = if (x != null) recvValue(x, node) else null
    return build(callee, null, dv, xv)
}

/**
 * `a?.b`: a block that evaluates the receiver once into a temporary and yields null when it is null; [build] gets the
 * receiver value (the temporary cast to its non-null type).
 */
fun Ast2Ir.safeCall(recv: RecvUse, node: Ast, resultType: Type, build: (IrExpression) -> IrExpression): IrExpression {
    val rexpr = convert(recv.expr!!)
    val tmpType = rexpr.type
    val tmp = newTemp("safe_receiver", tmpType, rexpr)
    val nn = irType(ops.makeNotNull(tmpType))
    val value: IrExpression = IrTypeOperatorCall(IrTypeOperator.IMPLICIT_CAST, nn, getValue(tmp), nn)
    val inner = build(value)
    val rt = irType(ops.makeNullable(resultType))
    val cond = eqNull(getValue(tmp))
    val branches = ArrayList<IrBranch>()
    branches.add(IrBranch(cond, nullConst()))
    branches.add(IrBranch(constBool(true), inner))
    val w = IrWhen(branches, rt, null)
    val stmts = ArrayList<IrStatement>()
    stmts.add(tmp)
    stmts.add(w)
    return IrBlock(stmts, rt, "SAFE_CALL")
}

fun Ast2Ir.eqNull(e: IrExpression): IrExpression {
    val call = IrCall(builtinFun("EQEQ"), sema.booleanType)
    call.origin = "EQEQ"
    call.arguments.add(e)
    call.arguments.add(nullConst())
    return call
}

// ---- is / as ------------------------------------------------------------------------------------------------------------------

fun Ast2Ir.isExpr(e: IsExpr): IrExpression {
    val r = rec(e) ?: fail(e, "is without record")
    val target = irType(r.target ?: fail(e, "is without target"))
    val arg = convert(e.expr)
    return IrTypeOperatorCall(if (e.negated) IrTypeOperator.NOT_INSTANCEOF else IrTypeOperator.INSTANCEOF, sema.booleanType, arg, target)
}

fun Ast2Ir.asExpr(e: AsExpr): IrExpression {
    val r = rec(e) ?: fail(e, "as without record")
    val target = irType(r.target ?: fail(e, "as without target"))
    val arg = convert(e.expr)
    if (e.safe) return IrTypeOperatorCall(IrTypeOperator.SAFE_CAST, irType(ops.makeNullable(target)), arg, target)
    return IrTypeOperatorCall(IrTypeOperator.CAST, target, arg, target)
}

// ---- builtin functions of kotlin.internal.ir ---------------------------------------------------------------------------------------

private val builtinFuns = HashMap<String, IrSimpleFunction>()
private var builtinFile: IrFile? = null

fun Ast2Ir.builtinFun(name: String): IrSimpleFunction {
    val ex = builtinFuns[name]
    if (ex != null) return ex
    var f = builtinFile
    if (f == null) {
        f = IrFile("<ir builtins>", "", "kotlin.internal.ir", false)
        builtinFile = f
    }
    val fn = IrSimpleFunction(name)
    fn.parent = f
    fn.origin = "IR_BUILTINS_STUB"
    val n = when (name) {
        "less", "lessOrEqual", "greater", "greaterOrEqual", "EQEQ", "EQEQEQ", "ieee754equals" -> 2
        "CHECK_NOT_NULL" -> 1
        else -> 0
    }
    var i = 0
    while (i < n) {
        val p = IrValueParameter("arg" + i, sema.nullableAny, IrParameterKind.Regular, i)
        p.parent = fn
        fn.parameters.add(p)
        i++
    }
    fn.returnType = if (name == "noWhenBranchMatchedException") sema.nothingType else if (name == "CHECK_NOT_NULL") sema.anyType else sema.booleanType
    builtinFuns[name] = fn
    return fn
}
