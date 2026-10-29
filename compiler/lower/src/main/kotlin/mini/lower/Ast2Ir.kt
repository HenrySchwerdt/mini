package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/** A receiver in scope while a body is built: `this` of a class or the receiver parameter of an extension function / lambda. */
class RecvEntry(val cls: ClassSym?, val fn: FunSym?, val lambda: LambdaExpr?, val prop: PropSym?, val param: IrValueParameter)

/** What the builder knows about the function (or class initializer) whose body it is building. */
class BodyCtx(val parent: BodyCtx?, val fn: IrFunction?, val label: String?, val isLambda: Boolean) {
    val recvs = ArrayList<RecvEntry>()
    /** loops being built, innermost last (with their source labels) */
    val loops = ArrayList<IrLoop>()
    /** class whose initializer this is (property initializers, init blocks) */
    var initOf: IrClass? = null
    /** counter of the temporaries (`tmp0_subject`) of this outermost body */
    var tmpCounter = 0
    /** a lambda passed to an inline function numbers its temporaries on in the counter of the enclosing body */
    var shareTmp = false
}

/**
 * Builds the IR of a set of analysed sources: the equivalent of K2's fir2ir, producing the same shapes (declaration
 * order, fake overrides, synthesized members, desugared expressions) so that [Ir2Mir] gives the same MIR as on the K2 IR.
 */
class Ast2Ir(val sema: Sema, val userPaths: Set<String>) {
    val ops: TypeOps = sema.ops
    /** set while the argument of an inline function's parameter is built (see BodyCtx.shareTmp) */
    var inlineArgument = false
    var lambdaInline = false
    val module = IrModule(ArrayList())

    val fileMap = HashMap<FileInfo, IrFile>()
    val classMap = HashMap<ClassSym, IrClass>()
    val funMap = HashMap<FunSym, IrFunction>()
    val propMap = HashMap<PropSym, IrProperty>()
    val entryMap = HashMap<EnumEntrySym, IrEnumEntry>()
    val varMap = HashMap<VarSym, IrValueDeclaration>()
    val functionClasses = HashMap<Int, IrClass>()
    val allClasses = ArrayList<IrClass>()
    var anonCounter = 0
    val localClassCtxs = HashMap<IrClass, BodyCtx?>()
    var pendingLocalCtx: BodyCtx? = null
    val lambdaLabels = HashMap<LambdaExpr, String?>()
    val delegatedLocals = HashMap<VarSym, IrLocalDelegatedProperty>()

    var ctx: BodyCtx? = null

    // ---- entry point ---------------------------------------------------------------------------------------------

    fun build(): IrModule {
        LowerState.ctx = this
        for (f in sema.files) {
            val irf = IrFile(f.path, f.text, f.pkg, !userPaths.contains(f.path))
            irf.isBuiltin = isBuiltinFile(f)
            fileMap[f] = irf
            module.files.add(irf)
        }
        for (f in sema.files) {
            val irf = fileMap[f]!!
            for (d in f.ast.declarations) {
                when (d) {
                    is ClassDecl -> {
                        val s = sema.declSyms[d] as? ClassSym ?: continue
                        val c = irClass(s)
                        irf.declarations.add(c)
                    }
                    is FunDecl -> {
                        val s = sema.declSyms[d] as? FunSym ?: continue
                        val fn = irFunction(s)
                        fn.parent = irf
                        irf.declarations.add(fn)
                    }
                    is PropertyDecl -> {
                        val s = sema.declSyms[d] as? PropSym ?: continue
                        val p = irProperty(s, null)
                        p.parent = irf
                        p.backingField?.parent = irf
                        p.getter?.parent = irf
                        p.setter?.parent = irf
                        irf.declarations.add(p)
                    }
                    else -> {}
                }
            }
        }
        // fake overrides: supertypes first
        val n = allClasses.size
        var i = 0
        while (i < n) {
            ensureFakeOverrides(allClasses[i])
            i++
        }
        return module
    }

    private fun isBuiltinFile(f: FileInfo): Boolean {
        for (a in f.ast.fileAnnotations) {
            val t = a.type
            if (t is UserType && t.segments.isNotEmpty() && t.segments[t.segments.size - 1].name == "Builtin") return true
        }
        return false
    }

