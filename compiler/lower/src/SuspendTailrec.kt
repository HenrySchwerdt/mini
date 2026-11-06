package mini.lower

import mini.frontend.sema.*

/*
 * `tailrec suspend fun`: a self call in tail position would become a state machine call (one more frame per iteration, the recursion
 * overflows the stack). Before SuspendLowering the body is rewritten into a loop, like Ir2MirTailrec does for ordinary functions:
 *
 *     while (true) { body }           a tail call `return f(a, b)` becomes   { val t1 = a; val t2 = b; p1 = t1; p2 = t2; continue }
 *
 * The parameters are assigned in place (the state machine captures them in cells as it does every assigned variable). The dispatch
 * receiver must stay `this`; an extension receiver may change (`(this - 1) test x`). A call that omits an argument (a default value) is
 * not rewritten.
 */

private fun isPlainThis(x0: IrExpression?, p: IrValueParameter): Boolean {
    var x: IrExpression? = x0
    while (x is IrTypeOperatorCall && x.operator == IrTypeOperator.IMPLICIT_CAST) x = x.argument
    return x is IrGetValue && x.owner === p
}

private fun isSuspendSelfCall(e: IrExpression, f: IrSimpleFunction): Boolean {
    if (e !is IrCall || e.callee !== f || e.superQualifier != null) return false
    for ((i, p) in f.parameters.withIndex()) {
        if (p.name == "\$completion") continue
        val a = e.arguments.getOrNull(i)
        when (p.kind) {
            IrParameterKind.ExtensionReceiver -> if (a == null) return false
            IrParameterKind.Regular -> if (a == null) return false
            else -> if (!isPlainThis(a, p)) return false
        }
    }
    return true
}

private fun hasSuspendTail(e: IrExpression?, f: IrSimpleFunction): Boolean = when (e) {
    is IrCall -> isSuspendSelfCall(e, f)
    is IrWhen -> e.branches.any { hasSuspendTail(it.result, f) }
    is IrContainerExpression -> e.origin != "FOR_LOOP" && hasSuspendTail(e.statements.lastOrNull() as? IrExpression, f)
    is IrTypeOperatorCall -> (e.operator == IrTypeOperator.IMPLICIT_CAST || e.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) && hasSuspendTail(e.argument, f)
    else -> false
}

private class SuspendTailLoop(val a: Ast2Ir, val f: IrSimpleFunction, val isUnit: Boolean) {
    val unit: Type = a.sema.unitType
    val nothing: Type = a.sema.nothingType
    lateinit var loop: IrWhileLoop
    var found = false
    /** the value the next iteration starts with, per parameter */
    val cur = LinkedHashMap<IrValueParameter, IrVariable>()

    fun jump(c: IrCall): IrExpression {
        val st = ArrayList<IrStatement>()
        val assigns = ArrayList<IrStatement>()
        for ((i, p) in f.parameters.withIndex()) {
            if (p.name == "\$completion" || p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.Context) continue
            val v = c.arguments[i] ?: continue
            val cv = cur[p] ?: continue
            if (v is IrGetValue && v.owner === p) continue
            if (v is IrConst) {
                assigns.add(IrSetValue(cv, v, unit, "EQ"))
                continue
            }
            val t = IrVariable("\$tail" + p.name, p.type, false)
            t.parent = f
            t.initializer = v
            st.add(t)
            assigns.add(IrSetValue(cv, IrGetValue(t, t.type, null), unit, "EQ"))
        }
        st.addAll(assigns)
        st.add(IrContinue(loop, nothing))
        found = true
        return IrBlock(st, nothing, null)
    }

