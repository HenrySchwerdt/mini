package mini.backend

/*
 * Exact-type devirtualization and scalar replacement of objects that never leave their function (escape analysis).
 *
 * [ExactDevirt]: a virtual or interface call on a local that is declared once, never reassigned and initialised by `New(C)` has the
 * exact class C, so the call goes to C's own table entry (the module-wide devirtualization only knows the static types). This is what
 * turns `val it = list.iterator(); while (it.hasNext()) ...` (after inlining `iterator()`) into direct calls the inliner can expand.
 *
 * [Sroa]: `val o = New(C, args)` where, once the constructor (and the super constructors) and the small methods called on `o` are
 * inlined, `o` is only used as the object of field reads and writes: the object is never allocated. Its fields become locals
 * (`o.f` is a local `o_f`), so there is no allocation, no header, no reference count and nothing for a collector to see. The
 * transformation is tried on a copy and kept only when no other use of `o` is left (a call that takes `o`, a store of `o`, a
 * comparison, a cast, a return: the object escapes and stays as it is). Strict functions and classes are left alone.
 */
internal class ExactDevirt(val classes: HashMap<String, MirClass>, val fns: HashMap<String, MirFunction>, val u: UseScan) : MirTransform() {
    var count = 0

    fun exactClass(e: MirExpr): MirClass? {
        var r = e
        while (r is MirExpr.Cast && r.mode == MirExpr.CastMode.Unchecked) r = r.operand
        if (r !is MirExpr.Local) return null
        val l = r.local
        if (u.assignCount(l) != 0 || u.declared[l.id] != 1 || u.isParam(l)) return null
        val init = u.inits[l.id] ?: return null
        if (init !is MirExpr.New) return null
        val t = init.type
        if (t !is TObject) return null
        val c = classes[t.cName] ?: return null
        return if (c.isInterface || c.external) null else c
    }

    private fun ok(name: String?, argc: Int): Boolean {
        if (name == null) return false
        val f = fns[name] ?: return false
        return f.params.size == argc
    }

    override fun expr(e: MirExpr): MirExpr {
        val r = rebuildExpr(e)
        if (r is MirExpr.VCall) {
            val c = exactClass(r.receiver)
            if (c != null && r.slot >= 3 && r.slot < c.vtable.size) {
                val t = c.vtable[r.slot]
                if (ok(t, r.args.size + 1)) {
                    count++
                    return MirExpr.Call(t!!, listOf(r.receiver) + r.args, r.type)
                }
            }
        } else if (r is MirExpr.ICall) {
            val c = exactClass(r.receiver)
            if (c != null) {
                for (tbl in c.itables) {
                    if (tbl.interfaceCName != r.interfaceCName) continue
                    val t = if (r.index < tbl.entries.size) tbl.entries[r.index] else null
                    if (ok(t, r.args.size + 1)) {
                        count++
                        return MirExpr.Call(t!!, listOf(r.receiver) + r.args, r.type)
                    }
                }
            }
        }
        return r
    }
}

/** Reads and writes of the fields of one object, rewritten to locals. */
private class FieldRewriter(val obj: MirLocal, val names: List<String>, val locals: List<MirLocal>) : MirTransform() {
    fun find(owner: String, field: String): MirLocal? {
        val key = owner + "." + field
        for (i in names.indices) if (names[i] == key) return locals[i]
        return null
    }

    override fun expr(e: MirExpr): MirExpr {
        if (e is MirExpr.FieldGet) {
            val o = e.obj
            if (o is MirExpr.Local && o.local === obj) {
                val l = find(e.owner, e.field)
                if (l != null) return MirExpr.Local(l)
            }
        }
        return rebuildExpr(e)
    }

    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.SetField) {
            val o = s.obj
            if (o is MirExpr.Local && o.local === obj) {
                val l = find(s.owner, s.field)
                if (l != null) return keep(s, MirStmt.Assign(l, expr(s.value)))
            }
        }
        return rebuildStmt(s)
    }
}

/** Counts the uses of one local. */
private class UseCounter(val l: MirLocal) : MirVisitor() {
    var count = 0
    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local && e.local === l) count++
    }
}

/** Replaces the declaration `o = New(...)` by the expansion of its constructor. */
private class DeclReplacer(val target: MirStmt, val replacement: List<MirStmt>) : MirTransform() {
    var hit = false
    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            if (s === target) {
                hit = true
                if (out == null) {
                    out = ArrayList(l.size + replacement.size)
                    for (k in 0 until i) out.add(l[k])
                }
                out.addAll(replacement)
                continue
            }
            val r = stmt(s)
            if (out == null && r !== s) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null) out.add(r)
        }
        return out ?: l
    }
}

