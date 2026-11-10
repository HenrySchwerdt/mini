package mini.backend

/*
 * `x is C` where no class of the (closed, whole-program) module has C as a superclass: every instance of C has exactly C's type
 * info, so the test is one compare of the type word (`o != null && o->type == &C_ti`) instead of a walk up the superclass chain.
 * The leaf classes of a sealed hierarchy (`when (j) { is JNull -> ...; is JNum -> ... }`) are the typical targets.
 * Only run where the whole program is linked (MirOpt is not run on libraries): a later program cannot add a subclass.
 */
internal class ExactIs(module: MirModule) : MirTransform() {
    private val classes = HashMap<String, MirClass>()
    private val hasSub = HashSet<String>()
    var changed = 0

    init {
        for (c in module.classes) {
            classes[c.cName] = c
            val s = c.superCName
            if (s != null) hasSub.add(s)
        }
    }

    private fun eligible(t: MirType): Boolean {
        if (t !is TObject || t.elem != null) return false
        val c = classes[t.cName] ?: return false
        return !c.isInterface && !c.external && t.cName !in hasSub
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
        return MirFunction(f.qualifiedName, f.cName, f.params, f.returnType, b, f.constructorOf, f.strict, f.returnsBorrowed, f.ownsParams, f.noHeapEffects)
    }
}