    // ---- types ---------------------------------------------------------------------------------------------------

    /** the type as the IR has it: no literal types, projections or inference variables */
    fun irType(t: Type): Type {
        return when (t) {
            is IntLiteralType -> ops.literalDefault(t)
            is CapType -> irType(ops.approximate(t))
            is IsectType -> if (t.parts.isNotEmpty()) irType(t.parts[t.parts.size - 1]) else sema.anyType
            is ErrType -> sema.nullableAny
            is VarType -> {
                val fx = t.v.fixed
                if (fx == null) sema.nullableAny else irType(if (t.nullable) fx.withNullable(true) else fx)
            }
            is ClassType -> {
                if (t.args.isEmpty()) t else {
                    var changed = false
                    val args = ArrayList<TArg>()
                    for (a in t.args) {
                        val x = a.type
                        if (x is VarType) {
                            changed = true
                            args.add(TArg(irType(x), a.variance))
                        } else args.add(a)
                    }
                    if (changed) ClassType(t.cls, args, t.nullable) else t
                }
            }
            else -> t
        }
    }

    fun classOf(t: Type): ClassSym? {
        return when (t) {
            is ClassType -> t.cls
            is TypeParamType -> {
                sema.ensureBounds(t.tp)
                var r: ClassSym? = null
                for (b in t.tp.bounds) {
                    val c = classOf(b)
                    if (c != null && !c.isInterface) {
                        r = c
                        break
                    }
                    if (c != null && r == null) r = c
                }
                if (r == null) r = sema.anyType.cls
                r
            }
            is IsectType -> if (t.parts.isNotEmpty()) classOf(t.parts[0]) else null
            else -> null
        }
    }

    // ---- synthetic function classes -------------------------------------------------------------------------------

    /** `kotlin.FunctionN`: an interface with the abstract `invoke` (what K2 synthesizes for function types) */
    fun functionClass(arity: Int): IrClass {
        val ex = functionClasses[arity]
        if (ex != null) return ex
        val c = IrClass("Function" + arity, null)
        c.kind = IrClassKind.INTERFACE
        c.modality = IrModality.ABSTRACT
        c.fqName = "kotlin.Function" + arity
        functionClasses[arity] = c
        val fn = IrSimpleFunction("invoke")
        fn.modality = IrModality.ABSTRACT
        fn.isOperator = true
        fn.parent = c
        fn.returnType = sema.nullableAny
        var i = 0
        val th = IrValueParameter("<this>", sema.anyType, IrParameterKind.DispatchReceiver, 0)
        th.parent = fn
        fn.parameters.add(th)
        while (i < arity) {
            val p = IrValueParameter("p" + (i + 1), sema.nullableAny, IrParameterKind.Regular, i + 1)
            p.parent = fn
            fn.parameters.add(p)
            i++
        }
        c.declarations.add(fn)
        c.membersBuilt = true
        return c
    }

    /** the abstract `invoke` of `kotlin.FunctionN` */
    fun invokeFunction(arity: Int): IrSimpleFunction {
        val c = functionClass(arity)
        return c.declarations[0] as IrSimpleFunction
    }

    // ---- files / names -------------------------------------------------------------------------------------------

    fun fileOf(f: FileInfo?): IrFile? = if (f == null) null else fileMap[f]

    // ---- classes -------------------------------------------------------------------------------------------------

