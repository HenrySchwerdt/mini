package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/* Ast2Ir: assignments, increments, control flow (if, when, loops, try, jumps), blocks. */

// ---- assignments -------------------------------------------------------------------------------------------------------

fun Ast2Ir.assignExpr(e: BinaryExpr): IrExpression {
    val left = unparen(e.left)
    val unit = sema.unitType
    if (left is IndexExpr) {
        val r = rec(e) ?: fail(e, "index assignment without record")
        val info = r.call ?: fail(e, "set is not resolved")
        val recv = convert(left.receiver)
        val args = ArrayList<IrExpression>()
        for (i in left.indices) args.add(convert(i))
        args.add(convert(e.right))
        return callOperator(info, recv, args, unit, "EQ")
    }
    val lr = rec(left) ?: fail(left, "assignment target without record")
    val sym = lr.callee
    if (sym is VarSym) {
        if (sym.isField) {
            val f = currentAccessorField() ?: fail(left, "field outside of an accessor")
            return IrSetField(f, currentThisOf(f), convert(e.right), unit)
        }
        val v = varMap[sym] ?: fail(left, "variable " + sym.name + " is not declared in the IR")
        return IrSetValue(v, convert(e.right), unit, "EQ")
    }
    if (sym is PropSym) {
        val p = propertyOf(sym)
        val s = p.setter ?: fail(left, "property " + sym.name + " has no setter")
        val access = lr.access
        val value = e.right
        return withReceivers(access, left, s, unit) { callee, c, disp, ext ->
            val cc = findMember(callee, access?.dispatch ?: access?.ext)
            val ir = IrCall(cc, unit)
            ir.origin = "EQ"
            fillAccessorArgs(ir, cc, disp, ext, convert(value))
            ir
        }
    }
    fail(left, "assignment to " + left::class.simpleName)
}

fun Ast2Ir.compoundAssign(e: BinaryExpr): IrExpression {
    val left = unparen(e.left)
    val r = rec(e) ?: fail(e, "compound assignment without record")
    val info = r.call ?: fail(e, "compound assignment operator is not resolved")
    val origin = when (e.op) {
        "+=" -> "PLUSEQ"
        "-=" -> "MINUSEQ"
        "*=" -> "MULTEQ"
        "/=" -> "DIVEQ"
        else -> "PERCEQ"
    }
    val unit = sema.unitType
    val ci = r.extra as? CompoundInfo ?: fail(e, "compound assignment without information")
    val assignOp = ci.assignOp
    if (left is IndexExpr) {
        val lr = rec(left) ?: fail(left, "indexed target without record")
        val getInfo = lr.call ?: fail(left, "get is not resolved")
        val setInfo = ci.set ?: fail(left, "set is not resolved")
        val stmts = ArrayList<IrStatement>()
        val arr = IrVariable("<array>", irType(rec(left.receiver)!!.type), false)
        arr.origin = "IR_TEMPORARY_VARIABLE"
        arr.parent = ctx?.fn
        arr.initializer = convert(left.receiver)
        stmts.add(arr)
        val idxVars = ArrayList<IrVariable>()
        var k = 0
        for (i in left.indices) {
            val iv = IrVariable("<index" + k + ">", irType(rec(i)!!.type), false)
            iv.origin = "IR_TEMPORARY_VARIABLE"
            iv.parent = ctx?.fn
            iv.initializer = convert(i)
            stmts.add(iv)
            idxVars.add(iv)
            k++
        }
        val getArgs = ArrayList<IrExpression>()
        for (iv in idxVars) getArgs.add(getValue(iv))
        val got = callOperator(getInfo, getValue(arr), getArgs, irType(lr.type), origin)
        val op = callOperator(info, got, listOf(convert(e.right)), irType(info.ret ?: lr.type), origin)
        val setArgs = ArrayList<IrExpression>()
        for (iv in idxVars) setArgs.add(getValue(iv))
        setArgs.add(op)
        stmts.add(callOperator(setInfo, getValue(arr), setArgs, unit, origin))
        return IrBlock(stmts, unit, origin)
    }
    val lr = rec(left) ?: fail(left, "compound assignment target without record")
    val sym = lr.callee
    if (assignOp) {
        // `a += b` with plusAssign: a call on the target value
        val target = convert(left)
        return callOperator(info, target, listOf(convert(e.right)), unit, origin)
    }
    if (sym is VarSym && !sym.isField) {
        val v = varMap[sym] ?: fail(left, "variable " + sym.name + " is not declared in the IR")
        val got = IrGetValue(v, v.type, origin)
        val op = callOperator(info, got, listOf(convert(e.right)), irType(info.ret ?: v.type), origin)
        return IrSetValue(v, op, unit, origin)
    }
    if (sym is PropSym || (sym is VarSym && sym.isField)) {
        return compoundOnProperty(e, left, lr, info, origin)
    }
    fail(left, "compound assignment to " + left::class.simpleName)
}

