package mini.frontend.sema

/*
 * Type algebra: substitution, supertype closure, subtyping, common supertypes, smart-cast narrowing.
 * Algorithms are simplified versions of the ones in K2 (compiler/resolution.common/.../model/ and
 * compiler/fir/semantics/.../ConeTypeContext, AbstractTypeChecker, NewCommonSuperTypeCalculator).
 */
class TypeOps(private val sema: Sema) {

    // ---- substitution --------------------------------------------------------------------------------------------

    fun substitute(t: Type, m: Map<TypeParamSym, Type>): Type {
        if (m.isEmpty()) return t
        when (t) {
            is TypeParamType -> {
                val r = m[t.tp] ?: return t
                if (t.definitelyNotNull) return makeNotNull(r)
                return if (t.nullable) makeNullable(r) else r
            }
            is ClassType -> {
                if (t.args.isEmpty()) return t
                val args = ArrayList<TArg>()
                for (a in t.args) args.add(substArg(a, m))
                return ClassType(t.cls, args, t.nullable)
            }
            is FunType -> {
                val r = t.receiver
                val ps = ArrayList<Type>()
                for (p in t.params) ps.add(substitute(p, m))
                return FunType(if (r != null) substitute(r, m) else null, ps, substitute(t.ret, m), t.nullable, t.suspend, t.contexts)
            }
            is IsectType -> {
                val ps = ArrayList<Type>()
                for (p in t.parts) ps.add(substitute(p, m))
                return IsectType(ps)
            }
            else -> return t
        }
    }

    fun substArg(a: TArg, m: Map<TypeParamSym, Type>): TArg {
        val t = a.type ?: return a
        val r = substitute(t, m)
        // `out`/`in` arguments substituted by projections keep the use-site variance of the position
        return TArg(r, a.variance)
    }

    fun makeNullable(t: Type): Type = t.withNullable(true)

    fun makeNotNull(t: Type): Type {
        if (t is TypeParamType) {
            if (t.definitelyNotNull) return t
            if (t.nullable) return TypeParamType(t.tp, false, true)
            if (hasNullableBound(t.tp)) return TypeParamType(t.tp, false, true)
            return t
        }
        return t.withNullable(false)
    }

    fun hasNullableBound(tp: TypeParamSym): Boolean {
        sema.ensureBounds(tp)
        if (tp.bounds.isEmpty()) return true
        for (b in tp.bounds) {
            if (b is TypeParamType) {
                if (!b.nullable && !hasNullableBound(b.tp)) return false
            } else if (!b.nullable) return false
        }
        return true
    }

    /** what a star-projected type parameter stands for when a member is accessed: its upper bound, else `Any?` */
    fun starBound(tp: TypeParamSym): Type {
        sema.ensureBounds(tp)
        if (tp.bounds.size == 1) {
            val b = tp.bounds[0]
            if (b is ClassType && b.args.isEmpty()) return b
        }
        return sema.nullableAny
    }

    /** substitution of the type parameters of [c] by the arguments of [t] (star projections become the bound, `Any?` here) */
    fun substOf(t: ClassType): Map<TypeParamSym, Type> {
        val m = HashMap<TypeParamSym, Type>()
        var i = 0
        for (tp in t.cls.typeParams) {
            if (i < t.args.size) {
                val a = t.args[i]
                val at = a.type
                if (at != null && (a.variance == Variance.INV || a.variance == tp.variance)) m[tp] = at
                else if (at == null) m[tp] = starBound(tp)
                else m[tp] = CapType(a, false)
            }
            i++
        }
        return m
    }

    // ---- supertypes ----------------------------------------------------------------------------------------------

    /** direct supertypes of a type, instantiated (class types only; `Any` last) */
    fun directSupertypes(t: Type): List<Type> {
        val out = ArrayList<Type>()
        when (t) {
            is ClassType -> {
                sema.ensureSupertypes(t.cls)
                val m = substOf(t)
                for (s in t.cls.supertypes) out.add(substitute(s, m))
            }
            is TypeParamType -> {
                sema.ensureBounds(t.tp)
                for (b in t.tp.bounds) out.add(b)
                if (out.isEmpty()) out.add(sema.nullableAny)
            }
            is FunType -> {
                val fn = sema.classNamed("kotlin.Function")
                if (fn != null) out.add(ClassType(fn, listOf(TArg(t.ret, Variance.OUT)), false))
            }
            is IsectType -> for (p in t.parts) out.add(p)
            is CapType -> out.add(capUpper(t))
            else -> {}
        }
        return out
    }