    /** The statement form of `return [e]`: tail calls jump, the other leaves return. [synth]: [e] is the implicit result of a Unit function. */
    fun ret(e: IrExpression, synth: Boolean): IrExpression {
        if (!hasSuspendTail(e, f)) return leaf(e, synth)
        return when (e) {
            is IrCall -> jump(e)
            is IrWhen -> {
                val bs = ArrayList<IrBranch>()
                for (b in e.branches) bs.add(IrBranch(b.condition, ret(b.result, synth)))
                val last = e.branches.lastOrNull()
                val closed = last != null && last.condition.let { it is IrConst && it.value == true }
                if (!closed && isUnit) bs.add(IrBranch(a.constBool(true), IrReturn(f, a.nullConst(), nothing)))
                IrWhen(bs, nothing, e.origin)
            }
            is IrContainerExpression -> {
                val ss = ArrayList<IrStatement>(e.statements)
                val l = ss.size - 1
                ss[l] = ret(ss[l] as IrExpression, synth)
                IrBlock(ss, nothing, e.origin)
            }
            is IrTypeOperatorCall -> ret(e.argument, synth)
            else -> leaf(e, synth)
        }
    }

    fun leaf(e: IrExpression, synth: Boolean): IrExpression {
        if (!synth) return IrReturn(f, e, nothing)
        val ss = ArrayList<IrStatement>()
        ss.add(e)
        ss.add(IrReturn(f, a.nullConst(), nothing))
        return IrBlock(ss, nothing, null)
    }

    fun rewrite(e: IrExpression): IrExpression {
        if (e is IrReturn && e.returnTarget === f && hasSuspendTail(e.value, f)) return ret(e.value, false)
        mapChildren(e) { rewrite(it) }
        return e
    }
}

/** Rewrites the self calls of the `tailrec suspend` function [fn] in tail position into a loop; [body] is returned unchanged when there are none. */
fun Ast2Ir.rewriteSuspendTailrec(fn: IrFunction, body: IrBlockBody): IrBlockBody {
    if (fn !is IrSimpleFunction || fn.sym?.has("tailrec") != true) return body
    val isUnit = isUnit(fn.origReturnType ?: fn.returnType)
    val t = SuspendTailLoop(this, fn, isUnit)
    t.loop = IrWhileLoop(sema.unitType)
    t.loop.origin = "WHILE_LOOP"
    t.loop.condition = constBool(true)
    for (p in fn.parameters) {
        if (p.name == "\$completion" || (p.kind != IrParameterKind.Regular && p.kind != IrParameterKind.ExtensionReceiver)) continue
        val v = IrVariable("\$" + p.name, p.type, true)
        v.parent = fn
        v.initializer = IrGetValue(p, p.type, null)
        t.cur[p] = v
    }
    val stmts = body.statements
    val last = stmts.lastOrNull()
    if (isUnit && last is IrExpression && last !is IrReturn && hasSuspendTail(last, fn)) stmts[stmts.size - 1] = t.ret(last, true)
    var i = 0
    while (i < stmts.size) {
        val s = stmts[i]
        if (s is IrExpression) stmts[i] = t.rewrite(s) else if (s is IrVariable) mapChildren(s) { t.rewrite(it) }
        i++
    }
    if (!t.found) return body
    // every iteration works on fresh values of the parameters (a lambda created in the body keeps its own)
    val subst = HashMap<IrValueDeclaration, IrVariable>()
    val inner = ArrayList<IrStatement>()
    for ((p, cv) in t.cur) {
        val iv = IrVariable(p.name, p.type, false)
        iv.parent = fn
        iv.initializer = IrGetValue(cv, cv.type, null)
        inner.add(iv)
        subst[p] = iv
    }
    inner.addAll(stmts)
    val w = object : IrWalker() {
        override fun visit(e: IrElement) {
            if (e is IrGetValue) {
                val r = subst[e.owner]
                if (r != null) e.owner = r
            }
            visitChildren(e)
        }
    }
    for (s in stmts) w.visit(s)
    if (isUnit) inner.add(IrReturn(fn, nullConst(), sema.nothingType))
    t.loop.body = IrBlock(inner, sema.unitType, null)
    val out = ArrayList<IrStatement>()
    out.addAll(t.cur.values)
    out.add(t.loop)
    out.add(IrReturn(fn, nullConst(), sema.nothingType))
    return IrBlockBody(out)
}
