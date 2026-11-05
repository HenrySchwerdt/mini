package mini.backend

import mini.internal.nanoTime

class InlinerStats {
    var sites = 0
    var functions = 0
    var exactDevirt = 0
    var scalarReplaced = 0
    var tExact = 0L
    var tExpand = 0L
    var tClean = 0L
    var tSroa = 0L
    var tOther = 0L
}

/*
 * MIR-level inlining of small functions (getters, setters, iterator steps, small stdlib helpers), across the library boundary
 * (the whole program is linked MIR). Functions are processed callees first, so the body that is inlined is already optimized.
 *
 * A call is inlined when it is a direct [MirExpr.Call] of a small, non-recursive, non-Strict function whose `return`s are all in
 * tail position. The call is replaced by statements in front of the statement that contains it:
 *  - parameters that are only read and whose argument is a local or a constant are replaced by the argument, the other arguments
 *    are evaluated in order into fresh locals;
 *  - the callee's locals and labels are renamed (fresh ids), every node is a fresh copy (the analyses key on node identity);
 *  - tail `return v` becomes an assignment to the result local.
 * Everything evaluated before the call in the same statement is kept in order: operands that are not stable (locals, constants,
 * arithmetic on them) are hoisted into temporaries first (only primitives can be; a call behind a reference operand that is not
 * a local stays a call). Calls in the right operand of `&&` / `||` and in the branches of a conditional expression are not inlined.
 * The MIR's ownership rules are uniform (arguments borrowed, results owned), so inlining needs no RC reasoning: RcOpt runs after it.
 */
object Inliner {
    /** Always inlined up to this size; up to [SIZE_LIMIT] when an argument is a fresh local object (scalar replacement can remove it). */
    const val SMALL_LIMIT = 40
    const val SIZE_LIMIT = 70
    const val GROWTH_LIMIT = 700

    /** Whole-program budget (nodes expanded): small programs are optimized completely, a huge program (the compiler itself) only partly, so compile time stays in check. */
    const val PROGRAM_BUDGET = 60000

    fun run(module: MirModule, stats: InlinerStats, options: MirOptions): MirModule {
        val st = InlineState(module, stats)
        st.doInline = options.on("inline")
        st.doSroa = options.on("sroa")
        st.doExact = options.on("exact-devirt")
        for (f in module.functions) st.visit(f.cName)
        val fs = ArrayList<MirFunction>(module.functions.size)
        for (f in module.functions) fs.add(st.done[f.cName] ?: f)
        return MirModule(module.sourceName, fs, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }
}

internal class InlineInfo(val fn: MirFunction, val paramAssigned: BooleanArray, val size: Int) {
    /** Takes or returns a reference: only then does inlining help beyond what the C compiler / LLVM do with a call of a pure arithmetic function. */
    val refs: Boolean = fn.returnType.isRef || fn.params.any { it.type.isRef }
}

internal class InlineState(val module: MirModule, val stats: InlinerStats) {
    val byName = HashMap<String, MirFunction>()
    val classes = HashMap<String, MirClass>()
    val done = HashMap<String, MirFunction>()
    val infos = HashMap<String, InlineInfo>()
    val visiting = HashSet<String>()
    var budget = Inliner.PROGRAM_BUDGET
    var doInline = true
    var doSroa = true
    var doExact = true

    init {
        for (f in module.functions) byName[f.cName] = f
        for (c in module.classes) classes[c.cName] = c
    }

