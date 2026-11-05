package mini.lower

import mini.frontend.sema.*

/*
 * Suspend functions (docs/coroutines.md): CPS plus a state machine, built on the IR of Ast2Ir before Ir2Mir.
 *
 *  - `suspend fun f(a): R` becomes `fun f(a, $completion: Continuation<Any?>): Any?`; the result is the value of the function or
 *    the marker COROUTINE_SUSPENDED. A call of a suspend function gets the continuation of the caller as its last argument.
 *    A function without suspension points just returns its value.
 *  - A function with suspension points is `CoroutineImpl($completion, step).start()`: `step` is a lambda
 *    `(self, result) -> Any?` holding the state machine `while (true) { try { when (label) { 0 -> ..., 1 -> ... } } catch ... }`.
 *    Every local variable of the function is hoisted into the function (so the lambda captures it in a heap cell, which is what
 *    keeps it alive across a suspension). A suspension point sets `label`, calls the function and returns the marker when it
 *    suspended; the resume block `k` reads the value (or throws the failure) from the result passed to `step`.
 *  - Structured control flow that contains a suspension point (if/when, loops, try/catch/finally, break/continue/return) is
 *    flattened into blocks; everything without one stays as it is. try/catch works with a `handler` variable that names the block
 *    the machine jumps to when an exception comes out of a block; `finally` is one shared block entered with a return address.
 */

private class SmBlock(val id: Int) {
    val stmts = ArrayList<IrStatement>()
}

private class SmLoop(val breakTo: SmBlock, val continueTo: SmBlock, val depth: Int)

private class SmExit(val idx: Int, val block: SmBlock)

private class SmTry(val depth: Int) {
    var active = -2
    var finBlock: SmBlock? = null
    var finVar: IrVariable? = null
    val exits = ArrayList<SmExit>()
}

/** One suspend function being lowered. */
private class SmBuilder(val a: Ast2Ir, val fn: IrFunction) {
    val sema = a.sema
    val nothing: Type = sema.nothingType
    val unit: Type = sema.unitType
    val anyN: Type = sema.nullableAny
    val intT: Type = sema.intType
    val boolT: Type = sema.booleanType
    val implCls: ClassSym = sema.classNamed("kotlin.coroutines.CoroutineImpl") ?: a.fail(null, "kotlin.coroutines.CoroutineImpl is missing from the standard library")
    val implT: Type = ClassType(implCls, emptyList(), false)
    val throwableT: Type = ClassType(sema.classNamed("kotlin.Throwable") ?: a.fail(null, "kotlin.Throwable"), emptyList(), true)

    val hoisted = ArrayList<IrStatement>()
    val prelude = ArrayList<IrStatement>()
    val blocks = ArrayList<SmBlock>()
    var cur: SmBlock = SmBlock(0)
    val suspendCache = HashMap<IrElement, Boolean>()
    val flatLoops = HashMap<IrLoop, SmLoop>()
    val tryStack = ArrayList<SmTry>()

    lateinit var step: IrSimpleFunction
    lateinit var self: IrValueParameter
    lateinit var resultParam: IrValueParameter
    lateinit var machine: IrWhileLoop
    lateinit var labelVar: IrVariable
    lateinit var handlerVar: IrVariable
    lateinit var excVar: IrVariable
    var retVar: IrVariable? = null
    var tmpCounter = 0

    fun newVar(name: String, t: Type, init: IrExpression?): IrVariable {
        val v = IrVariable(name, t, true)
        v.parent = fn
        v.initializer = init
        hoisted.add(v)
        return v
    }

    fun tmp(t: Type): IrVariable = newVar("\$tmp" + (tmpCounter++), t, null)

    fun newBlock(): SmBlock {
        val b = SmBlock(blocks.size + 1)
        blocks.add(b)
        return b
    }

    fun emit(s: IrStatement) {
        cur.stmts.add(s)
    }

    fun get(v: IrValueDeclaration): IrExpression = IrGetValue(v, v.type, null)

    fun set(v: IrValueDeclaration, e: IrExpression): IrExpression = IrSetValue(v, e, unit, "EQ")

    fun isUnitOrNothing(t: Type): Boolean = a.isUnit(t) || a.isNothing(t)

    // ---- suspension points ---------------------------------------------------------------------------------------