private fun Ast2Ir.compoundOnProperty(e: BinaryExpr, left: Expr, lr: ExprRec, info: CallInfo, origin: String): IrExpression {
    val unit = sema.unitType
    val sym = lr.callee
    if (sym is VarSym) {
        // `field += x`
        val f = currentAccessorField() ?: fail(left, "field outside of an accessor")
        val got = IrGetField(f, currentThisOf(f), f.type)
        val op = callOperator(info, got, listOf(convert(e.right)), f.type, origin)
        return IrSetField(f, currentThisOf(f), op, unit)
    }
    sym as PropSym
    val p = propertyOf(sym)
    val getter = p.getter ?: fail(left, "property without getter")
    val setter = p.setter ?: fail(left, "property " + sym.name + " has no setter")
    val access = lr.access
    val d = access?.dispatch
    val x = access?.ext
    val t = irType(lr.type)
    // an explicit receiver is evaluated once into `<receiver>`
    val explicit = d != null && d.expr != null
    val stmts = ArrayList<IrStatement>()
    var dispUse: IrExpression? = null
    if (explicit) {
        val rv = convert(d!!.expr!!)
        val tmp = IrVariable("<receiver>", rv.type, false)
        tmp.origin = "IR_TEMPORARY_VARIABLE"
        tmp.parent = ctx?.fn
        tmp.initializer = rv
        stmts.add(tmp)
        dispUse = getValue(tmp)
    }
    fun disp(): IrExpression? = if (explicit) getValue((stmts[0] as IrVariable)) else if (d != null) recvValue(d, left) else null
    val ext = if (x != null) recvValue(x, left) else null
    val gc = findMember(getter, d ?: x)
    val got = IrCall(gc, t)
    got.origin = origin
    fillAccessorArgs(got, gc, disp(), ext, null)
    val op = callOperator(info, got, listOf(convert(e.right)), irType(info.ret ?: lr.type), origin)
    val sc = findMember(setter, d ?: x)
    val set = IrCall(sc, unit)
    set.origin = origin
    fillAccessorArgs(set, sc, disp(), if (x != null) recvValue(x, left) else null, op)
    if (!explicit) return set
    stmts.add(set)
    return IrBlock(stmts, unit, origin)
}

