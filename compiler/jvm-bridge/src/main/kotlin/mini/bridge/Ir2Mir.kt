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

/** A Kotlin construct the backend does not implement. The message includes the source location. */
class UnsupportedConstruct(message: String) : Exception(message)

/**
 * Kotlin IR (post fir2ir) to MIR. Everything that touches Kotlin compiler internals
 * lives here; the produced MIR is pure data from the `backend` module.
 *
 * Ownership rule produced here (see MirExpr.isOwned): an expression that creates a new
 * reference is immediately stored in a fresh temporary local (`tmpOf`), and reads of
 * reference fields are copied (retained) into temporaries as well, so every operand is a
 * local, constant, cast of a local, or primitive expression.
 */
class Ir2Mir {
    private var labelCounter = 0
    private lateinit var fileEntry: IrFileEntry
    private lateinit var fileName: String

    private class ClassInfo(val ir: IrClass, val cName: String, val qualified: String, val file: IrFile)

    private val classInfos = LinkedHashMap<IrClass, ClassInfo>()

    /** user functions of the module being converted (top-level, members, constructors): IR function -> C name */
    private val functionNames = HashMap<IrFunction, String>()

    // per function state
    private var locals = HashMap<Any, MirLocal>()
    private var localCounter = 0
    private val loopLabels = HashMap<IrLoop, MirLabel>()
    private var currentFunction: IrFunction? = null
    private var currentReturn: MirType = TUnit
    private val ownedTemps = HashSet<MirLocal>()

    fun convert(module: IrModuleFragment, sourceName: String): MirModule {
        // pass 1: classes and top-level declarations
        val tops = ArrayList<Pair<IrFile, IrSimpleFunction>>()
        for (file in module.files) {
            setFile(file)
            val pkg = file.packageFqName.asString()
            for (d in file.declarations) {
                when (d) {
                    is IrSimpleFunction -> tops += file to d
                    is IrClass -> collectClass(d, pkg, listOf(d.name.asString()), file)
                    is IrProperty -> unsupported(d, "top-level property '${d.name.asString()}'")
                    else -> unsupported(d, describe(d))
                }
            }
        }
        // pass 2: names of all functions
        for ((file, f) in tops) {
            setFile(file)
            checkFunctionShape(f, member = false)
            val params = f.parameters.map { mirType(it.type, it) }
            if (f.name.asString() == "main" && params.isNotEmpty()) {
                unsupported(f, "main function with parameters (only `fun main()` is supported)")
            }
            functionNames[f] = Mangling.function(file.packageFqName.asString(), f.name.asString(), params)
        }
        for (info in classInfos.values) {
            setFile(info.file)
            registerMembers(info)
        }
        // pass 3: bodies
        val functions = ArrayList<MirFunction>()
        for ((file, f) in tops) {
            setFile(file)
            val pkg = file.packageFqName.asString()
            functions += convertFunction(f, functionNames[f]!!, (if (pkg.isEmpty()) "" else "$pkg.") + f.name.asString(), null)
        }
        for (info in classInfos.values) {
            setFile(info.file)
            functions += convertMembers(info)
        }
        val classes = ArrayList<MirClass>()
        val done = HashSet<IrClass>()
        fun visit(c: IrClass) {
            if (!done.add(c)) return
            superclassOf(c)?.let { visit(it) }
            for (i in directInterfaces(c)) visit(i)
            classes += buildClass(classInfos[c]!!)
        }
        for (c in classInfos.keys.toList()) visit(c)
        return MirModule(sourceName, functions, classes)
    }

    private fun setFile(f: IrFile) {
        fileEntry = f.fileEntry
        fileName = f.fileEntry.name
    }

    // ---- classes --------------------------------------------------------------------------

    private fun collectClass(c: IrClass, pkg: String, path: List<String>, file: IrFile) {
        when (c.kind) {
            ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.OBJECT -> {}
            ClassKind.ENUM_CLASS -> unsupported(c, "enum class declaration '${c.name}'")
            else -> unsupported(c, "${classKind(c)} declaration '${c.name}'")
        }
        if (c.typeParameters.isNotEmpty()) unsupported(c, "generic class '${c.name}'")
        if (c.isInner) unsupported(c, "inner class '${c.name}'")
        if (c.isValue) unsupported(c, "value class '${c.name}'")
        if (c.isFun) unsupported(c, "fun interface '${c.name}'")
        val cName = Mangling.className(pkg, path)
        val qn = (if (pkg.isEmpty()) "" else "$pkg.") + path.joinToString(".")
        classInfos[c] = ClassInfo(c, cName, qn, file)
        for (d in c.declarations) {
            if (d is IrClass) collectClass(d, pkg, path + d.name.asString(), file)
        }
    }

    private fun classKind(c: IrClass): String = when (c.kind) {
        ClassKind.INTERFACE -> "interface"
        ClassKind.ENUM_CLASS -> "enum class"
        ClassKind.ENUM_ENTRY -> "enum entry"
        ClassKind.ANNOTATION_CLASS -> "annotation class"
        ClassKind.OBJECT -> "object"
        else -> "class"
    }

    private fun isAny(c: IrClass) = c.kotlinFqName.asString() == "kotlin.Any"

    private fun superclassOf(c: IrClass): IrClass? {
        if (c.kind == ClassKind.INTERFACE) return null
        for (t in c.superTypes) {
            val s = t.classOrNull?.owner ?: unsupported(c, "supertype '${t.classFqName}' of '${c.name}'")
            if (isAny(s)) continue
            if (s.kind == ClassKind.INTERFACE) {
                if (s !in classInfos) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
                continue
            }
            if (s !in classInfos) unsupported(c, "supertype '${s.kotlinFqName}' of '${c.name}'")
            return s
        }
        return null
    }

    private fun directInterfaces(c: IrClass): List<IrClass> = c.superTypes.mapNotNull { t ->
        val s = t.classOrNull?.owner
        if (s != null && s.kind == ClassKind.INTERFACE && s in classInfos) s else null
    }

    /** All interfaces of [c], transitively and including those of superclasses, super-interfaces first. */
    private fun allInterfaces(c: IrClass): List<IrClass> {
        val out = LinkedHashSet<IrClass>()
        fun addIface(i: IrClass) {
            for (j in directInterfaces(i)) addIface(j)
            out += i
        }
        superclassOf(c)?.let { out += allInterfaces(it) }
        for (i in directInterfaces(c)) addIface(i)
        return out.toList()
    }

    private fun info(c: IrClass): ClassInfo = classInfos[c] ?: error("unknown class ${c.name}")

    private fun typeOf(c: IrClass, nullable: Boolean = false): TObject {
        val i = info(c)
        return TObject(i.qualified, i.cName, nullable, c.kind == ClassKind.INTERFACE)
    }

    private fun classFunctions(c: IrClass): List<IrSimpleFunction> = c.declarations.flatMap {
        when (it) {
            is IrSimpleFunction -> listOf(it)
            is IrProperty -> listOfNotNull(it.getter, it.setter)
            else -> emptyList()
        }
    }

    private fun ownFields(c: IrClass): List<IrField> = c.declarations.flatMap {
        when (it) {
            is IrProperty -> listOfNotNull(it.backingField)
            is IrField -> listOf(it)
            else -> emptyList()
        }
    }

