package mini.frontend.sema

import mini.frontend.*

/*
 * Pass 1: creates the symbols (packages, classes, functions, properties, type parameters, enum entries) from the
 * AST. Nothing is resolved here; types of declarations are filled lazily by [Sema].
 */
class Collector(private val sema: Sema) {

    fun collectFile(f: FileInfo) {
        val imports = ArrayList<ImportInfo>()
        for (i in f.ast.imports) imports.add(ImportInfo(i.path, i.star, i.alias))
        val fs = FileScope(f, imports)
        f.scope = fs
        val pkg = sema.packageInfo(f.pkg)
        for (d in f.ast.declarations) {
            when (d) {
                is ClassDecl -> {
                    val c = collectClass(d, null, f.pkg, f, fs, false)
                    pkg.classes[c.name] = c
                }
                is FunDecl -> {
                    val fn = collectFun(d, null, f, fs)
                    addTo(pkg.funs, fn.name, fn)
                }
                is PropertyDecl -> {
                    val p = collectProp(d, null, f, fs)
                    addTo(pkg.props, p.name, p)
                }
                is TypeAliasDecl -> {
                    val a = TypeAliasSym(d.name, f.pkg, d, null, f)
                    a.scope = fs
                    var i = 0
                    for (tp in d.typeParams) {
                        a.typeParams.add(typeParam(tp, i))
                        i++
                    }
                    pkg.aliases[d.name] = a
                }
                else -> sema.unsupported(f, d.start, "top-level declaration " + d::class.simpleName)
            }
        }
    }

    fun <T> addTo(m: LinkedHashMap<String, ArrayList<T>>, name: String, v: T) {
        var l = m[name]
        if (l == null) {
            l = ArrayList()
            m[name] = l
        }
        l.add(v)
    }

    private fun typeParam(tp: TypeParam, index: Int): TypeParamSym {
        val m = tp.mods
        var v = Variance.INV
        var reified = false
        if (m != null) {
            if (m.has("out")) v = Variance.OUT
            if (m.has("in")) v = Variance.IN
            if (m.has("reified")) reified = true
        }
        return TypeParamSym(tp.name, v, reified, tp, index)
    }

    private fun typeParams(list: List<TypeParam>, constraints: List<TypeConstraint>, out: ArrayList<TypeParamSym>) {
        var i = 0
        for (tp in list) {
            val s = typeParam(tp, i)
            val cs = ArrayList<TypeConstraint>()
            for (c in constraints) if (c.name == tp.name) cs.add(c)
            s.constraintsDecl = cs
            out.add(s)
            i++
        }
    }

    fun collectClass(d: ClassDecl, outer: ClassSym?, pkg: String, file: FileInfo, scope: Scope, local: Boolean): ClassSym {
        val name = d.name ?: "Companion"
        val c = ClassSym(name, d.kind, pkg, outer, d, file)
        sema.declSyms[d] = c
        c.start = d.start
        c.isLocal = local
        c.scope = scope
        typeParams(d.typeParams, d.constraints, c.typeParams)
        for (tp in c.typeParams) tp.ownerDescription = name
        val header = TypeParamScope(c.typeParams, scope)
        c.headerScope = header
        for (tp in c.typeParams) tp.scope = header
        val body = ClassScope(c, header, true)
        c.bodyScope = body

        // primary constructor
        val pc = d.primaryCtor
        if (d.kind == ClassKind.CLASS) {
            if (pc != null || (!hasSecondary(d))) {
                val f = FunSym("<init>", null, c, file)
                f.kind = FunKind.CONSTRUCTOR
                f.isPrimary = true
                f.scope = header
                if (pc != null) {
                    f.primaryParams = pc.params
                    f.primaryMods = pc.mods
                }
                f.start = if (pc != null) pc.start else d.start
                c.primaryCtor = f
                c.ctors.add(f)
                if (pc != null) {
                    for (p in pc.params) {
                        if (p.valVar != null) {
                            val ps = PropSym(p.name, null, p, c, file)
                            ps.isVar = p.valVar == "var"
                            ps.scope = header
                            addTo(c.props, p.name, ps)
                        }
                    }
                }
            }
        }
        // enum entries
        for (e in d.enumEntries) {
            val es = EnumEntrySym(e.name, c, e)
            c.entries.add(es)
        }
        for (m in d.members) collectMember(m, c, file, body)
        for (e in d.enumEntries) {
            val ms = e.members
            if (ms != null) {
                // entry with a body: an anonymous subclass of the enum, collected as an anonymous class
                val bodyDecl = ClassDecl(null, ClassKind.CLASS, e.name, emptyList(), null, emptyList(), emptyList(), emptyList(), ms, true)
                bodyDecl.start = e.start
                bodyDecl.end = e.end
                val ec = collectClass(bodyDecl, c, pkg, file, body, false)
                ec.isEnumEntryBody = true
                ec.isAnonymous = true
                sema.enumEntryBodies[e] = ec
            }
        }
        return c
    }

    private fun hasSecondary(d: ClassDecl): Boolean {
        for (m in d.members) if (m is SecondaryCtor) return true
        return false
    }

    fun collectMember(m: Decl, c: ClassSym, file: FileInfo, body: ClassScope) {
        when (m) {
            is ClassDecl -> {
                val inner = collectClass(m, c, c.pkg, file, ClassScope(c, c.headerScope, m.mods?.has("inner") == true), false)
                // the scope a nested class sees: the enclosing class, with an instance only for inner classes
                if (m.mods?.has("companion") == true) c.companion = inner
                c.nested[inner.name] = inner
            }
            is FunDecl -> {
                val f = collectFun(m, c, file, body)
                addTo(c.funs, f.name, f)
            }
            is PropertyDecl -> {
                val p = collectProp(m, c, file, body)
                addTo(c.props, p.name, p)
            }
            is SecondaryCtor -> {
                val f = FunSym("<init>", null, c, file)
                f.kind = FunKind.CONSTRUCTOR
                f.secondary = m
                sema.declSyms[m] = f
                f.scope = c.headerScope ?: body
                f.start = m.start
                c.ctors.add(f)
            }
            is TypeAliasDecl -> sema.unsupported(file, m.start, "nested typealias")
            is InitDecl, is DestructuringDecl, is EnumEntryDecl -> {}
        }
    }

    fun collectFun(d: FunDecl, owner: ClassSym?, file: FileInfo, scope: Scope): FunSym {
        val f = FunSym(d.name ?: "<anonymous>", d, owner, file)
        sema.declSyms[d] = f
        f.start = d.start
        typeParams(d.typeParams, d.constraints, f.typeParams)
        val tpScope = TypeParamScope(f.typeParams, scope)
        for (tp in f.typeParams) {
            tp.scope = tpScope
            tp.ownerDescription = f.name
        }
        f.scope = scope
        return f
    }

    fun collectProp(d: PropertyDecl, owner: ClassSym?, file: FileInfo, scope: Scope): PropSym {
        val p = PropSym(d.name, d, null, owner, file)
        sema.declSyms[d] = p
        p.isVar = d.isVar
        p.start = d.start
        typeParams(d.typeParams, d.constraints, p.typeParams)
        val tpScope = TypeParamScope(p.typeParams, scope)
        for (tp in p.typeParams) tp.scope = tpScope
        p.scope = scope
        return p
    }
}
