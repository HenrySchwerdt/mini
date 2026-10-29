
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Ir2Mir: classes, enums, dispatch tables (vtables / itables), function shape checks. */

// ---- classes --------------------------------------------------------------------------

internal fun Ir2Mir.collectClass(c: IrClass, pkg: String, path: List<String>, file: IrFile) {
    when (c.kind) {
        IrClassKind.CLASS, IrClassKind.INTERFACE, IrClassKind.OBJECT -> {}
        IrClassKind.ENUM_CLASS -> {}
        else -> unsupported(c, "${classKind(c)} declaration '${c.name}'")
    }
    if (c.isValue) unsupported(c, "value class '${c.name}'")
    val cName = Mangling.className(pkg, path)
    val qn = (if (pkg.isEmpty()) "" else "$pkg.") + path.joinToString(".")
    classInfos[c] = ClassInfo(c, cName, qn, file)
    // the Comparable interface is compiled like a user interface (compareTo on generic values goes through mk_compare_to)
    for (t in c.superTypes) {
        val s = t.classOrNull ?: continue
        if (s.kotlinFqName == "kotlin.Comparable" && s !in classInfos) {
            classInfos[s] = ClassInfo(s, Mangling.className("kotlin", listOf("Comparable")), "kotlin.Comparable", file)
        }
    }
    for (d in c.declarations) {
        if (d is IrClass && !isPtrClass(d)) collectClass(d, pkg, path + d.name, file)
    }
}

internal fun Ir2Mir.classKind(c: IrClass): String = when (c.kind) {
    IrClassKind.INTERFACE -> "interface"
    IrClassKind.ENUM_CLASS -> "enum class"
    IrClassKind.ENUM_ENTRY -> "enum entry"
    IrClassKind.ANNOTATION_CLASS -> "annotation class"
    IrClassKind.OBJECT -> "object"
    else -> "class"
}

internal fun Ir2Mir.isAny(c: IrClass) = c.kotlinFqName == "kotlin.Any"

internal fun Ir2Mir.superclassOf(c: IrClass): IrClass? {
    if (c.kind == IrClassKind.INTERFACE) return null
    if (builtinOf(c) != null) {
        for (t in c.superTypes) {
            val s = t.classOrNull ?: continue
            if (builtinOf(s) != null) return s
        }
        return null
    }
    for (t in c.superTypes) {
        val s = t.classOrNull ?: unsupported(c, "supertype '${t.classFqName}' of '${c.name}'")
        if (isAny(s) || s.kotlinFqName == "kotlin.Enum") continue
        if (s.kind == IrClassKind.INTERFACE) {
            if (s !in classInfos) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
            continue
        }
        if (s !in classInfos && builtinOf(s) == null) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
        return s
    }
    return null
}

internal fun Ir2Mir.directInterfaces(c: IrClass): List<IrClass> = c.superTypes.mapNotNull { t ->
    val s = t.classOrNull
    if (s != null && s.kind == IrClassKind.INTERFACE && s in classInfos) s else null
}

/** All interfaces of [c], transitively and including those of superclasses, super-interfaces first. */
internal fun Ir2Mir.allInterfaces(c: IrClass): List<IrClass> {
    val out = LinkedHashSet<IrClass>()
    fun addIface(i: IrClass) {
        for (j in directInterfaces(i)) addIface(j)
        out += i
    }
    superclassOf(c)?.let { out += allInterfaces(it) }
    for (i in directInterfaces(c)) addIface(i)
    return out.toList()
}

internal fun Ir2Mir.info(c: IrClass): ClassInfo = classInfos[c] ?: builtinInfos[c] ?: builtinOf(c)?.let { b ->
    ClassInfo(c, b.cName, b.qualified, null).also { builtinInfos[c] = it }
} ?: error("unknown class ${c.name}")

internal fun Ir2Mir.typeOf(c: IrClass, nullable: Boolean = false): TObject {
    val i = info(c)
    return TObject(i.qualified, i.cName, nullable, c.kind == IrClassKind.INTERFACE)
}

internal fun Ir2Mir.classFunctions(c: IrClass): List<IrSimpleFunction> = c.declarations.flatMap {
    when (it) {
        is IrSimpleFunction -> listOf(it)
        is IrProperty -> listOfNotNull(it.getter, it.setter)
        else -> emptyList()
    }
}

internal fun Ir2Mir.ownFields(c: IrClass): List<IrField> = c.declarations.flatMap {
    when (it) {
        is IrProperty -> listOfNotNull(it.backingField)
        is IrField -> listOf(it)
        else -> emptyList()
    }
}