    fun isSuspendCall(e: IrElement): Boolean = e is IrCall && (e.callee.isSuspend || e.origin == "SUSPEND_INVOKE")

    fun hasSuspend(e: IrElement?): Boolean {
        if (e == null) return false
        val c = suspendCache[e]
        if (c != null) return c
        var r = false
        if (isSuspendCall(e)) r = true
        else if (e !is IrFunctionExpression && e !is IrClass && e !is IrFunction) {
            val ch = ArrayList<IrElement>()
            irChildren(e, ch)
            for (x in ch) if (hasSuspend(x)) {
                r = true
                break
            }
        }
        suspendCache[e] = r
        return r
    }

    // ---- jumps -------------------------------------------------------------------------------------------------

    fun gotoStmt(b: SmBlock): IrExpression {
        val st = ArrayList<IrStatement>()
        st.add(set(labelVar, a.constInt(b.id)))
        st.add(IrContinue(machine, nothing))
        return IrBlock(st, nothing, null)
    }

    fun terminate(b: SmBlock) {
        emit(gotoStmt(b))
    }

    /** the handler block that is active at nesting depth [d] (the number of enclosing flattened try statements) */
    fun handlerAt(d: Int): Int {
        var k = d - 1
        while (k >= 0) {
            val t = tryStack[k]
            if (t.active >= 0) return t.active
            k--
        }
        return -1
    }

    fun setHandler(h: Int) {
        emit(set(handlerVar, a.constInt(h)))
    }

    /** Leaves to [target] (a loop's break or continue block) or, for a return (loop == null), out of the function. */
    fun jump(loop: SmLoop?, target: SmBlock?, value: IrExpression?) {
        val targetDepth = if (loop != null) loop.depth else 0
        var i = tryStack.size - 1
        while (i >= targetDepth) {
            val t = tryStack[i]
            val fin = t.finBlock
            if (fin != null) {
                val idx = t.exits.size + 1
                val saved = cur
                val savedStack = ArrayList<SmTry>(tryStack)
                val b = newBlock()
                cur = b
                while (tryStack.size > i) tryStack.removeAt(tryStack.size - 1)
                jump(loop, target, null)
                cur = saved
                tryStack.clear()
                tryStack.addAll(savedStack)
                t.exits.add(SmExit(idx, b))
                emit(set(t.finVar!!, a.constInt(idx)))
                terminate(fin)
                return
            }
            i--
        }
        if (loop == null) {
            val rv = retVar
            if (rv != null && value == null) emit(IrReturn(step, get(rv), nothing))
            else emit(IrReturn(step, value ?: a.nullConst(), nothing))
            return
        }
        setHandler(handlerAt(targetDepth))
        terminate(target!!)
    }

    /** `return value` in flattened code: the value is evaluated, stored when a finally block has to run first */
    fun emitReturn(value: IrExpression) {
        var anyFinally = false
        for (t in tryStack) if (t.finBlock != null) anyFinally = true
        if (anyFinally) {
            var rv = retVar
            if (rv == null) {
                rv = newVar("\$ret", anyN, null)
                retVar = rv
            }
            emit(set(rv, value))
            jump(null, null, null)
        } else {
            emit(IrReturn(step, value, nothing))
        }
        cur = newBlock()
    }

    /** Rewrites the jumps of code that stays as it is: returns of the function and breaks / continues of flattened loops. */
    fun retarget(e: IrExpression): IrExpression {
        if (e is IrReturn && e.returnTarget === fn) {
            val v = retarget(e.value)
            val saved = cur
            val tmpB = SmBlock(-1)
            cur = tmpB
            emitReturnValueOnly(v)
            cur = saved
            return IrBlock(tmpB.stmts, nothing, null)
        }
        if (e is IrBreak) {
            val l = flatLoops[e.loop]
            if (l != null) return jumpExpr(l, l.breakTo)
            return e
        }
        if (e is IrContinue) {
            val l = flatLoops[e.loop]
            if (l != null) return jumpExpr(l, l.continueTo)
            return e
        }
        mapChildren(e) { c -> retarget(c) }
        return e
    }

    fun jumpExpr(l: SmLoop, target: SmBlock): IrExpression {
        val saved = cur
        val tmpB = SmBlock(-1)
        cur = tmpB
        jump(l, target, null)
        cur = saved
        return IrBlock(tmpB.stmts, nothing, null)
    }

