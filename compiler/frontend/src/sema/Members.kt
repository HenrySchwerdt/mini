package mini.frontend.sema

import mini.frontend.*

/** A function found as a member of a receiver type, with the substitution of the declaring class' type parameters. */
class MemberFun(val fn: FunSym, val subst: Map<TypeParamSym, Type>, val site: ClassType?)

class MemberProp(val prop: PropSym, val subst: Map<TypeParamSym, Type>, val site: ClassType?)

/**
 * Member lookup with inheritance and override filtering (the member scope of K2: FirClassUseSiteMemberScope,
 * simplified to "the most derived declaration wins per signature").
 */
class Members(private val sema: Sema) {
    private val placeholders = ArrayList<TypeParamSym>()
    private val invokeCache = HashMap<Int, FunSym>()

    private fun placeholder(i: Int): TypeParamSym {
        while (placeholders.size <= i) placeholders.add(TypeParamSym("#" + placeholders.size, Variance.INV, false, null, placeholders.size))
        return placeholders[i]
    }

    /** a key equal for functions with the same signature (parameter and receiver types) after substitution */
    private fun sigKey(f: FunSym, subst: Map<TypeParamSym, Type>): String {
        sema.ensureSig(f)
        val m = HashMap<TypeParamSym, Type>()
        m.putAll(subst)
        var i = 0
        for (tp in f.typeParams) {
            m[tp] = TypeParamType(placeholder(i), false, false)
            i++
        }
        val sb = StringBuilder(f.name)
        sb.append('(')
        val r = f.receiver
        if (r != null) sb.append(renderType(sema.ops.substitute(r, m))).append('.')
        for (p in f.params) {
            val t = p.type
            if (t != null) sb.append(renderType(sema.ops.substitute(t, m)))
            sb.append(',')
        }
        sb.append(')')
        return sb.toString()
    }

    fun ensureSynth(c: ClassSym) {
        if (c.membersDone) return
        c.membersDone = true
        val tf = c.thisType()
        if (c.isData) {
            val pc = c.primaryCtor
            if (pc != null) {
                sema.ensureSig(pc)
                var i = 1
                for (p in pc.params) {
                    if (p.decl?.valVar != null) {
                        val f = FunSym("component$i", null, c, c.file)
                        f.kind = FunKind.SYNTH
                        f.sigState = 2
                        f.retState = 2
                        f.returnType = p.type
                        f.params = emptyList()
                        f.scope = c.bodyScope!!
                        f.start = c.start
                        c.collectorAdd(f)
                        i++
                    }
                }
                val cp = FunSym("copy", null, c, c.file)
                cp.kind = FunKind.SYNTH
                cp.sigState = 2
                cp.retState = 2
                val ps = ArrayList<VarSym>()
                for (p in pc.params) {
                    val v = VarSym(p.name, p.type, false, true, p.declStart)
                    v.hasDefault = true
                    ps.add(v)
                }
                cp.params = ps
                cp.returnType = tf
                cp.scope = c.bodyScope!!
                c.collectorAdd(cp)
            }
            addSynthAny(c, "toString", emptyList(), sema.stringType, tf)
            addSynthAny(c, "hashCode", emptyList(), sema.intType, tf)
            addSynthAny(c, "equals", listOf(sema.nullableAny), sema.booleanType, tf)
        }
        if (c.isEnum) {
            val arr = ClassType(sema.builtin("kotlin.Array"), listOf(TArg(tf, Variance.INV)), false)
            val vf = FunSym("values", null, c, c.file)
            vf.kind = FunKind.SYNTH
            vf.sigState = 2
            vf.retState = 2
            vf.returnType = arr
            vf.scope = c.bodyScope!!
            c.collectorAdd(vf)
            val vo = FunSym("valueOf", null, c, c.file)
            vo.kind = FunKind.SYNTH
            vo.sigState = 2
            vo.retState = 2
            vo.params = listOf(VarSym("value", sema.stringType, false, true, -1))
            vo.returnType = tf
            vo.scope = c.bodyScope!!
            c.collectorAdd(vo)
            val ec = sema.classNamed("kotlin.enums.EnumEntries")
            if (ec != null) {
                val ep = PropSym("entries", null, null, c, c.file)
                ep.synthetic = true
                ep.sigState = 2
                ep.typeState = 2
                ep.type = ClassType(ec, listOf(TArg(tf, Variance.INV)), false)
                ep.scope = c.bodyScope!!
                var l = c.props["entries"]
                if (l == null) {
                    l = ArrayList()
                    c.props["entries"] = l
                }
                l.add(ep)
            }
        }
    }

    private fun addSynthAny(c: ClassSym, name: String, params: List<Type>, ret: Type, tf: ClassType) {
        val existing = c.funs[name]
        if (existing != null) {
            for (e in existing) {
                sema.ensureSig(e)
                if (e.params.size == params.size) return
            }
        }
        val f = FunSym(name, null, c, c.file)
        f.kind = FunKind.SYNTH
        f.sigState = 2
        f.retState = 2
        val ps = ArrayList<VarSym>()
        for (p in params) ps.add(VarSym("other", p, false, true, -1))
        f.params = ps
        f.returnType = ret
        f.scope = c.bodyScope!!
        c.collectorAdd(f)
    }

    /** the member functions named [name] of a value of type [t] */
    fun funsNamed(t: Type, name: String): List<MemberFun> {
        val out = ArrayList<MemberFun>()
        if (t is FunType && name == "invoke") {
            out.add(MemberFun(invokeFor(t), HashMap(), null))
            return out
        }
        val keys = HashSet<String>()
        for (site in sema.ops.closure(t)) {
            ensureSynth(site.cls)
            val l = site.cls.funs[name] ?: continue
            val m = sema.ops.substOf(site)
            for (f in l) {
                val k = sigKey(f, m)
                if (keys.contains(k)) continue
                keys.add(k)
                out.add(MemberFun(f, m, site))
            }
        }
        return out
    }

    fun propsNamed(t: Type, name: String): List<MemberProp> {
        val out = ArrayList<MemberProp>()
        val keys = HashSet<String>()
        for (site in sema.ops.closure(t)) {
            ensureSynth(site.cls)
            val l = site.cls.props[name] ?: continue
            val m = sema.ops.substOf(site)
            for (p in l) {
                sema.ensurePropSig(p)
                val k = if (p.receiver != null) renderType(p.receiver!!) else ""
                if (keys.contains(k)) continue
                keys.add(k)
                out.add(MemberProp(p, m, site))
            }
        }
        return out
    }

    fun invokeFor(t: FunType): FunSym {
        val key = t.arity * 2 + (if (t.suspend) 1 else 0)
        val c = invokeCache[key]
        if (c != null) return c
        val f = FunSym("invoke", null, null, null)
        f.kind = FunKind.FUNCTION_INVOKE
        f.invokeArity = t.arity
        f.invokeSuspend = t.suspend
        f.sigState = 2
        f.retState = 2
        invokeCache[key] = f
        return f
    }

    /** an entry or nested classifier or companion access by name on a class (static scope) */
    fun staticFuns(c: ClassSym, name: String): List<FunSym> {
        ensureSynth(c)
        val l = c.funs[name] ?: return emptyList()
        return l
    }
}

fun ClassSym.collectorAdd(f: FunSym) {
    var l = funs[f.name]
    if (l == null) {
        l = ArrayList()
        funs[f.name] = l
    }
    l.add(f)
}