    fun irClass(sym: ClassSym): IrClass {
        val ex = classMap[sym]
        if (ex != null) return ex
        val name = if (sym.isAnonymous) "<no name provided>" else sym.name
        val c = IrClass(name, sym)
        classMap[sym] = c
        allClasses.add(c)
        c.kind = when {
            sym.isAnonymous && !sym.isEnumEntryBody -> IrClassKind.CLASS
            sym.isEnum -> IrClassKind.ENUM_CLASS
            sym.isInterface -> IrClassKind.INTERFACE
            sym.isObject -> IrClassKind.OBJECT
            sym.isAnnotation -> IrClassKind.ANNOTATION_CLASS
            else -> IrClassKind.CLASS
        }
        c.modality = when {
            sym.isInterface -> IrModality.ABSTRACT
            sym.has("abstract") -> IrModality.ABSTRACT
            sym.has("sealed") -> IrModality.SEALED
            sym.has("open") -> IrModality.OPEN
            else -> IrModality.FINAL
        }
        c.visibility = if (sym.isLocal || sym.isAnonymous) "local" else visibilityOf(sym.mods())
        c.isInner = sym.isInner
        c.isValue = sym.isValue
        c.isCompanion = sym.isCompanion
        c.typeParameters = sym.typeParams
        for (tp in sym.typeParams) sema.ensureBounds(tp)
        sema.ensureSupertypes(sym)
        val sup = ArrayList<Type>()
        for (t in sym.supertypes) sup.add(irType(t))
        if (sym.fnArity >= 0) {
            // KFunctionN / KPropertyN: the reflection interface of the stdlib and the function type
            val tps = sym.typeParams
            if (tps.isNotEmpty()) {
                val retTp = tps[tps.size - 1]
                val baseName = if (sym.name.startsWith("KFunction")) "KFunction" else if (sym.name.startsWith("KMutableProperty")) "KMutableProperty" else "KProperty"
                val base = sema.classNamed(REFLECT_PKG + "." + baseName)
                if (base != null) sup.add(ClassType(base, listOf(TArg(TypeParamType(retTp, false, false), Variance.INV)), false))
                val ps = ArrayList<Type>()
                var pi = 0
                while (pi < tps.size - 1) {
                    ps.add(TypeParamType(tps[pi], false, false))
                    pi++
                }
                sup.add(FunType(null, ps, TypeParamType(retTp, false, false), false, false))
            }
        }
        if (sup.isEmpty() && sym !== sema.anyType.cls) sup.add(sema.anyType)
        c.superTypes = sup
        c.fqName = classFqName(sym)
        val outer = sym.outer
        if (outer != null && !sym.isLocal && !sym.isAnonymous) c.parent = irClass(outer)
        else if (!sym.isLocal && !sym.isAnonymous) c.parent = fileOf(sym.file)
        val th = IrValueParameter("<this>", irType(sym.thisType()), IrParameterKind.DispatchReceiver, -1)
        th.parent = c
        th.origin = "INSTANCE_RECEIVER"
        c.thisReceiver = th
        buildMembers(c, sym)
        return c
    }

    fun classFqName(sym: ClassSym): String {
        if (sym.isLocal || sym.isAnonymous) return sym.name
        return sym.irFqName()
    }

    fun visibilityOf(m: Modifiers?): String {
        if (m == null) return "public"
        if (m.has("private")) return "private"
        if (m.has("protected")) return "protected"
        if (m.has("internal")) return "internal"
        return "public"
    }

    // ---- members of a class --------------------------------------------------------------------------------------

    private fun buildMembers(c: IrClass, sym: ClassSym) {
        val d = sym.decl
        sema.members.ensureSynth(sym)
        val decls = c.declarations
        // primary constructor, then the properties it declares
        val pc = sym.primaryCtor
        if (pc == null && sym.kind == ClassKind.OBJECT) decls.add(objectConstructor(c))
        if (pc != null && sym.kind != ClassKind.INTERFACE) {
            val k = irConstructor(pc, c)
            decls.add(k)
            if (d != null) {
                val pcd = d.primaryCtor
                if (pcd != null) {
                    for (p in pcd.params) {
                        if (p.valVar == null) continue
                        val ps = findCtorProp(sym, p)
                        if (ps != null) {
                            val ip = irProperty(ps, c)
                            decls.add(ip)
                        }
                    }
                }
            }
        }
        if (d != null) {
            // enum entries come first in the body
            for (e in d.enumEntries) {
                val es = findEntry(sym, e)
                if (es != null) decls.add(irEnumEntry(es, c))
            }
            for (m in d.members) {
                when (m) {
                    is FunDecl -> {
                        val s = sema.declSyms[m] as? FunSym ?: continue
                        val fn = irFunction(s)
                        fn.parent = c
                        decls.add(fn)
                    }
                    is PropertyDecl -> {
                        val s = sema.declSyms[m] as? PropSym ?: continue
                        decls.add(irProperty(s, c))
                    }
                    is ClassDecl -> {
                        val s = sema.declSyms[m] as? ClassSym ?: continue
                        decls.add(irClass(s))
                    }
                    is InitDecl -> decls.add(irInitializer(m, c))
                    is SecondaryCtor -> {
                        val s = sema.declSyms[m] as? FunSym ?: continue
                        decls.add(irConstructor(s, c))
                    }
                    else -> {}
                }
            }
        }
        if (sym.isData) addDataMembers(c, sym)
        if (sym.isEnum) addEnumMembers(c, sym)
    }

