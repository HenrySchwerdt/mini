package mini.frontend.sema

/*
 * Flow-sensitive type information for smart casts: for every stable reference (local val, parameter, unmodified
 * local var, stable member chain) the list of types it is known to have at the current program point.
 * K2 does this with a full control flow graph (compiler/fir/resolve/.../dfa/); here the checker threads a [Flow]
 * through the AST and merges at branches.
 */
class Fact(val root: String, val types: ArrayList<Type>)

/** a fact to add: stable reference [key] (whose root variable is named [root]) has type [type] */
class FactAdd(val key: String, val root: String, val type: Type)

class Flow(val facts: HashMap<String, Fact>, var dead: Boolean) {
    fun copy(): Flow {
        val m = HashMap<String, Fact>()
        for ((k, v) in facts) m[k] = Fact(v.root, ArrayList(v.types))
        return Flow(m, dead)
    }

    fun add(a: FactAdd) {
        var f = facts[a.key]
        if (f == null) {
            f = Fact(a.root, ArrayList())
            facts[a.key] = f
        }
        f.types.add(a.type)
    }

    fun addAll(l: List<FactAdd>) {
        for (a in l) add(a)
    }

    /** an assignment to the variable named [root]: forget everything known about it and about its members */
    fun killRoot(root: String) {
        val rm = ArrayList<String>()
        for ((k, v) in facts) if (v.root == root) rm.add(k)
        for (k in rm) facts.remove(k)
    }

    fun kill(key: String) {
        val rm = ArrayList<String>()
        for (k in facts.keys) if (k == key || k.startsWith("$key.")) rm.add(k)
        for (k in rm) facts.remove(k)
    }
}

object FlowOps {
    /** the state after two branches join: only what both knew (each fact weakened to the common weaker types) */
    fun merge(ops: TypeOps, a: Flow, b: Flow): Flow {
        if (a.dead && !b.dead) return b.copy()
        if (b.dead && !a.dead) return a.copy()
        val m = HashMap<String, Fact>()
        for ((k, fa) in a.facts) {
            val fb = b.facts[k] ?: continue
            val out = ArrayList<Type>()
            for (t in fa.types) {
                for (u in fb.types) {
                    if (ops.isSubtype(u, t)) {
                        addUnique(ops, out, t)
                    } else if (ops.isSubtype(t, u)) {
                        addUnique(ops, out, u)
                    }
                }
            }
            if (out.isNotEmpty()) m[k] = Fact(fa.root, out)
        }
        return Flow(m, a.dead && b.dead)
    }

    private fun addUnique(ops: TypeOps, out: ArrayList<Type>, t: Type) {
        for (o in out) if (typeEquals(o, t)) return
        out.add(t)
    }

    /** facts that hold on both sides */
    fun commonAdds(ops: TypeOps, a: List<FactAdd>, b: List<FactAdd>): ArrayList<FactAdd> {
        val out = ArrayList<FactAdd>()
        for (x in a) {
            for (y in b) {
                if (x.key != y.key) continue
                if (ops.isSubtype(y.type, x.type)) {
                    out.add(x)
                } else if (ops.isSubtype(x.type, y.type)) {
                    out.add(y)
                }
            }
        }
        return out
    }
}