fun Ast2Ir.incDec(target0: Expr, inc: Boolean, prefix: Boolean, node: Expr, r: ExprRec): IrExpression {
    val target = unparen(target0)
    val info = r.call ?: fail(node, "inc/dec is not resolved")
    val origin = if (prefix) (if (inc) "PREFIX_INCR" else "PREFIX_DECR") else (if (inc) "POSTFIX_INCR" else "POSTFIX_DECR")
    val tr = rec(target) ?: fail(target, "inc/dec target without record")
    val sym = tr.callee
    val t = irType(tr.type)
    val unit = sema.unitType
    if (target is IndexExpr) fail(target, "increment of an indexed element")
    if (sym is VarSym && !sym.isField) {
        val v = varMap[sym] ?: fail(target, "variable " + sym.name + " is not declared in the IR")
        val stmts = ArrayList<IrStatement>()
        if (prefix) {
            val op = callOperator(info, IrGetValue(v, v.type, origin), emptyList(), irType(info.ret ?: t), origin)
            stmts.add(IrSetValue(v, op, unit, origin))
            stmts.add(IrGetValue(v, v.type, origin))
            return IrBlock(stmts, t, origin)
        }
        val tmp = IrVariable("<unary>", v.type, false)
        tmp.origin = "IR_TEMPORARY_VARIABLE"
        tmp.parent = ctx?.fn
        tmp.initializer = IrGetValue(v, v.type, origin)
        stmts.add(tmp)
        val op = callOperator(info, getValue(tmp), emptyList(), irType(info.ret ?: t), origin)
        stmts.add(IrSetValue(v, op, unit, origin))
        stmts.add(getValue(tmp))
        return IrBlock(stmts, t, origin)
    }
    if (sym is PropSym) {
        val p = propertyOf(sym)
        val getter = p.getter ?: fail(target, "property without getter")
        val setter = p.setter ?: fail(target, "property " + sym.name + " has no setter")
        val access = tr.access
        val d = access?.dispatch
        val x = access?.ext
        val explicit = d != null && d.expr != null
        val stmts = ArrayList<IrStatement>()
        var recvVar: IrVariable? = null
        if (explicit) {
            val rv = convert(d!!.expr!!)
            val tmp = IrVariable("<receiver>", rv.type, false)
            tmp.origin = "IR_TEMPORARY_VARIABLE"
            tmp.parent = ctx?.fn
            tmp.initializer = rv
            stmts.add(tmp)
            recvVar = tmp
        }
        val disp: IrExpression? = if (recvVar != null) getValue(recvVar) else if (d != null) recvValue(d, target) else null
        val ext: IrExpression? = if (x != null) recvValue(x, target) else null
        val gc = findMember(getter, d ?: x)
        val got = IrCall(gc, t)
        got.origin = origin
        fillAccessorArgs(got, gc, disp, ext, null)
        val sc = findMember(setter, d ?: x)
        if (prefix) {
            val op = callOperator(info, got, emptyList(), irType(info.ret ?: t), origin)
            val set = IrCall(sc, unit)
            set.origin = origin
            fillAccessorArgs(set, sc, if (recvVar != null) getValue(recvVar) else disp, ext, op)
            stmts.add(set)
            val got2 = IrCall(gc, t)
            got2.origin = origin
            fillAccessorArgs(got2, gc, if (recvVar != null) getValue(recvVar) else disp, ext, null)
            stmts.add(got2)
            return IrBlock(stmts, t, origin)
        }
        val tmp = IrVariable("<unary>", t, false)
        tmp.origin = "IR_TEMPORARY_VARIABLE"
        tmp.parent = ctx?.fn
        tmp.initializer = got
        stmts.add(tmp)
        val op = callOperator(info, getValue(tmp), emptyList(), irType(info.ret ?: t), origin)
        val set = IrCall(sc, unit)
        set.origin = origin
        fillAccessorArgs(set, sc, if (recvVar != null) getValue(recvVar) else disp, ext, op)
        stmts.add(set)
        stmts.add(getValue(tmp))
        return IrBlock(stmts, t, origin)
    }
    fail(target, "increment of " + target::class.simpleName)
}

// ---- blocks, if, when -----------------------------------------------------------------------------------------------------

fun Ast2Ir.blockExpr(e: BlockExpr): IrExpression {
    val r = rec(e)
    val t = if (r != null) irType(r.type) else sema.unitType
    val stmts = ArrayList<IrStatement>()
    blockStatements(e.stmts, stmts, !isUnit(t))
    return IrBlock(stmts, t, null)
}

/** a branch body: a block stays a block, any other expression is the value itself */
fun Ast2Ir.branchBody(e: Expr): IrExpression = convert(e)

fun Ast2Ir.ifExpr(e: IfExpr): IrExpression {
    val r = rec(e) ?: fail(e, "if without record")
    val t = irType(r.type)
    val branches = ArrayList<IrBranch>()
    // `if (a) x else if (b) y else z` is one `when` with three branches
    var cur: IfExpr = e
    while (true) {
        val cond = convert(cur.cond)
        val thenE = cur.then
        val tb = if (thenE != null) convert(thenE) else unitValue()
        branches.add(IrBranch(cond, if (isUnit(t)) coerceToUnit(tb) else tb))
        val elseE = cur.otherwise
        if (elseE == null) break
        if (elseE is IfExpr) {
            cur = elseE
            continue
        }
        val eb = convert(elseE)
        branches.add(IrBranch(constBool(true), if (isUnit(t)) coerceToUnit(eb) else eb))
        break
    }
    return IrWhen(branches, t, "IF")
}