    fun visit(name: String) {
        if (done.containsKey(name) || visiting.contains(name)) return
        val f = byName[name] ?: return
        visiting.add(name)
        // callees first
        val calls = CallCollector()
        calls.walk(f.body)
        for (c in calls.names) visit(c)
        var cur = f
        val worth = budget > 0 && !f.strict && (doInline || doSroa || doExact) && worthIt(f)
        if (worth) {
            var round = 0
            var any = false
            while (round < 4) {
                round++
                var progress = false
                val t0 = nanoTime()
                val hs = HotScan()
                hs.walk(cur.body)
                val withNew = hs.hasNew
                val us = UseScan(cur)
                if (withNew) us.walk(cur.body)
                val ed = ExactDevirt(classes, byName, us)
                var body = if (doExact && withNew) ed.stmts(cur.body) else cur.body
                val t1 = nanoTime()
                stats.tExact += t1 - t0
                if (ed.count > 0) { progress = true; stats.exactDevirt += ed.count }
                val ex = Expander(this, cur.withBody(body))
                if (doInline) body = ex.stmts(body)
                if (ex.expanded > 0) { progress = true; stats.sites += ex.expanded; budget -= Inliner.GROWTH_LIMIT - ex.budget }
                val t2 = nanoTime()
                stats.tExpand += t2 - t1
                if (progress) body = Cleanup.run(body, cur)
                val t3 = nanoTime()
                stats.tClean += t3 - t2
                val sr = Sroa(this, cur.withBody(body))
                val body2 = if (doSroa && withNew) sr.run(body) else body
                stats.tSroa += nanoTime() - t3
                if (sr.count > 0) {
                    progress = true
                    stats.scalarReplaced += sr.count
                    body = Cleanup.run(body2, cur)
                }
                if (!progress) break
                cur = cur.withBody(body)
                any = true
            }
            if (any) stats.functions++
        }
        visiting.remove(name)
        done[name] = cur
        val info = analyze(cur)
        if (info != null) infos[name] = info
    }

    /** Only functions with a loop or an allocation can profit (calls outside loops are only inlined for scalar replacement). */
    fun worthIt(f: MirFunction): Boolean {
        val h = HotScan()
        h.walk(f.body)
        hasNew = h.hasNew
        return h.hasNew || h.hasLoop
    }

    var hasNew = false

    /** Is [f] inlinable? Returns what the expander needs to know. */
    fun analyze(f: MirFunction): InlineInfo? {
        // (noHeapEffects is a promise about the call as a whole: expanding the body would show RcOpt the stores it promised away)
        if (f.strict || f.noHeapEffects || f.returnType === TNothing || f.cName == Mangling.MAIN) return null
        val self = CallCollector()
        self.walk(f.body)
        if (self.names.contains(f.cName)) return null   // directly recursive
        val size = NodeCounter()
        size.walk(f.body)
        if (size.count > Inliner.SIZE_LIMIT) return null
        if (!tailReturnsOnly(f.body, f.returnType === TUnit)) return null
        val assigned = AssignedParams(f.params)
        assigned.walk(f.body)
        return InlineInfo(f, assigned.flags, size.count)
    }

    /** All `return`s are in tail position (and a function with a value ends in one on every path we can see). */
    fun tailReturnsOnly(body: List<MirStmt>, isUnit: Boolean): Boolean {
        val total = ReturnCounter()
        total.walk(body)
        return total.count == tailReturns(body)
    }

    fun tailReturns(body: List<MirStmt>): Int {
        if (body.isEmpty()) return 0
        val s = body[body.size - 1]
        return when (s) {
            is MirStmt.Return -> 1
            is MirStmt.If -> tailReturns(s.thenBody) + tailReturns(s.elseBody)
            is MirStmt.Block -> tailReturns(s.body)
            else -> 0
        }
    }
}

internal class HotScan : MirVisitor() {
    var hasNew = false
    var hasLoop = false
    override fun visit(e: MirExpr) {
        if (e is MirExpr.New) hasNew = true
    }
    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.While || s is MirStmt.Loop || s is MirStmt.DoWhile || s is MirStmt.ForRange) hasLoop = true
        return super.stmt(s)
    }
}

internal class CallCollector : MirVisitor() {
    val names = LinkedHashSet<String>()
    override fun visit(e: MirExpr) {
        if (e is MirExpr.Call) names.add(e.cName)
    }
}

internal class NodeCounter : MirVisitor() {
    var count = 0
    override fun visit(e: MirExpr) {
        count++
    }
    override fun stmt(s: MirStmt): MirStmt {
        count++
        return super.stmt(s)
    }
}

internal class ReturnCounter : MirVisitor() {
    var count = 0
    override fun visit(e: MirExpr) {}
    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.Return) count++
        return super.stmt(s)
    }
}

