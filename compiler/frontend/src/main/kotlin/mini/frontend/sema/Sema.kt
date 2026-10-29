package mini.frontend.sema

import mini.frontend.*

/** A diagnostic of the semantic analysis (unsupported construct, unresolved reference, type mismatch, ...). */
class Diag(val path: String, val start: Int, val message: String)

/**
 * The semantic model of a set of Kotlin sources: symbol table, scopes, type resolution, lazily resolved signatures.
 * Expression checking lives in [BodyChecker]; type algebra in [TypeOps]; member lookup in [Members].
 */
class Sema {
    val files = ArrayList<FileInfo>()
    val packages = LinkedHashMap<String, PackageInfo>()
    val diags = ArrayList<Diag>()
    val enumEntryBodies = HashMap<EnumEntryDecl, ClassSym>()
    val declSyms = HashMap<Ast, Sym>()
    val extraNodes = ArrayList<Ast>()
    val fileNodes = HashMap<Int, ArrayList<Ast>>()
    val ops = TypeOps(this)
    val members = Members(this)
    val collector = Collector(this)
    val records = Records()
    private val ownerCache = HashMap<String, Any?>()
    private val classCache = HashMap<String, ClassSym?>()
    var debug = false

    fun packageInfo(name: String): PackageInfo {
        var p = packages[name]
        if (p == null) {
            p = PackageInfo(name)
            packages[name] = p
        }
        return p
    }

    fun unsupported(f: FileInfo?, pos: Int, what: String) {
        diags.add(Diag(f?.path ?: "?", pos, "unsupported: $what"))
    }