fun Ast2Ir.whenExpr(e: WhenExpr): IrExpression {
    val r = rec(e) ?: fail(e, "when without record")
    val t = irType(r.type)
    val subj = e.subject
    var subjVar: IrVariable? = null
    var subjValue: IrExpression? = null
    val stmts = ArrayList<IrStatement>()
    if (subj != null) {
        val se = convert(subj.expr)
        if (subj.valName != null) {
            val vr = findSubjectVar(subj)
            val v = IrVariable(subj.valName!!, irType(vr?.type ?: se.type), false)
            v.parent = ctx?.fn
            v.initializer = se
            v.sym = vr
            if (vr != null) varMap[vr] = v
            subjVar = v
        } else {
            subjVar = newTemp("subject", se.type, se)
        }
        stmts.add(subjVar)
    }
    val branches = ArrayList<IrBranch>()
    var hasElse = false
    for (en in e.entries) {
        if (en.isElse) {
            hasElse = true
            val body = convert(en.body)
            branches.add(IrBranch(constBool(true), if (isUnit(t)) coerceToUnit(body) else body))
            continue
        }
        val ones = ArrayList<IrExpression>()
        for (c in en.conds) ones.add(whenCondition(c, subjVar))
        // `a, b, c` is `a || (b || c)`
        var cond: IrExpression? = null
        var oi = ones.size - 1
        while (oi >= 0) {
            cond = if (cond == null) ones[oi] else orElse(ones[oi], cond)
            oi--
        }
        val g = en.guard
        if (g != null) {
            val ge = convert(g)
            cond = andThen(cond!!, ge)
        }
        val body = convert(en.body)
        branches.add(IrBranch(cond!!, if (isUnit(t)) coerceToUnit(body) else body))
    }
    if (!hasElse && r.extra == "exhaustive") {
        val c = IrCall(builtinFun("noWhenBranchMatchedException"), sema.nothingType)
        branches.add(IrBranch(constBool(true), c))
    }
    val w = IrWhen(branches, t, "WHEN")
    if (subjVar == null) return w
    stmts.add(w)
    return IrBlock(stmts, t, "WHEN")
}

private fun Ast2Ir.findSubjectVar(subj: WhenSubject): VarSym? = null

private fun Ast2Ir.orElse(a: IrExpression, b: IrExpression): IrExpression {
    val br = ArrayList<IrBranch>()
    br.add(IrBranch(a, constBool(true)))
    br.add(IrBranch(constBool(true), b))
    return IrWhen(br, sema.booleanType, "OROR")
}

private fun Ast2Ir.andThen(a: IrExpression, b: IrExpression): IrExpression {
    val br = ArrayList<IrBranch>()
    br.add(IrBranch(a, b))
    br.add(IrBranch(constBool(true), constBool(false)))
    return IrWhen(br, sema.booleanType, "ANDAND")
}

private fun Ast2Ir.whenCondition(c: WhenCond, subj: IrVariable?): IrExpression {
    when (c) {
        is WhenExprCond -> {
            if (subj == null) return convert(c.expr)
            val ve = convert(c.expr)
            val eq = IrCall(builtinFun(if (isFloating(subj.type) && isFloating(ve.type)) "ieee754equals" else "EQEQ"), sema.booleanType)
            eq.origin = "EQEQ"
            eq.arguments.add(getValue(subj))
            eq.arguments.add(ve)
            return eq
        }
        is WhenIsCond -> {
            val r = rec(c) ?: fail(c, "is condition without record")
            val target = irType(r.target ?: fail(c, "is condition without type"))
            val op = if (c.negated) IrTypeOperator.NOT_INSTANCEOF else IrTypeOperator.INSTANCEOF
            return IrTypeOperatorCall(op, sema.booleanType, getValue(subj!!), target)
        }
        is WhenInCond -> {
            val r = rec(c) ?: fail(c, "in condition without record")
            val info = r.call ?: fail(c, "contains is not resolved")
            val range = convert(c.expr)
            val call = callOperator(info, range, listOf(getValue(subj!!)), sema.booleanType, if (c.negated) "NOT_IN" else "IN")
            if (!c.negated) return call
            val n = IrCall(memberFunNamed(sema.booleanType, "not", 0), sema.booleanType)
            n.origin = "NOT_IN"
            n.arguments.add(call)
            return n
        }
    }
}