    private fun findCtorProp(sym: ClassSym, p: Param): PropSym? {
        val l = sym.props[p.name] ?: return null
        for (x in l) if (x.ctorParam === p) return x
        return null
    }

    private fun findEntry(sym: ClassSym, e: EnumEntryDecl): EnumEntrySym? {
        for (x in sym.entries) if (x.decl === e) return x
        return null
    }

    // ---- enum entries ---------------------------------------------------------------------------------------------

    fun irEnumEntry(es: EnumEntrySym, c: IrClass): IrEnumEntry {
        val ex = entryMap[es]
        if (ex != null) return ex
        val e = IrEnumEntry(es.name)
        entryMap[es] = e
        e.parent = c
        e.initializerBuilder = { buildEnumEntryInit(es, c) }
        return e
    }

    // ---- initializers ---------------------------------------------------------------------------------------------

    private fun irInitializer(m: InitDecl, c: IrClass): IrAnonymousInitializer {
        val i = IrAnonymousInitializer()
        i.parent = c
        i.bodyBuilder = { buildInitializerBody(m, c) }
        return i
    }

    // ---- functions ------------------------------------------------------------------------------------------------

    fun cSymbolOf(m: Modifiers?): String? {
        if (m == null) return null
        for (a in m.annotations()) {
            val t = a.type
            if (t is UserType && t.segments.isNotEmpty() && t.segments[t.segments.size - 1].name == "CSymbol") {
                val args = a.args ?: continue
                if (args.isEmpty()) continue
                val v = args[0].value
                if (v is StringExpr) {
                    val sb = StringBuilder()
                    for (p in v.parts) {
                        if (p is StrText) sb.append(p.text)
                    }
                    return sb.toString()
                }
            }
        }
        return null
    }

    fun irFunction(sym: FunSym): IrSimpleFunction {
        val ex = funMap[sym]
        if (ex != null) return ex as IrSimpleFunction
        sema.ensureSig(sym)
        val fn = IrSimpleFunction(sym.name)
        funMap[sym] = fn
        fn.sym = sym
        val m = sym.mods()
        val owner = sym.owner
        fn.visibility = if (sym.kind == FunKind.LOCAL || sym.kind == FunKind.ANONYMOUS) "local" else visibilityOf(m)
        fn.isInline = m != null && m.has("inline")
        fn.isExternal = m != null && m.has("external")
        fn.isOperator = m != null && m.has("operator")
        fn.isSuspend = m != null && m.has("suspend")
        fn.cSymbolName = cSymbolOf(m)
        fn.typeParameters = sym.typeParams
        for (tp in sym.typeParams) sema.ensureBounds(tp)
        fn.modality = functionModality(sym, owner)
        if (sym.kind == FunKind.LOCAL || sym.kind == FunKind.ANONYMOUS) fn.origin = "LOCAL_FUNCTION"
        if (sym.kind == FunKind.SYNTH && owner != null) {
            fn.origin = "GENERATED_DATA_CLASS_MEMBER"
        }
        // parameters
        var idx = 0
        if (owner != null && sym.kind != FunKind.LOCAL && sym.kind != FunKind.ANONYMOUS) {
            val th = IrValueParameter("<this>", irType(owner.thisType()), IrParameterKind.DispatchReceiver, idx++)
            th.parent = fn
            fn.parameters.add(th)
        }
        val r = sym.receiver
        if (r != null) {
            val ep = IrValueParameter("<this>", irType(r), IrParameterKind.ExtensionReceiver, idx++)
            ep.parent = fn
            fn.parameters.add(ep)
        }
        addValueParameters(fn, sym.params, idx)
        fn.returnType = irType(sema.returnTypeOf(sym))
        val d = sym.decl
        if (d != null && d.body != null) {
            fn.bodyBuilder = { buildFunctionBody(fn, sym) }
        }
        return fn
    }

