
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
    val cName = Mangling.className(pkg, path)
    val qn = (if (pkg.isEmpty()) "" else "$pkg.") + path.joinToString(".")
    classInfos[c] = ClassInfo(c, cName, qn, file)
    // the Comparable interface is compiled like a user interface (compareTo on generic values goes through mk_compare_to)
    for (s in comparableSupers(c)) {
        if (s !in classInfos) {
            val sn = s.kotlinFqName.removePrefix("kotlin.")
            classInfos[s] = ClassInfo(s, Mangling.className("kotlin", listOf(sn)), "kotlin." + sn, file)
        }
    }
    // kotlin.Number is a class the runtime knows (boxed numbers); a user class that extends it gets the class compiled with the vtable slots of its members
    for (t in c.superTypes) {
        val s = t.classOrNull ?: continue
        if (s.kotlinFqName == "kotlin.Number" && s !in classInfos) classInfos[s] = ClassInfo(s, Mangling.className("kotlin", listOf("Number")), "kotlin.Number", file)
    }
    for (d in c.declarations) {
        if (d is IrClass && !isPtrClass(d) && d.kind != IrClassKind.ANNOTATION_CLASS) collectClass(d, pkg, path + d.name, file)
    }
}

/** The kotlin.Comparable interface among the supertypes of [c]; an enum class gets it through kotlin.Enum. */
internal fun Ir2Mir.comparableSupers(c: IrClass): List<IrClass> {
    val out = ArrayList<IrClass>()
    for (t in c.superTypes) {
        val s = t.classOrNull ?: continue
        if (s.kotlinFqName == "kotlin.Comparable" || s.kotlinFqName == "kotlin.CharSequence") out += s
        else if (c.kind == IrClassKind.ENUM_CLASS && s.kotlinFqName == "kotlin.Enum") {
            for (t2 in s.superTypes) {
                val s2 = t2.classOrNull ?: continue
                if (s2.kotlinFqName == "kotlin.Comparable") out += s2
            }
        }
    }
    return out
}

internal fun Ir2Mir.classKind(c: IrClass): String = when (c.kind) {
    IrClassKind.INTERFACE -> "interface"
    IrClassKind.ENUM_CLASS -> "enum class"
    IrClassKind.ENUM_ENTRY -> "enum entry"
    IrClassKind.ANNOTATION_CLASS -> "annotation class"
    IrClassKind.OBJECT -> "object"
    else -> "class"
}

/** The anonymous subclass of an enum that is the body of an entry (`A { ... }`). */
internal fun isEnumBody(c: IrClass): Boolean = c.sym?.isEnumEntryBody == true

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
        if (t is FunType) continue   // `class F : (Int) -> Int`: see functionSupertypes
        val s = t.classOrNull ?: unsupported(c, "supertype '${t.classFqName}' of '${c.name}'")
        if (isAny(s) || s.kotlinFqName == "kotlin.Enum" || s.kotlinFqName == "kotlin.Annotation" || s.kotlinFqName == "kotlin.Function") continue
        if (s.kind == IrClassKind.INTERFACE) {
            if (s !in classInfos) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
            continue
        }
        if (s !in classInfos && builtinOf(s) == null) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
        return s
    }
    return null
}

/** The arities of the function types (`(Int) -> Int` is 1) that [c] inherits from, through its supertypes and their supertypes. */
internal fun Ir2Mir.functionSupertypes(c: IrClass, acc: MutableSet<Int> = LinkedHashSet()): Set<Int> {
    for (t in c.superTypes) {
        if (t is FunType) acc += t.params.size + (if (t.receiver != null) 1 else 0) + (if (t.suspend) 1 else 0)
        else {
            val s = t.classOrNull ?: continue
            if (s in classInfos && s !== c) functionSupertypes(s, acc)
        }
    }
    return acc
}

/** The `invoke` with [arity] value parameters that [c] declares or inherits from a class. */
internal fun Ir2Mir.invokeImpl(c: IrClass, arity: Int): IrSimpleFunction? {
    val g = classFunctions(c).firstOrNull { it.name == "invoke" && !it.isFakeOverride && it.parameters.count { p -> p.kind != IrParameterKind.DispatchReceiver } == arity && hasBody(it) }
    if (g != null) return g
    val sup = superclassOf(c) ?: return null
    return invokeImpl(sup, arity)
}

