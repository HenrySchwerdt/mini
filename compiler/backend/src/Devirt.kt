package mini.backend

/*
 * Devirtualization of the whole program (class hierarchy analysis restricted to the instantiated classes, "RTA").
 *
 * A virtual call `recv.m()` (VCall on a class vtable slot) or interface call (ICall) whose possible targets are all the same
 * function becomes a direct Call. The possible targets are the implementations that the instantiated classes assignable to the
 * static type of the receiver have: every object that can exist at run time is an instance of a class that some `New`, `ObjectGet`
 * or boxing of an inline class creates, or of a class the runtime creates itself (`external` classes). The program is closed
 * when this runs: the MIR of the program and of all its libraries was linked and pruned. Never run it on the MIR of a library
 * that is saved for later use (a later program may add subclasses) or on code that is compiled incrementally (the REPL).
 *
 * Direct calls can be inlined by the C compiler / LLVM, are cheaper (no vtable load, no indirect branch) and let the throw
 * analysis see that the callee cannot throw. Calls in or on Strict code, calls through `Any` slots (toString, equals, hashCode,
 * whose default is in the runtime) and calls whose signature does not match the target are left alone.
 */
object Devirt {
    fun run(module: MirModule): MirModule = Pass(module).run()

    private class Pass(val m: MirModule) {
        val classes = HashMap<String, MirClass>()
        val fns = HashMap<String, MirFunction>()
        val instantiated = HashSet<String>()
        var changed = false
        var hasMain = false

        /** class cName -> instantiated classes assignable to it (itself and all its subclasses; for an interface, its implementors). */
        val assignable = HashMap<String, List<MirClass>>()

        // ---- reachability and instantiation (rapid type analysis)

        val reachable = HashSet<String>()
        val workF = ArrayList<MirFunction>()
        val ancestors = HashMap<String, Set<String>>()
        val vSites = HashMap<String, HashSet<Int>>()
        val iSites = HashMap<String, HashSet<Int>>()
        val instantiatedList = ArrayList<MirClass>()

        fun reach(name: String?) {
            if (name == null || !reachable.add(name)) return
            val f = fns[name] ?: return
            workF.add(f)
        }

        /** The class itself, its superclasses and every interface it implements: the static types a receiver of this class can have. */
        fun ancestorsOf(c: MirClass): Set<String> = ancestors.getOrPut(c.cName) {
            val out = HashSet<String>()
            var k: MirClass? = c
            while (k != null) {
                out.add(k.cName)
                out.addAll(k.interfaces)
                k = k.superCName?.let { classes[it] }
            }
            out
        }

        fun instantiate(cName: String?) {
            if (cName == null) return
            val c = classes[cName] ?: return
            if (c.isInterface || !instantiated.add(cName)) return
            instantiatedList.add(c)
            // what the runtime calls on any object: toString, equals, hashCode and the message of a Throwable (vtable slots 0..3)
            for (i in 0 until minOf(4, c.vtable.size)) reach(c.vtable[i])
            // mk_checked_number_to_* (the Number members on a value of static type Number) call the vtable slots 3..9 of a user class that extends Number
            if (ancestorsOf(c).any { classes[it]?.qualifiedName == "kotlin.Number" }) for (i in 3 until minOf(10, c.vtable.size)) reach(c.vtable[i])
            for (t in c.itables) {
                val q = classes[t.interfaceCName]?.qualifiedName
                // interfaces the runtime dispatches on by itself: threads call Function0.invoke, sorting calls Comparable.compareTo,
                // mk_cs_length / mk_cs_get / mk_cs_sub_sequence call CharSequence
                if (q == "kotlin.Function0" || q == "kotlin.Comparable" || q == "kotlin.CharSequence") for (e in t.entries) reach(e)
            }
            val anc = ancestorsOf(c)
            for (a in anc) {
                vSites[a]?.let { for (slot in it) reach(c.vtable.getOrNull(slot)) }
                iSites[a]?.let { for (idx in it) reach(c.itables.firstOrNull { t -> t.interfaceCName == a }?.entries?.getOrNull(idx)) }
            }
        }