    fun addValueParameters(fn: IrFunction, params: List<VarSym>, start: Int) {
        var idx = start
        for (p in params) {
            val pt = irType(p.type ?: sema.nullableAny)
            val ip = IrValueParameter(p.name, pt, IrParameterKind.Regular, idx++)
            ip.parent = fn
            ip.isNoinline = p.isNoinline
            ip.isCrossinline = p.isCrossinline
            val ve = p.varargElement
            if (p.isVararg && ve != null) ip.varargElementType = irType(ve)
            val pd = p.decl
            if (pd != null && pd.default != null) {
                val dv = pd.default!!
                ip.defaultValueBuilder = { buildDefaultValue(fn, ip, dv) }
            }
            fn.parameters.add(ip)
            varMap[p] = ip
        }
    }

    private fun functionModality(sym: FunSym, owner: ClassSym?): IrModality {
        val m = sym.mods()
        if (m != null) {
            if (m.has("abstract")) return IrModality.ABSTRACT
            if (m.has("final")) return IrModality.FINAL
            if (m.has("open")) return IrModality.OPEN
            if (m.has("override")) return IrModality.OPEN
        }
        if (owner != null && owner.isInterface && sym.kind == FunKind.NORMAL) {
            val d = sym.decl
            return if (d != null && d.body == null) IrModality.ABSTRACT else IrModality.OPEN
        }
        return IrModality.FINAL
    }

    // ---- constructors ---------------------------------------------------------------------------------------------

    fun irConstructor(sym: FunSym, c: IrClass): IrConstructor {
        val ex = funMap[sym]
        if (ex != null) return ex as IrConstructor
        sema.ensureSig(sym)
        val k = IrConstructor()
        funMap[sym] = k
        k.sym = sym
        k.parent = c
        k.isPrimary = sym.isPrimary
        val cs = c.sym
        k.visibility = if (cs != null && cs.isEnum) "private" else if (cs != null && cs.isObject) "private" else visibilityOf(sym.mods())
        k.isExternal = false
        k.typeParameters = emptyList()
        var idx = 0
        if (cs != null && cs.isInner) {
            val oc = cs.outer
            if (oc != null) {
                val th = IrValueParameter("<this>", irType(oc.thisType()), IrParameterKind.DispatchReceiver, idx++)
                th.parent = k
                k.parameters.add(th)
            }
        }
        addValueParameters(k, sym.params, idx)
        k.returnType = irType(c.sym!!.thisType())
        k.bodyBuilder = { buildConstructorBody(k, sym, c) }
        return k
    }

    // ---- properties -----------------------------------------------------------------------------------------------

    fun usesField(a: AccessorDecl?): Boolean {
        if (a == null) return false
        val b = a.body ?: return false
        return AstWalk.mentionsName(b, "field")
    }