internal class AssignedParams(val params: List<MirLocal>) : MirVisitor() {
    val flags = BooleanArray(params.size)
    override fun visit(e: MirExpr) {}
    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.Assign) {
            for (i in params.indices) if (params[i] === s.local) flags[i] = true
        }
        if (s is MirStmt.ForRange) {
            for (i in params.indices) if (params[i] === s.variable) flags[i] = true
        }
        return super.stmt(s)
    }
}

/** The largest local id and label id used in a function. */
internal class IdScan(val f: MirFunction) : MirVisitor() {
    var maxLocal = -1
    var maxLabel = -1
    init {
        for (p in f.params) if (p.id > maxLocal) maxLocal = p.id
        walk(f.body)
    }
    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local && e.local.id > maxLocal) maxLocal = e.local.id
    }
    override fun stmt(s: MirStmt): MirStmt {
        when (s) {
            is MirStmt.Declare -> if (s.local.id > maxLocal) maxLocal = s.local.id
            is MirStmt.ForRange -> { if (s.variable.id > maxLocal) maxLocal = s.variable.id; if (s.label.id > maxLabel) maxLabel = s.label.id }
            is MirStmt.While -> if (s.label.id > maxLabel) maxLabel = s.label.id
            is MirStmt.Loop -> if (s.label.id > maxLabel) maxLabel = s.label.id
            is MirStmt.DoWhile -> if (s.label.id > maxLabel) maxLabel = s.label.id
            else -> {}
        }
        return super.stmt(s)
    }
}

/** Replaces the nodes in [from] (by identity) by the nodes in [to]; does not enter nested statement lists. */
internal class Substitute(val from: List<MirExpr>, val to: List<MirExpr>) : MirTransform() {
    override fun stmts(l: List<MirStmt>): List<MirStmt> = l
    override fun expr(e: MirExpr): MirExpr {
        for (i in from.indices) if (from[i] === e) return to[i]
        return rebuildExpr(e)
    }
}

/** Fresh copy of a leaf expression (parameters substituted by an argument are copied per use: the analyses key on node identity). */
internal fun cloneLeaf(e: MirExpr): MirExpr = when (e) {
    is MirExpr.Local -> MirExpr.Local(e.local)
    is MirExpr.IntConst -> MirExpr.IntConst(e.value)
    is MirExpr.LongConst -> MirExpr.LongConst(e.value)
    is MirExpr.DoubleConst -> MirExpr.DoubleConst(e.value)
    is MirExpr.BooleanConst -> MirExpr.BooleanConst(e.value)
    is MirExpr.CharConst -> MirExpr.CharConst(e.code)
    is MirExpr.StringConst -> MirExpr.StringConst(e.value)
    is MirExpr.NullConst -> MirExpr.NullConst(e.type)
    is MirExpr.ObjectGet -> MirExpr.ObjectGet(e.classCName, e.type)
    is MirExpr.FuncAddr -> MirExpr.FuncAddr(e.cName)
    is MirExpr.TypeInfoOf -> MirExpr.TypeInfoOf(e.target)
    is MirExpr.Unreachable -> MirExpr.Unreachable(e.type)
    is MirExpr.CaughtException -> MirExpr.CaughtException(e.type)
    else -> e
}

/** Can a value of type [a] stand where [b] is expected without a conversion (same machine representation)? */
internal fun sameRep(a: MirType, b: MirType): Boolean {
    if (a == b) return true
    val ra = a is TObject || a is TBoxed
    val rb = b is TObject || b is TBoxed
    return ra && rb
}

internal fun isLeaf(e: MirExpr): Boolean = when (e) {
    is MirExpr.Local, is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst,
    is MirExpr.CharConst, is MirExpr.StringConst, is MirExpr.NullConst, is MirExpr.ObjectGet, is MirExpr.FuncAddr,
    is MirExpr.TypeInfoOf, is MirExpr.Unreachable, is MirExpr.CaughtException -> true
    else -> false
}

