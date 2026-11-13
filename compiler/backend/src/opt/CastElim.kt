package mini.backend

/*
 * Checked casts that cannot fail. The lowering casts every erased value (an element of a generic container, the result of a generic
 * call) to the type it has at the use, like the `checkcast` of the JVM (a polluted `List<String>` must fail where the value is read).
 * Where the operand's static type already is that type (or a subtype, or the target is nullable and the operand is the same class)
 * the test is dead: the cast becomes a plain retyping, which also lets the function be seen as one that cannot throw.
 */
internal class CastElim(module: MirModule) : MirTransform() {
    private val classes = HashMap<String, MirClass>()
    var removed = 0

    init {
        for (c in module.classes) classes[c.cName] = c
    }

    /** True when every instance of class [from] is an instance of [to] (both by C name). False when unknown. */
    private fun isSub(from: String, to: String): Boolean {
        if (to == ANY_CNAME || from == to) return true
        val fc = classes[from] ?: return false
        if (classes[to]?.isInterface == true) return fc.interfaces.contains(to)
        var k: String? = from
        var n = 0
        while (k != null && n < 1000) {
            if (k == to) return true
            k = classes[k]?.superCName
            n++
        }
        return false
    }

    private fun cannotFail(from: MirType, to: MirType): Boolean {
        if (from.isNullableRef() && !to.isNullableRef()) return false
        if (from is TObject && to is TObject && from.cName == ARRAY_CNAME && to.cName == ARRAY_CNAME) return arrayCastCannotFail(from, to)
        if (from is TObject && to is TObject) return isSub(from.cName, to.cName)
        if ((from === TString || from === TNString) && (to === TString || to === TNString)) return true
        return false
    }

    private fun MirType.isNullableRef(): Boolean = this === TNString || (this is TObject && nullable) || this is TBoxed || this === TNull

    override fun expr(e: MirExpr): MirExpr {
        val r = rebuildExpr(e)
        if (r is MirExpr.Cast && r.mode == MirExpr.CastMode.Checked && cannotFail(r.operand.type, r.to)) {
            removed++
            return MirExpr.Cast(r.operand, r.to, MirExpr.CastMode.Unchecked)
        }
        return r
    }

    fun run(f: MirFunction): MirFunction {
        val b = stmts(f.body)
        if (b === f.body) return f
        return MirFunction(f.qualifiedName, f.cName, f.params, f.returnType, b, f.constructorOf, f.strict, f.returnsBorrowed, f.ownsParams, f.noHeapEffects, f.alwaysInline)
    }
}