/** [impl] with the erased signature of `FunctionN.invoke` (everything `Any?`), as the itable of a function interface wants it. */
internal fun Ir2Mir.erasedInvoke(impl: IrSimpleFunction, arity: Int): String? {
    val name = implName(impl) ?: return null
    val rp = listOf<MirType>(typeOf(impl.parent as IrClass)) + List(arity) { anyNullable }
    val ip = impl.parameters.map { mirType(it.type, it) }
    val ir = mirType(impl.returnType, impl)
    if (rp.indices.all { it == 0 || cKind(rp[it]) == cKind(ip[it]) } && cKind(anyNullable) == cKind(ir)) return name
    val bname = name + "_brF" + arity
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
            out += MirStmt.Return(MirExpr.NullConst(anyNullable))
        } else if (ir == TUnit) {
            out += MirStmt.Eval(call)
            out += MirStmt.Return(MirExpr.NullConst(anyNullable))
        } else {
            val v = if (call.isOwned()) tmpOf(call, out) else call
            out += MirStmt.Return(coerceFlexible(v, anyNullable, impl, out))
        }
        MirFunction(impl.name + "\$bridge", bname, ps, anyNullable, out)
    }
    extraFunctions += fn
    return bname
}

internal fun Ir2Mir.directInterfaces(c: IrClass): List<IrClass> = c.superTypes.mapNotNull { t ->
    val s = t.classOrNull
    if (s != null && s.kind == IrClassKind.INTERFACE && s in classInfos) s else null
} + (if (c.kind == IrClassKind.ENUM_CLASS) comparableSupers(c).filter { it in classInfos } else emptyList())

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
        val hidden = if (c.kind == IrClassKind.ENUM_CLASS || isEnumBody(c)) listOf(TString, TInt) else emptyList()
        // captured values of local classes follow the declared parameters
        functionNames[k] = Mangling.constructor(info.cName, hidden + k.parameters.map { mirType(it.type, it) } + captureTypesFor(c))
    }
    for (d in c.declarations) {
        when (d) {
            is IrSimpleFunction, is IrProperty, is IrConstructor, is IrClass, is IrAnonymousInitializer, is IrField -> {}
            is IrEnumEntry -> {}
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
    if (c.kotlinFqName == "kotlin.Number") return out   // the members are the runtime's (boxed numbers) or the user class's
    for (f in classFunctions(c)) {
        if (f.isFakeOverride || isEnumSpecial(c, f)) continue
        if (f.body == null) {
            if (f.modality == IrModality.ABSTRACT || c.kind == IrClassKind.INTERFACE || c.kind == IrClassKind.ENUM_CLASS) continue
            unsupported(f, "function without body '${f.name}'")
        }
        out += convertFunction(f, functionNames[f]!!, info.qualified + "." + f.name, null)
    }
    if (inlineRepOf(c) != null) out += valueClassFunctions(info)
    else for (k in c.declarations.filterIsInstance<IrConstructor>()) {
        out += convertFunction(k, functionNames[k]!!, info.qualified + ".<init>", info.cName)
    }
    if (c.kind == IrClassKind.ENUM_CLASS) {
        out += convertEnumHolder(info)
        out += enumToString(info)
        out += enumCompareTo(info)
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
        val made = MirExpr.New(ctor, listOf(MirExpr.StringConst(e.name), MirExpr.IntConst(i)) + args, typeOf(k.parent as IrClass))
        val n = if (k.parent === info.ir) made else MirExpr.Cast(tmpOf(made, out), typeOf(info.ir), MirExpr.CastMode.Unchecked)
        out += MirStmt.SetField(MirExpr.Local(self), holderName(info), "e$i", typeOf(info.ir), n)
    }
    // the companion object is initialized after the entries
    val comp = info.ir.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion && it in classInfos }
    if (comp != null) out += MirStmt.Eval(MirExpr.ObjectGet(info(comp).cName, typeOf(comp)))
    return MirFunction(info.qualified + ".<entries>", holderName(info) + "_C", listOf(self), TUnit, out)
}