    fun emitReturnValueOnly(value: IrExpression) {
        var anyFinally = false
        for (t in tryStack) if (t.finBlock != null) anyFinally = true
        if (anyFinally) {
            var rv = retVar
            if (rv == null) {
                rv = newVar("\$ret", anyN, null)
                retVar = rv
            }
            emit(set(rv, value))
            jump(null, null, null)
        } else {
            emit(IrReturn(step, value, nothing))
        }
    }

    // ---- expressions ---------------------------------------------------------------------------------------------

    fun isTrivial(e: IrExpression): Boolean {
        if (e is IrConst) return true
        if (e is IrGetValue) {
            val o = e.owner
            return !(o is IrVariable && o.isVar)
        }
        return false
    }

    /** Evaluates the suspension points inside [e] into statements; the result computes the rest of [e]. */
    fun emitExpr(e: IrExpression): IrExpression {
        if (!hasSuspend(e)) return retarget(e)
        if (isSuspendCall(e)) return emitSuspendCall(e as IrCall)
        if (e is IrWhen || e is IrContainerExpression || e is IrTry || e is IrLoop) {
            if (isUnitOrNothing(e.type)) {
                emitInto(null, e)
                return a.unitValue()
            }
            val t = tmp(e.type)
            emitInto(t, e)
            return get(t)
        }
        when (e) {
            is IrFunctionAccessExpression -> emitList(e.arguments)
            is IrStringConcatenation -> {
                val l = ArrayList<IrExpression?>(e.arguments)
                emitList(l)
                e.arguments.clear()
                for (x in l) e.arguments.add(x!!)
            }
            is IrTypeOperatorCall -> e.argument = emitExpr(e.argument)
            is IrGetField -> {
                val r = e.receiver
                if (r != null) e.receiver = emitExpr(r)
            }
            is IrSetField -> {
                val l = ArrayList<IrExpression?>()
                l.add(e.receiver)
                l.add(e.value)
                emitList(l)
                e.receiver = l[0]
                e.value = l[1]!!
            }
            is IrSetValue -> e.value = emitExpr(e.value)
            is IrThrow -> e.value = emitExpr(e.value)
            is IrGetClass -> e.argument = emitExpr(e.argument)
            is IrVararg -> {
                val l = ArrayList<IrExpression?>()
                for (x in e.elements) l.add(if (x is IrSpreadElement) x.expression else x as IrExpression)
                emitList(l)
                var i = 0
                for (x in e.elements) {
                    if (x is IrSpreadElement) x.expression = l[i]!! else e.elements[i] = l[i]!!
                    i++
                }
            }
            is IrReturn -> {
                emitReturn(emitExpr(e.value))
                return a.unitValue()
            }
            else -> a.fail(null, "suspension point in an unsupported expression (" + e.toString() + ")")
        }
        return e
    }

    /** the operands of one expression, in evaluation order: those before the last suspension point are saved first */
    fun emitList(l: ArrayList<IrExpression?>) {
        var last = -1
        var i = 0
        while (i < l.size) {
            if (hasSuspend(l[i])) last = i
            i++
        }
        i = 0
        while (i <= last) {
            val x = l[i]
            if (x != null) {
                if (hasSuspend(x)) {
                    l[i] = emitExpr(x)
                } else if (i < last && !isTrivial(x)) {
                    val rx = retarget(x)
                    if (isUnitOrNothing(rx.type)) {
                        emit(rx)
                    } else {
                        val t = tmp(rx.type)
                        emit(set(t, rx))
                        l[i] = get(t)
                    }
                }
            }
            i++
        }
        // the arguments of a call are mirrored back by the caller; for calls the list is the call's own
        i = last + 1
        while (i < l.size) {
            val x = l[i]
            if (x != null) l[i] = retarget(x)
            i++
        }
    }