/** Copies a callee body with fresh locals, labels and nodes; parameters are replaced by [paramMap]. */
internal class BodyCopier(
    val paramLocals: List<MirLocal>,
    val paramRepl: List<MirExpr>,
    var nextLocal: Int,
    var nextLabel: Int,
) : MirTransform() {
    val localsFrom = ArrayList<MirLocal>()
    val localsTo = ArrayList<MirLocal>()
    val labelsFrom = ArrayList<MirLabel>()
    val labelsTo = ArrayList<MirLabel>()

    fun newLocal(l: MirLocal): MirLocal {
        for (i in localsFrom.indices) if (localsFrom[i] === l) return localsTo[i]
        val n = MirLocal(nextLocal++, l.name, l.type)
        localsFrom.add(l)
        localsTo.add(n)
        return n
    }

    fun mapLocal(l: MirLocal): MirLocal {
        for (i in paramLocals.indices) {
            val r = paramRepl[i]
            if (paramLocals[i] === l && r is MirExpr.Local) return r.local
        }
        for (i in localsFrom.indices) if (localsFrom[i] === l) return localsTo[i]
        return newLocal(l)
    }

    fun mapLabel(l: MirLabel): MirLabel {
        for (i in labelsFrom.indices) if (labelsFrom[i] === l) return labelsTo[i]
        val n = MirLabel(nextLabel++)
        labelsFrom.add(l)
        labelsTo.add(n)
        return n
    }

    override fun expr(e: MirExpr): MirExpr {
        if (e is MirExpr.Local) {
            for (i in paramLocals.indices) if (paramLocals[i] === e.local) return cloneLeaf(paramRepl[i])
            return MirExpr.Local(mapLocal(e.local))
        }
        val r = rebuildExpr(e)
        return if (r === e && isLeaf(e)) cloneLeaf(e) else r
    }

    override fun stmt(s: MirStmt): MirStmt {
        val r = rebuildStmt(s)
        val n: MirStmt = when (r) {
            is MirStmt.Declare -> MirStmt.Declare(mapLocal(r.local), r.init)
            is MirStmt.Assign -> MirStmt.Assign(mapLocal(r.local), r.value)
            is MirStmt.While -> MirStmt.While(mapLabel(r.label), r.cond, r.body)
            is MirStmt.Loop -> MirStmt.Loop(mapLabel(r.label), r.body)
            is MirStmt.DoWhile -> MirStmt.DoWhile(mapLabel(r.label), r.body, r.condStmts, r.cond)
            is MirStmt.ForRange -> MirStmt.ForRange(mapLabel(r.label), mapLocal(r.variable), r.start, r.end, r.step, r.ascending, r.inclusive, r.body)
            is MirStmt.Break -> MirStmt.Break(mapLabel(r.label))
            is MirStmt.Continue -> MirStmt.Continue(mapLabel(r.label))
            is MirStmt.Panic -> MirStmt.Panic(r.message)
            else -> r
        }
        if (n !== r) return keep(s, n)
        return r
    }
}

internal class Expander(val st: InlineState, val caller: MirFunction) : MirTransform() {
    var nextLocal: Int
    var nextLabel: Int
    var budget = Inliner.GROWTH_LIMIT
    var expanded = 0
    var allowAll = false
    var loopDepth = 0