    private fun registerMembers(info: ClassInfo) {
        val c = info.ir
        for (f in classFunctions(c)) {
            if (f.isFakeOverride) continue
            checkFunctionShape(f, member = true)
            val params = f.parameters.filter { it.kind == IrParameterKind.Regular }.map { mirType(it.type, it) }
            functionNames[f] = Mangling.method(info.cName, f.name.asString(), params)
        }
        for (k in c.declarations.filterIsInstance<IrConstructor>()) {
            for (p in k.parameters) {
                if (p.kind != IrParameterKind.Regular) unsupported(k, "constructor of an inner class")
                if (p.varargElementType != null) unsupported(p, "vararg parameter '${p.name}'")
            }
            functionNames[k] = Mangling.constructor(info.cName, k.parameters.map { mirType(it.type, it) })
        }
        for (d in c.declarations) {
            when (d) {
                is IrSimpleFunction, is IrProperty, is IrConstructor, is IrClass, is IrAnonymousInitializer, is IrField -> {}
                else -> unsupported(d, describe(d))
            }
            if (d is IrProperty) {
                if (d.isDelegated) unsupported(d, "delegated property '${d.name}'")
                if (d.isLateinit) unsupported(d, "lateinit property '${d.name}'")
                if (d.getter?.extensionReceiverParameter() != null) unsupported(d, "extension property '${d.name}'")
            }
        }
    }