    fun emitSuspendCall(c: IrCall): IrExpression {
        val origType = c.type
        var call: IrCall = c
        if (c.callee.isSuspend && c.callee.name == "suspendCoroutineUninterceptedOrReturn" && c.arguments.size == 2 && isCoroutinePkg(c.callee)) {
            // block(<current continuation>)
            val inv = IrCall(a.invokeFunction(1), anyN)
            inv.origin = "INVOKE"
            inv.arguments.add(c.arguments[0])
            inv.arguments.add(get(self))
            inv.typeArguments = emptyList()
            call = inv
        } else {
            // the last argument is the continuation
            val n = call.arguments.size
            call.arguments[n - 1] = get(self)
        }
        emitList(call.arguments)
        call.type = anyN
        val rb = newBlock()
        val cb = newBlock()
        val tv = tmp(anyN)
        emit(set(labelVar, a.constInt(rb.id)))
        // the result is checked in a variable of the block, not in a hoisted one: a suspension object that stayed in the
        // heap cell of the function would keep the continuation it refers to alive (a reference cycle)
        val cv = IrVariable("\$call", anyN, false)
        cv.parent = step
        cv.initializer = call
        emit(cv)
        val isS = IrCall(a.memberFunNamed(implT, "isSuspended", 1), boolT)
        isS.arguments.add(get(self))
        isS.arguments.add(get(cv))
        val w = ArrayList<IrBranch>()
        w.add(IrBranch(isS, IrReturn(step, get(cv), nothing)))
        emit(IrWhen(w, unit, null))
        emit(set(tv, get(cv)))
        terminate(cb)
        cur = rb
        val rv = IrCall(a.memberFunNamed(implT, "resumeValue", 1), anyN)
        rv.arguments.add(get(self))
        rv.arguments.add(get(resultParam))
        emit(set(tv, rv))
        terminate(cb)
        cur = cb
        if (isUnitOrNothing(origType)) return a.unitValue()
        return IrTypeOperatorCall(IrTypeOperator.CAST, origType, get(tv), origType)
    }

    fun isCoroutinePkg(f: IrSimpleFunction): Boolean {
        val p = f.parent
        return p is IrFile && p.packageFqName == "kotlin.coroutines"
    }

    // ---- statements ----------------------------------------------------------------------------------------------

    fun assignTo(target: IrVariable?, e: IrExpression) {
        if (target == null || isUnitOrNothing(e.type)) {
            emit(e)
        } else {
            emit(set(target, e))
        }
    }

    /** emits [e] as flattened code; its value goes to [target] (null: not needed) */
    fun emitInto(target: IrVariable?, e: IrElement) {
        if (e is IrVariable) {
            // a local variable: hoisted, so that it lives across suspension points
            val init = e.initializer
            e.initializer = null
            e.isVar = true
            e.parent = fn
            hoisted.add(e)
            if (init != null) emitInto(e, init)
            return
        }
        if (e is IrFunction || e is IrClass || e is IrLocalDelegatedProperty) {
            prelude.add(e as IrStatement)
            return
        }
        e as IrExpression
        if (!hasSuspend(e)) {
            val r = retarget(e)
            assignTo(target, r)
            return
        }
        if (isSuspendCall(e)) {
            assignTo(target, emitSuspendCall(e as IrCall))
            return
        }
        when (e) {
            is IrContainerExpression -> {
                var i = 0
                for (s in e.statements) {
                    if (i == e.statements.size - 1) emitInto(target, s) else emitInto(null, s)
                    i++
                }
            }
            is IrWhen -> emitWhen(target, e)
            is IrWhileLoop -> emitLoop(e, false)
            is IrDoWhileLoop -> emitLoop(e, true)
            is IrTry -> emitTry(target, e)
            is IrReturn -> emitReturn(emitExpr(e.value))
            is IrTypeOperatorCall -> {
                if (e.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) emitInto(null, e.argument) else assignTo(target, emitExpr(e))
            }
            else -> assignTo(target, emitExpr(e))
        }
    }

    fun emitWhen(target: IrVariable?, w: IrWhen) {
        val endB = newBlock()
        var closed = false
        for (br in w.branches) {
            val cond = br.condition
            if (cond is IrConst && cond.value == true) {
                emitInto(target, br.result)
                closed = true
                break
            }
            val c = emitExpr(cond)
            val thenB = newBlock()
            val nextB = newBlock()
            val ws = ArrayList<IrBranch>()
            ws.add(IrBranch(c, gotoStmt(thenB)))
            ws.add(IrBranch(a.constBool(true), gotoStmt(nextB)))
            emit(IrWhen(ws, unit, null))
            cur = thenB
            emitInto(target, br.result)
            terminate(endB)
            cur = nextB
        }
        terminate(endB)
        cur = endB
    }