        fun visitV(t: String, slot: Int) {
            if (!vSites.getOrPut(t) { HashSet() }.add(slot)) return
            for (c in instantiatedList) if (t in ancestorsOf(c)) reach(c.vtable.getOrNull(slot))
        }

        fun visitI(iface: String, idx: Int) {
            if (!iSites.getOrPut(iface) { HashSet() }.add(idx)) return
            for (c in instantiatedList) if (iface in ancestorsOf(c)) reach(c.itables.firstOrNull { it.interfaceCName == iface }?.entries?.getOrNull(idx))
        }

        fun analyze() {
            for (c in m.classes) if (c.external) { instantiated.add(c.cName); instantiatedList.add(c) }
            reach(Mangling.MAIN)
            hasMain = fns.containsKey(Mangling.MAIN)
            if (!hasMain) for (f in m.functions) reach(f.cName)   // a library-like program: everything is a root
            // the emitters make an accessor for every `object`: its initialiser stays
            for (c in m.classes) reach(c.objectCtor)
            while (workF.isNotEmpty()) collect(workF.removeAt(workF.size - 1).body)
        }

        fun run(): MirModule {
            for (c in m.classes) classes[c.cName] = c
            for (f in m.functions) fns[f.cName] = f
            analyze()
            buildAssignable()
            val out = ArrayList<MirFunction>(m.functions.size)
            for (f in m.functions) {
                if (f.strict) { out.add(f); continue }
                val body = stmts(f.body)
                out.add(if (body === f.body) f else MirFunction(f.qualifiedName, f.cName, f.params, f.returnType, body, f.constructorOf, f.strict,
                    f.returnsBorrowed, f.ownsParams, f.noHeapEffects))
            }
            if (!hasMain) return if (!changed) m else MirModule(m.sourceName, out, m.classes, m.externs, m.includes, m.links, m.wasmExports)
            return prune(out)
        }

        /**
         * Drops the functions nothing can call (not reachable from `main` through calls and the dispatch of the instantiated classes)
         * and the entries that name them in vtables and interface tables (the emitters make those entries abstract). The pruning of
         * [MirLink] keeps every method of every live class; this one keeps what the program can reach.
         */
        fun prune(fs: List<MirFunction>): MirModule {
            val keep = ArrayList<MirFunction>(fs.size)
            for (f in fs) if (f.cName in reachable) keep.add(f)
            if (keep.size == fs.size && !changed) return m
            val cs = ArrayList<MirClass>(m.classes.size)
            for (c in m.classes) {
                var touched = false
                for (e in c.vtable) if (e != null && e !in reachable) touched = true
                for (t in c.itables) for (e in t.entries) if (e != null && e !in reachable) touched = true
                if (!touched) { cs.add(c); continue }
                val vt = c.vtable.map { if (it != null && it !in reachable) null else it }
                val its = c.itables.map { t -> MirItable(t.interfaceCName, t.entries.map { if (it != null && it !in reachable) null else it }) }
                cs.add(MirClass(c.qualifiedName, c.cName, c.isInterface, c.superCName, c.fields, c.interfaces, vt, its, c.objectCtor, c.external, c.strict))
            }
            return MirModule(m.sourceName, keep, cs, m.externs, m.includes, m.links, m.wasmExports)
        }

        // ---- which classes can have instances

        fun collect(l: List<MirStmt>) { for (s in l) collect(s) }

        fun collect(s: MirStmt) {
            when (s) {
                is MirStmt.Declare -> if (s.init != null) collect(s.init)
                is MirStmt.Assign -> collect(s.value)
                is MirStmt.SetField -> { collect(s.obj); collect(s.value) }
                is MirStmt.Store -> { collect(s.ptr); collect(s.value) }
                is MirStmt.Eval -> collect(s.expr)
                is MirStmt.If -> { collect(s.cond); collect(s.thenBody); collect(s.elseBody) }
                is MirStmt.Block -> collect(s.body)
                is MirStmt.While -> { collect(s.cond); collect(s.body) }
                is MirStmt.Loop -> collect(s.body)
                is MirStmt.DoWhile -> { collect(s.body); collect(s.condStmts); collect(s.cond) }
                is MirStmt.ForRange -> { collect(s.start); collect(s.end); if (s.step != null) collect(s.step); collect(s.body) }
                is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> {}
                is MirStmt.Return -> if (s.value != null) collect(s.value)
                is MirStmt.Throw -> collect(s.value)
                is MirStmt.Try -> { collect(s.body); for (c in s.catches) collect(c.body) }
            }
        }

