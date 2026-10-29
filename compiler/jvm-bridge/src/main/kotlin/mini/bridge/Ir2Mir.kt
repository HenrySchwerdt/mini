@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirExtern
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
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
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
import org.jetbrains.kotlin.ir.util.dump
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
    internal var labelCounter = 0
    internal lateinit var fileEntry: IrFileEntry
    internal lateinit var fileName: String

    internal val classInfos = LinkedHashMap<IrClass, ClassInfo>()

    /** user functions of the module being converted (top-level, members, constructors): IR function -> C name */
    internal val functionNames = HashMap<IrFunction, String>()

    // per function state
    internal var locals = HashMap<Any, MirLocal>()
    internal var localCounter = 0
    internal val loopLabels = HashMap<IrLoop, MirLabel>()
    internal var currentFunction: IrFunction? = null
    internal var currentReturn: MirType = TUnit
    internal val ownedTemps = HashSet<MirLocal>()
    /** The current function returns the erased Unit (null) instead of void (closure `invoke`). */
    internal var unitAsNull = false

    // closures, inlining, extra generated declarations
    internal val closure = ClosureInfo()
    internal val bridgeCache = HashSet<String>()
    internal val inlineLambdaParams = HashMap<IrValueParameter, InlineLambda>()
    internal val extraClasses = ArrayList<MirClass>()
    internal val extraFunctions = ArrayList<MirFunction>()
    internal var lambdaCounter = 0
    internal val refClasses = HashMap<MirType, RefClass>()
    internal val functionInterfaces = HashMap<Int, TObject>()
    internal val liftedFns = HashMap<IrSimpleFunction, LiftedFn>()
    internal val returnTargets = HashMap<IrFunction, InlineReturn>()
    internal var finallyStack = ArrayList<IrExpression>()
    internal val loopFinallyDepth = HashMap<IrLoop, Int>()
    internal var builtinMessageRoot: IrSimpleFunction? = null
    internal val builtinInfos = HashMap<IrClass, ClassInfo>()
    internal val facades = LinkedHashMap<IrFile, Facade>()
    internal val typeSubst = HashMap<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>()

    /** C interop (Ir2MirInterop.kt): classes that are plain addresses, struct layouts, the C functions called. */
    internal val ptrClassCache = HashMap<IrClass, Boolean>()
    internal val structCache = HashMap<IrClass, StructInfo>()
    internal val cExterns = ArrayList<MirExtern>()
    internal val cExternIndex = HashMap<String, MirExtern>()

    /** Strict mode (Ir2MirStrict.kt): the function being converted is Strict; the allocator context levels in scope. */
    internal var currentStrict = false
    internal var allocLevels = ArrayList<List<AllocRef>>()
    internal val strictClassCache = HashMap<IrClass, Boolean>()

    /** `kotlin.reflect.KClassImpl` of the stdlib sources (what `Foo::class` creates), if present in the module. */
    internal var kclassImpl: IrClass? = null

    fun convert(module: IrModuleFragment, sourceName: String): MirModule {
        kclassImpl = module.files.flatMap { it.declarations }.filterIsInstance<IrClass>().firstOrNull { it.kotlinFqName.asString() == "kotlin.reflect.KClassImpl" }
        if (System.getenv("MINIC_DUMP_IR") != null) System.err.println(module.dump())
        closure.analyze(module)
        // pass 1: classes and top-level declarations
        val tops = ArrayList<Pair<IrFile, IrSimpleFunction>>()
        for (file in module.files) {
            setFile(file)
            val pkg = file.packageFqName.asString()
            for (d in file.declarations) {
                when (d) {
                    // an inline function with a reified type parameter only exists expanded at its call sites
                    is IrSimpleFunction -> if (!(d.isInline && d.typeParameters.any { it.isReified })) tops += file to d
                    is IrClass -> if (!isRuntimeProvided(d)) collectClass(d, pkg, listOf(d.name.asString()), file)
                    is org.jetbrains.kotlin.ir.declarations.IrTypeAlias -> {}
                    is IrProperty -> {
                        d.getter?.takeIf { !it.isExternal && it.body != null }?.let { tops += file to it }
                        d.setter?.takeIf { !it.isExternal && it.body != null }?.let { tops += file to it }
                        if (d.backingField != null && (d.getter?.body != null || d.getter == null)) facadeFor(file)
                    }
                    else -> unsupported(d, describe(d))
                }
            }
        }
        // local and anonymous classes (declared inside function bodies)
        val localCounts = HashMap<String, Int>()
        for (lc in closure.localClasses) {
            setFile(lc.file)
            val n = localCounts.merge(lc.enclosing, 1, Int::plus)!!
            val nm = lc.cls.name.asString().let { if (it.startsWith("<")) "" + n else it }
            val pkg = lc.file.packageFqName.asString()
            collectClass(lc.cls, pkg, listOf(lc.enclosing + "$" + nm), lc.file)
        }
        // pass 2: names of all functions
        for ((file, f) in tops) {
            setFile(file)
            checkFunctionShape(f, member = false)
            val params = f.parameters.map { mirType(it.type, it) }
            if (f.name.asString() == "main" && params.isNotEmpty() && !isArgsMain(f)) {
                unsupported(f, "main function with unsupported parameters (only `fun main()` and `fun main(args: Array<String>)`)")
            }
            functionNames[f] = uniqueTopLevelName(Mangling.function(file.packageFqName.asString(), f.name.asString(), params))
        }
        for (info in classInfos.values) {
            setFile(info.file ?: continue)
            registerMembers(info)
        }
        // pass 3: bodies
        val functions = ArrayList<MirFunction>()
        for ((file, f) in tops) {
            setFile(file)
            val pkg = file.packageFqName.asString()
            var mf = convertFunction(f, functionNames[f]!!, (if (pkg.isEmpty()) "" else "$pkg.") + f.name.asString(), null)
            if (mf.cName == Mangling.MAIN && facades.isNotEmpty()) {
                // top-level property initialisers run before main
                val force = facades.values.map { MirStmt.Eval(MirExpr.ObjectGet(it.cName, it.type)) }
                mf = MirFunction(mf.qualifiedName, mf.cName, mf.params, mf.returnType, force + mf.body, mf.constructorOf, mf.strict, mf.returnsBorrowed, mf.ownsParams, mf.noHeapEffects)
            }
            functions += mf
        }
        for (info in classInfos.values) {
            setFile(info.file ?: continue)
            functions += convertMembers(info)
        }
        val classes = ArrayList<MirClass>()
        classes += builtinMirClasses()
        val (facadeClasses, facadeFunctions) = buildFacades()
        functions += facadeFunctions
        classes += facadeClasses
        val done = HashSet<IrClass>()
        fun visit(c: IrClass) {
            if (c !in classInfos) return
            if (!done.add(c)) return
            superclassOf(c)?.let { visit(it) }
            for (i in directInterfaces(c)) visit(i)
            val inf = classInfos[c]!!
            classes += buildClass(inf)
            if (c.kind == ClassKind.ENUM_CLASS) {
                classes += MirClass(
                    inf.qualified + ".\$entries", holderName(inf), false, null,
                    enumEntries(c).indices.map { MirField("e$it", typeOf(c)) }, emptyList(), listOf(null, null, null), emptyList(),
                    holderName(inf) + "_C",
                )
            }
        }
        for (c in classInfos.keys.toList()) visit(c)
        classes += extraClasses
        functions += extraFunctions
        return MirModule(sourceName, functions, classes, cExterns, interopFileAnnotations(module, "kotlinx.cinterop.CInclude"), interopFileAnnotations(module, "kotlinx.cinterop.CLink"))
    }

    /**
     * Overloads that differ only in the class of a pointer-typed parameter (`CPointer<*>` / `CPointed`: all of them the
     * C type `void *`) get the same mangled name; later ones get an `_O<n>` suffix, in declaration order.
     */
    internal val topLevelNames = HashSet<String>()

    internal fun uniqueTopLevelName(name: String): String {
        if (topLevelNames.add(name)) return name
        var k = 2
        while (!topLevelNames.add(name + "_O" + k)) k++
        return name + "_O" + k
    }

    internal fun setFile(f: IrFile) {
        fileEntry = f.fileEntry
        fileName = f.fileEntry.name
    }

    internal val slotCache = HashMap<IrClass, List<IrSimpleFunction>>()
    internal val anyType = TObject("kotlin.Any", ANY_CNAME, false)
    internal val anyNullable = TObject("kotlin.Any", ANY_CNAME, true)
}