    fun emitLoop(l: IrLoop, doWhile: Boolean) {
        val condB = newBlock()
        val bodyB = newBlock()
        val endB = newBlock()
        flatLoops[l] = SmLoop(endB, condB, tryStack.size)
        terminate(if (doWhile) bodyB else condB)
        cur = condB
        val c = emitExpr(l.condition)
        val ws = ArrayList<IrBranch>()
        ws.add(IrBranch(c, gotoStmt(bodyB)))
        ws.add(IrBranch(a.constBool(true), gotoStmt(endB)))
        emit(IrWhen(ws, unit, null))
        cur = bodyB
        val body = l.body
        if (body != null) emitInto(null, body)
        terminate(condB)
        cur = endB
    }

    fun emitTry(target: IrVariable?, t: IrTry) {
        val fin = t.finallyExpression
        val endB = newBlock()
        val ctx = SmTry(tryStack.size)
        val handlerB = if (t.catches.isNotEmpty()) newBlock() else null
        var faB: SmBlock? = null
        if (fin != null) {
            ctx.finBlock = newBlock()
            ctx.finVar = newVar("\$fin", intT, a.constInt(0))
            faB = newBlock()
        }
        val outerH = handlerAt(tryStack.size)
        ctx.active = if (handlerB != null) handlerB.id else if (faB != null) faB.id else -2
        tryStack.add(ctx)
        if (ctx.active >= 0) setHandler(ctx.active)
        emitInto(target, t.tryResult)
        leaveNormally(ctx, endB, outerH)
        if (handlerB != null) {
            cur = handlerB
            if (faB != null) {
                ctx.active = faB.id
                setHandler(faB.id)
            } else {
                tryStack.removeAt(tryStack.size - 1)
                setHandler(outerH)
            }
            for (c in t.catches) {
                val cp = c.catchParameter
                val nextB = newBlock()
                val bodyB = newBlock()
                val test = IrTypeOperatorCall(IrTypeOperator.INSTANCEOF, boolT, get(excVar), cp.type)
                val ws = ArrayList<IrBranch>()
                ws.add(IrBranch(test, gotoStmt(bodyB)))
                ws.add(IrBranch(a.constBool(true), gotoStmt(nextB)))
                emit(IrWhen(ws, unit, null))
                cur = bodyB
                cp.initializer = null
                cp.isVar = true
                cp.parent = fn
                hoisted.add(cp)
                emit(set(cp, IrTypeOperatorCall(IrTypeOperator.CAST, cp.type, get(excVar), cp.type)))
                emitInto(target, c.result)
                if (faB != null) leaveNormally(ctx, endB, outerH) else terminate(endB)
                cur = nextB
            }
            // no catch matched
            if (faB != null) {
                emit(set(ctx.finVar!!, a.constInt(-1)))
                terminate(ctx.finBlock!!)
            } else {
                emit(IrThrow(get(excVar), nothing))
            }
        }
        if (faB != null) {
            // an exception inside the try body (without a matching catch) or a catch body: run the finally block, rethrow
            cur = faB
            emit(set(ctx.finVar!!, a.constInt(-1)))
            terminate(ctx.finBlock!!)
        }
        if (fin != null) {
            tryStack.removeAt(tryStack.size - 1)
            cur = ctx.finBlock!!
            setHandler(outerH)
            emitInto(null, fin)
            // where to go on: every exit that was registered
            val ws = ArrayList<IrBranch>()
            for (x in ctx.exits) {
                val cond = IrCall(a.builtinFun("EQEQ"), boolT)
                cond.origin = "EQEQ"
                cond.arguments.add(get(ctx.finVar!!))
                cond.arguments.add(a.constInt(x.idx))
                ws.add(IrBranch(cond, gotoStmt(x.block)))
            }
            val rethrow = IrCall(a.builtinFun("EQEQ"), boolT)
            rethrow.origin = "EQEQ"
            rethrow.arguments.add(get(ctx.finVar!!))
            rethrow.arguments.add(a.constInt(-1))
            ws.add(IrBranch(rethrow, IrThrow(get(excVar), nothing)))
            emit(IrWhen(ws, unit, null))
            // the normal exit (index 0) falls through to the end
            terminate(endB)
        }
        cur = endB
    }