    init {
        val ids = IdScan(caller)
        nextLocal = ids.maxLocal + 1
        nextLabel = ids.maxLabel + 1
    }

    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.While || s is MirStmt.Loop || s is MirStmt.DoWhile || s is MirStmt.ForRange) {
            loopDepth++
            val r = rebuildStmt(s)
            loopDepth--
            return r
        }
        return rebuildStmt(s)
    }

    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            val r = stmt(s)
            val x = expandStmt(r)
            if (out == null && (x != null || r !== s)) {
                out = ArrayList(l.size + 4)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null) {
                if (x != null) out.addAll(x) else out.add(r)
            }
        }
        return out ?: l
    }

    fun infoFor(e: MirExpr): InlineInfo? {
        if (e !is MirExpr.Call || budget <= 0) return null
        val info = st.infos[e.cName] ?: return null
        if (info.fn.params.size != e.args.size) return null
        if (info.fn.cName == caller.cName) return null
        if (!info.refs && !allowAll) return null
        if (info.size > Inliner.SMALL_LIMIT && !allowAll && !hasFreshObjectArg(e)) return null
        if (loopDepth == 0 && !allowAll && !hasFreshObjectArg(e)) return null   // outside loops only what scalar replacement needs
        return info
    }

    private var freshObjects: HashSet<Int>? = null

    /** Is one of the arguments a local that holds an object created in this function (a candidate for scalar replacement)? */
    fun hasFreshObjectArg(call: MirExpr.Call): Boolean {
        var fo = freshObjects
        if (fo == null) {
            fo = HashSet()
            val u = UseScan(caller)
            u.walk(caller.body)
            for ((id, init) in u.inits) if (init is MirExpr.New && u.assigns[id] == null && u.declared[id] == 1) fo.add(id)
            freshObjects = fo
        }
        for (a in call.args) if (a is MirExpr.Local && fo.contains(a.local.id)) return true
        return false
    }

    fun freshTmp(name: String, t: MirType): MirLocal = MirLocal(nextLocal++, name, t)

    /** Replaces the inlinable calls of a simple statement; null when there is nothing to do. */
    fun expandStmt(s: MirStmt): List<MirStmt>? {
        when (s) {
            is MirStmt.Declare -> {
                val init = s.init ?: return null
                val info = infoFor(init)
                if (info != null) return stamp(s, expandCall(init as MirExpr.Call, info, 1, s.local))
            }
            is MirStmt.Assign -> {
                val info = infoFor(s.value)
                if (info != null) return stamp(s, expandCall(s.value as MirExpr.Call, info, 2, s.local))
            }
            is MirStmt.Eval -> {
                val info = infoFor(s.expr)
                if (info != null) return stamp(s, expandCall(s.expr as MirExpr.Call, info, 0, null))
            }
            else -> {}
        }
        return extractFrom(s)
    }

    fun stamp(orig: MirStmt, l: List<MirStmt>): List<MirStmt> {
        for (x in l) if (x.srcLine == 0) { x.srcLine = orig.srcLine; x.srcFile = orig.srcFile }
        return l
    }

    /** The general case: the first inlinable call somewhere in the operands of [s]. */
    fun extractFrom(s: MirStmt): List<MirStmt>? {
        if (s is MirStmt.Block || s is MirStmt.Loop || s is MirStmt.Try || s is MirStmt.Break || s is MirStmt.Continue || s is MirStmt.Panic) return null
        var cur = s
        var all: ArrayList<MirStmt>? = null
        var guard = 0
        while (guard < 8 && budget > 0) {
            guard++
            val ex = Extractor(this)
            val r = ex.run(cur) ?: break
            if (all == null) all = ArrayList()
            all.addAll(r.pre)
            cur = r.stmt
            if (cur is MirStmt.Loop) break
        }
        if (all == null) return null
        all.add(cur)
        return stamp(s, all)
    }

    /**
     * The statements of [call]'s inlined body. [mode]: 0 the value is discarded, 1 the value initialises the new local [dest],
     * 2 the value is assigned to [dest]. For mode 1 the statements end with the declaration of [dest].
     */
    fun expandCall(call: MirExpr.Call, info: InlineInfo, mode: Int, dest: MirLocal?): List<MirStmt> {
        val fn = info.fn
        val out = ArrayList<MirStmt>()
        val repl = ArrayList<MirExpr>()
        val params = fn.params
        for (i in params.indices) {
            val a = call.args[i]
            if (!info.paramAssigned[i] && sameRep(a.type, params[i].type) && isLeaf(a) && a !is MirExpr.ObjectGet && a !is MirExpr.CaughtException && a !is MirExpr.Unreachable) {
                repl.add(a)
            } else {
                val t = if (a.type is TObject && params[i].type is TObject) a.type else params[i].type
                val nl = MirLocal(nextLocal++, params[i].name, t)
                // a value of another representation (a String passed as Any): an explicit retyping, as the producer writes it
                out.add(MirStmt.Declare(nl, if (sameRep(a.type, t)) a else MirExpr.Cast(a, t, MirExpr.CastMode.Unchecked)))
                repl.add(MirExpr.Local(nl))
            }
        }
        val copier = BodyCopier(params, repl, nextLocal, nextLabel)
        val copied = copier.stmts(fn.body)
        nextLocal = copier.nextLocal
        nextLabel = copier.nextLabel
        budget -= 20 + NodeSize.of(fn.body)
        expanded++
        val isUnit = fn.returnType === TUnit
        if (isUnit || mode == 0) {
            // no value (or a discarded one)
            out.addAll(Retarget.run(copied, null, false))
            if (mode != 0 && !isUnit) { /* unreachable */ }
            if (mode == 1) out.add(MirStmt.Declare(dest!!, null))
            return out
        }
        val single = copied.isNotEmpty() && copied[copied.size - 1] is MirStmt.Return && ReturnCount.of(copied) == 1
        if (mode == 1 && single) {
            val ret = copied[copied.size - 1] as MirStmt.Return
            for (k in 0 until copied.size - 1) out.add(copied[k])
            val d = MirStmt.Declare(dest!!, ret.value)
            d.srcLine = ret.srcLine
            d.srcFile = ret.srcFile
            out.add(d)
            return out
        }
        if (mode == 1) out.add(MirStmt.Declare(dest!!, null))
        out.addAll(Retarget.run(copied, dest, true))
        return out
    }
}

