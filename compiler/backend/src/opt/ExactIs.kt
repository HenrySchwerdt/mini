package mini.backend

/*
 * `x is C` where no class of the (closed, whole-program) module has C as a superclass: every instance of C has exactly C's type
 * info, so the test is one compare of the type word (`o != null && o->type == &C_ti`) instead of a walk up the superclass chain.
 * The leaf classes of a sealed hierarchy (`when (j) { is JNull -> ...; is JNum -> ... }`) are the typical targets.
 * Only run where the whole program is linked (MirOpt is not run on libraries): a later program cannot add a subclass.
 *
 * C interop can break the closed world: a C function declared `@CSymbol("f") external fun f(...): Base` may return an object whose
 * type info (made in C) has a class of the program as its superclass, a "foreign" subclass the MIR does not list, and `is` must see
 * it. The classes such a result can be an instance of (its declared class, every subclass of it, and everything for `Any`) keep the
 * general test. The functions of the runtime (`mk_...`, `Mk...`) return objects of the runtime's classes and of the program, never
 * a subclass the program does not know.
 */
internal class ExactIs(module: MirModule) : MirTransform() {
    private val classes = HashMap<String, MirClass>()
    private val hasSub = HashSet<String>()
    private val foreignRoots = HashSet<String>()
    var changed = 0

    init {
        for (c in module.classes) {
            classes[c.cName] = c
            val s = c.superCName
            if (s != null) hasSub.add(s)
        }
        val scan = ForeignScan(foreignRoots)
        for (f in module.functions) scan.walk(f.body)
    }

    /** True when an object returned by a C function of the program may be an instance of the class [cName] or of a subclass the MIR does not know. */
    private fun foreignPossible(cName: String): Boolean {
        if (foreignRoots.isEmpty()) return false
        if (foreignRoots.contains(ANY_CNAME)) return true
        var k: String? = cName
        var n = 0
        while (k != null && n < 1000) {
            if (foreignRoots.contains(k)) return true
            k = classes[k]?.superCName
            n++
        }
        // a root that is a subclass of the target: a foreign object may extend it too
        for (r in foreignRoots) {
            var q: String? = r
            var m = 0
            while (q != null && m < 1000) {
                if (q == cName) return true
                q = classes[q]?.superCName
                m++
            }
        }
        return false
    }

    private fun eligible(t: MirType): Boolean {
        if (t !is TObject || t.elem != null) return false
        val c = classes[t.cName] ?: return false
        return !c.isInterface && !c.external && t.cName !in hasSub && !foreignPossible(t.cName)
    }

    override fun expr(e: MirExpr): MirExpr {
        val r = rebuildExpr(e)
        if (r is MirExpr.IsInstance && !r.exact && eligible(r.target)) {
            changed++
            return MirExpr.IsInstance(r.operand, r.target, true)
        }
        return r
    }

    fun run(f: MirFunction): MirFunction {
        val b = stmts(f.body)
        if (b === f.body) return f
        return MirFunction(f.qualifiedName, f.cName, f.params, f.returnType, b, f.constructorOf, f.strict, f.returnsBorrowed, f.ownsParams, f.noHeapEffects, f.alwaysInline)
    }
}

/** The result classes of the calls of C functions of the program (not the runtime's mk_ / Mk functions). */
internal class ForeignScan(val roots: HashSet<String>) : MirVisitor() {
    override fun visit(e: MirExpr) {
        if (e is MirExpr.RuntimeCall && !e.cName.startsWith("mk_") && !e.cName.startsWith("Mk")) {
            val t = e.type
            if (t is TObject) roots.add(t.cName)
        }
    }
}