    /** the end of a try body or catch body: through the finally block (as exit 0) or straight on */
    fun leaveNormally(ctx: SmTry, endB: SmBlock, outerH: Int) {
        val fb = ctx.finBlock
        if (fb != null) {
            emit(set(ctx.finVar!!, a.constInt(0)))
            setHandler(outerH)
            terminate(fb)
        } else {
            setHandler(outerH)
            terminate(endB)
        }
    }

    // ---- the whole function ----------------------------------------------------------------------------------------

    fun build(body: IrBlockBody): IrBlockBody {
        val impl = a.irClass(implCls)
        val ctor = impl.constructors.firstOrNull() ?: a.fail(null, "CoroutineImpl without constructor")
        // the lambda of the state machine
        step = IrSimpleFunction("<anonymous>")
        step.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
        step.visibility = "local"
        step.modality = IrModality.FINAL
        step.parent = fn
        self = IrValueParameter("\$self", implT, IrParameterKind.Regular, 0)
        self.parent = step
        resultParam = IrValueParameter("\$result", anyN, IrParameterKind.Regular, 1)
        resultParam.parent = step
        step.parameters.add(self)
        step.parameters.add(resultParam)
        step.returnType = anyN
        labelVar = newVar("\$label", intT, a.constInt(0))
        handlerVar = newVar("\$handler", intT, a.constInt(-1))
        excVar = newVar("\$exc", throwableT, null)
        machine = IrWhileLoop(unit)
        machine.origin = "WHILE_LOOP"
        machine.condition = a.constBool(true)
        blocks.add(cur)
        for (s in body.statements) emitInto(null, s)
        emit(IrReturn(step, a.nullConst(), nothing))
        // the dispatch
        val ws = ArrayList<IrBranch>()
        for (b in blocks) {
            val cond = IrCall(a.builtinFun("EQEQ"), boolT)
            cond.origin = "EQEQ"
            cond.arguments.add(get(labelVar))
            cond.arguments.add(a.constInt(b.id))
            ws.add(IrBranch(cond, IrBlock(b.stmts, unit, null)))
        }
        val dispatch = IrWhen(ws, unit, null)
        val tp = IrVariable("\$t", throwableT, false)
        tp.parent = step
        val cs = ArrayList<IrStatement>()
        val neg = IrCall(a.builtinFun("less"), boolT)
        neg.arguments.add(get(handlerVar))
        neg.arguments.add(a.constInt(0))
        val rethrow = ArrayList<IrBranch>()
        rethrow.add(IrBranch(neg, IrThrow(get(tp), nothing)))
        cs.add(IrWhen(rethrow, unit, null))
        cs.add(set(excVar, get(tp)))
        cs.add(set(labelVar, get(handlerVar)))
        val catches = ArrayList<IrCatch>()
        catches.add(IrCatch(tp, IrBlock(cs, unit, null)))
        val tryStmts = ArrayList<IrStatement>()
        tryStmts.add(dispatch)
        machine.body = IrTry(IrBlock(tryStmts, unit, null), catches, null, unit)
        val stepStmts = ArrayList<IrStatement>()
        stepStmts.addAll(prelude)
        stepStmts.add(machine)
        stepStmts.add(IrReturn(step, a.nullConst(), nothing))
        step.body = IrBlockBody(stepStmts)
        val stepType = FunType(null, listOf(implT, anyN), anyN, false, false)
        val fe = IrFunctionExpression(step, stepType, "LAMBDA")
        // the new function body
        val out = ArrayList<IrStatement>()
        out.addAll(hoisted)
        val completion = fn.parameters[fn.parameters.size - 1]
        val ctorCall = IrConstructorCall(ctor, implT)
        ctorCall.arguments.add(get(completion))
        ctorCall.arguments.add(fe)
        val sm = IrVariable("\$sm", implT, false)
        sm.parent = fn
        sm.initializer = ctorCall
        out.add(sm)
        val start = IrCall(a.memberFunNamed(implT, "start", 0), anyN)
        start.arguments.add(get(sm))
        out.add(IrReturn(fn, start, nothing))
        return IrBlockBody(out)
    }
}