internal object NodeSize {
    fun of(body: List<MirStmt>): Int {
        val c = NodeCounter()
        c.walk(body)
        return c.count
    }
}

internal object ReturnCount {
    fun of(body: List<MirStmt>): Int {
        val c = ReturnCounter()
        c.walk(body)
        return c.count
    }
}

/** Turns the tail `return`s of a copied body into assignments to [target] (or drops them). */
internal object Retarget {
    fun run(body: List<MirStmt>, target: MirLocal?, hasValue: Boolean): List<MirStmt> {
        if (body.isEmpty()) return body
        val last = body[body.size - 1]
        val out = ArrayList<MirStmt>(body.size)
        for (k in 0 until body.size - 1) out.add(body[k])
        when (last) {
            is MirStmt.Return -> {
                val v = last.value
                if (v != null && v !== MirExpr.UnitValue) {
                    if (target != null) {
                        val a = MirStmt.Assign(target, v)
                        a.srcLine = last.srcLine
                        a.srcFile = last.srcFile
                        out.add(a)
                    } else if (v.type !== TUnit) {
                        val e = MirStmt.Eval(v)
                        e.srcLine = last.srcLine
                        e.srcFile = last.srcFile
                        out.add(e)
                    }
                }
            }
            is MirStmt.If -> {
                val n = MirStmt.If(last.cond, run(last.thenBody, target, hasValue), run(last.elseBody, target, hasValue))
                n.srcLine = last.srcLine
                n.srcFile = last.srcFile
                out.add(n)
            }
            is MirStmt.Block -> {
                val n = MirStmt.Block(run(last.body, target, hasValue))
                n.srcLine = last.srcLine
                n.srcFile = last.srcFile
                out.add(n)
            }
            else -> out.add(last)
        }
        return out
    }
}

/** Is [e] free of effects and independent of the heap (so it can be evaluated before or after a call)? */
internal fun isStable(e: MirExpr): Boolean = when (e) {
    is MirExpr.Local, is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst,
    is MirExpr.CharConst, is MirExpr.StringConst, is MirExpr.NullConst -> true
    is MirExpr.Binary -> {
        val op = e.op
        if (op == BinaryOp.Div || op == BinaryOp.Rem) false else isStable(e.left) && isStable(e.right)
    }
    is MirExpr.Unary -> isStable(e.operand)
    is MirExpr.Convert -> isStable(e.operand)
    else -> false
}

internal class ExtractResult(val pre: List<MirStmt>, val stmt: MirStmt)

/**
 * Finds the first inlinable call in the operands of one statement (in evaluation order, not in conditionally evaluated operands)
 * and replaces it by a local holding its result; the inlined body goes into [ExtractResult.pre].
 */
internal class Extractor(val ex: Expander) : MirTransform() {
    val pre = ArrayList<MirStmt>()
    var done = false
    var blocked = false
    val evaluated = ArrayList<MirExpr>()
    val hoistedFrom = ArrayList<MirExpr>()
    val hoistedTo = ArrayList<MirExpr>()
    var resultLocal: MirLocal? = null
    var wholeLoopCond = false

