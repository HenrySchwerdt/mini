package mini.backend.memory

/*
 * Control-flow graph of one function (or escaping lambda) built from MemIr statements.
 *
 * Blocks hold straight-line [Op]s; edges may carry a null-test refinement. Everything that leaves
 * a scope (end of block, return, break, continue, throw) goes through the frame stack so that the
 * right exit operations are inserted on *every* outgoing edge: leak checks of scopes, `finally`
 * bodies (copied into each exit path), `use` closes, thread-lambda ends (docs/memory-model.md 5.6).
 */

/** Allocator context: innermost level last. A level is the set of allocator locals of one `context(...)` / parameter list. */
class Ctx(val levels: List<List<MLocal>>) {
    fun push(level: List<MLocal>) = Ctx(levels + listOf(level))
}

/** Per-operation environment. */
class Env(
    val ctx: Ctx,
    /** Ids of arena `use` targets that have a loop between their scope entry and this operation. */
    val loopArenas: Set<Int>,
)

internal class Edge(val to: Blk, val slot: Int = -1, val isNull: Boolean = false)

internal class Blk(val id: Int) {
    val ops = ArrayList<Op>()
    val succ = ArrayList<Edge>()
}

internal sealed class Op {
    abstract val env: Env

    class Decl(val s: MStmt.Decl, override val env: Env, val scopes: Set<Int>) : Op()
    class Assign(val s: MStmt.Assign, override val env: Env, val scopes: Set<Int>) : Op()
    class SetField(val s: MStmt.SetField, override val env: Env) : Op()
    class SetGlobal(val s: MStmt.SetGlobal, override val env: Env) : Op()
    class Eval(val e: MExpr, override val env: Env) : Op()
    class Cond(val e: MExpr, override val env: Env) : Op()

    /** `return value` from the function (or escaping lambda unit). */
    class Return(val value: MExpr?, val pos: Pos, override val env: Env) : Op()

    /** A scope of [locals] ends; [path] says how ([isFunction]: also parameters and moved-field checks). */
    class Exit(val locals: List<MLocal>, val path: String, val isFunction: Boolean, override val env: Env) : Op()

    /** `use` end: close [target]. */
    class Close(val target: MLocal, val pos: Pos, override val env: Env) : Op()

    class ThreadStart(val captures: List<MLocal>, val pos: Pos, override val env: Env) : Op()
    class ThreadEnd(val captures: List<MLocal>, val pos: Pos, override val env: Env) : Op()
    class Throw(val value: MExpr, val pos: Pos, override val env: Env) : Op()
}

internal class Cfg(val entry: Blk, val blocks: List<Blk>, val lambdas: List<MExpr.Lambda>)

internal sealed class Frame {
    class Scope(val locals: ArrayList<MLocal> = ArrayList(), val isFunction: Boolean = false) : Frame()
    class Loop(val label: Int, val brk: Blk, val cont: Blk?) : Frame()
    class Fin(val stmts: List<MStmt>) : Frame()
    class Use(val target: MLocal, val pos: Pos) : Frame()
    class Lam(val id: Int, val exit: Blk) : Frame()
    class Thread(val captures: List<MLocal>, val pos: Pos) : Frame()
    class Try(val handler: Blk) : Frame()
}

