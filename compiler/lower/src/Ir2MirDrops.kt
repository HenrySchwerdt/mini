package mini.lower

import mini.backend.*

/*
 * Ir2Mir: the owners of the default allocator (docs/memory-model.md, "Default allocator").
 *
 * An object of a Strict class that no allocator was chosen for (rule 3 of 2.2) is reference counted with a count of one, which is the
 * *owner's* count: whoever owns the value releases it when the owner ends, or earlier with `free()`. The checker proved who the owner is
 * (it moves, frees, returns it or lets it end), and it handed back, on the IR: which local owns such an object (`dropOwner`), which
 * reads move the owner out (`movedOwner`), which allocation sites have an owner (`escapes`) and which call results nobody binds
 * (`ownedTemp`). This file turns that into the code:
 *
 *  - a read that moves the owner out of a local copies the value and sets the local to null (the moved owner is never released twice);
 *  - `free()` of such a local releases the object and sets the local to null;
 *  - [DropPass] wraps the rest of the scope of every local that is a `dropOwner` in a `try`, which on every way out of the scope (end of the
 *    block, `return`, `break`, `continue`, an exception) releases the local with `mk_discard`, which ignores null, objects of arenas and objects
 *    that are not reference counted; an assignment first releases the old value.
 */

/** The statements after a drop local's declaration run in a protected region: the pass over one function body. */
internal class DropPass(private val lw: Ir2Mir) {
    private val active = ArrayList<MirLocal>()
    private val loopBase = HashMap<Int, Int>()
    private var failed = false
    private var noWrap = 0

    fun run(body: List<MirStmt>): List<MirStmt> {
        if (!anyDrop(body)) return body
        val r = list(body)
        return if (failed) body else r
    }

    private fun anyDrop(list: List<MirStmt>): Boolean {
        for (s in list) {
            if (s is MirStmt.Declare && s.local.dropOwner) return true
            if (anyDropIn(s)) return true
        }
        return false
    }

    private fun anyDropIn(s: MirStmt): Boolean {
        if (s is MirStmt.If) return anyDrop(s.thenBody) || anyDrop(s.elseBody)
        if (s is MirStmt.Block) return anyDrop(s.body)
        if (s is MirStmt.While) return anyDrop(s.body)
        if (s is MirStmt.Loop) return anyDrop(s.body)
        if (s is MirStmt.DoWhile) return anyDrop(s.body) || anyDrop(s.condStmts)
        if (s is MirStmt.ForRange) return anyDrop(s.body)
        if (s is MirStmt.Try) {
            if (anyDrop(s.body)) return true
            for (c in s.catches) if (anyDrop(c.body)) return true
        }
        return false
    }

    private fun isTerminator(s: MirStmt?) = s is MirStmt.Return || s is MirStmt.Break || s is MirStmt.Continue || s is MirStmt.Panic || s is MirStmt.Throw

    private fun dropOf(l: MirLocal): MirStmt = MirStmt.Eval(MirExpr.RuntimeCall("mk_discard", listOf<MirExpr>(MirExpr.Local(l)), TUnit))

    /** Converts the statements of one list; a drop local's declaration wraps the rest of the list. */
    private fun list(l: List<MirStmt>): List<MirStmt> {
        val out = ArrayList<MirStmt>()
        var i = 0
        while (i < l.size) {
            val s = l[i]
            if (s is MirStmt.Declare && s.local.dropOwner && noWrap == 0) {
                out += s
                active.add(s.local)
                val rest = list(l.subList(i + 1, l.size))
                active.removeAt(active.size - 1)
                val ex = MirLocal(lw.localCounter++, "exc", lw.builtinType("MkThrowable"))
                val handler = ArrayList<MirStmt>()
                handler += MirStmt.Declare(ex, MirExpr.CaughtException(ex.type))
                handler += dropOf(s.local)
                handler += MirStmt.Throw(MirExpr.Local(ex))
                out += MirStmt.Try(rest, listOf(MirCatch(ex.type, handler)))
                if (!isTerminator(rest.lastOrNull())) out += dropOf(s.local)
                return out
            }
            out += stmt(s)
            i++
        }
        return out
    }

    /** The statements [s] stands for. */
    private fun stmt(s: MirStmt): List<MirStmt> {
        if (s is MirStmt.Assign) {
            if (s.local.dropOwner && noWrap == 0 && !lw.moveNulls.contains(s)) {
                // the old value is released after the new one is computed (which may have moved it out: then it is null)
                val t = MirLocal(lw.localCounter++, "tmp", s.local.type)
                val r = ArrayList<MirStmt>()
                r += MirStmt.Declare(t, s.value)
                r += dropOf(s.local)
                r += MirStmt.Assign(s.local, MirExpr.Local(t))
                return r
            }
            return listOf(s)
        }
        if (s is MirStmt.If) return listOf(MirStmt.If(s.cond, list(s.thenBody), list(s.elseBody)))
        if (s is MirStmt.Block) return listOf(MirStmt.Block(list(s.body)))
        if (s is MirStmt.While) {
            loopBase[s.label.id] = active.size
            val b = list(s.body)
            loopBase.remove(s.label.id)
            return listOf(MirStmt.While(s.label, s.cond, b))
        }
        if (s is MirStmt.Loop) {
            loopBase[s.label.id] = active.size
            val b = list(s.body)
            loopBase.remove(s.label.id)
            return listOf(MirStmt.Loop(s.label, b))
        }
        if (s is MirStmt.ForRange) {
            loopBase[s.label.id] = active.size
            val b = list(s.body)
            loopBase.remove(s.label.id)
            return listOf(MirStmt.ForRange(s.label, s.variable, s.start, s.end, s.step, s.ascending, s.inclusive, b))
        }
        if (s is MirStmt.DoWhile) {
            // the declarations of the body are visible in the condition: they are not wrapped
            loopBase[s.label.id] = active.size
            noWrap++
            val b = list(s.body)
            val c = list(s.condStmts)
            noWrap--
            loopBase.remove(s.label.id)
            return listOf(MirStmt.DoWhile(s.label, b, c, s.cond))
        }
        if (s is MirStmt.Try) {
            val body = list(s.body)
            val cs = ArrayList<MirCatch>()
            for (c in s.catches) cs += MirCatch(c.type, list(c.body))
            return listOf(MirStmt.Try(body, cs))
        }
        if (s is MirStmt.Return) {
            if (active.isEmpty()) return listOf(s)
            val r = ArrayList<MirStmt>()
            val v = s.value
            if (v != null && v.type != TUnit && v !== MirExpr.UnitValue) {
                val t = MirLocal(lw.localCounter++, "ret", v.type)
                r += MirStmt.Declare(t, v)
                for (k in active.indices.reversed()) r += dropOf(active[k])
                r += MirStmt.Return(MirExpr.Local(t))
            } else {
                if (v != null) r += MirStmt.Eval(v)
                for (k in active.indices.reversed()) r += dropOf(active[k])
                r += MirStmt.Return(null)
            }
            return r
        }
        if (s is MirStmt.Break) return jump(s, s.label.id)
        if (s is MirStmt.Continue) return jump(s, s.label.id)
        return listOf(s)
    }