internal fun Ir2Mir.registerMembers(info: ClassInfo) {
    val c = info.ir
    for (f in classFunctions(c)) {
        if (f.isFakeOverride) continue
        if (isEnumSpecial(c, f)) continue // values(), valueOf(), entries
        checkFunctionShape(f, member = true)
        val params = f.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.map { mirType(it.type, it) }
        functionNames[f] = Mangling.method(info.cName, f.name, params)
    }
    for (k in c.declarations.filterIsInstance<IrConstructor>()) {
        val hidden = if (c.kind == IrClassKind.ENUM_CLASS) listOf(TString, TInt) else emptyList()
        // captured values of local classes follow the declared parameters
        functionNames[k] = Mangling.constructor(info.cName, hidden + k.parameters.map { mirType(it.type, it) } + captureTypesFor(c))
    }
    for (d in c.declarations) {
        when (d) {
            is IrSimpleFunction, is IrProperty, is IrConstructor, is IrClass, is IrAnonymousInitializer, is IrField -> {}
            is IrEnumEntry -> if (d.correspondingClass != null) unsupported(d, "enum entry with a body '${d.name}'")
            else -> unsupported(d, describe(d))
        }
        if (d is IrProperty) {
        }
    }
}

internal fun IrFunction.extensionReceiverParameter(): IrValueParameter? =
    parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }

internal fun Ir2Mir.convertMembers(info: ClassInfo): List<MirFunction> {
    val out = ArrayList<MirFunction>()
    val c = info.ir
    for (f in classFunctions(c)) {
        if (f.isFakeOverride || isEnumSpecial(c, f)) continue
        if (f.body == null) {
            if (f.modality == IrModality.ABSTRACT || c.kind == IrClassKind.INTERFACE || c.kind == IrClassKind.ENUM_CLASS) continue
            unsupported(f, "function without body '${f.name}'")
        }
        out += convertFunction(f, functionNames[f]!!, info.qualified + "." + f.name, null)
    }
    for (k in c.declarations.filterIsInstance<IrConstructor>()) {
        out += convertFunction(k, functionNames[k]!!, info.qualified + ".<init>", info.cName)
    }
    if (c.kind == IrClassKind.ENUM_CLASS) {
        out += convertEnumHolder(info)
        out += enumToString(info)
        out += enumValues(info)
        out += enumValueOf(info)
    }
    return out
}

// ---- enums ----------------------------------------------------------------------------------
//
// An enum class is an ordinary class with two hidden leading constructor parameters and fields
// ($name, $ordinal). Its entries live in a hidden singleton `<cName>_E` (created on first access,
// like an object) with one field per entry; `Color.RED` reads that field.

/** The compiler-synthesised `values()`, `valueOf()` and `entries` of an enum class (arrays are not supported yet). */
internal fun Ir2Mir.isEnumSpecial(c: IrClass, f: IrSimpleFunction): Boolean {
    if (c.kind != IrClassKind.ENUM_CLASS) return false
    val n = f.name
    return (n == "values" || n == "valueOf" || n == "<get-entries>") && f.origin != "DEFINED"
}

internal fun Ir2Mir.enumEntries(c: IrClass): List<IrEnumEntry> = c.declarations.filterIsInstance<IrEnumEntry>()

internal fun Ir2Mir.holderName(info: ClassInfo) = info.cName + "_E"

internal fun Ir2Mir.holderType(info: ClassInfo) = TObject(info.qualified + ".\$entries", holderName(info), false)

internal fun Ir2Mir.enumGet(info: ClassInfo, ordinal: Int): MirExpr =
    MirExpr.FieldGet(MirExpr.ObjectGet(holderName(info), holderType(info)), holderName(info), "e$ordinal", typeOf(info.ir))

internal fun Ir2Mir.convertEnumHolder(info: ClassInfo): MirFunction {
    locals = HashMap()
    localCounter = 0
    currentFunction = null
    currentReturn = TUnit
    val self = MirLocal(localCounter++, "this", holderType(info))
    val out = ArrayList<MirStmt>()
    for ((i, e) in enumEntries(info.ir).withIndex()) {
        val call = e.initializerExpression?.expression as? IrEnumConstructorCall ?: unsupported(e, "enum entry '${e.name}'")
        val k = call.callee
        val args = callArgs(call, k, out)
        val ctor = functionNames[k] ?: unsupported(e, "enum entry '${e.name}'")
        val n = MirExpr.New(ctor, listOf(MirExpr.StringConst(e.name), MirExpr.IntConst(i)) + args, typeOf(info.ir))
        out += MirStmt.SetField(MirExpr.Local(self), holderName(info), "e$i", typeOf(info.ir), n)
    }
    return MirFunction(info.qualified + ".<entries>", holderName(info) + "_C", listOf(self), TUnit, out)
}