        fun collect(e: MirExpr) {
            when (e) {
                is MirExpr.Call -> reach(e.cName)
                is MirExpr.New -> { reach(e.ctorCName); instantiate(fns[e.ctorCName]?.constructorOf); e.allocator?.let { collect(it) } }
                is MirExpr.ObjectGet -> { instantiate(e.classCName); reach(classes[e.classCName]?.objectCtor) }
                is MirExpr.Box -> { val t = e.operand.type; if (t is TInline) instantiate(t.cName) }
                is MirExpr.Unbox -> { val t = e.to; if (t is TInline) instantiate(t.cName) }
                is MirExpr.FuncAddr -> reach(e.cName)
                is MirExpr.VCall -> { val t = e.receiver.type; if (t is TObject) visitV(t.cName, e.slot) }
                is MirExpr.ICall -> visitI(e.interfaceCName, e.index)
                else -> {}
            }
            for (c in mirChildren(e)) collect(c)
        }

        fun buildAssignable() {
            val children = HashMap<String, ArrayList<MirClass>>()
            val implementors = HashMap<String, ArrayList<MirClass>>()
            for (c in m.classes) {
                if (c.isInterface) continue
                val sup = c.superCName
                if (sup != null) children.getOrPut(sup) { ArrayList() }.add(c)
                for (i in c.interfaces) implementors.getOrPut(i) { ArrayList() }.add(c)
            }
            // classes (not interfaces) are keyed by their own cName, interfaces by theirs: both lists hold only classes that can have instances
            for (c in m.classes) {
                val out = ArrayList<MirClass>()
                if (c.isInterface) {
                    for (k in implementors[c.cName] ?: emptyList<MirClass>()) if (k.cName in instantiated) out.add(k)
                } else {
                    val work = ArrayList<MirClass>()
                    work.add(c)
                    while (work.isNotEmpty()) {
                        val k = work.removeAt(work.size - 1)
                        if (k.cName in instantiated) out.add(k)
                        for (ch in children[k.cName] ?: emptyList<MirClass>()) work.add(ch)
                    }
                }
                assignable[c.cName] = out
            }
        }

        // ---- the target of a call

        fun targetOfV(e: MirExpr.VCall): String? {
            val t = e.receiver.type
            if (t !is TObject || t.cName == ANY_CNAME || e.slot < 3) return null
            val c = classes[t.cName] ?: return null
            if (c.strict || c.isInterface) return null
            var target: String? = null
            val cands = assignable[t.cName] ?: return null
            if (cands.isEmpty()) return null
            for (k in cands) {
                if (k.external) return null
                if (k.strict) return null
                val impl = k.vtable.getOrNull(e.slot) ?: return null
                if (target == null) target = impl else if (target != impl) return null
            }
            return target
        }

        fun targetOfI(e: MirExpr.ICall): String? {
            val c = classes[e.interfaceCName] ?: return null
            if (!c.isInterface || c.strict) return null
            val cands = assignable[e.interfaceCName] ?: return null
            if (cands.isEmpty()) return null
            var target: String? = null
            for (k in cands) {
                if (k.external || k.strict) return null
                val table = k.itables.firstOrNull { it.interfaceCName == e.interfaceCName } ?: return null
                val impl = table.entries.getOrNull(e.index) ?: return null
                if (target == null) target = impl else if (target != impl) return null
            }
            return target
        }