    /**
     * All class-type supertypes of [t] including itself (nullability dropped), nearest first, each class once.
     * `Any` is appended when missing so that its members are found on every type.
     */
    fun closure(t: Type): List<ClassType> {
        val out = ArrayList<ClassType>()
        val seen = HashSet<ClassSym>()
        collectClosure(t, out, seen, 0)
        val any = sema.anyType
        if (!seen.contains(any.cls)) out.add(any)
        return out
    }

    private fun collectClosure(t: Type, out: ArrayList<ClassType>, seen: HashSet<ClassSym>, depth: Int) {
        if (depth > 40) return
        if (t is ClassType) {
            if (seen.contains(t.cls)) return
            seen.add(t.cls)
            out.add(t.withNullable(false) as ClassType)
        }
        val direct = directSupertypes(t)
        // superclasses before interfaces
        for (s in direct) if (s is ClassType && !s.cls.isInterface) collectClosure(s, out, seen, depth + 1)
        for (s in direct) if (s !is ClassType || s.cls.isInterface) collectClosure(s, out, seen, depth + 1)
    }

    /** The instantiation of class [target] among the supertypes of [t], or null. */
    fun asSuper(t: Type, target: ClassSym): ClassType? {
        for (c in closure(t)) if (c.cls === target) return c
        return null
    }

    fun capUpper(t: CapType): Type {
        val x = t.arg.type
        val r = if (x != null && t.arg.variance == Variance.OUT) x else sema.nullableAny
        return if (t.nullable) r.withNullable(true) else r
    }

    /** replaces captured types by what K2 infers for declarations: the upper bound / a projection */
    fun approximate(t: Type): Type {
        when (t) {
            is CapType -> return approximate(capUpper(t))
            is ClassType -> {
                if (t.args.isEmpty()) return t
                var changed = false
                val args = ArrayList<TArg>()
                var i2 = -1
                for (a in t.args) {
                    i2++
                    val x = a.type
                    if (x is CapType) {
                        changed = true
                        val ax = x.arg.type
                        if (ax == null) args.add(TArg(null, Variance.INV)) else args.add(TArg(approximate(ax), x.arg.variance))
                        if (i2 < t.cls.typeParams.size && ax != null && x.arg.variance == t.cls.typeParams[i2].variance) {
                            args[args.size - 1] = TArg(approximate(ax), Variance.INV)
                        }
                    } else if (x != null) {
                        val n = approximate(x)
                        if (n !== x) changed = true
                        args.add(TArg(n, a.variance))
                    } else args.add(a)
                }
                return if (changed) ClassType(t.cls, args, t.nullable) else t
            }
            is FunType -> {
                val r = t.receiver
                val ps = ArrayList<Type>()
                for (p in t.params) ps.add(approximate(p))
                return FunType(if (r != null) approximate(r) else null, ps, approximate(t.ret), t.nullable, t.suspend, t.contexts)
            }
            else -> return t
        }
    }

    // ---- subtyping -------------------------------------------------------------------------------------------------

    fun isSubtype(a: Type, b: Type): Boolean = sub(a, b, 0)

    private fun isAnyNullable(t: Type): Boolean = t is ClassType && t.nullable && t.cls === sema.anyType.cls