internal fun Ir2Mir.enumToString(info: ClassInfo): MirFunction {
    val self = MirLocal(0, "this", typeOf(info.ir))
    val body = listOf<MirStmt>(
        MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(self), info.cName, "\$name", TString)),
    )
    return MirFunction(info.qualified + ".toString", info.cName + "_enumToString", listOf(self), TString, body)
}

// ---- dispatch tables ---------------------------------------------------------------------

internal fun Ir2Mir.parentClass(f: IrSimpleFunction): IrClass? = f.parent as? IrClass

internal fun Ir2Mir.overridden(f: IrSimpleFunction): List<IrSimpleFunction> = f.overridden

internal fun Ir2Mir.allOverridden(f: IrSimpleFunction, acc: MutableSet<IrSimpleFunction> = LinkedHashSet()): Set<IrSimpleFunction> {
    if (acc.add(f)) for (o in overridden(f)) allOverridden(o, acc)
    return acc
}

/** The topmost class-declared function in the override chain of [f] (may be an Any member), or null. */
internal fun Ir2Mir.topClass(f: IrSimpleFunction): IrSimpleFunction? {
    val viaClass = overridden(f).filter { parentClass(it)?.kind != IrClassKind.INTERFACE }
    if (viaClass.isNotEmpty()) {
        for (v in viaClass) {
            val t = topClass(v)
            if (t != null) return t
        }
        return null
    }
    // An Any member reached only through an interface (`data class Q : I` overriding toString):
    // it still belongs to the fixed Any slots, not to a new vtable slot.
    allOverridden(f).firstOrNull { anyMember(it) != null }?.let { return it }
    return if (parentClass(f)?.kind != IrClassKind.INTERFACE) f else null
}

private fun Ir2Mir.anyRootOf(f: IrSimpleFunction): IrSimpleFunction? {
    if (parentClass(f)?.let { isAny(it) } == true) return f
    for (o in overridden(f)) anyRootOf(o)?.let { return it }
    return null
}

internal fun Ir2Mir.interfaceRoot(f: IrSimpleFunction): IrSimpleFunction {
    val up = overridden(f).filter { parentClass(it)?.kind == IrClassKind.INTERFACE }
    return if (up.isEmpty()) f else interfaceRoot(up.first())
}

/** 0 toString, 1 equals, 2 hashCode when [f] is the corresponding member of kotlin.Any. */
internal fun Ir2Mir.anyMember(f: IrSimpleFunction): Int? {
    val p = parentClass(f) ?: return null
    if (!isAny(p)) return null
    val n = f.parameters.size
    return when {
        f.name == "toString" && n == 1 -> 0
        f.name == "equals" && n == 2 -> 1
        f.name == "hashCode" && n == 1 -> 2
        else -> null
    }
}

internal fun Ir2Mir.hasBody(f: IrSimpleFunction) = f.body != null && f.modality != IrModality.ABSTRACT

internal fun Ir2Mir.resolveFake(f: IrSimpleFunction): IrSimpleFunction {
    if (!f.isFakeOverride) return f
    val cands = overridden(f).map { resolveFake(it) }
    return cands.firstOrNull { parentClass(it)?.kind != IrClassKind.INTERFACE && hasBody(it) }
        ?: cands.firstOrNull { hasBody(it) }
        ?: cands.firstOrNull()
        ?: f
}

internal fun Ir2Mir.implName(f: IrSimpleFunction?): String? {
    if (f == null) return null
    val p = parentClass(f)
    if (p != null && (isAny(p) || isRuntimeProvided(p))) return null
    if (!hasBody(f)) return null
    return functionNames[f] ?: error("no C name for ${f.name}")
}


/** Roots of the user vtable slots of [c] (superclass slots first); slot index = 3 + position. */
internal fun Ir2Mir.slotsOf(c: IrClass): List<IrSimpleFunction> = slotCache.getOrPut(c) {
    if (builtinOf(c) != null) {
        // runtime exception classes: vtable slot 3 is Throwable.message
        val sup = superclassOf(c)
        return@getOrPut if (sup != null) slotsOf(sup) else listOfNotNull(messageRootOf(c))
    }
    val base = superclassOf(c)?.let { slotsOf(it) } ?: emptyList()
    base + classFunctions(c).filter { f ->
        topClass(f) === f && anyMember(f) == null && f.modality != IrModality.FINAL &&
            f.visibility != "private"
    }
}