        /** The signature of [f] must be what the call site passes: same arity, same representation of every operand and the result. */
        fun compatible(f: MirFunction, recv: MirExpr, args: List<MirExpr>, paramTypes: List<MirType>, type: MirType): Boolean {
            if (f.params.size != args.size + 1 || f.constructorOf != null || f.strict) return false
            if (!kindEq(f.returnType, type)) return false
            if (!f.params[0].type.isRef) return false
            for (i in args.indices) if (!kindEq(f.params[i + 1].type, paramTypes[i])) return false
            return true
        }

        fun kindEq(a: MirType, b: MirType): Boolean {
            if (a.isRef && b.isRef) return isStr(a) == isStr(b)
            return a == b
        }

        fun isStr(t: MirType): Boolean = t === TString || t === TNString

        // ---- rewriting

        fun stmts(l: List<MirStmt>): List<MirStmt> {
            var out: ArrayList<MirStmt>? = null
            for (i in l.indices) {
                val s = l[i]
                val n = stmt(s)
                if (n !== s && out == null) { out = ArrayList(l.size); for (j in 0 until i) out.add(l[j]) }
                out?.add(n)
            }
            return out ?: l
        }

        fun <T : MirStmt> keep(old: MirStmt, n: T): T { n.srcLine = old.srcLine; n.srcFile = old.srcFile; return n }

        fun stmt(s: MirStmt): MirStmt = when (s) {
            is MirStmt.Declare -> { val i = s.init?.let { expr(it) }; if (i === s.init) s else keep(s, MirStmt.Declare(s.local, i)) }
            is MirStmt.Assign -> { val v = expr(s.value); if (v === s.value) s else keep(s, MirStmt.Assign(s.local, v)) }
            is MirStmt.SetField -> {
                val o = expr(s.obj); val v = expr(s.value)
                if (o === s.obj && v === s.value) s else keep(s, MirStmt.SetField(o, s.owner, s.field, s.fieldType, v))
            }
            is MirStmt.Store -> {
                val p = expr(s.ptr); val v = expr(s.value)
                if (p === s.ptr && v === s.value) s else keep(s, MirStmt.Store(p, v))
            }
            is MirStmt.Eval -> { val x = expr(s.expr); if (x === s.expr) s else keep(s, MirStmt.Eval(x)) }
            is MirStmt.If -> {
                val c = expr(s.cond); val t = stmts(s.thenBody); val e = stmts(s.elseBody)
                if (c === s.cond && t === s.thenBody && e === s.elseBody) s else keep(s, MirStmt.If(c, t, e))
            }
            is MirStmt.Block -> { val b = stmts(s.body); if (b === s.body) s else keep(s, MirStmt.Block(b)) }
            is MirStmt.While -> {
                val c = expr(s.cond); val b = stmts(s.body)
                if (c === s.cond && b === s.body) s else keep(s, MirStmt.While(s.label, c, b))
            }
            is MirStmt.Loop -> { val b = stmts(s.body); if (b === s.body) s else keep(s, MirStmt.Loop(s.label, b)) }
            is MirStmt.DoWhile -> {
                val b = stmts(s.body); val cs = stmts(s.condStmts); val c = expr(s.cond)
                if (b === s.body && cs === s.condStmts && c === s.cond) s else keep(s, MirStmt.DoWhile(s.label, b, cs, c))
            }
            is MirStmt.ForRange -> {
                val st = expr(s.start); val en = expr(s.end); val sp = s.step?.let { expr(it) }; val b = stmts(s.body)
                if (st === s.start && en === s.end && sp === s.step && b === s.body) s
                else keep(s, MirStmt.ForRange(s.label, s.variable, st, en, sp, s.ascending, s.inclusive, b))
            }
            is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> s
            is MirStmt.Return -> { val v = s.value?.let { expr(it) }; if (v === s.value) s else keep(s, MirStmt.Return(v)) }
            is MirStmt.Throw -> { val v = expr(s.value); if (v === s.value) s else keep(s, MirStmt.Throw(v)) }
            is MirStmt.Try -> {
                val b = stmts(s.body)
                var cs: ArrayList<MirCatch>? = null
                for (i in s.catches.indices) {
                    val c = s.catches[i]
                    val nb = stmts(c.body)
                    if (nb !== c.body && cs == null) { cs = ArrayList(s.catches); }
                    if (cs != null) cs[i] = if (nb === c.body) c else MirCatch(c.type, nb)
                }
                if (b === s.body && cs == null) s else keep(s, MirStmt.Try(b, cs ?: s.catches))
            }
        }

