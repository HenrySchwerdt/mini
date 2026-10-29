package mini.frontend.sema

/*
 * Type inference: a constraint system over type variables (one per type parameter of a called generic function).
 * Simplified from K2's NewConstraintSystemImpl / ConstraintInjector / ResultTypeResolver
 * (compiler/resolution.common/src/org/jetbrains/kotlin/resolve/calls/inference/): subtype constraints are decomposed
 * structurally into lower / upper / equality bounds per variable, and a variable is fixed to the common supertype of its
 * lower bounds, else to its equality bound, else to the intersection of its upper bounds.
 */
class TypeVariable(val tp: TypeParamSym) {
    val lower = ArrayList<Type>()
    val upper = ArrayList<Type>()
    val equal = ArrayList<Type>()
    var fixed: Type? = null
    var solving = false
}

class ConstraintSystem(private val sema: Sema) {
    val vars = ArrayList<TypeVariable>()
    private val ops = sema.ops

    /** creates a variable per type parameter and returns the substitution parameter -> variable type */
    fun addVariables(tps: List<TypeParamSym>, into: HashMap<TypeParamSym, Type>) {
        val created = ArrayList<TypeVariable>()
        for (tp in tps) {
            val v = TypeVariable(tp)
            vars.add(v)
            created.add(v)
            into[tp] = VarType(v, false)
        }
        // declared upper bounds
        for (v in created) {
            sema.ensureBounds(v.tp)
            for (b in v.tp.bounds) {
                val sb = ops.substitute(b, into)
                v.upper.add(sb)
                // `T : S` also tells S that T is below it
                if (sb is VarType && !sb.nullable) sb.v.lower.add(VarType(v, false))
            }
        }
    }

    fun hasVars(t: Type): Boolean {
        when (t) {
            is VarType -> return true
            is ClassType -> {
                for (a in t.args) {
                    val x = a.type
                    if (x != null && hasVars(x)) return true
                }
                return false
            }
            is FunType -> {
                val r = t.receiver
                if (r != null && hasVars(r)) return true
                for (p in t.params) if (hasVars(p)) return true
                return hasVars(t.ret)
            }
            is IsectType -> {
                for (p in t.parts) if (hasVars(p)) return true
                return false
            }
            else -> return false
        }
    }

    fun unfixedVars(t: Type, out: ArrayList<TypeVariable>) {
        when (t) {
            is VarType -> {
                if (t.v.fixed == null && !out.contains(t.v)) out.add(t.v)
            }
            is ClassType -> for (a in t.args) {
                val x = a.type
                if (x != null) unfixedVars(x, out)
            }
            is FunType -> {
                val r = t.receiver
                if (r != null) unfixedVars(r, out)
                for (p in t.params) unfixedVars(p, out)
                unfixedVars(t.ret, out)
            }
            is IsectType -> for (p in t.parts) unfixedVars(p, out)
            else -> {}
        }
    }

    /** replaces fixed variables by their value (unfixed ones stay) */
    fun resolveFixed(t: Type): Type {
        when (t) {
            is VarType -> {
                val f = t.v.fixed ?: return t
                return if (t.nullable) f.withNullable(true) else f
            }
            is ClassType -> {
                if (t.args.isEmpty()) return t
                val args = ArrayList<TArg>()
                for (a in t.args) {
                    val x = a.type
                    args.add(if (x == null) a else TArg(resolveFixed(x), a.variance))
                }
                return ClassType(t.cls, args, t.nullable)
            }
            is FunType -> {
                val r = t.receiver
                val ps = ArrayList<Type>()
                for (p in t.params) ps.add(resolveFixed(p))
                return FunType(if (r != null) resolveFixed(r) else null, ps, resolveFixed(t.ret), t.nullable, t.suspend, t.contexts)
            }
            is IsectType -> {
                val ps = ArrayList<Type>()
                for (p in t.parts) ps.add(resolveFixed(p))
                return IsectType(ps)
            }
            else -> return t
        }
    }

    // ---- constraints -----------------------------------------------------------------------------------------------

    fun addEqual(a: Type, b: Type): Boolean = addSub(a, b, 0) && addSub(b, a, 0)