internal fun Ir2Mir.implClass(c: IrClass, root: IrSimpleFunction): String? {
    if (builtinOf(c) != null) return if (root === builtinMessageRoot) "mk_throwable_message" else null
    val g = classFunctions(c).firstOrNull { topClass(it) === root }
    val sup = superclassOf(c)
    if (g != null) {
        val r = bridged(resolveFake(g), root)
        if (r != null || !g.isFakeOverride) return r
    }
    if (sup == null) return null
    return implClass(sup, root)
}

internal fun Ir2Mir.implAny(c: IrClass, slot: Int): String? {
    val g = classFunctions(c).firstOrNull { anyMember(it) == null && topClass(it)?.let { t -> anyMember(t) } == slot }
    if (g != null) return implName(resolveFake(g))
    val sup = superclassOf(c) ?: return null
    return implAny(sup, slot)
}

internal fun Ir2Mir.interfaceMethods(i: IrClass): List<IrSimpleFunction> =
    classFunctions(i).filter { !it.isFakeOverride && it.visibility != "private" }

internal fun Ir2Mir.findImpl(c: IrClass, m: IrSimpleFunction): IrSimpleFunction? {
    val g = classFunctions(c).firstOrNull { m in allOverridden(it) }
    if (g != null) return resolveFake(g)
    val sup = superclassOf(c)
    if (sup != null) return findImpl(sup, m)
    return if (hasBody(m)) m else null
}

internal fun Ir2Mir.buildClass(info: ClassInfo): MirClass {
    val c = info.ir
    val isInterface = c.kind == IrClassKind.INTERFACE
    val ifaces = if (isInterface) allInterfaces(c).filter { it !== c } else allInterfaces(c)
    val hiddenFields = if (c.kind == IrClassKind.ENUM_CLASS) listOf(MirField("\$name", TString), MirField("\$ordinal", TInt)) else emptyList()
    val capFields = classCaptures(c).mapIndexed { i, v -> MirField("cap$i", capturedType(v)) }
    val outerField = if (c.isInner) listOf(MirField("\$outer", typeOf(c.parent as IrClass))) else emptyList()
    val fields = if (isInterface) emptyList() else hiddenFields + outerField + capFields + ownFields(c).map { f ->
        MirField(f.name, mirType(f.type, f), isBorrowedField(f))
    }
    var vtable = emptyList<String?>()
    var itables = emptyList<MirItable>()
    if (!isInterface) {
        val slots = slotsOf(c)
        vtable = (0..2).map { implAny(c, it) } + slots.map { implClass(c, it) }
        if (vtable[0] == null && inheritsBuiltin(c)) vtable = listOf<String?>("mk_throwable_to_string") + vtable.drop(1)
        if (c.kind == IrClassKind.ENUM_CLASS && vtable[0] == null) vtable = listOf<String?>(info.cName + "_enumToString") + vtable.drop(1)
        itables = ifaces.map { i ->
            MirItable(info(i).cName, interfaceMethods(i).map { m -> bridged(findImpl(c, m), m) })
        }
    }
    val objectCtor = if (c.kind == IrClassKind.OBJECT) {
        val k = c.declarations.filterIsInstance<IrConstructor>().firstOrNull() ?: unsupported(c, "object without constructor")
        functionNames[k]
    } else null
    return MirClass(
        info.qualified, info.cName, isInterface, superclassOf(c)?.let { info(it).cName }, fields,
        ifaces.map { info(it).cName }, vtable, itables, objectCtor, strict = isStrictClass(c),
    )
}


internal fun Ir2Mir.dispatchOf(f: IrSimpleFunction): Dispatch {
    val top = topClass(f)
    if (top != null) {
        anyMember(top)?.let { return Dispatch.AnyMember(it) }
        val owner = parentClass(top)!!
        if (top.modality != IrModality.FINAL && top.visibility != "private") {
            val idx = slotsOf(owner).indexOf(top)
            if (idx >= 0) return Dispatch.Virtual(3 + idx)
        }
        return Dispatch.Direct(resolveFake(f))
    }
    val root = interfaceRoot(f)
    val iface = parentClass(root)!!
    val idx = interfaceMethods(iface).indexOf(root)
    if (idx < 0) error("interface method ${root.name} not found")
    return Dispatch.Interface(info(iface).cName, idx)
}

internal fun Ir2Mir.checkFunctionShape(f: IrSimpleFunction, member: Boolean) {
    if (f.isSuspend) unsupported(f, "suspend function '${f.name}'")
    for (p in f.parameters) {
        when (p.kind) {
            IrParameterKind.Regular, IrParameterKind.ExtensionReceiver, IrParameterKind.Context -> {}
            IrParameterKind.DispatchReceiver -> if (!member) unsupported(f, "member function '${f.name}'")
        }
    }
    if (f.isExternal) unsupported(f, "external function '${f.name}'")
    if (!member && f.body == null) unsupported(f, "function without body '${f.name}'")
}

