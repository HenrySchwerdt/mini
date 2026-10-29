@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirField
import mini.backend.MirFunction
import mini.backend.MirItable
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TBoxed
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TNull
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.UnaryOp
import mini.backend.asNullable
import mini.backend.isOwned
import mini.backend.isPrimitive
import mini.backend.isRef
import mini.backend.nonNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements

/* Ir2Mir: classes, enums, dispatch tables (vtables / itables), function shape checks. */

// ---- classes --------------------------------------------------------------------------

internal fun Ir2Mir.collectClass(c: IrClass, pkg: String, path: List<String>, file: IrFile) {
    when (c.kind) {
        ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.OBJECT -> {}
        ClassKind.ENUM_CLASS -> {}
        else -> unsupported(c, "${classKind(c)} declaration '${c.name}'")
    }
    if (c.isValue) unsupported(c, "value class '${c.name}'")
    if (c.isFun) unsupported(c, "fun interface '${c.name}'")
    val cName = Mangling.className(pkg, path)
    val qn = (if (pkg.isEmpty()) "" else "$pkg.") + path.joinToString(".")
    classInfos[c] = ClassInfo(c, cName, qn, file)
    // the Comparable interface is compiled like a user interface (compareTo on generic values goes through mk_compare_to)
    for (t in c.superTypes) {
        val s = t.classOrNull?.owner ?: continue
        if (s.kotlinFqName.asString() == "kotlin.Comparable" && s !in classInfos) {
            classInfos[s] = ClassInfo(s, Mangling.className("kotlin", listOf("Comparable")), "kotlin.Comparable", file)
        }
    }
    for (d in c.declarations) {
        if (d is IrClass) collectClass(d, pkg, path + d.name.asString(), file)
    }
}

internal fun Ir2Mir.classKind(c: IrClass): String = when (c.kind) {
    ClassKind.INTERFACE -> "interface"
    ClassKind.ENUM_CLASS -> "enum class"
    ClassKind.ENUM_ENTRY -> "enum entry"
    ClassKind.ANNOTATION_CLASS -> "annotation class"
    ClassKind.OBJECT -> "object"
    else -> "class"
}

internal fun Ir2Mir.isAny(c: IrClass) = c.kotlinFqName.asString() == "kotlin.Any"

internal fun Ir2Mir.superclassOf(c: IrClass): IrClass? {
    if (c.kind == ClassKind.INTERFACE) return null
    if (builtinOf(c) != null) {
        for (t in c.superTypes) {
            val s = t.classOrNull?.owner ?: continue
            if (builtinOf(s) != null) return s
        }
        return null
    }
    for (t in c.superTypes) {
        val s = t.classOrNull?.owner ?: unsupported(c, "supertype '${t.classFqName}' of '${c.name}'")
        if (isAny(s) || s.kotlinFqName.asString() == "kotlin.Enum") continue
        if (s.kind == ClassKind.INTERFACE) {
            if (s !in classInfos) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
            continue
        }
        if (s !in classInfos && builtinOf(s) == null) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
        return s
    }
    return null
}