    override fun stmts(l: List<MirStmt>): List<MirStmt> = l

    fun run(s: MirStmt): ExtractResult? {
        if (s is MirStmt.While) return runWhile(s)
        if (s is MirStmt.DoWhile) return runDoWhile(s)
        val r = rebuildStmt(s)
        if (!done) return null
        val sub = if (hoistedFrom.isEmpty()) r else Substitute(hoistedFrom, hoistedTo).rebuildStmt(r)
        return ExtractResult(pre, sub)
    }

    /** `while (cond) body` with a call in `cond`: `loop { pre; if (!cond') break; body }`. */
    fun runWhile(s: MirStmt.While): ExtractResult? {
        val c = expr(s.cond)
        if (!done) return null
        val cond = if (hoistedFrom.isEmpty()) c else Substitute(hoistedFrom, hoistedTo).expr(c)
        val body = ArrayList<MirStmt>()
        body.addAll(pre)
        val brk = MirStmt.If(cond, emptyList(), listOf(MirStmt.Break(s.label)))
        brk.srcLine = s.srcLine
        brk.srcFile = s.srcFile
        body.add(brk)
        body.addAll(s.body)
        val loop = MirStmt.Loop(s.label, body)
        loop.srcLine = s.srcLine
        loop.srcFile = s.srcFile
        return ExtractResult(emptyList(), loop)
    }

    fun runDoWhile(s: MirStmt.DoWhile): ExtractResult? {
        val c = expr(s.cond)
        if (!done) return null
        val cond = if (hoistedFrom.isEmpty()) c else Substitute(hoistedFrom, hoistedTo).expr(c)
        val cs = ArrayList<MirStmt>(s.condStmts)
        cs.addAll(pre)
        val n = MirStmt.DoWhile(s.label, s.body, cs, cond)
        n.srcLine = s.srcLine
        n.srcFile = s.srcFile
        return ExtractResult(emptyList(), n)
    }

    override fun expr(e: MirExpr): MirExpr {
        if (done || blocked) return e
        val mark = evaluated.size
        var r: MirExpr
        if (e is MirExpr.Binary && (e.op == BinaryOp.AndAnd || e.op == BinaryOp.OrOr)) {
            val l = expr(e.left)
            r = if (l === e.left) e else MirExpr.Binary(e.op, l, e.right)
            if (!done) {
                while (evaluated.size > mark) evaluated.removeAt(evaluated.size - 1)
                // the right operand is evaluated conditionally and may have effects: one unit, nothing after it may move before it
                evaluated.add(r)
            }
            return r
        }
        if (e is MirExpr.Conditional) {
            val c = expr(e.cond)
            r = if (c === e.cond) e else MirExpr.Conditional(c, e.thenValue, e.elseValue, e.resultType)
            if (!done) {
                while (evaluated.size > mark) evaluated.removeAt(evaluated.size - 1)
                evaluated.add(r)
            }
            return r
        }
        r = rebuildExpr(e)
        if (done || blocked) return r
        while (evaluated.size > mark) evaluated.removeAt(evaluated.size - 1)
        if (r is MirExpr.Call) {
            val info = ex.infoFor(r)
            if (info != null && r.type !== TUnit && r.type !== TNothing) return tryExtract(r, info)
        }
        evaluated.add(r)
        return r
    }

    fun tryExtract(call: MirExpr.Call, info: InlineInfo): MirExpr {
        // everything evaluated before the call must be able to move after it
        val hoist = ArrayList<MirExpr>()
        for (u in evaluated) {
            if (isStable(u)) continue
            if (u.type.isRef || u.type === TUnit || u.type === TNothing) {
                blocked = true
                evaluated.add(call)
                return call
            }
            hoist.add(u)
        }
        for (u in hoist) {
            val t = ex.freshTmp("tmp", u.type)
            val d = MirStmt.Declare(t, u)
            pre.add(d)
            hoistedFrom.add(u)
            hoistedTo.add(MirExpr.Local(t))
        }
        val res = ex.freshTmp("tmp", call.type)
        pre.addAll(ex.expandCall(call, info, 1, res))
        done = true
        return MirExpr.Local(res)
    }
}