// ---- loops --------------------------------------------------------------------------------------------------------------------

fun Ast2Ir.labeledExpr(e: LabeledExpr): IrExpression {
    val inner = e.expr
    if (inner is WhileExpr) return whileExpr(inner, e.label)
    if (inner is DoWhileExpr) return doWhileExpr(inner, e.label)
    if (inner is ForExpr) return forExpr(inner, e.label)
    if (inner is LambdaExpr) return lambdaExpr(inner, e.label)
    return convert(inner)
}

fun Ast2Ir.loopBodyOf(body: Expr?): IrExpression? {
    if (body == null) return null
    if (body is BlockExpr) {
        val stmts = ArrayList<IrStatement>()
        blockStatements(body.stmts, stmts, false)
        return IrBlock(stmts, sema.unitType, null)
    }
    return coerceToUnit(convert(body))
}

fun Ast2Ir.whileExpr(e: WhileExpr, label: String?): IrExpression {
    val loop = IrWhileLoop(sema.unitType)
    loop.origin = "WHILE_LOOP"
    loop.label = label
    loop.condition = convert(e.cond)
    ctx!!.loops.add(loop)
    loop.body = loopBodyOf(e.body)
    ctx!!.loops.removeAt(ctx!!.loops.size - 1)
    return loop
}

fun Ast2Ir.doWhileExpr(e: DoWhileExpr, label: String?): IrExpression {
    val loop = IrDoWhileLoop(sema.unitType)
    loop.origin = "DO_WHILE_LOOP"
    loop.label = label
    ctx!!.loops.add(loop)
    val b = e.body
    if (b != null) {
        val stmts = ArrayList<IrStatement>()
        if (b is BlockExpr) blockStatements(b.stmts, stmts, false) else stmts.add(coerceToUnit(convert(b)))
        loop.body = IrComposite(stmts, sema.unitType, null)
    }
    loop.condition = convert(e.cond)
    ctx!!.loops.removeAt(ctx!!.loops.size - 1)
    val out = ArrayList<IrStatement>()
    out.add(loop)
    return IrBlock(out, sema.unitType, null)
}