    fun addSub(a0: Type, b0: Type): Boolean = addSub(a0, b0, 0)

    private fun addSub(a0: Type, b0: Type, depth: Int): Boolean {
        if (depth > 25) return true
        val a = resolveFixed(a0)
        val b = resolveFixed(b0)
        if (a is ErrType || b is ErrType) return true
        if (a is CapType) return addSub(ops.capUpper(a), b, depth + 1)
        if (b is VarType) {
            if (a is VarType && a.v === b.v) return true
            var lo = a
            if (b.nullable && a !is VarType) lo = if (a is IntLiteralType) a else a.withNullable(false)
            if (a is VarType) {
                a.v.upper.add(b)
            }
            b.v.lower.add(lo)
            return true
        }
        if (a is VarType) {
            var up = b
            if (a.nullable) up = b
            a.v.upper.add(up)
            return true
        }
        if (!hasVars(a) && !hasVars(b)) return ops.isSubtype(a, b)
        if (a is IntLiteralType) return true
        if (a is IsectType) {
            for (p in a.parts) if (addSub(p, b, depth + 1)) return true
            return false
        }
        if (b is IsectType) {
            for (p in b.parts) if (!addSub(a, p, depth + 1)) return false
            return true
        }
        if (a is ClassType && a.cls === sema.nothingType.cls) return true
        if (a.nullable && !b.nullable) return false
        when (b) {
            is ClassType -> {
                if (a is ClassType) {
                    val s = ops.asSuper(a, b.cls) ?: return false
                    return decomposeArgs(s, b, depth)
                }
                if (a is TypeParamType) {
                    sema.ensureBounds(a.tp)
                    for (bd in a.tp.bounds) if (addSub(bd, b, depth + 1)) return true
                    return false
                }
                if (a is FunType) {
                    return b.cls === sema.anyType.cls || b.cls.name == "Function"
                }
                return false
            }
            is FunType -> {
                val ak = if (a is ClassType) ops.asFunType(a) else null
                if (ak != null) return addSub(ak, b, depth + 1)
                if (a is FunType) {
                    if (a.arity != b.arity) return false
                    val pa = ops.allParams(a)
                    val pb = ops.allParams(b)
                    var i = 0
                    while (i < pa.size) {
                        if (!addSub(pb[i], pa[i], depth + 1)) return false
                        i++
                    }
                    return addSub(a.ret, b.ret, depth + 1)
                }
                if (a is TypeParamType) {
                    sema.ensureBounds(a.tp)
                    for (bd in a.tp.bounds) if (addSub(bd, b, depth + 1)) return true
                }
                return false
            }
            is TypeParamType -> {
                if (a is TypeParamType && a.tp === b.tp) return true
                return ops.isSubtype(a, b)
            }
            else -> return true
        }
    }

    private fun decomposeArgs(s: ClassType, b: ClassType, depth: Int): Boolean {
        var i = 0
        while (i < b.args.size && i < s.args.size) {
            val ba = b.args[i]
            val sa = s.args[i]
            val bt = ba.type
            val st = sa.type
            if (bt != null) {
                val tp = b.cls.typeParams[i]
                val v = if (ba.variance != Variance.INV) ba.variance else tp.variance
                if (st == null) {
                    // star projection against a variable: it gets `Any?`
                    if (hasVars(bt)) {
                        if (!addSub(sema.nullableAny, bt, depth + 1) && v != Variance.OUT) return false
                    }
                } else {
                    when (v) {
                        Variance.OUT -> if (!addSub(st, bt, depth + 1)) return false
                        Variance.IN -> if (!addSub(bt, st, depth + 1)) return false
                        Variance.INV -> {
                            if (sa.variance == Variance.OUT) {
                                if (!addSub(st, bt, depth + 1)) return false
                            } else if (sa.variance == Variance.IN) {
                                if (!addSub(bt, st, depth + 1)) return false
                            } else {
                                if (!addSub(st, bt, depth + 1)) return false
                                if (!addSub(bt, st, depth + 1)) return false
                            }
                        }
                    }
                }
            }
            i++
        }
        return true
    }

    // ---- solving ---------------------------------------------------------------------------------------------------

    fun fixAll() {
        fixInOrder(vars)
    }