internal class CandidateScan : MirVisitor() {
    val decls = ArrayList<MirStmt.Declare>()
    override fun visit(e: MirExpr) {}
    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.Declare) {
            val i = s.init
            if (i is MirExpr.New) decls.add(s)
        }
        return super.stmt(s)
    }
}

internal class Sroa(val st: InlineState, val caller: MirFunction) {
    var count = 0
    val classes = HashMap<String, MirClass>()

    init {
        for (c in st.module.classes) classes[c.cName] = c
    }

    /** All fields of [c] including inherited ones, as (key, field); null when the hierarchy is not fully known. */
    private fun fieldsOf(c: MirClass, keys: ArrayList<String>, types: ArrayList<MirType>): Boolean {
        var cur: MirClass? = c
        val chain = ArrayList<MirClass>()
        while (cur != null) {
            if (cur.external) return false
            chain.add(cur)
            val sup = cur.superCName
            cur = if (sup == null) null else (classes[sup] ?: return false)
        }
        for (k in chain.indices.reversed()) {
            val cc = chain[k]
            for (fl in cc.fields) {
                keys.add(cc.cName + "." + fl.name)
                types.add(fl.type)
            }
        }
        return true
    }

    fun run(body: List<MirStmt>): List<MirStmt> {
        if (caller.strict) return body
        var cur = body
        val scan = CandidateScan()
        scan.walk(cur)
        for (d in scan.decls) {
            val r = tryReplace(cur, d)
            if (r != null) {
                cur = r
                count++
            }
        }
        return cur
    }

    private fun tryReplace(body: List<MirStmt>, d: MirStmt.Declare): List<MirStmt>? {
        val o = d.local
        val n = d.init as MirExpr.New
        if (n.allocator != null || n.auto) return null
        val t = n.type
        if (t !is TObject) return null
        val c = classes[t.cName] ?: return null
        if (c.isInterface || c.external || c.strict || c.objectCtor != null) return null
        val info = st.infos[n.ctorCName] ?: return null
        if (info.fn.params.size != n.args.size + 1) return null
        val u = UseScan(caller)
        u.walk(body)
        if (u.assignCount(o) != 0 || u.declared[o.id] != 1) return null
        val keys = ArrayList<String>()
        val types = ArrayList<MirType>()
        if (!fieldsOf(c, keys, types)) return null
        val ex = Expander(st, caller.withBody(body))
        val repl = ArrayList<MirStmt>()
        val locals = ArrayList<MirLocal>()
        val dummy = MirStmt.Declare(o, null)
        dummy.srcLine = d.srcLine
        dummy.srcFile = d.srcFile
        repl.add(dummy)
        for (i in keys.indices) {
            val ft = types[i]
            val l = ex.freshTmp(keys[i].substring(keys[i].indexOf('.') + 1), ft)
            locals.add(l)
            val init: MirExpr? = when (ft) {
                TInt -> MirExpr.IntConst(0)
                TLong -> MirExpr.LongConst(0L)
                TBoolean -> MirExpr.BooleanConst(false)
                TDouble -> MirExpr.DoubleConst(0.0)
                TChar -> MirExpr.CharConst(0)
                else -> null
            }
            if (init == null && !ft.isRef) return null   // other value types (floats, pointers, value classes): not worth the care
            repl.add(MirStmt.Declare(l, init))
        }
        val args = ArrayList<MirExpr>()
        args.add(MirExpr.Local(o))
        args.addAll(n.args)
        val call = MirExpr.Call(n.ctorCName, args, TUnit)
        val expansion = ex.expandCall(call, info, 0, null)
        // constructors of the super classes (and whatever else is small) inside the expansion
        val ex2 = Expander(st, caller.withBody(body))
        ex2.nextLocal = ex.nextLocal
        ex2.nextLabel = ex.nextLabel
        repl.addAll(ex2.stmts(expansion))
        for (x in repl) if (x.srcLine == 0) { x.srcLine = d.srcLine; x.srcFile = d.srcFile }
        val rep = DeclReplacer(d, repl)
        val replaced = rep.stmts(body)
        if (!rep.hit) return null
        val rw = FieldRewriter(o, keys, locals)
        val out = rw.stmts(replaced)
        val uc = UseCounter(o)
        uc.walk(out)
        if (uc.count != 0) return null
        return out
    }
}