    private fun IrFunction.extensionReceiverParameter(): IrValueParameter? =
        parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }

    private fun convertMembers(info: ClassInfo): List<MirFunction> {
        val out = ArrayList<MirFunction>()
        val c = info.ir
        for (f in classFunctions(c)) {
            if (f.isFakeOverride) continue
            if (f.body == null) {
                if (f.modality == Modality.ABSTRACT || c.kind == ClassKind.INTERFACE) continue
                unsupported(f, "function without body '${f.name}'")
            }
            out += convertFunction(f, functionNames[f]!!, info.qualified + "." + f.name.asString(), null)
        }
        for (k in c.declarations.filterIsInstance<IrConstructor>()) {
            out += convertFunction(k, functionNames[k]!!, info.qualified + ".<init>", info.cName)
        }
        return out
    }

    // ---- dispatch tables ---------------------------------------------------------------------

    private fun parentClass(f: IrSimpleFunction): IrClass? = f.parent as? IrClass

    private fun overridden(f: IrSimpleFunction): List<IrSimpleFunction> = f.overriddenSymbols.map { it.owner }

    private fun allOverridden(f: IrSimpleFunction, acc: MutableSet<IrSimpleFunction> = LinkedHashSet()): Set<IrSimpleFunction> {
        if (acc.add(f)) for (o in overridden(f)) allOverridden(o, acc)
        return acc
    }

    /** The topmost class-declared function in the override chain of [f] (may be an Any member), or null. */
    private fun topClass(f: IrSimpleFunction): IrSimpleFunction? {
        val viaClass = overridden(f).filter { parentClass(it)?.kind != ClassKind.INTERFACE }
        if (viaClass.isNotEmpty()) return viaClass.firstNotNullOfOrNull { topClass(it) }
        return if (parentClass(f)?.kind != ClassKind.INTERFACE) f else null
    }

    private fun interfaceRoot(f: IrSimpleFunction): IrSimpleFunction {
        val up = overridden(f).filter { parentClass(it)?.kind == ClassKind.INTERFACE }
        return if (up.isEmpty()) f else interfaceRoot(up.first())
    }

    /** 0 toString, 1 equals, 2 hashCode when [f] is the corresponding member of kotlin.Any. */
    private fun anyMember(f: IrSimpleFunction): Int? {
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

    private fun hasBody(f: IrSimpleFunction) = f.body != null && f.modality != Modality.ABSTRACT

    private fun resolveFake(f: IrSimpleFunction): IrSimpleFunction {
        if (!f.isFakeOverride) return f
        val cands = overridden(f).map { resolveFake(it) }
        return cands.firstOrNull { parentClass(it)?.kind != ClassKind.INTERFACE && hasBody(it) }
            ?: cands.firstOrNull { hasBody(it) }
            ?: cands.firstOrNull()
            ?: f
    }

    private fun implName(f: IrSimpleFunction?): String? {
        if (f == null) return null
        val p = parentClass(f)
        if (p != null && isAny(p)) return null
        if (!hasBody(f)) return null
        return functionNames[f] ?: error("no C name for ${f.name}")
    }

    private val slotCache = HashMap<IrClass, List<IrSimpleFunction>>()

    /** Roots of the user vtable slots of [c] (superclass slots first); slot index = 3 + position. */
    private fun slotsOf(c: IrClass): List<IrSimpleFunction> = slotCache.getOrPut(c) {
        val base = superclassOf(c)?.let { slotsOf(it) } ?: emptyList()
        base + classFunctions(c).filter { f ->
            topClass(f) === f && anyMember(f) == null && f.modality != Modality.FINAL &&
                f.visibility != DescriptorVisibilities.PRIVATE
        }
    }

    private fun implClass(c: IrClass, root: IrSimpleFunction): String? {
        val g = classFunctions(c).firstOrNull { topClass(it) === root }
        if (g != null) return implName(resolveFake(g))
        val sup = superclassOf(c) ?: return null
        return implClass(sup, root)
    }

    private fun implAny(c: IrClass, slot: Int): String? {
        val g = classFunctions(c).firstOrNull { anyMember(it) == null && topClass(it)?.let { t -> anyMember(t) } == slot }
        if (g != null) return implName(resolveFake(g))
        val sup = superclassOf(c) ?: return null
        return implAny(sup, slot)
    }

    private fun interfaceMethods(i: IrClass): List<IrSimpleFunction> =
        classFunctions(i).filter { !it.isFakeOverride && it.visibility != DescriptorVisibilities.PRIVATE }

    private fun findImpl(c: IrClass, m: IrSimpleFunction): IrSimpleFunction? {
        val g = classFunctions(c).firstOrNull { m in allOverridden(it) }
        if (g != null) return resolveFake(g)
        val sup = superclassOf(c)
        if (sup != null) return findImpl(sup, m)
        return if (hasBody(m)) m else null
    }

    private fun buildClass(info: ClassInfo): MirClass {
        val c = info.ir
        val isInterface = c.kind == ClassKind.INTERFACE
        val ifaces = if (isInterface) allInterfaces(c).filter { it !== c } else allInterfaces(c)
        val fields = if (isInterface) emptyList() else ownFields(c).map { f ->
            if (f.origin == IrDeclarationOrigin.PROPERTY_DELEGATE) unsupported(f, "delegated property")
            MirField(f.name.asString(), mirType(f.type, f))
        }
        var vtable = emptyList<String?>()
        var itables = emptyList<MirItable>()
        if (!isInterface) {
            val slots = slotsOf(c)
            vtable = (0..2).map { implAny(c, it) } + slots.map { implClass(c, it) }
            itables = ifaces.map { i ->
                MirItable(info(i).cName, interfaceMethods(i).map { m -> implName(findImpl(c, m)) })
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

    private sealed class Dispatch {
        class AnyMember(val slot: Int) : Dispatch()
        class Virtual(val slot: Int) : Dispatch()
        class Interface(val cName: String, val index: Int) : Dispatch()
        class Direct(val target: IrSimpleFunction) : Dispatch()
    }

    private fun dispatchOf(f: IrSimpleFunction): Dispatch {
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

    private fun checkFunctionShape(f: IrSimpleFunction, member: Boolean) {
        if (f.typeParameters.isNotEmpty()) unsupported(f, "generic function '${f.name}'")
        if (f.isSuspend) unsupported(f, "suspend function '${f.name}'")
        for (p in f.parameters) {
            when (p.kind) {
                IrParameterKind.Regular -> {}
                IrParameterKind.ExtensionReceiver -> unsupported(f, "extension function '${f.name}'")
                IrParameterKind.Context -> unsupported(f, "context parameter in '${f.name}'")
                IrParameterKind.DispatchReceiver -> if (!member) unsupported(f, "member function '${f.name}'")
            }
            if (p.varargElementType != null) unsupported(p, "vararg parameter '${p.name}'")
        }
        if (f.isExternal) unsupported(f, "external function '${f.name}'")
        if (!member && f.body == null) unsupported(f, "function without body '${f.name}'")
    }

    // ---- locations / errors -----------------------------------------------------------

    private fun location(e: IrElement): String {
        val off = e.startOffset
        if (off < 0) return fileName
        return "$fileName:${fileEntry.getLineNumber(off) + 1}:${fileEntry.getColumnNumber(off) + 1}"
    }

    private fun unsupported(e: IrElement, what: String): Nothing =
        throw UnsupportedConstruct("${location(e)}: error: unsupported construct: $what")

    private fun describe(e: IrElement): String {
        val n = e.javaClass.simpleName.removePrefix("Ir").removeSuffix("Impl")
        return n.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
    }

    // ---- types ------------------------------------------------------------------------

    private val anyType = TObject("kotlin.Any", ANY_CNAME, false)
    private val anyNullable = TObject("kotlin.Any", ANY_CNAME, true)

    private fun mirType(t: IrType, at: IrElement): MirType {
        val nullable = t.isMarkedNullable()
        val n = t.classFqName?.asString()
        val prim: MirType? = when (n) {
            "kotlin.Int" -> TInt
            "kotlin.Long" -> TLong
            "kotlin.Boolean" -> TBoolean
            "kotlin.Double" -> TDouble
            "kotlin.Char" -> TChar
            else -> null
        }
        if (prim != null) return if (nullable) TBoxed(prim) else prim
        return when (n) {
            "kotlin.String" -> if (nullable) TNString else TString
            "kotlin.Unit" -> if (nullable) unsupported(at, "nullable type 'Unit?'") else TUnit
            "kotlin.Nothing" -> if (nullable) TNull else TNothing
            "kotlin.Any" -> if (nullable) anyNullable else anyType
            else -> {
                val c = t.classOrNull?.owner
                if (c != null && c in classInfos) typeOf(c, nullable) else unsupported(at, "type '${n ?: t}'")
            }
        }
    }

    // ---- functions --------------------------------------------------------------------

    private fun convertFunction(f: IrFunction, cName: String, qualifiedName: String, ctorOf: String?): MirFunction {
        locals = HashMap()
        localCounter = 0
        currentFunction = f
        val params = ArrayList<MirLocal>()
        if (f is IrConstructor) {
            val cls = f.parent as IrClass
            val self = MirLocal(localCounter++, "this", typeOf(cls))
            params += self
            f.parent.let { (it as IrClass).thisReceiver }?.let { locals[it] = self }
        }
        for (p in f.parameters) {
            val l = MirLocal(localCounter++, if (p.kind == IrParameterKind.DispatchReceiver) "this" else p.name.asString(), mirType(p.type, p))
            locals[p] = l
            params += l
        }
        val ret = if (f is IrConstructor) TUnit else mirType(f.returnType, f)
        currentReturn = ret
        val out = ArrayList<MirStmt>()
        when (val body = f.body) {
            is IrBlockBody -> for (s in body.statements) stmt(s, out)
            is IrExpressionBody -> {
                val e = body.expression
                if (ret == TUnit) stmt(e, out) else returnValue(e, out)
            }
            else -> unsupported(f, "function body")
        }
        return MirFunction(qualifiedName, cName, params, ret, out, ctorOf)
    }

    private fun newTemp(type: MirType, name: String = "tmp"): MirLocal = MirLocal(localCounter++, name, type)

    /** Stores [v] (owned or borrowed) in a fresh temporary that lives until the end of the scope. */
    private fun tmpOf(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
        val t = newTemp(v.type)
        out += MirStmt.Declare(t, v)
        ownedTemps += t
        return MirExpr.Local(t)
    }

    /** If [v] is a single-use temporary just declared by [tmpOf], takes its initialiser back (saves a retain/release pair). */
    private fun consumeOwned(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
        if (v is MirExpr.Local && v.local in ownedTemps) {
            val last = out.lastOrNull()
            val init = (last as? MirStmt.Declare)?.init
            if (last is MirStmt.Declare && last.local === v.local && init != null) {
                out.removeAt(out.size - 1)
                ownedTemps -= v.local
                return init
            }
        }
        return v
    }

    private fun returnValue(e: IrExpression, out: MutableList<MirStmt>) {
        val v = bindValue(e, currentReturn, out)
        if (v.type == TNothing) return
        out += MirStmt.Return(v)
    }

    // ---- statements -------------------------------------------------------------------

    private fun stmt(s: IrStatement, out: MutableList<MirStmt>) {
        when (s) {
            is IrVariable -> {
                if (s.isLateinit) unsupported(s, "lateinit variable '${s.name}'")
                val t = mirType(s.type, s)
                if (t == TUnit || t == TNothing) unsupported(s, "variable '${s.name}' of type $t")
                val init = s.initializer?.let { bindValue(it, t, out) }
                val l = MirLocal(localCounter++, s.name.asString(), t)
                locals[s] = l
                out += MirStmt.Declare(l, init)
            }
            is IrComposite -> for (x in s.statements) stmt(x, out)
            is IrBlock -> {
                if (s.origin?.debugName == "FOR_LOOP") forLoop(s, out) else for (x in s.statements) stmt(x, out)
            }
            is IrWhen -> whenStmt(s, out)
            is IrWhileLoop -> whileLoop(s, out)
            is IrDoWhileLoop -> doWhileLoop(s, out)
            is IrBreak -> out += MirStmt.Break(labelOf(s.loop, s))
            is IrContinue -> out += MirStmt.Continue(labelOf(s.loop, s))
            is IrReturn -> returnStmt(s, out)
            is IrSetValue -> {
                val l = localOf(s.symbol.owner, s)
                out += MirStmt.Assign(l, bindValue(s.value, l.type, out))
            }
            is IrSetField -> setField(s, out)
            is IrInstanceInitializerCall -> instanceInitializer(s, out)
            is IrDelegatingConstructorCall -> delegatingCall(s, out)
            is IrTypeOperatorCall -> {
                if (s.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) stmt(s.argument, out)
                else discard(value(s, out), out)
            }
            is IrGetObjectValue -> {
                if (s.symbol.owner.kotlinFqName.asString() != "kotlin.Unit") discard(value(s, out), out)
            }
            is IrCall -> if (isNoWhenBranch(s)) out += MirStmt.Panic("NoWhenBranchMatchedException") else out += MirStmt.Eval(call(s, out))
            is IrExpression -> discard(value(s, out), out)
            else -> unsupported(s, describe(s))
        }
    }

    private fun discard(v: MirExpr, out: MutableList<MirStmt>) {
        if (v is MirExpr.Local || v === MirExpr.UnitValue) return
        out += MirStmt.Eval(v)
    }

    /** The synthetic `else` branch of an exhaustive `when` without `else`. */
    private fun isNoWhenBranch(c: IrCall): Boolean {
        val o = c.symbol.owner
        return o.name.asString() == "noWhenBranchMatchedException" &&
            (o.parent as? IrPackageFragment)?.packageFqName?.asString() == "kotlin.internal.ir"
    }

    private fun returnStmt(s: IrReturn, out: MutableList<MirStmt>) {
        val f = currentFunction!!
        if (s.returnTargetSymbol.owner !== f) unsupported(s, "return from a lambda or nested function")
        if (currentReturn == TUnit) {
            stmt(s.value, out)
            out += MirStmt.Return(null)
        } else {
            returnValue(s.value, out)
        }
    }

    private fun labelOf(loop: IrLoop, at: IrElement): MirLabel =
        loopLabels[loop] ?: unsupported(at, "jump to a loop outside the current function")

    private fun localOf(owner: Any, at: IrElement): MirLocal =
        locals[owner] ?: unsupported(at, "reference to a variable that is not a local of the current function")

    // ---- classes: constructors and fields -------------------------------------------------------

    private fun fieldOwner(f: IrField): ClassInfo = info(f.parent as? IrClass ?: unsupported(f, "top-level field"))

    private fun fieldGet(recv: MirExpr, f: IrField): MirExpr =
        MirExpr.FieldGet(recv, fieldOwner(f).cName, f.name.asString(), mirType(f.type, f))

    private fun setField(s: IrSetField, out: MutableList<MirStmt>) {
        val f = s.symbol.owner
        val r = s.receiver ?: unsupported(s, "static field")
        val ops = operands(listOf(r), out)
        val ft = mirType(f.type, f)
        out += MirStmt.SetField(ops[0], fieldOwner(f).cName, f.name.asString(), ft, bindValue(s.value, ft, out))
    }

    private fun instanceInitializer(s: IrInstanceInitializerCall, out: MutableList<MirStmt>) {
        val cls = s.classSymbol.owner
        val self = MirExpr.Local(localOf(cls.thisReceiver ?: unsupported(s, "class without this"), s))
        for (d in cls.declarations) {
            when (d) {
                is IrProperty -> d.backingField?.let { initField(self, it, out) }
                is IrField -> initField(self, d, out)
                is IrAnonymousInitializer -> for (x in d.body.statements) stmt(x, out)
                else -> {}
            }
        }
    }

    private fun initField(self: MirExpr, f: IrField, out: MutableList<MirStmt>) {
        val e = f.initializer?.expression ?: return
        val ft = mirType(f.type, f)
        out += MirStmt.SetField(self, fieldOwner(f).cName, f.name.asString(), ft, bindValue(e, ft, out))
    }

    private fun delegatingCall(c: IrDelegatingConstructorCall, out: MutableList<MirStmt>) {
        val k = c.symbol.owner
        val cls = k.parent as IrClass
        if (isAny(cls)) return
        val cName = functionNames[k] ?: unsupported(c, "call of constructor of '${cls.kotlinFqName}'")
        val self = MirExpr.Local(locals.values.first { it.name == "this" && it.id == 0 })
        val args = callArgs(c, k, out)
        out += MirStmt.Eval(MirExpr.Call(cName, listOf<MirExpr>(self) + args, TUnit))
    }

    private fun constructorCall(c: IrConstructorCall, out: MutableList<MirStmt>): MirExpr {
        val k = c.symbol.owner
        val cls = k.parent as IrClass
        val cName = functionNames[k] ?: unsupported(c, "construction of '${cls.kotlinFqName}'")
        if (cls.kind != ClassKind.CLASS) unsupported(c, "construction of ${classKind(cls)} '${cls.name}'")
        if (cls.modality == Modality.ABSTRACT || cls.modality == Modality.SEALED) unsupported(c, "construction of abstract class '${cls.name}'")
        return MirExpr.New(cName, callArgs(c, k, out), typeOf(cls))
    }

    // ---- loops ------------------------------------------------------------------------

    private fun flattenBody(e: IrExpression?, out: MutableList<MirStmt>) {
        if (e != null) stmt(e, out)
    }

    private fun whileLoop(s: IrWhileLoop, out: MutableList<MirStmt>) {
        val label = MirLabel(labelCounter++)
        loopLabels[s] = label
        val pre = ArrayList<MirStmt>()
        val cond = value(s.condition, pre)
        val body = ArrayList<MirStmt>()
        if (pre.isEmpty()) {
            flattenBody(s.body, body)
            out += MirStmt.While(label, cond, body)
        } else {
            body += pre
            body += MirStmt.If(MirExpr.Unary(UnaryOp.Not, cond), listOf(MirStmt.Break(label)), emptyList())
            flattenBody(s.body, body)
            out += MirStmt.Loop(label, body)
        }
    }

    private fun doWhileLoop(s: IrDoWhileLoop, out: MutableList<MirStmt>) {
        val label = MirLabel(labelCounter++)
        loopLabels[s] = label
        val body = ArrayList<MirStmt>()
        flattenBody(s.body, body)
        val pre = ArrayList<MirStmt>()
        val cond = value(s.condition, pre)
        out += MirStmt.DoWhile(label, body, pre, cond)
    }

    private class Progression(
        val start: IrExpression,
        val end: IrExpression,
        val step: IrExpression?,
        val ascending: Boolean,
        val inclusive: Boolean,
    )

    private fun progression(e: IrExpression): Progression {
        if (e is IrCall) {
            val owner = e.symbol.owner
            val parent = owner.parent
            val name = owner.name.asString()
            val args = e.arguments
            if (parent is IrPackageFragment && parent.packageFqName.asString() == "kotlin.ranges") {
                when (name) {
                    "downTo" -> return Progression(args[0]!!, args[1]!!, null, ascending = false, inclusive = true)
                    "until" -> return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = false)
                    "step" -> {
                        val inner = progression(args[0]!!)
                        if (inner.step != null) unsupported(e, "step applied to a progression that already has a step")
                        return Progression(inner.start, inner.end, args[1]!!, inner.ascending, inner.inclusive)
                    }
                }
            }
            if (parent is IrClass && name == "rangeTo") {
                val fq = parent.kotlinFqName.asString()
                if (fq == "kotlin.Int" || fq == "kotlin.Long" || fq == "kotlin.Char") {
                    return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = true)
                }
            }
        }
        unsupported(e, "for loop over something other than an Int/Long/Char range built with `..`, `until`, `downTo` or `step`")
    }

    /** `for (x in range)`: fir2ir leaves the iterator protocol in place; match it. */
    private fun forLoop(b: IrBlock, out: MutableList<MirStmt>) {
        val st = b.statements
        val iter = st.getOrNull(0) as? IrVariable
        val loop = st.getOrNull(1) as? IrWhileLoop
        val iterCall = iter?.initializer as? IrCall
        if (iter == null || loop == null || iterCall == null || st.size != 2) unsupported(b, "for loop shape")
        val elemType = when (iter.type.classFqName?.asString()) {
            "kotlin.collections.IntIterator" -> TInt
            "kotlin.collections.LongIterator" -> TLong
            "kotlin.collections.CharIterator" -> TChar
            else -> unsupported(b, "for loop over '${iter.type.classFqName}'")
        }
        val p = progression(iterCall.arguments[0]!!)

        val body = loop.body as? IrBlock ?: unsupported(loop, "for loop body")
        val loopVar = body.statements.getOrNull(0) as? IrVariable ?: unsupported(loop, "for loop variable")
        val label = MirLabel(labelCounter++)
        loopLabels[loop] = label

        val exprs = listOfNotNull(p.start, p.end, p.step)
        val vals = operands(exprs, out)
        val start = convertTo(vals[0], elemType, b)
        val end = convertTo(vals[1], elemType, b)
        val step = vals.getOrNull(2)
        val mirVar = MirLocal(localCounter++, loopVar.name.asString(), elemType)
        locals[loopVar] = mirVar
        val inner = ArrayList<MirStmt>()
        for (x in body.statements.drop(1)) stmt(x, inner)
        out += MirStmt.ForRange(label, mirVar, start, end, step, p.ascending, p.inclusive, inner)
    }

    // ---- when / if --------------------------------------------------------------------

    private fun isConstTrue(e: IrExpression) = e is IrConst && e.value == true

    private fun whenStmt(w: IrWhen, out: MutableList<MirStmt>) = whenInto(w, out, null)

    /** Lowers [w] as an if-chain. If [target] is not null every branch assigns its value to it. */
    private fun whenInto(w: IrWhen, out: MutableList<MirStmt>, target: MirLocal?) {
        class Piece(val condStmts: List<MirStmt>, val cond: MirExpr?, val body: List<MirStmt>)
        val pieces = w.branches.map { br ->
            val cs = ArrayList<MirStmt>()
            val c = if (isConstTrue(br.condition)) null else value(br.condition, cs)
            val body = ArrayList<MirStmt>()
            if (target != null && br.result.type.classFqName?.asString() != "kotlin.Nothing") {
                body += MirStmt.Assign(target, bindValue(br.result, target.type, body))
            } else {
                stmt(br.result, body)
            }
            Piece(cs, c, body)
        }
        fun build(i: Int): List<MirStmt> {
            if (i >= pieces.size) return emptyList()
            val p = pieces[i]
            val c = p.cond ?: return p.body
            return p.condStmts + MirStmt.If(c, p.body, build(i + 1))
        }
        out += build(0)
    }

    private fun whenValue(w: IrWhen, out: MutableList<MirStmt>): MirExpr {
        val type = mirType(w.type, w)
        if (type == TUnit || type == TNothing) {
            whenInto(w, out, null)
            return if (type == TUnit) MirExpr.UnitValue else MirExpr.Unreachable(TNothing)
        }
        // Short-circuit operators and pure if-else expressions over primitives become expressions.
        val br = w.branches
        val origin = w.origin?.debugName
        if (br.size == 2 && isConstTrue(br[1].condition)) {
            val c = ArrayList<MirStmt>(); val a = ArrayList<MirStmt>(); val b = ArrayList<MirStmt>()
            val cv = value(br[0].condition, c)
            val av = value(br[0].result, a)
            val bv = value(br[1].result, b)
            if (c.isEmpty() && a.isEmpty() && b.isEmpty() && av.type != TNothing && bv.type != TNothing && !type.isRef &&
                !av.type.isRef && !bv.type.isRef
            ) {
                if (origin == "ANDAND") return MirExpr.Binary(BinaryOp.AndAnd, cv, av)
                if (origin == "OROR") return MirExpr.Binary(BinaryOp.OrOr, cv, bv)
                return MirExpr.Conditional(cv, av, bv, type)
            }
            // General form, reusing the pieces lowered above (no second lowering).
            val tmp = newTemp(type)
            out += MirStmt.Declare(tmp, null)
            out += c
            fun assign(vv: MirExpr, body: MutableList<MirStmt>, r: IrExpression) {
                if (vv.type != TNothing) body += MirStmt.Assign(tmp, convertTo(vv, type, r))
            }
            assign(av, a, br[0].result)
            assign(bv, b, br[1].result)
            out += MirStmt.If(cv, a, b)
            return MirExpr.Local(tmp)
        }
        val tmp = newTemp(type)
        out += MirStmt.Declare(tmp, null)
        whenInto(w, out, tmp)
        return MirExpr.Local(tmp)
    }

    // ---- expressions ------------------------------------------------------------------

    /** Free of side effects and of dependence on mutable state, so evaluation order does not matter. */
    private fun isPure(e: MirExpr): Boolean = when (e) {
        is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst, is MirExpr.CharConst,
        is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local, is MirExpr.NullConst, is MirExpr.Unreachable -> true
        is MirExpr.Binary -> isPure(e.left) && isPure(e.right)
        is MirExpr.Unary -> isPure(e.operand)
        is MirExpr.Convert -> isPure(e.operand)
        is MirExpr.StringLength -> isPure(e.operand)
        is MirExpr.Cast -> isPure(e.operand)
        is MirExpr.IsInstance -> isPure(e.operand)
        is MirExpr.Conditional -> isPure(e.cond) && isPure(e.thenValue) && isPure(e.elseValue)
        else -> false
    }

    /**
     * Lowers [args] left to right. If a later operand needs hoisted statements, the
     * values of earlier operands are first stored in temporaries so evaluation order is kept;
     * operands with side effects (calls, field reads) are stored in temporaries unless last.
     */
    private fun operands(args: List<IrExpression>, out: MutableList<MirStmt>): List<MirExpr> {
        val results = ArrayList<MirExpr>()
        for ((idx, a) in args.withIndex()) {
            val before = out.size
            var v = value(a, out)
            if (out.size > before) {
                var insertAt = before
                for (j in results.indices) {
                    val r = results[j]
                    if (r is MirExpr.IntConst || r is MirExpr.LongConst || r is MirExpr.DoubleConst ||
                        r is MirExpr.BooleanConst || r is MirExpr.CharConst || r is MirExpr.StringConst ||
                        r is MirExpr.NullConst
                    ) continue
                    val t = newTemp(r.type)
                    out.add(insertAt++, MirStmt.Declare(t, r))
                    results[j] = MirExpr.Local(t)
                }
            }
            if (idx < args.size - 1 && !isPure(v) && v.type != TUnit && v.type != TNothing) {
                val t = newTemp(v.type)
                out += MirStmt.Declare(t, v)
                v = MirExpr.Local(t)
            }
            results += v
        }
        return results
    }

    /** Lowers [e]; the result is never an owned expression or a field read of reference type. */
    private fun value(e: IrExpression, out: MutableList<MirStmt>): MirExpr {
        val v = valueRaw(e, out)
        return if (v.isOwned() || (v is MirExpr.FieldGet && v.type.isRef)) tmpOf(v, out) else v
    }

    /** Lowers [e] for storing into a location of type [to] (owned results are not wrapped in a temporary). */
    private fun bindValue(e: IrExpression, to: MirType, out: MutableList<MirStmt>): MirExpr {
        var v = value(e, out)
        if (v.type.isRef && to.isRef) v = consumeOwned(v, out)
        return convertTo(v, to, e)
    }

    private fun coerceOperand(v: MirExpr, to: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
        val c = convertTo(v, to, at)
        return if (c.isOwned()) tmpOf(c, out) else c
    }

    private fun valueRaw(e: IrExpression, out: MutableList<MirStmt>): MirExpr = when (e) {
        is IrConst -> constant(e)
        is IrGetValue -> {
            val owner = e.symbol.owner
            if (owner is IrValueParameter && owner.kind == IrParameterKind.ExtensionReceiver) unsupported(e, "receiver parameter")
            MirExpr.Local(localOf(owner, e))
        }
        is IrCall -> if (isNoWhenBranch(e)) {
            out += MirStmt.Panic("NoWhenBranchMatchedException")
            MirExpr.Unreachable(TNothing)
        } else call(e, out)
        is IrConstructorCall -> constructorCall(e, out)
        is IrGetField -> {
            val f = e.symbol.owner
            val r = e.receiver ?: unsupported(e, "static field")
            fieldGet(operands(listOf(r), out)[0], f)
        }
        is IrStringConcatenation -> stringConcat(e, out)
        is IrWhen -> whenValue(e, out)
        is IrComposite -> blockValue(e.statements, e, out)
        is IrBlock -> {
            if (e.origin?.debugName == "FOR_LOOP") { forLoop(e, out); MirExpr.UnitValue }
            else blockValue(e.statements, e, out)
        }
        is IrTypeOperatorCall -> typeOperator(e, out)
        is IrGetObjectValue -> {
            val c = e.symbol.owner
            if (c.kotlinFqName.asString() == "kotlin.Unit") MirExpr.UnitValue
            else if (c in classInfos) MirExpr.ObjectGet(info(c).cName, typeOf(c))
            else unsupported(e, "object reference '${c.kotlinFqName}'")
        }
        is IrSetValue, is IrSetField, is IrWhileLoop, is IrDoWhileLoop, is IrInstanceInitializerCall, is IrDelegatingConstructorCall ->
            { stmt(e, out); MirExpr.UnitValue }
        is IrReturn, is IrBreak, is IrContinue -> { stmt(e, out); MirExpr.Unreachable(TNothing) }
        else -> unsupported(e, describe(e))
    }

    private fun typeOperator(e: IrTypeOperatorCall, out: MutableList<MirStmt>): MirExpr {
        when (e.operator) {
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> { stmt(e.argument, out); return MirExpr.UnitValue }
            IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF -> {
                var v = value(e.argument, out)
                if (v.type.isPrimitive) v = coerceOperand(v, anyNullable, e, out)
                val target = mirType(e.typeOperand, e)
                val test: MirExpr = MirExpr.IsInstance(v, if (target.isPrimitive) TBoxed(target) else target.nonNull())
                return if (e.operator == IrTypeOperator.INSTANCEOF) test else MirExpr.Unary(UnaryOp.Not, test)
            }
            IrTypeOperator.CAST, IrTypeOperator.SAFE_CAST -> {
                var v = value(e.argument, out)
                val target = mirType(e.type, e)
                val safe = e.operator == IrTypeOperator.SAFE_CAST
                if (v.type.isPrimitive) v = coerceOperand(v, anyNullable, e, out)
                if (target.isPrimitive) {
                    return MirExpr.Unbox(MirExpr.Cast(v, TBoxed(target), MirExpr.CastMode.Checked), target)
                }
                return MirExpr.Cast(v, target, if (safe) MirExpr.CastMode.Safe else MirExpr.CastMode.Checked)
            }
            IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> {
                val v = value(e.argument, out)
                val target = mirType(e.type, e)
                if (v.type == target || v.type == TNothing) return v
                if (v.type.isRef && target.isRef) return MirExpr.Cast(v, target, MirExpr.CastMode.Unchecked)
                if (v.type.isRef && v.type !is TBoxed && target.isPrimitive) {
                    return MirExpr.Unbox(MirExpr.Cast(v, TBoxed(target), MirExpr.CastMode.Unchecked), target)
                }
                return coerceOperand(v, target, e, out)
            }
            else -> unsupported(e, "type operator ${e.operator}")
        }
    }

    private fun blockValue(statements: List<IrStatement>, at: IrExpression, out: MutableList<MirStmt>): MirExpr {
        if (statements.isEmpty()) return MirExpr.UnitValue
        for (s in statements.dropLast(1)) stmt(s, out)
        val last = statements.last()
        return if (last is IrExpression) value(last, out) else { stmt(last, out); MirExpr.UnitValue }
    }

    private fun constant(c: IrConst): MirExpr = when (val v = c.value) {
        is Int -> MirExpr.IntConst(v)
        is Long -> MirExpr.LongConst(v)
        is Double -> MirExpr.DoubleConst(v)
        is Boolean -> MirExpr.BooleanConst(v)
        is Char -> MirExpr.CharConst(v.code)
        is String -> MirExpr.StringConst(v)
        null -> MirExpr.NullConst()
        else -> unsupported(c, "constant of type ${v::class.simpleName}")
    }

    private fun convertTo(e: MirExpr, to: MirType, at: IrElement): MirExpr {
        if (e.type == to || e.type == TNothing) return e
        val from = e.type
        if (from.isPrimitive && to.isPrimitive) {
            val ok = (from == TInt || from == TLong || from == TChar || from == TDouble) &&
                (to == TInt || to == TLong || to == TChar || to == TDouble)
            if (!ok) unsupported(at, "conversion from $from to $to")
            return MirExpr.Convert(e, to)
        }
        if (from === TNull && to.isRef) return MirExpr.NullConst(to)
        if (from.isPrimitive && to.isRef) {
            if (to == TBoxed(from) || (to is TObject && to.cName == ANY_CNAME)) return MirExpr.Box(e, to)
            unsupported(at, "conversion from $from to $to")
        }
        if (from is TBoxed && to.isPrimitive) {
            if (from.prim == to) return MirExpr.Unbox(e, to)
            unsupported(at, "conversion from $from to $to")
        }
        if (from.isRef && to.isRef) return e // C-level retyping is done by the emitter
        unsupported(at, "conversion from $from to $to")
    }

    private fun asAny(e: MirExpr): MirExpr =
        if (e.type is TObject && (e.type as TObject).cName == ANY_CNAME) e else MirExpr.Cast(e, anyNullable, MirExpr.CastMode.Unchecked)

    private fun boxIfPrim(e: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr =
        if (e.type.isPrimitive) coerceOperand(e, TBoxed(e.type), at, out) else e

    /** String conversion; the result is a String local, constant or temporary (never owned inline). */
    private fun toStringExpr(e: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr {
        val call = when (e.type) {
            TString -> return e
            TInt -> MirExpr.RuntimeCall("mk_string_from_int", listOf(e), TString)
            TLong -> MirExpr.RuntimeCall("mk_string_from_long", listOf(e), TString)
            TDouble -> MirExpr.RuntimeCall("mk_string_from_double", listOf(e), TString)
            TBoolean -> MirExpr.RuntimeCall("mk_string_from_boolean", listOf(e), TString)
            TChar -> MirExpr.RuntimeCall("mk_string_from_char", listOf(e), TString)
            else -> if (e.type.isRef) MirExpr.RuntimeCall("mk_to_string", listOf(asAny(e)), TString)
            else unsupported(at, "string conversion of a value of type ${e.type}")
        }
        return tmpOf(call, out)
    }

    private fun concat(a: MirExpr, b: MirExpr, out: MutableList<MirStmt>): MirExpr {
        if (a is MirExpr.StringConst && b is MirExpr.StringConst) return MirExpr.StringConst(a.value + b.value)
        return tmpOf(MirExpr.RuntimeCall("mk_string_concat", listOf(a, b), TString), out)
    }

    private fun stringConcat(e: IrStringConcatenation, out: MutableList<MirStmt>): MirExpr {
        val vals = operands(e.arguments, out)
        var acc: MirExpr? = null
        for (v in vals) {
            val s = toStringExpr(v, e, out)
            acc = if (acc == null) s else concat(acc, s, out)
        }
        return acc ?: MirExpr.StringConst("")
    }

    // ---- calls ------------------------------------------------------------------------

    /**
     * Lowers the arguments of a call to [callee] (receiver first, if any) converted to the declared
     * parameter types. Omitted arguments are filled by inlining the default value expression.
     */
    private fun callArgs(c: IrFunctionAccessExpression, callee: IrFunction, out: MutableList<MirStmt>): List<MirExpr> {
        val params = callee.parameters
        val given = c.arguments
        val idxs = params.indices.filter { given.getOrNull(it) != null }
        val vals = operands(idxs.map { given[it]!! }, out)
        val byIndex = HashMap<Int, MirExpr>()
        for ((k, i) in idxs.withIndex()) byIndex[i] = vals[k]
        val result = ArrayList<MirExpr>()
        val saved = ArrayList<Pair<IrValueParameter, MirLocal?>>()
        for ((i, p) in params.withIndex()) {
            val given1 = byIndex[i]
            val v: MirExpr
            if (given1 != null) {
                val pt = mirType(p.type, p)
                v = coerceOperand(given1, pt, c, out)
            } else {
                val d = p.defaultValue?.expression ?: unsupported(c, "call with omitted argument '${p.name}' of '${callee.name}'")
                v = coerceOperand(value(d, out), mirType(p.type, p), c, out)
            }
            // make this value visible to the default expressions of later parameters
            if (idxs.size == params.size) {
                result += v
                continue
            }
            val lv: MirExpr = if (v is MirExpr.Local) v else tmpOfPlain(v, out)
            saved += p to locals[p]
            locals[p] = (lv as MirExpr.Local).local
            result += lv
        }
        for ((p, old) in saved) if (old == null) locals.remove(p) else locals[p] = old
        return result
    }

    /** Like [tmpOf] but only when needed for primitives (constants stay inline in the argument list). */
    private fun tmpOfPlain(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
        val t = newTemp(v.type)
        out += MirStmt.Declare(t, v)
        return MirExpr.Local(t)
    }

    private fun call(c: IrCall, out: MutableList<MirStmt>): MirExpr {
        val owner = c.symbol.owner
        val name = owner.name.asString()
        val parent = owner.parent

        // user function of this module (top-level)
        functionNames[owner]?.let { cName ->
            if (parent !is IrClass) {
                val args = callArgs(c, owner, out)
                return MirExpr.Call(cName, args, mirType(c.type, c))
            }
        }
        if (parent is IrClass && (parent in classInfos || isAny(parent))) return memberCall(c, owner, out)
        // fake overrides of Any members declared in user classes have the user class as parent
        val ownerName = when (parent) {
            is IrClass -> parent.kotlinFqName.asString()
            is IrPackageFragment -> parent.packageFqName.asString()
            else -> unsupported(c, "call of '$name'")
        }
        val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$ownerName.$name'") }

        return when {
            ownerName == "kotlin" && name == "<get-code>" && args.size == 1 ->
                convertTo(operands(args, out)[0], TInt, c)
            ownerName == "kotlin" && name == "toString" && args.size == 1 -> anyMember(0, operands(args, out), c, out)
            ownerName == "kotlin" && name == "hashCode" && args.size == 1 -> anyMember(2, operands(args, out), c, out)
            ownerName == "kotlin.io" && (name == "println" || name == "print") -> consoleCall(c, name, args, out)
            ownerName == "kotlin.internal.ir" -> irBuiltin(c, name, args, out)
            parent is IrClass && ownerName in RANGES && name == "contains" -> rangeContains(c, args, out)
            ownerName == "kotlin.String" && name == "<get-length>" -> MirExpr.StringLength(operands(args, out)[0])
            parent is IrClass && ownerName in PRIMITIVES -> primitiveMember(c, ownerName, name, args, out)
            else -> unsupported(c, "call to '${if (parent is IrClass) "$ownerName.$name" else "$ownerName.$name"}'")
        }
    }

    /** `toString` / `equals` / `hashCode` on any reference through the virtual runtime helpers. */
    private fun anyMember(slot: Int, v: List<MirExpr>, at: IrElement, out: MutableList<MirStmt>): MirExpr {
        val recv = asAny(boxIfPrim(v[0], at, out))
        return when (slot) {
            0 -> MirExpr.RuntimeCall("mk_to_string", listOf(recv), TString)
            1 -> MirExpr.RuntimeCall("mk_equals", listOf(recv, asAny(boxIfPrim(v[1], at, out))), TBoolean)
            else -> MirExpr.RuntimeCall("mk_hash_code", listOf(recv), TInt)
        }
    }

    private fun memberCall(c: IrCall, f: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
        // direct access to the backing field for final default accessors
        val real = resolveFake(f)
        val prop = real.correspondingPropertySymbol?.owner
        if (c.superQualifierSymbol == null && real.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
            real.modality == Modality.FINAL && prop?.backingField != null && !isAny(real.parent as IrClass)
        ) {
            val field = prop.backingField!!
            val args = callArgs(c, f, out)
            if (real.parameters.size == 1) return fieldGet(args[0], field)
            out += MirStmt.SetField(args[0], fieldOwner(field).cName, field.name.asString(), mirType(field.type, field), args[1])
            return MirExpr.UnitValue
        }

        val d: Dispatch = if (c.superQualifierSymbol != null) {
            val t = resolveFake(f)
            if (parentClass(t)?.let { isAny(it) } == true) Dispatch.AnyMember(anyMember(topClass(f) ?: f) ?: unsupported(c, "super call of '${f.name}'"))
            else Dispatch.Direct(t)
        } else dispatchOf(f)

        if (d is Dispatch.AnyMember) {
            val vals = operands(c.arguments.map { it ?: unsupported(c, "omitted argument") }, out)
            val recv = asAny(vals[0])
            val viaSuper = c.superQualifierSymbol != null
            return when (d.slot) {
                0 -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_to_string" else "mk_to_string", listOf(recv), TString)
                1 -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_equals" else "mk_equals", listOf(recv, asAny(boxIfPrim(vals[1], c, out))), TBoolean)
                else -> MirExpr.RuntimeCall(if (viaSuper) "mk_any_hash_code" else "mk_hash_code", listOf(recv), TInt)
            }
        }

        val args = callArgs(c, f, out)
        val rt = mirType(f.returnType, f)
        val ptypes = f.parameters.drop(1).map { mirType(it.type, it) }
        return when (d) {
            is Dispatch.Direct -> {
                val cName = functionNames[d.target] ?: unsupported(c, "call of '${d.target.name}'")
                if (!hasBody(d.target)) unsupported(c, "call of abstract function '${f.name}'")
                MirExpr.Call(cName, args, rt)
            }
            is Dispatch.Virtual -> MirExpr.VCall(d.slot, args[0], args.drop(1), ptypes, rt)
            is Dispatch.Interface -> MirExpr.ICall(d.cName, d.index, args[0], args.drop(1), ptypes, rt)
            is Dispatch.AnyMember -> error("unreachable")
        }
    }

    /** `x in a..b` / `x in a until b` (the range is built in place; no range object exists). */
    private fun rangeContains(c: IrCall, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        if (args.size != 2) unsupported(c, "range contains")
        val p = progression(args[0])
        if (p.step != null || !p.ascending) unsupported(c, "`in` on a progression with step or direction")
        val vals = operands(listOf(p.start, p.end, args[1]), out)
        val t = widest(widest(vals[0].type, vals[1].type, c), vals[2].type, c)
        val parts = vals.map { v ->
            val x = convertTo(widenCharIf(v, t), t, c)
            if (x is MirExpr.IntConst || x is MirExpr.LongConst || x is MirExpr.CharConst || x is MirExpr.Local) x
            else {
                val tmp = newTemp(x.type)
                out += MirStmt.Declare(tmp, x)
                MirExpr.Local(tmp)
            }
        }
        val lo = MirExpr.Binary(BinaryOp.Ge, parts[2], parts[0])
        val hi = MirExpr.Binary(if (p.inclusive) BinaryOp.Le else BinaryOp.Lt, parts[2], parts[1])
        return MirExpr.Binary(BinaryOp.AndAnd, lo, hi)
    }

    private fun widenCharIf(e: MirExpr, t: MirType): MirExpr = if (e.type == TChar && t != TChar) MirExpr.Convert(e, TInt) else e

    private fun consoleCall(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        if (args.isEmpty()) {
            if (name == "println") return MirExpr.RuntimeCall("mk_println_empty", emptyList(), TUnit)
            unsupported(c, "print() without argument")
        }
        if (args.size != 1) unsupported(c, "call to '$name'")
        val v = operands(args, out)[0]
        if (name == "print") return MirExpr.RuntimeCall("mk_print_string", listOf(toStringExpr(v, c, out)), TUnit)
        return when (v.type) {
            TString -> MirExpr.RuntimeCall("mk_println_string", listOf(v), TUnit)
            TInt -> MirExpr.RuntimeCall("mk_println_int", listOf(v), TUnit)
            TLong -> MirExpr.RuntimeCall("mk_println_long", listOf(v), TUnit)
            TDouble -> MirExpr.RuntimeCall("mk_println_double", listOf(v), TUnit)
            TBoolean -> MirExpr.RuntimeCall("mk_println_boolean", listOf(v), TUnit)
            TChar -> MirExpr.RuntimeCall("mk_println_char", listOf(v), TUnit)
            else -> if (v.type.isRef) MirExpr.RuntimeCall("mk_println_string", listOf(toStringExpr(v, c, out)), TUnit)
            else unsupported(c, "println of a value of type ${v.type}")
        }
    }

    private fun irBuiltin(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        if (name == "CHECK_NOT_NULL") {
            val v = operands(args, out)
            if (v.size != 1) unsupported(c, "compiler intrinsic '$name'")
            val t = v[0].type
            return if (t is TBoxed) MirExpr.Unbox(v[0], t.prim) else if (t.isRef) MirExpr.NotNull(v[0]) else v[0]
        }
        val op = when (name) {
            "less" -> BinaryOp.Lt
            "lessOrEqual" -> BinaryOp.Le
            "greater" -> BinaryOp.Gt
            "greaterOrEqual" -> BinaryOp.Ge
            "EQEQ", "ieee754equals" -> BinaryOp.Eq
            "EQEQEQ" -> BinaryOp.IdEq
            else -> unsupported(c, "compiler intrinsic '$name'")
        }
        val v = operands(args, out)
        if (v.size != 2) unsupported(c, "compiler intrinsic '$name'")
        val ta = v[0].type
        val tb = v[1].type
        if (ta == TUnit || ta == TNothing) unsupported(c, "comparison of $ta")
        if (op == BinaryOp.Eq || op == BinaryOp.IdEq) {
            if (ta.isRef || tb.isRef) return refEquality(op, v[0], v[1], c, out)
        }
        if (ta != tb) unsupported(c, "comparison of $ta with $tb")
        if (ta == TString && op != BinaryOp.Eq) unsupported(c, "ordering comparison of strings")
        return MirExpr.Binary(if (op == BinaryOp.IdEq) BinaryOp.Eq else op, v[0], v[1])
    }

    private fun refEquality(op: BinaryOp, a: MirExpr, b: MirExpr, at: IrElement, out: MutableList<MirStmt>): MirExpr {
        val ta = a.type
        val tb = b.type
        if (ta === TNull && tb === TNull) return MirExpr.BooleanConst(true)
        if (ta === TNull || tb === TNull) {
            val other = if (ta === TNull) b else a
            if (!other.type.isRef) return MirExpr.BooleanConst(false)
            return MirExpr.Binary(BinaryOp.IdEq, other, MirExpr.NullConst())
        }
        if (op == BinaryOp.IdEq) {
            return MirExpr.Binary(BinaryOp.IdEq, boxIfPrim(a, at, out), boxIfPrim(b, at, out))
        }
        if (ta == TString && tb == TString) return MirExpr.Binary(BinaryOp.Eq, a, b)
        return MirExpr.RuntimeCall("mk_equals", listOf(asAny(boxIfPrim(a, at, out)), asAny(boxIfPrim(b, at, out))), TBoolean)
    }

    private fun arithmeticOp(name: String): BinaryOp? = when (name) {
        "plus" -> BinaryOp.Add; "minus" -> BinaryOp.Sub; "times" -> BinaryOp.Mul
        "div" -> BinaryOp.Div; "rem" -> BinaryOp.Rem
        "and" -> BinaryOp.And; "or" -> BinaryOp.Or; "xor" -> BinaryOp.Xor
        "shl" -> BinaryOp.Shl; "shr" -> BinaryOp.Shr; "ushr" -> BinaryOp.Ushr
        else -> null
    }

    private fun primitiveMember(
        c: IrCall,
        cls: String,
        name: String,
        args: List<IrExpression>,
        out: MutableList<MirStmt>,
    ): MirExpr {
        val v = operands(args, out)
        val recv = v[0]
        val resultType = mirType(c.type, c)

        // String members
        if (cls == "kotlin.String") {
            return when (name) {
                "plus" -> concat(recv, toStringExpr(v[1], c, out), out)
                "toString" -> recv
                "hashCode" -> MirExpr.RuntimeCall("mk_string_hash", listOf(recv), TInt)
                "equals" -> anyMember(1, v, c, out)
                else -> unsupported(c, "call to 'kotlin.String.$name'")
            }
        }

        // conversions and toString
        when (name) {
            "toString" -> return toStringExpr(recv, c, out)
            "toInt" -> return convertTo(recv, TInt, c)
            "toLong" -> return convertTo(recv, TLong, c)
            "toDouble" -> return convertTo(recv, TDouble, c)
            "toChar" -> return convertTo(recv, TChar, c)
            "<get-code>" -> return convertTo(recv, TInt, c)
            "hashCode" -> return when (recv.type) {
                TInt -> recv
                TChar -> MirExpr.Convert(recv, TInt)
                TBoolean -> MirExpr.Conditional(recv, MirExpr.IntConst(1231), MirExpr.IntConst(1237))
                TLong -> MirExpr.Convert(
                    MirExpr.Binary(BinaryOp.Xor, recv, MirExpr.Binary(BinaryOp.Ushr, recv, MirExpr.IntConst(32))), TInt,
                )
                else -> anyMember(2, v, c, out)
            }
            "equals" -> if (v.size == 2) return anyMember(1, v, c, out)
        }

        if (cls == "kotlin.Boolean") {
            return when (name) {
                "not" -> MirExpr.Unary(UnaryOp.Not, recv)
                "and" -> MirExpr.Binary(BinaryOp.And, recv, v[1])
                "or" -> MirExpr.Binary(BinaryOp.Or, recv, v[1])
                "xor" -> MirExpr.Binary(BinaryOp.Xor, recv, v[1])
                else -> unsupported(c, "call to 'kotlin.Boolean.$name'")
            }
        }

        // unary numeric
        if (v.size == 1) {
            val numeric = if (recv.type == TChar) MirExpr.Convert(recv, TInt) else recv
            return when (name) {
                "unaryMinus" -> MirExpr.Unary(UnaryOp.Neg, numeric)
                "unaryPlus" -> numeric
                "inv" -> MirExpr.Unary(UnaryOp.Inv, recv)
                "inc" -> convertTo(incDec(numeric, BinaryOp.Add), recv.type, c)
                "dec" -> convertTo(incDec(numeric, BinaryOp.Sub), recv.type, c)
                else -> unsupported(c, "call to '$cls.$name'")
            }
        }

        // compareTo
        if (name == "compareTo") {
            val t = widest(recv.type, v[1].type, c)
            val a = convertTo(widenChar(recv), t, c)
            val b = convertTo(widenChar(v[1]), t, c)
            // (a > b) - (a < b) with Int result
            return MirExpr.Binary(
                BinaryOp.Sub,
                convertTo(MirExpr.Conditional(MirExpr.Binary(BinaryOp.Gt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)), TInt, c),
                MirExpr.Conditional(MirExpr.Binary(BinaryOp.Lt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)),
            )
        }

        // binary arithmetic / bit operations: compute in the wider type
        val op = arithmeticOp(name) ?: unsupported(c, "call to '$cls.$name'")
        if (op.isShift) {
            return MirExpr.Binary(op, recv, v[1])
        }
        if (recv.type == TChar || v[1].type == TChar) {
            // Char + Int -> Char, Char - Int -> Char, Char - Char -> Int
            val t = TInt
            val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
            return convertTo(r, resultType, c)
        }
        val t = widest(recv.type, v[1].type, c)
        val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
        return convertTo(r, resultType, c)
    }

    private fun widenChar(e: MirExpr): MirExpr = if (e.type == TChar) MirExpr.Convert(e, TInt) else e

    private fun incDec(e: MirExpr, op: BinaryOp): MirExpr = when (e.type) {
        TInt -> MirExpr.Binary(op, e, MirExpr.IntConst(1))
        TLong -> MirExpr.Binary(op, e, MirExpr.LongConst(1))
        TDouble -> MirExpr.Binary(op, e, MirExpr.DoubleConst(1.0))
        else -> throw IllegalStateException("inc/dec on ${e.type}")
    }

    private fun widest(a: MirType, b: MirType, at: IrElement): MirType {
        fun rank(t: MirType) = when (t) {
            TInt, TChar -> 1; TLong -> 2; TDouble -> 3
            else -> unsupported(at, "arithmetic on $t")
        }
        return if (rank(a) >= rank(b)) (if (a == TChar) TInt else a) else b
    }

    private companion object {
        val RANGES = setOf("kotlin.ranges.IntRange", "kotlin.ranges.LongRange", "kotlin.ranges.CharRange")
        val PRIMITIVES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Boolean", "kotlin.Char", "kotlin.String")
    }
}