fun Ast2Ir.forExpr(e: ForExpr, label: String?): IrExpression {
    val r = rec(e) ?: fail(e, "for without record")
    val fi = r.extra as? ForInfo ?: fail(e, "for loop without iterator information")
    val unit = sema.unitType
    val iterInfo = fi.iterator ?: fail(e, "iterator() is not resolved")
    val nextInfo = fi.next ?: fail(e, "next() is not resolved")
    val hasNextInfo = fi.hasNext ?: fail(e, "hasNext() is not resolved")
    val iterable = convert(e.iterable)
    val iterCall = callOperator(iterInfo, iterable, emptyList(), irType(iterInfo.ret ?: sema.nullableAny), "FOR_LOOP_ITERATOR")
    val itVar = IrVariable("<iterator>", iterCall.type, false)
    itVar.origin = "FOR_LOOP_ITERATOR"
    itVar.parent = ctx?.fn
    itVar.initializer = iterCall
    val loop = IrWhileLoop(unit)
    loop.origin = "FOR_LOOP_INNER_WHILE"
    loop.label = label
    loop.condition = callOperator(hasNextInfo, getValue(itVar), emptyList(), sema.booleanType, "FOR_LOOP_HAS_NEXT")
    ctx!!.loops.add(loop)
    val body = ArrayList<IrStatement>()
    val elemType = irType(fi.elemType)
    val nextCall = callOperator(nextInfo, getValue(itVar), emptyList(), irType(nextInfo.ret ?: fi.elemType), "FOR_LOOP_NEXT")
    if (e.destructuring) {
        val tmp = IrVariable("<destruct>", nextCall.type, false)
        tmp.origin = "IR_TEMPORARY_VARIABLE"
        tmp.parent = ctx?.fn
        tmp.initializer = nextCall
        body.add(tmp)
        destructureInto(e.entries, tmp, body)
    } else {
        val en = e.entries[0]
        val er = rec(en) ?: fail(en, "loop variable without record")
        val vs = er.callee as? VarSym ?: fail(en, "loop variable without symbol")
        val lv = IrVariable(en.name, irType(vs.type ?: sema.nullableAny), false)
        lv.origin = "FOR_LOOP_VARIABLE"
        lv.parent = ctx?.fn
        lv.sym = vs
        lv.initializer = nextCall
        varMap[vs] = lv
        body.add(lv)
    }
    val b = e.body
    if (b != null) {
        if (b is BlockExpr) {
            val inner = ArrayList<IrStatement>()
            blockStatements(b.stmts, inner, false)
            body.add(IrBlock(inner, unit, null))
        } else body.add(coerceToUnit(convert(b)))
    }
    loop.body = IrBlock(body, unit, "FOR_LOOP_INNER_WHILE")
    ctx!!.loops.removeAt(ctx!!.loops.size - 1)
    val stmts = ArrayList<IrStatement>()
    stmts.add(itVar)
    stmts.add(loop)
    return IrBlock(stmts, unit, "FOR_LOOP")
}

// ---- jumps, try -----------------------------------------------------------------------------------------------------------------

fun Ast2Ir.returnTarget(label: String?, at: Ast): IrFunction {
    var c = ctx
    while (c != null) {
        val fn = c.fn
        if (fn != null) {
            if (label == null) {
                if (!c.isLambda) return fn
            } else if (c.label == label) return fn
        }
        c = c.parent
    }
    fail(at, "return target not found")
}

fun Ast2Ir.returnExpr(e: ReturnExpr): IrExpression {
    val target = returnTarget(e.label, e)
    val v = e.expr
    val value = if (v != null) convert(v) else unitValue()
    return IrReturn(target, value, sema.nothingType)
}

fun Ast2Ir.findLoop(label: String?, at: Ast): IrLoop {
    val c = ctx!!
    if (label == null) {
        if (c.loops.isEmpty()) fail(at, "break / continue outside of a loop")
        return c.loops[c.loops.size - 1]
    }
    for (i in c.loops.indices.reversed()) if (c.loops[i].label == label) return c.loops[i]
    fail(at, "unknown loop label " + label)
}

fun Ast2Ir.breakExpr(e: BreakExpr): IrExpression = IrBreak(findLoop(e.label, e), sema.nothingType)

fun Ast2Ir.continueExpr(e: ContinueExpr): IrExpression = IrContinue(findLoop(e.label, e), sema.nothingType)

fun Ast2Ir.throwExpr(e: ThrowExpr): IrExpression {
    val x = e.expr ?: fail(e, "throw without value")
    return IrThrow(convert(x), sema.nothingType)
}

fun Ast2Ir.tryExpr(e: TryExpr): IrExpression {
    val r = rec(e) ?: fail(e, "try without record")
    val t = irType(r.type)
    val tryBlock = convert(e.body)
    val catches = ArrayList<IrCatch>()
    for (c in e.catches) {
        val pr = rec(c.param) ?: fail(c, "catch parameter without record")
        val vs = pr.callee as? VarSym ?: fail(c, "catch parameter without symbol")
        val v = IrVariable(c.param.name, irType(vs.type ?: sema.nullableAny), false)
        v.origin = "CATCH_PARAMETER"
        v.parent = ctx?.fn
        v.sym = vs
        varMap[vs] = v
        catches.add(IrCatch(v, convert(c.body)))
    }
    val fin = e.finallyBlock
    return IrTry(tryBlock, catches, if (fin != null) convert(fin) else null, t)
}