    private fun jump(s: MirStmt, label: Int): List<MirStmt> {
        val base = loopBase[label]
        if (base == null) {
            // a jump to a loop outside what this pass saw: leave the function as it was
            failed = true
            return listOf(s)
        }
        if (active.size <= base) return listOf(s)
        val r = ArrayList<MirStmt>()
        for (k in active.size - 1 downTo base) r += dropOf(active[k])
        r += s
        return r
    }
}

/** The body of a converted function with the drops of its [MirLocal.dropOwner] locals. */
internal fun Ir2Mir.withDrops(body: List<MirStmt>): List<MirStmt> = DropPass(this).run(body)

/**
 * The read [e] of a local, as [v]: when the checker says it moves the owner out ([IrGetValue.movedOwner]) the value is copied and the local
 * is set to null, so that the scope end of the local does not release what its new owner holds.
 */
internal fun Ir2Mir.movedOut(e: IrGetValue, v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    val o = e.movedOwner ?: return v
    if (!currentStrict || o in closure.boxed || o in unitVars) return v
    val l = locals[o] ?: return v
    if (!l.type.isRef || !v.type.isRef) return v
    val t = MirLocal(localCounter++, "moved", v.type)
    out += MirStmt.Declare(t, v)
    val nul = MirStmt.Assign(l, MirExpr.NullConst(l.type))
    moveNulls += nul
    out += nul
    return MirExpr.Local(t)
}

/**
 * The owned result [v] of a call that nobody binds (the checker's `ownedTemp`): kept in a temporary that is released at the end of the
 * enclosing scope like any owner. Null when [v] is not such a value (the caller then evaluates it as before).
 */
internal fun Ir2Mir.ownedTempOf(v: MirExpr, out: MutableList<MirStmt>): MirExpr? {
    val t = v.type
    if (!currentStrict || !t.isRef || t == TUnit || t == TNothing) return null
    val tmp = newTemp(t, "owned")
    tmp.dropOwner = true
    out += MirStmt.Declare(tmp, v)
    return MirExpr.Local(tmp)
}

/** A call whose owned result nobody binds, as a value. */
internal fun Ir2Mir.ownedCallValue(e: IrCall, out: MutableList<MirStmt>): MirExpr {
    val v = call(e, out)
    return ownedTempOf(v, out) ?: v
}

/** A call whose owned result nobody binds, as a statement. */
internal fun Ir2Mir.ownedCallStmt(e: IrCall, out: MutableList<MirStmt>) {
    val v = call(e, out)
    if (ownedTempOf(v, out) == null) out += MirStmt.Eval(erasedChecks[v] ?: v)
}

/**
 * `x.free()` of [target] under the default allocator: the object of an owner that was moved out of its variable or came from a call is
 * released by `mk_free_owned` (a reference counted object: the owner's count; any other: freed), and a local variable is set to null so
 * that its scope end does not release it again. Returns false when the target is an ordinary local (the old `mk_free_dealloc`).
 */
internal fun Ir2Mir.ownedFree(target: IrExpression, v: MirExpr, out: MutableList<MirStmt>): Boolean {
    var t: IrExpression = target
    while (t is IrTypeOperatorCall && t.operator == IrTypeOperator.IMPLICIT_CAST) t = t.argument
    if (t is IrGetValue) {
        val o = t.owner
        // (the library's own `free(this)` of its resources keeps the old behaviour; a receiver is never an owner)
        val flagged = (o is IrVariable && o.dropOwner) || (o is IrValueParameter && o.kind == IrParameterKind.Regular && !currentFile.isStdlib)
        if (!flagged || !currentStrict) return false
        out += MirStmt.Eval(MirExpr.RuntimeCall("mk_free_owned", listOf(v), TUnit))
        val l = locals[o]
        if (l != null && l.type.isRef && o !in closure.boxed && o !in unitVars) {
            val nul = MirStmt.Assign(l, MirExpr.NullConst(l.type))
            moveNulls += nul
            out += nul
        }
        return true
    }
    if (!currentStrict || currentFile.isStdlib) return false
    out += MirStmt.Eval(MirExpr.RuntimeCall("mk_free_owned", listOf(v), TUnit))
    return true
}