    fun fixVarsOf(t: Type) {
        val l = ArrayList<TypeVariable>()
        unfixedVars(resolveFixed(t), l)
        fixInOrder(l)
    }

    /** variables with a lower or equal constraint go first: the others may get their bounds from them (`C : MutableCollection<in R>`) */
    private fun fixInOrder(l: List<TypeVariable>) {
        for (v in ArrayList(l)) if (v.equal.isNotEmpty() || v.lower.isNotEmpty()) fix(v)
        for (v in ArrayList(l)) fix(v)
    }

    fun fix(v: TypeVariable): Type {
        val f = v.fixed
        if (f != null) return f
        if (v.solving) return ErrType("cyclic inference")
        v.solving = true
        val r = compute(v)
        v.fixed = r
        v.solving = false
        // incorporation: bounds that mention other variables become constraints on them
        for (u in ArrayList(v.upper)) {
            val ur = resolveFixed(u)
            if (hasVars(ur)) addSub(r, ur)
        }
        for (l in ArrayList(v.lower)) {
            val lr = resolveFixed(l)
            if (hasVars(lr)) addSub(lr, r)
        }
        return r
    }

    private fun proper(t: Type): Type? {
        // fix other variables a bound depends on, then check no variable is left
        val dep = ArrayList<TypeVariable>()
        unfixedVars(resolveFixed(t), dep)
        for (d in dep) {
            if (d.solving) return null
            fix(d)
        }
        return resolveFixed(t)
    }

    private fun compute(v: TypeVariable): Type {
        val eqs = ArrayList<Type>()
        for (e in ArrayList(v.equal)) {
            val p = proper(e)
            if (p != null) eqs.add(p)
        }
        if (eqs.isNotEmpty()) return eqs[0]
        val lows = ArrayList<Type>()
        for (l in ArrayList(v.lower)) {
            val p = proper(l)
            if (p != null) lows.add(p)
        }
        var onlyLit = lows.isNotEmpty()
        for (l in lows) if (l !is IntLiteralType) onlyLit = false
        // the upper bounds are only needed without lower bounds or for literals; looking at them early would fix the
        // variables they mention before the constraints that determine those arrived (`C : MutableCollection<in R>`)
        val ups = ArrayList<Type>()
        for (u in ArrayList(v.upper)) {
            if (lows.isNotEmpty() && !onlyLit) break
            val p = proper(u)
            if (p != null) ups.add(p)
        }
        if (lows.isNotEmpty()) {
            if (onlyLit) {
                // `val x: Long = id(1)`: an integral upper bound decides, otherwise the literal's default type
                for (u in ups) {
                    if (u is ClassType && !u.nullable) {
                        var ok = true
                        for (l in lows) if (!ops.isSubtype(l, u)) ok = false
                        if (ok && u.cls !== sema.anyType.cls && u.cls.name != "Comparable" && u.cls.name != "Number") return u
                    }
                }
            }
            var r = ops.commonSuperType(lows)
            if (r is TypeParamType && r.definitelyNotNull && !r.nullable) r = TypeParamType(r.tp, false, false)
            if (r is IsectType && ups.isNotEmpty()) {
                for (u in ups) {
                    if (ops.isSubtype(r, u)) continue
                }
            }
            return r
        }
        if (ups.isNotEmpty()) return greatestLower(ups)
        return ErrType("cannot infer " + v.tp.name)
    }

    private fun greatestLower(ts: List<Type>): Type {
        var best: Type? = null
        for (t in ts) {
            if (t is ClassType && t.cls === sema.anyType.cls && t.nullable && ts.size > 1) continue
            if (best == null) {
                best = t
            } else if (ops.isSubtype(t, best)) {
                best = t
            }
        }
        if (best == null) return ts[0]
        var all = true
        for (t in ts) if (!ops.isSubtype(best, t)) all = false
        if (all) return best
        // intersection of the incomparable upper bounds
        val parts = ArrayList<Type>()
        for (t in ts) {
            if (t is ClassType && t.cls === sema.anyType.cls && t.nullable) continue
            parts.add(t)
        }
        if (parts.size == 1) return parts[0]
        return best
    }
}
