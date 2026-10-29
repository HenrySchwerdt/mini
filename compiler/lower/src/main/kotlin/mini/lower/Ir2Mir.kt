
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


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
    internal lateinit var currentFile: IrFile
    internal val fileName: String get() = currentFile.name

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
    internal val typeSubst = HashMap<TypeParamSym, Type>()

    /** `KClassImpl` (package kotlin, reflect) of the stdlib sources (what `Foo::class` creates), if present in the module. */
    internal var kclassImpl: IrClass? = null

    fun convert(module: IrModule, sourceName: String): MirModule {
        kclassImpl = module.files.flatMap { it.declarations }.filterIsInstance<IrClass>().firstOrNull { it.kotlinFqName == REFLECT_PKG + ".KClassImpl" }
        closure.analyze(module)
        // pass 1: classes and top-level declarations
        val tops = ArrayList<Pair<IrFile, IrSimpleFunction>>()
        for (file in module.files) {
            setFile(file)
            val pkg = file.packageFqName
            for (d in file.declarations) {
                when (d) {
                    is IrSimpleFunction -> tops += file to d
                    is IrClass -> if (!isRuntimeProvided(d)) collectClass(d, pkg, listOf(d.name), file)
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
            val n = (localCounts[lc.enclosing] ?: 0) + 1
            localCounts[lc.enclosing] = n
            val nm = lc.cls.name.let { if (it.startsWith("<")) "" + n else it }
            val pkg = lc.file.packageFqName
            collectClass(lc.cls, pkg, listOf(lc.enclosing + "$" + nm), lc.file)
        }
        // pass 2: names of all functions
        for ((file, f) in tops) {
            setFile(file)
            checkFunctionShape(f, member = false)
            val params = f.parameters.map { mirType(it.type, it) }
            if (f.name == "main" && params.isNotEmpty() && !isArgsMain(f)) {
                unsupported(f, "main function with unsupported parameters (only `fun main()` and `fun main(args: Array<String>)`)")
            }
            functionNames[f] = Mangling.function(file.packageFqName, f.name, params)
        }
        for (info in classInfos.values) {
            setFile(info.file ?: continue)
            registerMembers(info)
        }
        // pass 3: bodies
        val functions = ArrayList<MirFunction>()
        for ((file, f) in tops) {
            setFile(file)
            val pkg = file.packageFqName
            var mf = convertFunction(f, functionNames[f]!!, (if (pkg.isEmpty()) "" else "$pkg.") + f.name, null)
            if (mf.cName == Mangling.MAIN && facades.isNotEmpty()) {
                // top-level property initialisers run before main
                val force = facades.values.map { MirStmt.Eval(MirExpr.ObjectGet(it.cName, it.type)) }
                mf = MirFunction(mf.qualifiedName, mf.cName, mf.params, mf.returnType, force + mf.body, mf.constructorOf)
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
            if (c.kind == IrClassKind.ENUM_CLASS) {
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
        return MirModule(sourceName, functions, classes)
    }

    internal fun setFile(f: IrFile) {
        currentFile = f
    }

    internal val slotCache = HashMap<IrClass, List<IrSimpleFunction>>()
    internal val anyType = TObject("kotlin.Any", ANY_CNAME, false)
    internal val anyNullable = TObject("kotlin.Any", ANY_CNAME, true)
}