// ---- bridges ----------------------------------------------------------------------------------------------

/** How a MirType looks in C: only differences here need a bridge (all classes are `MkObject *`). */
private fun cKind(t: MirType): String = when {
    t == TString || t == TNString -> "S"
    t.isRef -> "O"
    else -> t.displayName
}


/**
 * C name of the implementation of [impl] to store in a vtable / itable slot whose signature is that
 * of [root]. Generic code erases type parameters (`Any?`), so an override such as
 * `override fun push(x: Int)` of `fun push(x: T)` has a different C signature than the slot; a small
 * bridge function with the slot's signature converts (unbox, cast) and calls the implementation.
 */
internal fun Ir2Mir.bridged(impl: IrSimpleFunction?, root: IrSimpleFunction): String? {
    val name = implName(impl) ?: return null
    impl!!
    val rp = root.parameters.map { mirType(it.type, it) }
    val ip = impl.parameters.map { mirType(it.type, it) }
    val rr = mirType(root.returnType, root)
    val ir = mirType(impl.returnType, impl)
    if (rp.size == ip.size && rp.indices.all { it == 0 || cKind(rp[it]) == cKind(ip[it]) } && cKind(rr) == cKind(ir)) return name
    val bname = name + "_br" + rp.joinToString("") { Mangling.typeLetter(it) } + "_" + Mangling.typeLetter(rr)
    if (!bridgeCache.add(bname)) return bname
    val fn = inFreshFunction {
        val ps = rp.mapIndexed { i, t -> MirLocal(localCounter++, if (i == 0) "this" else "p$i", t) }
        val out = ArrayList<MirStmt>()
        val args = ps.mapIndexed { i, p ->
            if (i == 0) MirExpr.Cast(MirExpr.Local(p), ip[0], MirExpr.CastMode.Unchecked)
            else coerceFlexible(MirExpr.Local(p), ip[i], impl, out)
        }
        val call = MirExpr.Call(name, args, ir)
        if (ir == TNothing) {
            out += MirStmt.Eval(call)
            out += if (rr == TUnit) MirStmt.Return(null) else MirStmt.Return(zeroOf(rr))
        } else if (rr == TUnit) {
            out += MirStmt.Eval(call)
            out += MirStmt.Return(null)
        } else {
            val v = if (call.isOwned()) tmpOf(call, out) else call
            out += MirStmt.Return(coerceFlexible(v, rr, impl, out))
        }
        MirFunction(impl.name + "\$bridge", bname, ps, rr, out)
    }
    extraFunctions += fn
    return bname
}

// ---- enum values() / valueOf() ----------------------------------------------------------------------------

internal fun Ir2Mir.enumValues(info: ClassInfo): MirFunction = inFreshFunction {
    val arrType = arrayType(arrayKindOfFq("kotlin.Array")!!)
    val entries = enumEntries(info.ir)
    val out = ArrayList<MirStmt>()
    val arr = tmpOf(MirExpr.RuntimeCall("mk_array_new_obj", listOf(MirExpr.IntConst(entries.size)), arrType), out)
    for (i in entries.indices) {
        out += MirStmt.Eval(arraySet(arrayKindOfFq("kotlin.Array")!!, arr, MirExpr.IntConst(i), enumGet(info, i)))
    }
    out += MirStmt.Return(arr)
    MirFunction(info.qualified + ".values", info.cName + "_values", emptyList(), arrType, out)
}

internal fun Ir2Mir.enumValueOf(info: ClassInfo): MirFunction = inFreshFunction {
    val name = MirLocal(localCounter++, "name", TString)
    val out = ArrayList<MirStmt>()
    for ((i, e) in enumEntries(info.ir).withIndex()) {
        out += MirStmt.If(
            MirExpr.Binary(BinaryOp.Eq, MirExpr.Local(name), MirExpr.StringConst(e.name)),
            listOf(MirStmt.Return(enumGet(info, i))), emptyList(),
        )
    }
    val msg = tmpOf(MirExpr.RuntimeCall("mk_string_concat", listOf(MirExpr.StringConst("No enum constant " + info.qualified + "."), MirExpr.Local(name)), TString), out)
    out += MirStmt.Throw(tmpOf(newBuiltin("MkIllegalArgumentException", msg), out))
    MirFunction(info.qualified + ".valueOf", info.cName + "_valueOf", listOf(name), typeOf(info.ir), out)
}