internal fun Ir2Mir.enumToString(info: ClassInfo): MirFunction {
    val self = MirLocal(0, "this", typeOf(info.ir))
    val body = listOf<MirStmt>(
        MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(self), info.cName, "\$name", TString)),
    )
    return MirFunction(info.qualified + ".toString", info.cName + "_enumToString", listOf(self), TString, body)
}

/** `Comparable.compareTo` of an enum: the difference of the ordinals (the slot of the Comparable itable, see buildClass). */
internal fun Ir2Mir.enumCompareTo(info: ClassInfo): MirFunction {
    val self = MirLocal(0, "this", typeOf(info.ir))
    val other = MirLocal(1, "other", anyNullable)
    val o = MirExpr.Cast(MirExpr.Local(other), typeOf(info.ir), MirExpr.CastMode.Checked)
    val body = listOf<MirStmt>(
        MirStmt.Return(MirExpr.Binary(BinaryOp.Sub, MirExpr.FieldGet(MirExpr.Local(self), info.cName, "\$ordinal", TInt),
            MirExpr.FieldGet(o, info.cName, "\$ordinal", TInt))),
    )
    return MirFunction(info.qualified + ".compareTo", info.cName + "_enumCompareTo", listOf(self, other), TInt, body)
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

internal fun Ir2Mir.hasBody(f: IrSimpleFunction) = (f.body != null || (f.stub && f.hasBody)) && f.modality != IrModality.ABSTRACT

internal fun Ir2Mir.resolveFake(f: IrSimpleFunction): IrSimpleFunction {
    if (!f.isFakeOverride) return f
    val all = overridden(f).map { resolveFake(it) }
    // a diamond (`D : B, C` with `C` overriding what `B` inherits from `A`): the implementation another candidate overrides is not a candidate
    val cands = all.filter { c -> all.none { o -> o !== c && c in allOverridden(o) } }.ifEmpty { all }
    return cands.firstOrNull { parentClass(it)?.kind != IrClassKind.INTERFACE && hasBody(it) }
        ?: cands.firstOrNull { hasBody(it) }
        ?: cands.firstOrNull()
        ?: f
}

/** A function `(enum) -> name / ordinal`: every enum class starts with the same hidden fields, so the layout of any one gives it. */
internal fun Ir2Mir.enumAccessor(name: Boolean): String {
    val owner = classInfos.values.first { it.ir.kind == IrClassKind.ENUM_CLASS }
    val cName = "mkh_enum_" + (if (name) "get_name" else "get_ordinal")
    if (extraFunctions.none { it.cName == cName }) {
        val self = MirLocal(0, "this", anyNullable)
        val t = if (name) TString else TInt
        val field = MirExpr.FieldGet(MirExpr.Cast(MirExpr.Local(self), typeOf(owner.ir), MirExpr.CastMode.Unchecked), owner.cName, if (name) "\$name" else "\$ordinal", t)
        extraFunctions += MirFunction("kotlin.Enum." + (if (name) "name" else "ordinal"), cName, listOf(self), t, listOf(MirStmt.Return(field)))
    }
    return cName
}

internal fun Ir2Mir.implName(f: IrSimpleFunction?): String? {
    if (f == null) return null
    val p = parentClass(f)
    if (p != null && (isAny(p) || isRuntimeProvided(p))) return null
    // `enum class E : Named` where `Named.name` is implemented by Enum.name: the itable entry reads the hidden field
    if (p != null && p.kotlinFqName == "kotlin.Enum" && (f.name == "<get-name>" || f.name == "<get-ordinal>")) return enumAccessor(f.name == "<get-name>")
    if (!hasBody(f)) return null
    val n = functionNames[f] ?: error("no C name for ${f.name}")
    if (p != null && p.isValue && f.parameters.isNotEmpty() && f.parameters[0].kind == IrParameterKind.DispatchReceiver && inlineRepOf(p) != null) return valueClassWrapper(f, n)
    return n
}


/** Roots of the user vtable slots of [c] (superclass slots first); slot index = 3 + position. */
internal fun Ir2Mir.slotsOf(c: IrClass): List<IrSimpleFunction> = slotCache.getOrPut(c) {
    if (builtinOf(c) != null) {
        // runtime exception classes: vtable slot 3 is Throwable.message
        val sup = superclassOf(c)
        return@getOrPut if (sup != null) slotsOf(sup) else listOfNotNull(messageRootOf(c))
    }
    val base = superclassOf(c)?.let { slotsOf(it) } ?: emptyList()
    val own = classFunctions(c).filter { f ->
        topClass(f) === f && anyMember(f) == null && f.modality != IrModality.FINAL &&
            f.visibility != "private"
    }
    // the runtime (v1l.c, mk_checked_number_to_*) calls these slots of a user class that extends Number: a fixed order
    if (c.kotlinFqName == "kotlin.Number") {
        val order = listOf("toDouble", "toFloat", "toLong", "toInt", "toChar", "toShort", "toByte")
        return@getOrPut base + own.sortedBy { order.indexOf(it.name) }
    }
    base + own
}

internal fun Ir2Mir.implClass(c: IrClass, root: IrSimpleFunction): String? {
    if (c.kotlinFqName == "kotlin.Number" && root.name == "toChar") return "mk_number_to_char_default"
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
    val capFields = classCaptures(c).mapIndexed { i, v -> MirField("cap$i", capturedType(v), isStrictClass(c) && v !in closure.boxed && v.type.classOrNull?.let { k -> isStrictClass(k) } == true) }
    val outerField = if (c.isInner) listOf(MirField("\$outer", typeOf(c.parent as IrClass))) else emptyList()
    val fields = if (isInterface) emptyList() else hiddenFields + outerField + capFields + ownFields(c).map { f ->
        MirField(f.name, mirType(f.type, f), isBorrowedField(f))
    }
    var vtable = emptyList<String?>()
    var itables = emptyList<MirItable>()
    val funIfaces = ArrayList<String>()
    if (!isInterface) {
        val slots = slotsOf(c)
        vtable = (0..2).map { implAny(c, it) } + slots.map { implClass(c, it) }
        if (vtable[0] == null && inheritsBuiltin(c)) vtable = listOf<String?>("mk_throwable_to_string") + vtable.drop(1)
        if (c.kind == IrClassKind.ENUM_CLASS && vtable[0] == null) vtable = listOf<String?>(info.cName + "_enumToString") + vtable.drop(1)
        if (isEnumBody(c) && vtable[0] == null) superclassOf(c)?.let { vtable = listOf<String?>(info(it).cName + "_enumToString") + vtable.drop(1) }
        itables = ifaces.map { i ->
            if ((c.kind == IrClassKind.ENUM_CLASS || isEnumBody(c)) && i.kotlinFqName == "kotlin.Comparable") {
                val enumInfo = if (isEnumBody(c)) info(superclassOf(c)!!) else info
                MirItable(info(i).cName, listOf(enumInfo.cName + "_enumCompareTo"))
            }
            else MirItable(info(i).cName, interfaceMethods(i).map { m -> bridged(findImpl(c, m), m) })
        }
        for (n in functionSupertypes(c)) {
            val impl = invokeImpl(c, n)
            if (impl == null && c.modality != IrModality.ABSTRACT) unsupported(c, "class '${c.name}' implements a function type without an invoke function")
            funIfaces += functionInterface(n).cName
            // an abstract class leaves the invoke to its subclasses
            itables = itables + MirItable(functionInterface(n).cName, listOf(if (impl == null) null else erasedInvoke(impl, n)))
        }
    }
    val objectCtor = if (c.kind == IrClassKind.OBJECT) {
        val k = c.declarations.filterIsInstance<IrConstructor>().firstOrNull() ?: unsupported(c, "object without constructor")
        functionNames[k]
    } else null
    return MirClass(
        info.qualified, info.cName, isInterface, superclassOf(c)?.let { info(it).cName }, fields,
        ifaces.map { info(it).cName } + funIfaces, vtable, itables, objectCtor, strict = isStrictClass(c),
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
    // a private interface function (with a body) is not part of the itable: it is called directly
    if (idx < 0 && root.visibility == "private" && root.body != null) return Dispatch.Direct(root)
    if (idx < 0) error("interface method ${root.name} not found")
    return Dispatch.Interface(info(iface).cName, idx)
}

internal fun Ir2Mir.checkFunctionShape(f: IrSimpleFunction, member: Boolean) {
    for (p in f.parameters) {
        when (p.kind) {
            IrParameterKind.Regular, IrParameterKind.ExtensionReceiver, IrParameterKind.Context -> {}
            IrParameterKind.DispatchReceiver -> if (!member) unsupported(f, "member function '${f.name}'")
        }
    }
    if (f.isExternal) unsupported(f, "external function '${f.name}'")
    if (!member && f.body == null && !currentFile.isLib) unsupported(f, "function without body '${f.name}'")
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
    if (name.startsWith("mkh_enum_get_")) return name   // the accessor of the hidden enum fields takes any object
    val rp = root.parameters.map { mirType(it.type, it) }
    val ip = impl.parameters.map { mirType(it.type, it) }
    val rr = mirType(root.returnType, root)
    val ir = mirType(impl.returnType, impl)
    if (rp.size == ip.size && rp.indices.all { it == 0 || cKind(rp[it]) == cKind(ip[it]) } && cKind(rr) == cKind(ir)) return name
    val bname = name + "_br" + rp.joinToString("") { Mangling.typeLetter(it) } + "_" + Mangling.typeLetter(rr)
    if (!bridgeCache.add(bname)) return bname
    val failures = libFailures
    val fn = try { inFreshFunction {
        val ps = rp.mapIndexed { i, t -> MirLocal(localCounter++, if (i == 0) "this" else "p$i", t) }
        val out = ArrayList<MirStmt>()
        val args = ps.mapIndexed { i, p ->
            if (i == 0) MirExpr.Cast(MirExpr.Local(p), ip[0].let { if (it is TInline) inlineObjType(it, true) else it }, MirExpr.CastMode.Unchecked)
            else if (ip[i] == TNothing) {
                // `override fun foo(p: Nothing)` of `foo(p: T)`: the bridge casts the argument to Nothing, which never succeeds
                out += MirStmt.Throw(tmpOf(newBuiltin("MkClassCastException", MirExpr.StringConst("value cannot be cast to kotlin.Nothing")), out))
                MirExpr.NullConst(anyNullable)
            }
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
    } } catch (e: UnsupportedConstruct) {
        // building a library: record it, the link reports it when a program reaches the bridge (docs/mlib.md)
        if (failures == null) throw e
        failures[bname] = (e.message ?: "unsupported construct").replace('\n', ' ')
        MirFunction(impl.name + "\$bridge", bname, rp.mapIndexed { i, t -> MirLocal(i, if (i == 0) "this" else "p$i", t) }, rr, listOf(MirStmt.Panic("unsupported construct in library code")))
    }
    extraFunctions += fn
    return bname
}

// ---- enum values() / valueOf() ----------------------------------------------------------------------------

/** `Array<E>` of an enum class E: the array `values()` makes records E as its element type. */
internal fun Ir2Mir.enumArrayType(info: ClassInfo): TObject = arrayType(arrayKindOfFq("kotlin.Array")!!).copy(elem = typeOf(info.ir))

internal fun Ir2Mir.enumValues(info: ClassInfo): MirFunction = inFreshFunction {
    val arrType = enumArrayType(info)
    val entries = enumEntries(info.ir)
    val out = ArrayList<MirStmt>()
    val arr = tmpOf(newObjectArray(MirExpr.IntConst(entries.size), arrType), out)
    for (i in entries.indices) {
        out += MirStmt.Eval(arraySet(arrayKindOfFq("kotlin.Array")!!, arr, MirExpr.IntConst(i), enumGet(info, i), true))
    }
    out += MirStmt.Return(arr)
    MirFunction(info.qualified + ".values", info.cName + "_values", emptyList(), arrType, out)
}

internal fun Ir2Mir.enumValueOf(info: ClassInfo): MirFunction = inFreshFunction {
    val name = MirLocal(localCounter++, "name", TString)
    val out = ArrayList<MirStmt>()
    // the class is initialized even when no entry has the name
    out += MirStmt.Eval(MirExpr.ObjectGet(holderName(info), holderType(info)))
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