    private fun sub(a: Type, b: Type, depth: Int): Boolean {
        if (depth > 30) return true
        if (a is ErrType || b is ErrType) return true
        if (a === b) return true
        if (a is VarType || b is VarType) return true
        if (a is IntLiteralType) return subLiteral(a, b)
        if (b is IntLiteralType) return false
        if (a is CapType) return sub(capUpper(a), b, depth + 1)
        if (b is CapType) {
            val x = b.arg.type
            if (x != null && b.arg.variance == Variance.IN) return sub(a, x, depth + 1)
            return true
        }
        if (b is IsectType) {
            for (p in b.parts) if (!sub(a, p, depth + 1)) return false
            return true
        }
        if (a is IsectType) {
            for (p in a.parts) if (sub(p, b, depth + 1)) return true
            return false
        }
        // Nothing and Nothing?
        if (a is ClassType && a.cls === sema.nothingType.cls) {
            if (!a.nullable) return true
            return b.nullable || isNullableLike(b)
        }
        if (isAnyNullable(b)) return true
        if (a.nullable && !b.nullable && !isNullableLike(b)) return false
        if (a is TypeParamType && a.nullable && b is TypeParamType && !b.nullable && b.tp === a.tp) return false
        when (b) {
            is TypeParamType -> {
                if (a is TypeParamType) {
                    if (a.tp === b.tp) {
                        if (b.definitelyNotNull) return a.definitelyNotNull || !a.nullable
                        return true
                    }
                    return boundsSub(a, b, depth)
                }
                return false
            }
            is ClassType -> {
                if (a is TypeParamType) return boundsSub(a, b, depth)
                if (a is FunType) {
                    if (b.cls === sema.anyType.cls) return true
                    val fn = sema.classNamed("kotlin.Function")
                    return fn != null && b.cls === fn
                }
                if (a is ClassType) {
                    val s = asSuper(a, b.cls) ?: return false
                    return argsSub(s, b, depth)
                }
                return false
            }
            is FunType -> {
                if (a is FunType) return funSub(a, b, depth)
                if (a is ClassType) {
                    val af = asFunType(a)
                    if (af != null) return funSub(af, b, depth)
                    return false
                }
                if (a is TypeParamType) return boundsSub(a, b, depth)
                return false
            }
            else -> return false
        }
    }

    private fun isNullableLike(t: Type): Boolean = false

    private fun boundsSub(a: TypeParamType, b: Type, depth: Int): Boolean {
        sema.ensureBounds(a.tp)
        if (a.tp.bounds.isEmpty()) return b is ClassType && b.cls === sema.anyType.cls && (b.nullable || a.definitelyNotNull)
        for (bound in a.tp.bounds) {
            var bd = bound
            if (a.nullable && !a.definitelyNotNull) bd = bd.withNullable(true)
            if (a.definitelyNotNull) bd = makeNotNull(bd)
            if (sub(bd, b, depth + 1)) return true
        }
        return false
    }

    private fun funSub(a: FunType, b: FunType, depth: Int): Boolean {
        if (a.params.size != b.params.size) return false
        if ((a.receiver == null) != (b.receiver == null)) {
            // `A.(B) -> R` and `(A, B) -> R` are interchangeable as values
            if (a.arity != b.arity) return false
            val pa = allParams(a)
            val pb = allParams(b)
            var i = 0
            while (i < pa.size) {
                if (!sub(pb[i], pa[i], depth + 1)) return false
                i++
            }
            return sub(a.ret, b.ret, depth + 1)
        }
        val ra = a.receiver
        val rb = b.receiver
        if (ra != null && rb != null && !sub(rb, ra, depth + 1)) return false
        var i = 0
        while (i < a.params.size) {
            if (!sub(b.params[i], a.params[i], depth + 1)) return false
            i++
        }
        return sub(a.ret, b.ret, depth + 1)
    }

    /** the function type a KFunctionN / KPropertyN class type stands for */
    fun asFunType(t: Type): FunType? {
        if (t is ClassType && t.cls.fnArity >= 0) {
            val ps = ArrayList<Type>()
            var i = 0
            while (i < t.cls.fnArity) {
                ps.add(t.args[i].type ?: sema.nullableAny)
                i++
            }
            return FunType(null, ps, t.args[t.args.size - 1].type ?: sema.nullableAny, t.nullable, false)
        }
        if (t is ClassType) return funSuper(t, 0)
        return null
    }