        fun exprs(l: List<MirExpr>): List<MirExpr> {
            var out: ArrayList<MirExpr>? = null
            for (i in l.indices) {
                val x = l[i]
                val n = expr(x)
                if (n !== x && out == null) { out = ArrayList(l); }
                if (out != null) out[i] = n
            }
            return out ?: l
        }

        fun expr(e: MirExpr): MirExpr = when (e) {
            is MirExpr.Binary -> { val a = expr(e.left); val b = expr(e.right); if (a === e.left && b === e.right) e else MirExpr.Binary(e.op, a, b) }
            is MirExpr.Unary -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.Unary(e.op, a) }
            is MirExpr.Convert -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.Convert(a, e.to) }
            is MirExpr.Call -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.Call(e.cName, a, e.type) }
            is MirExpr.RuntimeCall -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.RuntimeCall(e.cName, a, e.type) }
            is MirExpr.Conditional -> {
                val c = expr(e.cond); val t = expr(e.thenValue); val f = expr(e.elseValue)
                if (c === e.cond && t === e.thenValue && f === e.elseValue) e else MirExpr.Conditional(c, t, f, e.resultType)
            }
            is MirExpr.Box -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.Box(a, e.to) }
            is MirExpr.Unbox -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.Unbox(a, e.to) }
            is MirExpr.Cast -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.Cast(a, e.to, e.mode) }
            is MirExpr.IsInstance -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.IsInstance(a, e.target, e.exact) }
            is MirExpr.NotNull -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.NotNull(a) }
            is MirExpr.FieldGet -> { val a = expr(e.obj); if (a === e.obj) e else MirExpr.FieldGet(a, e.owner, e.field, e.type) }
            is MirExpr.VCall -> {
                val r = expr(e.receiver); val a = exprs(e.args)
                val v = if (r === e.receiver && a === e.args) e else MirExpr.VCall(e.slot, r, a, e.paramTypes, e.type)
                val target = targetOfV(v)
                val f = if (target != null) fns[target] else null
                if (f != null && compatible(f, r, a, v.paramTypes, v.type)) {
                    changed = true
                    MirExpr.Call(f.cName, listOf(r) + a, v.type)
                } else v
            }
            is MirExpr.ICall -> {
                val r = expr(e.receiver); val a = exprs(e.args)
                val v = if (r === e.receiver && a === e.args) e else MirExpr.ICall(e.interfaceCName, e.index, r, a, e.paramTypes, e.type)
                val target = targetOfI(v)
                val f = if (target != null) fns[target] else null
                if (f != null && compatible(f, r, a, v.paramTypes, v.type)) {
                    changed = true
                    MirExpr.Call(f.cName, listOf(r) + a, v.type)
                } else v
            }
            is MirExpr.New -> {
                val a = exprs(e.args); val al = e.allocator?.let { expr(it) }
                if (a === e.args && al === e.allocator) e else MirExpr.New(e.ctorCName, a, e.type, al, e.auto)
            }
            is MirExpr.StringLength -> { val a = expr(e.operand); if (a === e.operand) e else MirExpr.StringLength(a) }
            is MirExpr.Load -> { val a = expr(e.ptr); if (a === e.ptr) e else MirExpr.Load(a, e.type) }
            is MirExpr.PtrAdd -> { val a = expr(e.ptr); val b = expr(e.bytes); if (a === e.ptr && b === e.bytes) e else MirExpr.PtrAdd(a, b) }
            is MirExpr.CCall -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.CCall(e.extern, a) }
            is MirExpr.CCallIndirect -> {
                val c = expr(e.callee); val a = exprs(e.args)
                if (c === e.callee && a === e.args) e else MirExpr.CCallIndirect(c, a, e.paramTypes, e.type)
            }
            else -> e
        }
    }
}