/** Replaces every child expression of [e] by `f(child)` (in place); does not enter nested functions and classes. */
internal fun mapChildren(e: IrElement, f: (IrExpression) -> IrExpression) {
    when (e) {
        is IrFunctionExpression, is IrClass, is IrFunction -> {}
        is IrBlockBody -> mapList(e.statements, f)
        is IrVariable -> {
            val i = e.initializer
            if (i != null) e.initializer = f(i)
        }
        is IrSetValue -> e.value = f(e.value)
        is IrFunctionAccessExpression -> {
            var i = 0
            while (i < e.arguments.size) {
                val x = e.arguments[i]
                if (x != null) e.arguments[i] = f(x)
                i++
            }
        }
        is IrGetField -> {
            val r = e.receiver
            if (r != null) e.receiver = f(r)
        }
        is IrSetField -> {
            val r = e.receiver
            if (r != null) e.receiver = f(r)
            e.value = f(e.value)
        }
        is IrStringConcatenation -> {
            var i = 0
            while (i < e.arguments.size) {
                e.arguments[i] = f(e.arguments[i])
                i++
            }
        }
        is IrGetClass -> e.argument = f(e.argument)
        is IrTry -> {
            e.tryResult = f(e.tryResult)
            for (c in e.catches) c.result = f(c.result)
            val fin = e.finallyExpression
            if (fin != null) e.finallyExpression = f(fin)
        }
        is IrVararg -> {
            var i = 0
            while (i < e.elements.size) {
                val x = e.elements[i]
                if (x is IrSpreadElement) x.expression = f(x.expression) else if (x is IrExpression) e.elements[i] = f(x)
                i++
            }
        }
        is IrThrow -> e.value = f(e.value)
        is IrWhen -> for (b in e.branches) {
            b.condition = f(b.condition)
            b.result = f(b.result)
        }
        is IrContainerExpression -> mapList(e.statements, f)
        is IrTypeOperatorCall -> e.argument = f(e.argument)
        is IrReturn -> e.value = f(e.value)
        is IrLoop -> {
            e.condition = f(e.condition)
            val b = e.body
            if (b != null) e.body = f(b)
        }
        else -> {}
    }
}

private fun mapList(l: ArrayList<IrStatement>, f: (IrExpression) -> IrExpression) {
    var i = 0
    while (i < l.size) {
        val s = l[i]
        if (s is IrExpression) l[i] = f(s) else if (s is IrVariable) mapChildren(s, f)
        i++
    }
}

// ---- entry points ------------------------------------------------------------------------------------------------

/** the IR type of the hidden continuation parameter of suspend functions: `Continuation<Any?>` */
fun Ast2Ir.continuationType(): Type {
    val c = sema.classNamed("kotlin.coroutines.Continuation") ?: fail(null, "kotlin.coroutines.Continuation is missing from the standard library")
    return ClassType(c, listOf(TArg(sema.nullableAny, Variance.INV)), false)
}

/** Makes [fn] a suspend function: the continuation parameter at the end and the result type Any?. */
fun Ast2Ir.makeSuspend(fn: IrFunction, declaredReturn: Type) {
    fn.isSuspend = true
    fn.origReturnType = declaredReturn
    val p = IrValueParameter("\$completion", continuationType(), IrParameterKind.Context, fn.parameters.size)
    p.parent = fn
    fn.parameters.add(p)
    fn.returnType = sema.nullableAny
}

/** The body of a suspend function [fn] in the form described above. */
fun Ast2Ir.lowerSuspendBody(fn: IrFunction, body: IrBlockBody): IrBlockBody {
    val b = SmBuilder(this, fn)
    var any = false
    for (s in body.statements) if (b.hasSuspend(s)) any = true
    if (!any) {
        // no suspension point: an ordinary function that returns its value
        val stmts = ArrayList<IrStatement>(body.statements)
        val orig = fn.origReturnType
        if (orig != null && isUnit(orig)) stmts.add(IrReturn(fn, nullConst(), sema.nothingType))
        return IrBlockBody(stmts)
    }
    return b.build(body)
}

/** A suspend call inside a function that is not a suspend function (a lambda that is not suspend): reported as an error. */
fun Ast2Ir.checkNoSuspendCalls(fn: IrFunction, body: IrBlockBody) {
    val b = SmBuilder(this, fn)
    for (s in body.statements) {
        if (b.hasSuspend(s)) fail(null, "suspension point in a function that is not a suspend function or suspend lambda: " + fn.name)
    }
}