    private fun funSuper(t: ClassType, depth: Int): FunType? {
        if (depth > 5 || t.cls.supertypes.isEmpty() && t.cls.supertypeState == 2) return null
        for (s in directSupertypes(t)) {
            if (s is FunType) return s
            if (s is ClassType && s.cls.fnArity < 0) {
                val r = funSuper(s, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    fun allParams(f: FunType): List<Type> {
        val l = ArrayList<Type>()
        val r = f.receiver
        if (r != null) l.add(r)
        l.addAll(f.params)
        return l
    }

    private fun argsSub(s: ClassType, b: ClassType, depth: Int): Boolean {
        var i = 0
        while (i < b.args.size && i < s.args.size) {
            val ba = b.args[i]
            val sa = s.args[i]
            val bt = ba.type
            if (bt != null) {
                val st = sa.type
                val tp = b.cls.typeParams[i]
                val v = if (ba.variance != Variance.INV) ba.variance else tp.variance
                if (st == null) {
                    // star projection in the subtype: only fine for `out` positions with an `Any?` supertype argument
                    if (!(v == Variance.OUT && isAnyNullable(bt))) return false
                } else {
                    val sv = if (sa.variance != Variance.INV) sa.variance else Variance.INV
                    when (v) {
                        Variance.OUT -> if (!sub(st, bt, depth + 1)) return false
                        Variance.IN -> if (!sub(bt, st, depth + 1)) return false
                        Variance.INV -> {
                            if (sv == Variance.OUT && sa.variance == Variance.OUT) return false
                            if (sv == Variance.IN) return false
                            if (!sub(st, bt, depth + 1) || !sub(bt, st, depth + 1)) return false
                        }
                    }
                }
            }
            i++
        }
        return true
    }

    private fun subLiteral(a: IntLiteralType, b: Type): Boolean {
        if (b is ClassType) {
            if (b.cls === sema.anyType.cls) return true
            val n = b.cls.fqName()
            if (b.nullable || true) {
                if (n == "kotlin.Int") return a.fitsInt
                if (n == "kotlin.Long") return true
                if (n == "kotlin.Short") return a.value >= -32768 && a.value <= 32767
                if (n == "kotlin.Byte") return a.value >= -128 && a.value <= 127
                if (n == "kotlin.Number" || n == "kotlin.Comparable") return true
            }
            return false
        }
        if (b is IsectType) {
            for (p in b.parts) if (!subLiteral(a, p)) return false
            return true
        }
        if (b is TypeParamType) return false
        return false
    }

    /** the default type of an integer literal type */
    fun literalDefault(t: IntLiteralType): Type = if (t.fitsInt) sema.intType else sema.longType

    // ---- common supertype (LUB) -----------------------------------------------------------------------------------

    fun commonSuperType(types: List<Type>): Type {
        if (types.isEmpty()) return sema.nothingType
        var list = ArrayList<Type>()
        for (t in types) {
            if (t is ErrType) return t
            list.add(if (t is IntLiteralType) literalDefault(t) else t)
        }
        // Nothing is neutral
        val nonNothing = ArrayList<Type>()
        var anyNullable = false
        for (t in list) {
            if (t is ClassType && t.cls === sema.nothingType.cls) {
                if (t.nullable) anyNullable = true
                continue
            }
            nonNothing.add(t)
        }
        if (nonNothing.isEmpty()) return if (anyNullable) sema.nullableNothing else sema.nothingType
        var nullable = anyNullable
        for (t in nonNothing) if (t.nullable) nullable = true
        val nn = ArrayList<Type>()
        for (t in nonNothing) nn.add(if (t is TypeParamType) t.withNullable(false) else t.withNullable(false))
        val r = cst(nn, 0)
        return if (nullable) r.withNullable(true) else r
    }

    private fun cst(ts: List<Type>, depth: Int): Type {
        if (ts.size == 1) return ts[0]
        // one of them is a supertype of all others
        for (c in ts) {
            var all = true
            for (o in ts) if (!isSubtype(o, c)) all = false
            if (all) return c
        }
        if (depth > 3) return sema.anyType
        // type parameters / function types / intersections: go through their bounds
        val expanded = ArrayList<Type>()
        for (t in ts) expanded.add(t)
        // candidate classes: supertypes of the first that are supertypes of all
        val first = closure(expanded[0])
        val cands = ArrayList<ClassType>()
        for (c in first) {
            var inAll = true
            for (k in 1 until expanded.size) {
                if (asSuper(expanded[k], c.cls) == null) inAll = false
            }
            if (inAll) cands.add(c)
        }
        // keep minimal candidates
        val minimal = ArrayList<ClassType>()
        for (c in cands) {
            var hasSub = false
            for (o in cands) {
                if (o !== c && o.cls !== c.cls && asSuper(o, c.cls) != null) hasSub = true
            }
            if (!hasSub) minimal.add(c)
        }
        val results = ArrayList<Type>()
        for (c in minimal) {
            if (c.cls === sema.anyType.cls && minimal.size > 1) continue
            results.add(mergeArgs(c, expanded, depth))
        }
        if (results.isEmpty()) return sema.anyType
        if (results.size == 1) return results[0]
        return IsectType(results)
    }

    private fun mergeArgs(c: ClassType, ts: List<Type>, depth: Int): Type {
        if (c.args.isEmpty()) return c
        val insts = ArrayList<ClassType>()
        for (t in ts) {
            val s = asSuper(t, c.cls)
            if (s != null) insts.add(s)
        }
        val args = ArrayList<TArg>()
        var i = 0
        while (i < c.args.size) {
            val tp = c.cls.typeParams[i]
            val ats = ArrayList<Type>()
            var star = false
            var allEqual = true
            var firstT: Type? = null
            for (s in insts) {
                val a = s.args[i]
                val at = a.type
                if (at == null) {
                    star = true
                } else {
                    ats.add(at)
                    if (firstT == null) firstT = at else if (!typeEquals(firstT, at)) allEqual = false
                }
            }
            if (star) {
                args.add(TArg(null, Variance.INV))
            } else if (allEqual && firstT != null) {
                args.add(TArg(firstT, if (c.args[i].variance != Variance.INV) c.args[i].variance else Variance.INV))
            } else if (tp.variance == Variance.OUT) {
                args.add(TArg(cst(ats, depth + 1), Variance.INV))
            } else if (tp.variance == Variance.IN) {
                args.add(TArg(null, Variance.INV))
            } else {
                if (depth > 2) args.add(TArg(null, Variance.INV)) else args.add(TArg(cst(ats, depth + 1), Variance.OUT))
            }
            i++
        }
        return ClassType(c.cls, args, false)
    }

    // ---- smart casts -------------------------------------------------------------------------------------------------

    /** the type of a value of type [orig] known to also have all [casts] */
    fun narrow(orig: Type, casts: List<Type>): Type {
        if (casts.isEmpty()) return orig
        var parts = ArrayList<Type>()
        parts.add(orig)
        for (c in casts) {
            // `x != null` is the cast to Any
            if (c is ClassType && c.cls === sema.anyType.cls && !c.nullable) {
                val np = ArrayList<Type>()
                for (p in parts) np.add(if (p.nullable) makeNotNull(p) else p)
                parts = np
                continue
            }
            parts.add(c)
        }
        // drop parts that are supertypes of other parts
        val keep = ArrayList<Type>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            var redundant = false
            var j = 0
            while (j < parts.size) {
                if (i != j) {
                    val q = parts[j]
                    val ps = isSubtype(q, p)
                    val qs = isSubtype(p, q)
                    if (ps && (!qs || j < i)) redundant = true
                }
                j++
            }
            if (!redundant) keep.add(p)
            i++
        }
        if (keep.isEmpty()) return orig
        if (keep.size == 1) return keep[0]
        return IsectType(keep)
    }

    fun isNothing(t: Type): Boolean = t is ClassType && t.cls === sema.nothingType.cls && !t.nullable

    fun isUnit(t: Type): Boolean = t is ClassType && t.cls === sema.unitType.cls

    fun isNullable(t: Type): Boolean {
        if (t is TypeParamType && !t.nullable && !t.definitelyNotNull) return hasNullableBound(t.tp) && false
        return t.nullable
    }

    /** `T` or `T?` with a nullable bound may hold null */
    fun mayBeNull(t: Type): Boolean {
        if (t is TypeParamType) {
            if (t.definitelyNotNull) return false
            if (t.nullable) return true
            return hasNullableBound(t.tp)
        }
        if (t is IsectType) {
            for (p in t.parts) if (!mayBeNull(p)) return false
            return true
        }
        return t.nullable
    }
}