    fun error(f: FileInfo?, pos: Int, what: String) {
        diags.add(Diag(f?.path ?: "?", pos, what))
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    fun addFile(path: String, text: String): FileInfo {
        val r = parseKotlin(text)
        val f = FileInfo(path, text, r.file, r.file.packageName ?: "")
        f.index = files.size
        files.add(f)
        for (e in r.errors) diags.add(Diag(path, e.offset, "syntax error: " + e.message))
        collector.collectFile(f)
        return f
    }

    // ---- builtins -----------------------------------------------------------------------------------------------

    fun classNamed(fq: String): ClassSym? {
        if (classCache.containsKey(fq)) return classCache[fq]
        var found: ClassSym? = null
        val i = fq.lastIndexOf('.')
        val pkg = if (i < 0) "" else fq.substring(0, i)
        val name = if (i < 0) fq else fq.substring(i + 1)
        val p = packages[pkg]
        if (p != null) found = p.classes[name]
        classCache[fq] = found
        return found
    }

    fun builtin(fq: String): ClassSym {
        val c = classNamed(fq)
        if (c != null) return c
        throw IllegalStateException("builtin class $fq is missing (is the stdlib part of the input?)")
    }

    private var anyT: ClassType? = null
    private var nothingT: ClassType? = null
    private var unitT: ClassType? = null
    private var intT: ClassType? = null
    private var longT: ClassType? = null
    private var booleanT: ClassType? = null
    private var stringT: ClassType? = null
    private var charT: ClassType? = null
    private var doubleT: ClassType? = null

    fun cls0(c: ClassSym): ClassType = ClassType(c, emptyList(), false)

    val anyType: ClassType
        get() {
            var t = anyT
            if (t == null) {
                t = cls0(builtin("kotlin.Any"))
                anyT = t
            }
            return t
        }
    val nullableAny: Type get() = anyType.withNullable(true)
    val nothingType: ClassType
        get() {
            var t = nothingT
            if (t == null) {
                t = cls0(builtin("kotlin.Nothing"))
                nothingT = t
            }
            return t
        }
    val nullableNothing: Type get() = nothingType.withNullable(true)
    val unitType: ClassType
        get() {
            var t = unitT
            if (t == null) {
                t = cls0(builtin("kotlin.Unit"))
                unitT = t
            }
            return t
        }
    val intType: ClassType
        get() {
            var t = intT
            if (t == null) {
                t = cls0(builtin("kotlin.Int"))
                intT = t
            }
            return t
        }
    val longType: ClassType
        get() {
            var t = longT
            if (t == null) {
                t = cls0(builtin("kotlin.Long"))
                longT = t
            }
            return t
        }
    val booleanType: ClassType
        get() {
            var t = booleanT
            if (t == null) {
                t = cls0(builtin("kotlin.Boolean"))
                booleanT = t
            }
            return t
        }
    val stringType: ClassType
        get() {
            var t = stringT
            if (t == null) {
                t = cls0(builtin("kotlin.String"))
                stringT = t
            }
            return t
        }
    val charType: ClassType
        get() {
            var t = charT
            if (t == null) {
                t = cls0(builtin("kotlin.Char"))
                charT = t
            }
            return t
        }
    val doubleType: ClassType
        get() {
            var t = doubleT
            if (t == null) {
                t = cls0(builtin("kotlin.Double"))
                doubleT = t
            }
            return t
        }

    fun classTypeOf(fq: String, vararg args: Type): ClassType {
        val a = ArrayList<TArg>()
        for (x in args) a.add(TArg(x, Variance.INV))
        return ClassType(builtin(fq), a, false)
    }

    // ---- callable reference classes ------------------------------------------------------------------------------

    private val kClasses = HashMap<String, ClassSym>()

    /** kotlin.reflect.KFunctionN / KPropertyN / KMutablePropertyN: n parameters (for properties: 0 or 1 receivers) */
    fun kRefClass(base: String, n: Int): ClassSym {
        val key = base + n
        var c = kClasses[key]
        if (c != null) return c
        c = ClassSym(key, ClassKind.INTERFACE, "kotlin.reflect", null, null, null)
        var i = 0
        while (i < n) {
            c.typeParams.add(TypeParamSym("P" + (i + 1), Variance.IN, false, null, i))
            i++
        }
        c.typeParams.add(TypeParamSym(if (base == "KFunction") "R" else "V", Variance.OUT, false, null, n))
        for (tp in c.typeParams) {
            tp.boundsState = 2
        }
        c.fnArity = n
        c.supertypeState = 2
        c.membersDone = true
        kClasses[key] = c
        return c
    }

    // ---- file level name lookup -----------------------------------------------------------------------------------

    private val defaultImports = listOf(
        "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io", "kotlin.ranges",
        "kotlin.sequences", "kotlin.text", "java.lang", "kotlin.jvm",
    )

    /** a package ([PackageInfo]) or a class ([ClassSym]) named by a dotted path, or null */
    fun resolveOwner(path: String): Any? {
        if (ownerCache.containsKey(path)) return ownerCache[path]
        var r: Any? = null
        val p = packages[path]
        if (p != null) {
            r = p
        } else {
            val segs = path.split('.')
            var k = segs.size - 1
            while (k >= 1 && r == null) {
                val pk = segs.subList(0, k).joinToString(".")
                val pi = packages[pk]
                if (pi != null) {
                    var c: ClassSym? = pi.classes[segs[k]]
                    var j = k + 1
                    while (c != null && j < segs.size) {
                        c = c.nested[segs[j]]
                        j++
                    }
                    if (c != null) r = c
                }
                k--
            }
            if (r == null) {
                // root package class
                val pi = packages[""]
                if (pi != null) {
                    var c: ClassSym? = pi.classes[segs[0]]
                    var j = 1
                    while (c != null && j < segs.size) {
                        c = c.nested[segs[j]]
                        j++
                    }
                    if (c != null) r = c
                }
            }
        }
        ownerCache[path] = r
        return r
    }

    private fun membersOfOwner(owner: Any?, name: String, out: ArrayList<Sym>) {
        if (owner is PackageInfo) {
            val c = owner.classes[name]
            if (c != null) out.add(c)
            val a = owner.aliases[name]
            if (a != null) out.add(a)
            val fs = owner.funs[name]
            if (fs != null) for (f in fs) out.add(f)
            val ps = owner.props[name]
            if (ps != null) for (p in ps) out.add(p)
        } else if (owner is ClassSym) {
            val c = owner.nested[name]
            if (c != null) out.add(c)
            val a = owner.aliases[name]
            if (a != null) out.add(a)
            for (e in owner.entries) if (e.name == name) out.add(e)
            if (owner.isObject) {
                val fs = owner.funs[name]
                if (fs != null) for (f in fs) out.add(f)
                val ps = owner.props[name]
                if (ps != null) for (p in ps) out.add(p)
            }
        }
    }

    /**
     * Top-level lookup of [name] seen from a file: one list per priority group (explicit imports, own package,
     * star imports, default imports). Each list holds classes, typealiases, functions, properties and enum entries.
     */
    fun topLevel(file: FileInfo, name: String): List<List<Sym>> {
        val groups = ArrayList<List<Sym>>()
        val fs = file.scope
        val g1 = ArrayList<Sym>()
        for (imp in fs.imports) {
            if (!imp.star && imp.importedName == name) {
                val owner = resolveOwner(imp.qualifier)
                membersOfOwner(owner, imp.originalName, g1)
            }
        }
        groups.add(g1)
        val g2 = ArrayList<Sym>()
        val own = packages[file.pkg]
        if (own != null) membersOfOwner(own, name, g2)
        groups.add(g2)
        val g3 = ArrayList<Sym>()
        for (imp in fs.imports) {
            if (imp.star) {
                val owner = resolveOwner(imp.path)
                membersOfOwner(owner, name, g3)
            }
        }
        groups.add(g3)
        val g4 = ArrayList<Sym>()
        for (dp in defaultImports) {
            val p = packages[dp]
            if (p != null) membersOfOwner(p, name, g4)
        }
        groups.add(g4)
        return groups
    }

    fun fileOf(scope: Scope?): FileInfo? {
        var s = scope
        while (s != null) {
            if (s is FileScope) return s.file
            s = s.parent
        }
        return null
    }

    // ---- classifier lookup and type resolution -------------------------------------------------------------------

    /** the classifier (class, typealias or type parameter) a simple name refers to, or null */
    fun lookupClassifier(scope: Scope, name: String): Sym? {
        var s: Scope? = scope
        while (s != null) {
            when (s) {
                is LocalScope -> {
                    val c = s.classes[name]
                    if (c != null) return c
                }
                is TypeParamScope -> {
                    for (tp in s.tps) if (tp.name == name) return tp
                }
                is ClassScope -> {
                    val n = nestedClassifier(s.cls, name, 0)
                    if (n != null) return n
                    if (s.cls.name == name && s.cls.isLocal) return s.cls
                }
                is FileScope -> {
                    val groups = topLevel(s.file, name)
                    for (g in groups) {
                        for (x in g) {
                            if (x is ClassSym || x is TypeAliasSym) return x
                        }
                    }
                    return null
                }
                is ReceiverScope -> {}
            }
            s = s.parent
        }
        return null
    }

    fun nestedClassifier(c: ClassSym, name: String, depth: Int): Sym? {
        val n = c.nested[name]
        if (n != null) return n
        val a = c.aliases[name]
        if (a != null) return a
        if (depth > 8) return null
        if (c.supertypeState == 1) return null
        ensureSupertypes(c)
        for (st in c.supertypes) {
            if (st is ClassType) {
                val r = nestedClassifier(st.cls, name, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    fun resolveType(ref: TypeRef, scope: Scope): Type {
        val file = fileOf(scope)
        return resolveType0(ref, scope, file)
    }

    private fun resolveType0(ref: TypeRef, scope: Scope, file: FileInfo?): Type {
        when (ref) {
            is NullableType -> return resolveType0(ref.inner, scope, file).withNullable(true)
            is ParenType -> return resolveType0(ref.inner, scope, file)
            is FunctionType -> {
                val recv = ref.receiver
                val rt = if (recv != null) resolveType0(recv, scope, file) else null
                val ps = ArrayList<Type>()
                for (p in ref.params) ps.add(resolveType0(p.type, scope, file))
                val ret = resolveType0(ref.returnType, scope, file)
                val susp = ref.mods?.has("suspend") == true
                return FunType(rt, ps, ret, false, susp)
            }
            is UserType -> return resolveUserType(ref, scope, file)
            is DynamicType -> {
                unsupported(file, ref.start, "dynamic type")
                return ErrType("dynamic")
            }
            is mini.frontend.IntersectionType -> {
                // `T & Any`
                val l = resolveType0(ref.left, scope, file)
                if (l is TypeParamType) return TypeParamType(l.tp, false, true)
                return l
            }
            is mini.frontend.ErrorType -> return ErrType("syntax")
        }
    }

    private fun resolveUserType(ref: UserType, scope: Scope, file: FileInfo?): Type {
        val segs = ref.segments
        var cur: Sym? = lookupClassifier(scope, segs[0].name)
        var idx = 0
        if (cur == null) {
            // qualified by a package
            var k = 1
            while (k < segs.size && cur == null) {
                val sb = StringBuilder()
                var j = 0
                while (j <= k) {
                    if (j > 0) sb.append('.')
                    sb.append(segs[j].name)
                    j++
                }
                val p = packages[sb.toString()]
                if (p != null && k + 1 < segs.size) {
                    val nm = segs[k + 1].name
                    val c: Sym? = p.classes[nm] ?: p.aliases[nm]
                    if (c != null) {
                        cur = c
                        idx = k + 1
                    }
                }
                k++
            }
            if (cur == null) {
                error(file, ref.start, "unresolved type '" + segs[0].name + "'")
                return ErrType("unresolved " + segs[0].name)
            }
        }
        var i = idx + 1
        while (i < segs.size) {
            val c = cur
            if (c is ClassSym) {
                val n = nestedClassifier(c, segs[i].name, 0)
                if (n == null) {
                    error(file, ref.start, "unresolved nested type '" + segs[i].name + "'")
                    return ErrType("unresolved " + segs[i].name)
                }
                cur = n
            } else {
                error(file, ref.start, "bad qualified type")
                return ErrType("qualified")
            }
            i++
        }
        val last = segs[i - 1]
        val args = ArrayList<TArg>()
        for (a in last.args) {
            val t = a.type
            if (a.star || t == null) {
                args.add(TArg(null, Variance.INV))
            } else {
                val v = if (a.variance == "out") Variance.OUT else if (a.variance == "in") Variance.IN else Variance.INV
                args.add(TArg(resolveType0(t, scope, file), v))
            }
        }
        val c = cur
        when (c) {
            is TypeParamSym -> return TypeParamType(c, false, false)
            is ClassSym -> {
                if (args.size != c.typeParams.size) {
                    if (args.isEmpty()) {
                        // `Foo` for a generic class where the arguments cannot be left out: tolerated as star projections
                        val st = ArrayList<TArg>()
                        for (tp in c.typeParams) st.add(TArg(null, Variance.INV))
                        error(file, ref.start, "type arguments expected for " + c.name)
                        return ClassType(c, st, false)
                    }
                    error(file, ref.start, "wrong number of type arguments for " + c.name)
                    return ErrType("type argument count")
                }
                return ClassType(c, args, false)
            }
            is TypeAliasSym -> {
                val e = expandAlias(c)
                if (args.size != c.typeParams.size) {
                    error(file, ref.start, "wrong number of type arguments for typealias " + c.name)
                    return ErrType("alias args")
                }
                val m = HashMap<TypeParamSym, Type>()
                var k = 0
                for (tp in c.typeParams) {
                    val t = args[k].type
                    m[tp] = t ?: nullableAny
                    k++
                }
                return ops.substitute(e, m)
            }
            else -> return ErrType("classifier")
        }
    }

    fun expandAlias(a: TypeAliasSym): Type {
        val e = a.expanded
        if (e != null) return e
        if (a.state == 1) return ErrType("recursive typealias")
        a.state = 1
        val tpScope = TypeParamScope(a.typeParams, a.scope)
        for (tp in a.typeParams) tp.scope = tpScope
        val r = a.decl.type
        val t = if (r == null) ErrType("alias") else resolveType(r, tpScope)
        a.expanded = t
        a.state = 2
        return t
    }

    // ---- class headers -------------------------------------------------------------------------------------------

    fun ensureBounds(tp: TypeParamSym) {
        if (tp.boundsState != 0) return
        tp.boundsState = 1
        val out = ArrayList<Type>()
        val d = tp.decl
        if (d != null) {
            val b = d.bound
            if (b != null) out.add(resolveType(b, tp.scope))
            for (c in tp.constraintsDecl) out.add(resolveType(c.bound, tp.scope))
        }
        tp.bounds = out
        tp.boundsState = 2
    }

    fun ensureSupertypes(c: ClassSym) {
        if (c.supertypeState != 0) return
        c.supertypeState = 1
        val out = ArrayList<Type>()
        val d = c.decl
        val hs = c.headerScope
        if (d != null && hs != null) {
            for (s in d.supers) {
                val ref = when (s) {
                    is SuperType -> s.type
                    is SuperCall -> s.type
                    is SuperDelegate -> s.type
                }
                val t = resolveType(ref, hs)
                if (t is ClassType) out.add(t)
            }
        }
        if (c.isEnum) {
            val enumCls = classNamed("kotlin.Enum")
            if (enumCls != null) {
                var has = false
                for (t in out) if (t is ClassType && t.cls === enumCls) has = true
                if (!has) out.add(ClassType(enumCls, listOf(TArg(c.thisType(), Variance.INV)), false))
            }
        }
        if (c.isEnumEntryBody) {
            val o = c.outer
            if (o != null) out.add(o.thisType())
        }
        if (c.isAnnotation) {
            val a = classNamed("kotlin.Annotation")
            if (a != null) out.add(ClassType(a, emptyList(), false))
        }
        c.supertypes = out
        c.supertypeState = 2
    }

    /** The superclass (not interface) of [c], or null when it is `Any`. */
    fun superclassOf(c: ClassSym): ClassType? {
        ensureSupertypes(c)
        for (t in c.supertypes) {
            if (t is ClassType && !t.cls.isInterface) return t
        }
        return null
    }

    // ---- signatures -------------------------------------------------------------------------------------------

    fun varargArrayType(elem: Type): Type {
        if (elem is ClassType && !elem.nullable) {
            val n = elem.cls.fqName()
            val prim = when (n) {
                "kotlin.Int" -> "kotlin.IntArray"
                "kotlin.Long" -> "kotlin.LongArray"
                "kotlin.Byte" -> "kotlin.ByteArray"
                "kotlin.Short" -> "kotlin.ShortArray"
                "kotlin.Char" -> "kotlin.CharArray"
                "kotlin.Boolean" -> "kotlin.BooleanArray"
                "kotlin.Double" -> "kotlin.DoubleArray"
                "kotlin.Float" -> "kotlin.FloatArray"
                else -> null
            }
            if (prim != null) {
                val pc = classNamed(prim)
                if (pc != null) return ClassType(pc, emptyList(), false)
            }
        }
        return ClassType(builtin("kotlin.Array"), listOf(TArg(elem, Variance.OUT)), false)
    }

    private fun makeParam(p: Param, scope: Scope): VarSym {
        var t: Type = if (p.type != null) resolveType(p.type, scope) else ErrType("missing parameter type")
        val v = VarSym(p.name, t, false, true, p.start)
        v.decl = p
        v.hasDefault = p.default != null
        val m = p.mods
        if (m != null) {
            if (m.has("vararg")) {
                v.isVararg = true
                v.varargElement = t
                v.type = varargArrayType(t)
            }
            if (m.has("crossinline")) v.isCrossinline = true
            if (m.has("noinline")) v.isNoinline = true
        }
        return v
    }

    fun ensureSig(f: FunSym) {
        if (f.sigState != 0) return
        f.sigState = 1
        val d = f.decl
        if (f.kind == FunKind.CONSTRUCTOR) {
            val o = f.owner!!
            val sc = f.scope
            val ps = ArrayList<VarSym>()
            val plist = if (f.isPrimary) f.primaryParams else (f.secondary?.params ?: emptyList())
            for (p in plist) ps.add(makeParam(p, sc))
            f.params = ps
            f.returnType = o.thisType()
        } else if (d != null) {
            val tpScope = TypeParamScope(f.typeParams, f.scope)
            val recv = d.receiver
            if (recv != null) f.receiver = resolveType(recv, tpScope)
            val ps = ArrayList<VarSym>()
            for (p in d.params) ps.add(makeParam(p, tpScope))
            f.params = ps
            val rt = d.returnType
            if (rt != null) {
                f.returnType = resolveType(rt, tpScope)
                f.retState = 2
            } else if (!d.expressionBody) {
                f.returnType = unitType
                f.retState = 2
            }
        }
        f.sigState = 2
    }

    /** scope used to resolve a function's own signature and body: its type parameters over its declaration scope */
    fun funScope(f: FunSym): Scope = if (f.typeParams.isEmpty()) f.scope else TypeParamScope(f.typeParams, f.scope)

    fun ensurePropSig(p: PropSym) {
        if (p.sigState != 0) return
        p.sigState = 1
        val d = p.decl
        if (d != null) {
            val tpScope = TypeParamScope(p.typeParams, p.scope)
            val recv = d.receiver
            if (recv != null) p.receiver = resolveType(recv, tpScope)
            val t = d.type
            if (t != null) {
                p.type = resolveType(t, tpScope)
                p.typeState = 2
            }
        } else {
            val cp = p.ctorParam
            if (cp != null) {
                val t = cp.type
                var ty: Type = if (t != null) resolveType(t, p.scope) else ErrType("ctor property type")
                if (cp.mods?.has("vararg") == true) ty = varargArrayType(ty)
                p.type = ty
                p.typeState = 2
            }
        }
        p.sigState = 2
    }
}