internal fun Ir2Mir.directInterfaces(c: IrClass): List<IrClass> = c.superTypes.mapNotNull { t ->
    val s = t.classOrNull?.owner
    if (s != null && s.kind == ClassKind.INTERFACE && s in classInfos) s else null
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
    return TObject(i.qualified, i.cName, nullable, c.kind == ClassKind.INTERFACE)
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
        functionNames[f] = Mangling.method(info.cName, f.name.asString(), params)
    }
    for (k in c.declarations.filterIsInstance<IrConstructor>()) {
        val hidden = if (c.kind == ClassKind.ENUM_CLASS) listOf(TString, TInt) else emptyList()
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
            if (d.isDelegated) unsupported(d, "delegated property '${d.name}'")
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
            if (f.modality == Modality.ABSTRACT || c.kind == ClassKind.INTERFACE || c.kind == ClassKind.ENUM_CLASS) continue
            unsupported(f, "function without body '${f.name}'")
        }
        out += convertFunction(f, functionNames[f]!!, info.qualified + "." + f.name.asString(), null)
    }
    for (k in c.declarations.filterIsInstance<IrConstructor>()) {
        out += convertFunction(k, functionNames[k]!!, info.qualified + ".<init>", info.cName)
    }
    if (c.kind == ClassKind.ENUM_CLASS) {
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
    if (c.kind != ClassKind.ENUM_CLASS) return false
    val n = f.name.asString()
    return (n == "values" || n == "valueOf" || n == "<get-entries>") && f.origin != IrDeclarationOrigin.DEFINED
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
        val k = call.symbol.owner
        val args = callArgs(call, k, out)
        val ctor = functionNames[k] ?: unsupported(e, "enum entry '${e.name}'")
        val n = MirExpr.New(ctor, listOf(MirExpr.StringConst(e.name.asString()), MirExpr.IntConst(i)) + args, typeOf(info.ir))
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

internal fun Ir2Mir.overridden(f: IrSimpleFunction): List<IrSimpleFunction> = f.overriddenSymbols.map { it.owner }

internal fun Ir2Mir.allOverridden(f: IrSimpleFunction, acc: MutableSet<IrSimpleFunction> = LinkedHashSet()): Set<IrSimpleFunction> {
    if (acc.add(f)) for (o in overridden(f)) allOverridden(o, acc)
    return acc
}

/** The topmost class-declared function in the override chain of [f] (may be an Any member), or null. */
internal fun Ir2Mir.topClass(f: IrSimpleFunction): IrSimpleFunction? {
    val viaClass = overridden(f).filter { parentClass(it)?.kind != ClassKind.INTERFACE }
    if (viaClass.isNotEmpty()) return viaClass.firstNotNullOfOrNull { topClass(it) }
    // An Any member reached only through an interface (`data class Q : I` overriding toString):
    // it still belongs to the fixed Any slots, not to a new vtable slot.
    allOverridden(f).firstOrNull { anyMember(it) != null }?.let { return it }
    return if (parentClass(f)?.kind != ClassKind.INTERFACE) f else null
}

private fun Ir2Mir.anyRootOf(f: IrSimpleFunction): IrSimpleFunction? {
    if (parentClass(f)?.let { isAny(it) } == true) return f
    for (o in overridden(f)) anyRootOf(o)?.let { return it }
    return null
}

internal fun Ir2Mir.interfaceRoot(f: IrSimpleFunction): IrSimpleFunction {
    val up = overridden(f).filter { parentClass(it)?.kind == ClassKind.INTERFACE }
    return if (up.isEmpty()) f else interfaceRoot(up.first())
}

/** 0 toString, 1 equals, 2 hashCode when [f] is the corresponding member of kotlin.Any. */
internal fun Ir2Mir.anyMember(f: IrSimpleFunction): Int? {
    val p = parentClass(f) ?: return null
    if (!isAny(p)) return null
    val n = f.parameters.size
    return when {
        f.name.asString() == "toString" && n == 1 -> 0
        f.name.asString() == "equals" && n == 2 -> 1
        f.name.asString() == "hashCode" && n == 1 -> 2
        else -> null
    }
}

internal fun Ir2Mir.hasBody(f: IrSimpleFunction) = f.body != null && f.modality != Modality.ABSTRACT

internal fun Ir2Mir.resolveFake(f: IrSimpleFunction): IrSimpleFunction {
    if (!f.isFakeOverride) return f
    val cands = overridden(f).map { resolveFake(it) }
    return cands.firstOrNull { parentClass(it)?.kind != ClassKind.INTERFACE && hasBody(it) }
        ?: cands.firstOrNull { hasBody(it) }
        ?: cands.firstOrNull()
        ?: f
}

internal fun Ir2Mir.implName(f: IrSimpleFunction?): String? {
    if (f == null) return null
    val p = parentClass(f)
    if (p != null && isAny(p)) return null
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
        topClass(f) === f && anyMember(f) == null && f.modality != Modality.FINAL &&
            f.visibility != DescriptorVisibilities.PRIVATE
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
    classFunctions(i).filter { !it.isFakeOverride && it.visibility != DescriptorVisibilities.PRIVATE }

internal fun Ir2Mir.findImpl(c: IrClass, m: IrSimpleFunction): IrSimpleFunction? {
    val g = classFunctions(c).firstOrNull { m in allOverridden(it) }
    if (g != null) return resolveFake(g)
    val sup = superclassOf(c)
    if (sup != null) return findImpl(sup, m)
    return if (hasBody(m)) m else null
}

internal fun Ir2Mir.buildClass(info: ClassInfo): MirClass {
    val c = info.ir
    val isInterface = c.kind == ClassKind.INTERFACE
    val ifaces = if (isInterface) allInterfaces(c).filter { it !== c } else allInterfaces(c)
    val hiddenFields = if (c.kind == ClassKind.ENUM_CLASS) listOf(MirField("\$name", TString), MirField("\$ordinal", TInt)) else emptyList()
    val capFields = classCaptures(c).mapIndexed { i, v -> MirField("cap$i", capturedType(v)) }
    val outerField = if (c.isInner) listOf(MirField("\$outer", typeOf(c.parent as IrClass))) else emptyList()
    val fields = if (isInterface) emptyList() else hiddenFields + outerField + capFields + ownFields(c).map { f ->
        if (f.origin == IrDeclarationOrigin.PROPERTY_DELEGATE) unsupported(f, "delegated property")
        MirField(f.name.asString(), mirType(f.type, f))
    }
    var vtable = emptyList<String?>()
    var itables = emptyList<MirItable>()
    if (!isInterface) {
        val slots = slotsOf(c)
        vtable = (0..2).map { implAny(c, it) } + slots.map { implClass(c, it) }
        if (vtable[0] == null && inheritsBuiltin(c)) vtable = listOf<String?>("mk_throwable_to_string") + vtable.drop(1)
        if (c.kind == ClassKind.ENUM_CLASS && vtable[0] == null) vtable = listOf<String?>(info.cName + "_enumToString") + vtable.drop(1)
        itables = ifaces.map { i ->
            MirItable(info(i).cName, interfaceMethods(i).map { m -> bridged(findImpl(c, m), m) })
        }
    }
    val objectCtor = if (c.kind == ClassKind.OBJECT) {
        val k = c.declarations.filterIsInstance<IrConstructor>().firstOrNull() ?: unsupported(c, "object without constructor")
        functionNames[k]
    } else null
    return MirClass(
        info.qualified, info.cName, isInterface, superclassOf(c)?.let { info(it).cName }, fields,
        ifaces.map { info(it).cName }, vtable, itables, objectCtor,
    )
}


internal fun Ir2Mir.dispatchOf(f: IrSimpleFunction): Dispatch {
    val top = topClass(f)
    if (top != null) {
        anyMember(top)?.let { return Dispatch.AnyMember(it) }
        val owner = parentClass(top)!!
        if (top.modality != Modality.FINAL && top.visibility != DescriptorVisibilities.PRIVATE) {
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
            IrParameterKind.Regular, IrParameterKind.ExtensionReceiver -> {}
            IrParameterKind.Context -> unsupported(f, "context parameter in '${f.name}'")
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
        if (rr == TUnit) {
            out += MirStmt.Eval(call)
            out += MirStmt.Return(null)
        } else {
            val v = if (call.isOwned()) tmpOf(call, out) else call
            out += MirStmt.Return(coerceFlexible(v, rr, impl, out))
        }
        MirFunction(impl.name.asString() + "\$bridge", bname, ps, rr, out)
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
            MirExpr.Binary(BinaryOp.Eq, MirExpr.Local(name), MirExpr.StringConst(e.name.asString())),
            listOf(MirStmt.Return(enumGet(info, i))), emptyList(),
        )
    }
    val msg = tmpOf(MirExpr.RuntimeCall("mk_string_concat", listOf(MirExpr.StringConst("No enum constant " + info.qualified + "."), MirExpr.Local(name)), TString), out)
    out += MirStmt.Throw(tmpOf(newBuiltin("MkIllegalArgumentException", msg), out))
    MirFunction(info.qualified + ".valueOf", info.cName + "_valueOf", listOf(name), typeOf(info.ir), out)
}