    fun irProperty(sym: PropSym, c: IrClass?): IrProperty {
        val ex = propMap[sym]
        if (ex != null) return ex
        sema.ensurePropSig(sym)
        val p = IrProperty(sym.name, sym)
        propMap[sym] = p
        val d = sym.decl
        val m = sym.mods()
        p.isVar = sym.isVar
        p.isLateinit = m != null && m.has("lateinit")
        p.cSymbolName = cSymbolOf(m)
        val owner = sym.owner
        val type = irType(sema.typeOfProp(sym))
        val abstract = (m != null && m.has("abstract")) || (owner != null && owner.isInterface && (d == null || (d.getter?.body == null && d.initializer == null && d.delegate == null)))
        val modality: IrModality = if (m != null && m.has("abstract")) IrModality.ABSTRACT
        else if (abstract) IrModality.ABSTRACT
        else if (m != null && m.has("final")) IrModality.FINAL
        else if (m != null && (m.has("open") || m.has("override"))) IrModality.OPEN
        else if (owner != null && owner.isInterface) IrModality.OPEN
        else IrModality.FINAL
        val vis = visibilityOf(m)
        val hasInit = d != null && d.initializer != null
        val hasDelegate = d != null && d.delegate != null
        val getterBody = d?.getter?.body
        val setterBody = d?.setter?.body
        var needsField = false
        if (!abstract && !(m != null && m.has("external"))) {
            if (sym.decl == null) needsField = true // constructor property
            else if (hasInit) needsField = true
            else if (hasDelegate) needsField = false
            else if (getterBody == null && setterBody == null && d?.getter == null && d?.setter == null) needsField = true
            else if (usesField(d?.getter) || usesField(d?.setter)) needsField = true
            else if (getterBody == null && !(sym.receiver != null)) needsField = true
        }
        if (sym.receiver != null) needsField = false
        if (hasDelegate) {
            // `val x by delegate`: the delegate lives in a field `x$delegate`
            val f = IrField(sym.name + "\$delegate", irType(delegateFieldType(sym)))
            f.origin = "PROPERTY_DELEGATE"
            f.parent = c
            f.isStatic = c == null
            f.isFinal = true
            f.correspondingProperty = p
            p.backingField = f
            val dd = d!!.delegate!!
            f.initializerBuilder = { buildDelegateInit(p, sym, c, dd) }
        } else if (needsField) {
            val f = IrField(sym.name, type)
            f.origin = "PROPERTY_BACKING_FIELD"
            f.parent = c
            f.isStatic = c == null
            f.isFinal = !sym.isVar
            f.correspondingProperty = p
            p.backingField = f
            if (sym.decl == null) {
                val cp = sym.ctorParam
                f.initializerBuilder = { buildCtorPropInit(f, sym, c!!) }
            } else if (hasInit) {
                f.initializerBuilder = { buildPropertyInit(f, sym, c) }
            }
        }
        // accessors
        val g = IrSimpleFunction("<get-" + sym.name + ">")
        configureAccessor(g, p, sym, c, modality, vis, type, true)
        p.getter = g
        if (sym.isVar) {
            val s = IrSimpleFunction("<set-" + sym.name + ">")
            val setterMods = d?.setter?.mods
            configureAccessor(s, p, sym, c, modality, if (setterMods != null) visibilityOf(setterMods) else vis, type, false)
            p.setter = s
        }
        p.parent = c
        return p
    }

    private fun delegateFieldType(sym: PropSym): Type {
        val d = sym.decl ?: return sema.nullableAny
        val r = sema.records.get(d.delegate!!)
        return r?.type ?: sema.nullableAny
    }

    private fun configureAccessor(f: IrSimpleFunction, p: IrProperty, sym: PropSym, c: IrClass?, modality: IrModality, vis: String, type: Type, isGetter: Boolean) {
        f.correspondingProperty = p
        f.parent = c
        f.modality = modality
        f.visibility = vis
        f.cSymbolName = null
        f.typeParameters = sym.typeParams
        val d = sym.decl
        val acc = if (d == null) null else if (isGetter) d.getter else d.setter
        val customBody = acc != null && acc.body != null
        val m = sym.mods()
        f.isExternal = m != null && m.has("external")
        f.origin = if (d != null && d.delegate != null) "DELEGATED_PROPERTY_ACCESSOR" else if (customBody) "DEFINED" else "DEFAULT_PROPERTY_ACCESSOR"
        var idx = 0
        if (c != null) {
            val th = IrValueParameter("<this>", irType(c.sym!!.thisType()), IrParameterKind.DispatchReceiver, idx++)
            th.parent = f
            f.parameters.add(th)
        }
        val r = sym.receiver
        if (r != null) {
            val ep = IrValueParameter("<this>", irType(r), IrParameterKind.ExtensionReceiver, idx++)
            ep.parent = f
            f.parameters.add(ep)
        }
        if (isGetter) {
            f.returnType = type
        } else {
            f.returnType = sema.unitType
            val sp = acc?.param
            val pn = if (customBody && sp != null) sp.name else if (customBody) "value" else "<set-?>"
            val vp = IrValueParameter(pn, type, IrParameterKind.Regular, idx++)
            vp.parent = f
            f.parameters.add(vp)
        }
        val abstract = modality == IrModality.ABSTRACT
        if (!abstract) f.bodyBuilder = { buildAccessorBody(f, p, sym, c, isGetter) }
    }

    // ---- fake overrides --------------------------------------------------------------------------------------------

    private val fakeDone = HashSet<IrClass>()

    fun ensureFakeOverrides(c: IrClass) {
        if (!fakeDone.add(c)) return
        val cs = c.sym ?: return
        buildFakeOverrides(c, cs)
    }
}