internal class CfgBuilder(
    private val unitId: Int,
    private val params: List<MLocal>,
    private val ctx0: Ctx,
) {
    private val blocks = ArrayList<Blk>()
    private var cur: Blk = newBlock()
    private val frames = ArrayList<Frame>()
    private var ctx: Ctx = ctx0
    private val lambdas = ArrayList<MExpr.Lambda>()
    private val declScopes = HashMap<Int, Set<Int>>()

    /** Ids of locals declared in the unit (used to tell local arenas from parameters). */
    val declared = HashSet<Int>()
    val names = HashMap<Int, String>()

    private fun newBlock(): Blk = Blk(blocks.size).also { blocks += it }

    private fun env(): Env {
        val loopArenas = HashSet<Int>()
        for ((i, f) in frames.withIndex()) {
            if (f is Frame.Use) {
                for (j in i + 1 until frames.size) if (frames[j] is Frame.Loop) { loopArenas += f.target.id; break }
            }
        }
        return Env(ctx, loopArenas)
    }

    private fun emit(op: Op) { cur.ops += op }

    private fun useScopes(): Set<Int> = frames.filterIsInstance<Frame.Use>().map { it.target.id }.toSet()

    fun build(body: List<MStmt>, entryEdgeCtx: Ctx = ctx0): Cfg {
        val entry = cur
        val fs = Frame.Scope(isFunction = true)
        fs.locals += params
        for (p in params) names[p.id] = p.name
        frames += fs
        buildList(body)
        // normal end of the function
        emit(Op.Exit(allLocals(fs), "end of scope", true, env()))
        frames.removeAt(frames.size - 1)
        return Cfg(entry, blocks, lambdas)
    }

    private fun allLocals(s: Frame.Scope): List<MLocal> = s.locals.toList()

    // ---- statements -------------------------------------------------------------------------

    private fun buildScoped(body: List<MStmt>, path: String = "end of scope") {
        val sc = Frame.Scope()
        frames += sc
        buildList(body)
        emit(Op.Exit(sc.locals.toList(), path, false, env()))
        frames.removeAt(frames.size - 1)
    }

    private fun buildList(body: List<MStmt>) {
        for (s in body) buildStmt(s)
    }

    private fun top(): Frame.Scope? = frames.lastOrNull { it is Frame.Scope } as Frame.Scope?

    private fun declare(l: MLocal) {
        top()?.locals?.add(l)
        declared += l.id
        names[l.id] = l.name
        declScopes[l.id] = useScopes()
    }

    private fun branch(to: Blk, slot: Int = -1, isNull: Boolean = false) { cur.succ += Edge(to, slot, isNull) }

    private fun goto(to: Blk) { cur.succ += Edge(to) }

    private fun startNew(): Blk { cur = newBlock(); return cur }

    /** If any call in [e] may throw, add an exception edge after the current op. */
    private fun mayThrowEdge(e: MExpr) {
        if (!exprMayThrow(e)) return
        val tb = newBlock()
        goto(tb)
        val saved = cur
        cur = tb
        jumpThrow()
        cur = newBlock()
        saved.succ += Edge(cur)
    }

    private fun exprMayThrow(e: MExpr): Boolean {
        if (e is MExpr.Call && (e.fn.throwsKnown || e.fn.mayThrow)) return true
        if (e is MExpr.Lambda) return false
        return e.childExprs().any { exprMayThrow(it) }
    }

    private fun buildStmt(s: MStmt) {
        when (s) {
            is MStmt.Decl -> {
                emit(Op.Decl(s, env(), useScopes()))
                declare(s.local)
                s.init?.let { mayThrowEdge(it) }
            }
            is MStmt.Assign -> {
                emit(Op.Assign(s, env(), declScopes[s.local.id] ?: emptySet()))
                mayThrowEdge(s.value)
            }
            is MStmt.SetField -> { emit(Op.SetField(s, env())); mayThrowEdge(s.value) }
            is MStmt.SetGlobal -> { emit(Op.SetGlobal(s, env())); mayThrowEdge(s.value) }
            is MStmt.Eval -> { emit(Op.Eval(s.expr, env())); mayThrowEdge(s.expr) }
            is MStmt.Block -> buildScoped(s.body)
            is MStmt.If -> buildIf(s)
            is MStmt.While -> buildWhile(s)
            is MStmt.DoWhile -> buildDoWhile(s)
            is MStmt.Break -> {
                val i = frames.indexOfLast { it is Frame.Loop && it.label == s.label }
                if (i >= 0) { jumpOut(i, "break", (frames[i] as Frame.Loop).brk) }
                startNew()
            }
            is MStmt.Continue -> {
                val i = frames.indexOfLast { it is Frame.Loop && it.label == s.label }
                if (i >= 0) { jumpOut(i, "continue", (frames[i] as Frame.Loop).cont!!) }
                startNew()
            }
            is MStmt.Return -> buildReturn(s)
            is MStmt.Throw -> {
                emit(Op.Throw(s.value, s.pos, env()))
                jumpThrow()
                startNew()
            }
            is MStmt.Try -> buildTry(s)
            is MStmt.Scope -> buildScope(s)
        }
    }

    private fun condEdges(cond: MExpr, thenBlk: Blk, elseBlk: Blk) {
        var c = cond
        var negate = false
        while (c is MExpr.Not) { negate = !negate; c = c.operand }
        val nt = c as? MExpr.NullTest
        val op = nt?.operand
        if (nt != null && op is MExpr.Local && op.local.type.tracked) {
            val isNullThen = nt.isNull != negate // in the then-branch the operand is null
            cur.succ += Edge(thenBlk, op.local.id, isNullThen)
            cur.succ += Edge(elseBlk, op.local.id, !isNullThen)
        } else {
            cur.succ += Edge(thenBlk)
            cur.succ += Edge(elseBlk)
        }
    }

    private fun buildIf(s: MStmt.If) {
        emit(Op.Cond(s.cond, env()))
        mayThrowEdge(s.cond)
        val thenB = newBlock()
        val elseB = newBlock()
        val join = newBlock()
        condEdges(s.cond, thenB, elseB)
        cur = thenB
        buildScoped(s.thenBody)
        goto(join)
        cur = elseB
        buildScoped(s.elseBody)
        goto(join)
        cur = join
    }

    private fun buildWhile(s: MStmt.While) {
        val head = newBlock()
        val body = newBlock()
        val exit = newBlock()
        goto(head)
        cur = head
        frames += Frame.Loop(s.label, exit, head)
        // condition statements live in their own scope that ends before the body is entered or the loop exits
        val condScope = Frame.Scope()
        frames += condScope
        buildList(s.condStmts)
        s.cond?.let { emit(Op.Cond(it, env())); mayThrowEdge(it) }
        if (s.cond != null) condEdges(s.cond, body, exit) else goto(body)
        frames.removeAt(frames.size - 1)
        cur = body
        buildScoped(s.body)
        goto(head)
        frames.removeAt(frames.size - 1)
        cur = exit
    }

    private fun buildDoWhile(s: MStmt.DoWhile) {
        val body = newBlock()
        val condB = newBlock()
        val exit = newBlock()
        goto(body)
        cur = body
        frames += Frame.Loop(s.label, exit, condB)
        val sc = Frame.Scope()
        frames += sc
        buildList(s.body)
        goto(condB)
        cur = condB
        buildList(s.condStmts)
        emit(Op.Cond(s.cond, env()))
        mayThrowEdge(s.cond)
        condEdges(s.cond, body, exit)
        frames.removeAt(frames.size - 1)
        frames.removeAt(frames.size - 1)
        cur = exit
    }

    private fun buildReturn(s: MStmt.Return) {
        if (s.target == 0 || s.target == unitId) {
            emit(Op.Return(s.value, s.pos, env()))
            mayThrowEdge(s.value ?: MExpr.Opaque(MType.PRIM, s.pos))
            jumpOut(0, "early return", null)
        } else {
            val i = frames.indexOfLast { it is Frame.Lam && it.id == s.target }
            if (s.value != null) emit(Op.Eval(s.value, env()))
            if (i >= 0) jumpOut(i, "return", (frames[i] as Frame.Lam).exit)
        }
        startNew()
    }

    private fun buildTry(s: MStmt.Try) {
        val after = newBlock()
        val hasFin = s.finallyBody != null
        if (hasFin) frames += Frame.Fin(s.finallyBody!!)
        val finIdx = frames.size - 1
        var handler: Blk? = null
        if (s.catches.isNotEmpty()) {
            handler = newBlock()
            frames += Frame.Try(handler)
        }
        buildScoped(s.body)
        if (handler != null) frames.removeAt(frames.size - 1)
        // normal completion of the body
        runFinallyThenGoto(hasFin, finIdx, after)
        if (handler != null) {
            for (c in s.catches) {
                val cb = newBlock()
                handler.succ += Edge(cb)
                cur = cb
                val sc = Frame.Scope()
                frames += sc
                c.local?.let { declare(it) }
                buildList(c.body)
                emit(Op.Exit(sc.locals.toList(), "end of scope", false, env()))
                frames.removeAt(frames.size - 1)
                runFinallyThenGoto(hasFin, finIdx, after)
            }
        }
        if (hasFin) frames.removeAt(finIdx)
        cur = after
    }

    /** Falls through out of try/catch: emit the finally body (still inside its frame's outer context) and go to [after]. */
    private fun runFinallyThenGoto(hasFin: Boolean, finIdx: Int, after: Blk) {
        if (hasFin) emitFinally(finIdx)
        goto(after)
    }

    private fun emitFinally(idx: Int) {
        val fin = frames[idx] as Frame.Fin
        val saved = ArrayList(frames.subList(idx, frames.size))
        while (frames.size > idx) frames.removeAt(frames.size - 1)
        buildScoped(fin.stmts)
        frames.addAll(saved)
    }

    private fun buildScope(s: MStmt.Scope) {
        val after = newBlock()
        val savedCtx = ctx
        if (s.kind == ScopeKind.Context) ctx = ctx.push(s.ctx)
        frames += Frame.Lam(s.id, after)
        val lamIdx = frames.size - 1
        when (s.kind) {
            ScopeKind.Use -> frames += Frame.Use(s.target!!, s.lambdaPos)
            ScopeKind.Thread -> {
                emit(Op.ThreadStart(s.captures, s.lambdaPos, env()))
                frames += Frame.Thread(s.captures, s.lambdaPos)
            }
            else -> {}
        }
        if (s.kind == ScopeKind.Repeat) {
            val head = newBlock()
            val exit = after
            goto(head)
            cur = head
            val bodyB = newBlock()
            head.succ += Edge(bodyB)
            head.succ += Edge(exit)
            cur = bodyB
            frames += Frame.Loop(-s.id - 1, exit, head)
            buildScoped(s.body)
            goto(head)
            frames.removeAt(frames.size - 1)
        } else {
            buildScoped(s.body)
            // normal end: leave the lambda frames above (and including use/thread frames)
            exitFrames(lamIdx, "end of scope")
            goto(after)
        }
        while (frames.size > lamIdx) frames.removeAt(frames.size - 1)
        ctx = savedCtx
        cur = after
    }

    // ---- jumps ------------------------------------------------------------------------------

    /** Emit exit operations for all frames above index [stop] (exclusive), innermost first. */
    private fun exitFrames(stop: Int, path: String) {
        var i = frames.size - 1
        while (i > stop) {
            when (val f = frames[i]) {
                is Frame.Scope -> emit(Op.Exit(f.locals.toList(), path, f.isFunction, env()))
                is Frame.Fin -> {
                    // finally bodies run in the context outside of the try
                    val saved = ArrayList(frames.subList(i, frames.size))
                    while (frames.size > i) frames.removeAt(frames.size - 1)
                    buildScoped(f.stmts)
                    frames.addAll(saved)
                }
                is Frame.Use -> emit(Op.Close(f.target, f.pos, env()))
                is Frame.Thread -> emit(Op.ThreadEnd(f.captures, f.pos, env()))
                else -> {}
            }
            i--
        }
    }

    /**
     * Leave all frames above [stop] and continue at [to] (null: function exit). The emitted exits
     * run innermost first; frames are not popped (the builder continues in dead code).
     */
    private fun jumpOut(stop: Int, path: String, to: Blk?) {
        // exitFrames processes frames strictly above `stop`; for a function return that includes the function scope (index 0)
        val s = if (to == null) -1 else stop
        val saved = ArrayList(frames)
        exitFrames(s, path)
        // exitFrames may have temporarily rebuilt frames; restore the exact stack
        frames.clear(); frames.addAll(saved)
        if (to != null) goto(to)
    }

    private fun jumpThrow() {
        val ti = frames.indexOfLast { it is Frame.Try }
        val saved = ArrayList(frames)
        exitFrames(ti, "throw")
        frames.clear(); frames.addAll(saved)
        if (ti >= 0) goto((frames[ti] as Frame.Try).handler)
    }
}

internal fun MExpr.childExprs(): List<MExpr> = when (this) {
    is MExpr.Opaque -> children
    is MExpr.Null, is MExpr.Local, is MExpr.GetGlobal -> emptyList()
    is MExpr.New -> args
    is MExpr.Call -> listOfNotNull(receiver) + args
    is MExpr.FieldGet -> listOf(obj)
    is MExpr.NullTest -> listOf(operand)
    is MExpr.Not -> listOf(operand)
    is MExpr.Free -> listOfNotNull(target, alloc)
    is MExpr.Move -> listOf(target)
    is MExpr.RawAlloc -> listOf(alloc)
    is MExpr.Lambda -> emptyList()
}
