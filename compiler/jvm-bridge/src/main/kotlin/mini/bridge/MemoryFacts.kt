@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.memory.AllocKind
import mini.backend.memory.FreeKind
import mini.backend.memory.MAnnot
import mini.backend.memory.MClass
import mini.backend.memory.MExpr
import mini.backend.memory.MField
import mini.backend.memory.MFun
import mini.backend.memory.MFunction
import mini.backend.memory.MLocal
import mini.backend.memory.MModule
import mini.backend.memory.MParam
import mini.backend.memory.MReturn
import mini.backend.memory.MStmt
import mini.backend.memory.MType
import mini.backend.memory.MemMode
import mini.backend.memory.Pos
import mini.backend.memory.ScopeKind
import mini.backend.memory.TK
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.statements

/**
 * Kotlin IR to MemIr (the input of the ownership checker, `mini.backend.memory`). Everything that touches
 * compiler internals lives here; the produced MemIr is pure data from the `backend` module.
 *
 * It is a second, independent view of the same IR module as [Ir2Mir]: it keeps source positions, annotations,
 * context parameters, allocator objects and lambdas, none of which the C-oriented MIR can carry yet, and it must
 * not throw on constructs the C backend does not support (the checker also has to judge programs that cannot be
 * compiled natively yet).
 */
class MemoryFacts(private val isUserFile: (IrFile) -> Boolean) {
    private val classes = HashMap<IrClass, MClass>()
    private val classList = ArrayList<MClass>()
    private val funs = HashMap<IrFunction, MFun>()
    private val paramLocals = HashMap<IrValueDeclaration, MLocal>()
    private var localCounter = 0
    private var lambdaCounter = 0
    private var labelCounter = 0
    private lateinit var fileEntry: org.jetbrains.kotlin.ir.IrFileEntry
    private lateinit var fileName: String

    /** Functions that could not be lowered (name -> reason); the checker skips them. */
    val failures = ArrayList<String>()
    val strictFailures = ArrayList<String>()

    fun lower(module: IrModuleFragment): MModule {
        val functions = ArrayList<MFunction>()
        val sources = HashMap<String, List<String>>()
        var mainFile = ""
        for (file in module.files) {
            if (!isUserFile(file)) continue
            setFile(file)
            if (mainFile.isEmpty()) mainFile = fileName
            runCatching { sources[fileName] = java.io.File(fileName).readLines() }
            for (d in file.declarations) lowerDeclaration(d, functions)
        }
        return MModule(mainFile, functions, classList, sources)
    }

    private fun setFile(f: IrFile) {
        fileEntry = f.fileEntry
        fileName = f.fileEntry.name
    }

    private fun lowerDeclaration(d: org.jetbrains.kotlin.ir.declarations.IrDeclaration, out: MutableList<MFunction>) {
        when (d) {
            is IrSimpleFunction -> if (d.origin == IrDeclarationOrigin.DEFINED) lowerFunction(d)?.let { out += it }
            is IrClass -> {
                classOf(d)
                for (m in d.declarations) {
                    when (m) {
                        is IrSimpleFunction -> if (!m.isFakeOverride && m.origin == IrDeclarationOrigin.DEFINED) lowerFunction(m)?.let { out += it }
                        is IrProperty -> {
                            m.getter?.let { g -> if (g.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) lowerFunction(g)?.let { out += it } }
                            m.setter?.let { g -> if (g.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) lowerFunction(g)?.let { out += it } }
                        }
                        is IrClass -> lowerDeclaration(m, out)
                        else -> {}
                    }
                }
            }
            else -> {}
        }
    }

    // ---- positions -----------------------------------------------------------------------------

    internal fun pos(e: IrElement): Pos = pos(if (e is IrExpression) leftOffset(e) else e.startOffset)

    /** Start of an expression including its receivers (`a.b.c()` starts at `a`; K2 puts calls at the selector). */
    private fun leftOffset(e: IrElement?): Int {
        if (e == null) return Int.MAX_VALUE
        var m = if (e.startOffset >= 0) e.startOffset else Int.MAX_VALUE
        when (e) {
            is IrCall -> for ((i, p) in e.symbol.owner.parameters.withIndex()) {
                if (p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.ExtensionReceiver) m = minOf(m, leftOffset(e.arguments.getOrNull(i)))
            }
            is IrTypeOperatorCall -> m = minOf(m, leftOffset(e.argument))
            is IrGetField -> m = minOf(m, leftOffset(e.receiver))
            is IrSetField -> m = minOf(m, leftOffset(e.receiver))
            else -> {}
        }
        return if (m == Int.MAX_VALUE) -1 else m
    }

    internal fun pos(off: Int): Pos =
        if (off < 0) Pos.NONE else Pos(fileEntry.getLineNumber(off) + 1, fileEntry.getColumnNumber(off) + 1)

    // ---- annotations ---------------------------------------------------------------------------

    private fun annos(list: List<IrConstructorCall>, fq: String): IrConstructorCall? =
        list.firstOrNull { it.type.classFqName?.asString() == fq }

    private fun annotOf(vararg sources: List<IrConstructorCall>): MAnnot {
        var owned: Pos? = null
        var borrowed: Pos? = null
        for (l in sources) {
            annos(l, OWNED)?.let { if (owned == null) owned = annotPos(it) }
            annos(l, BORROWED)?.let { if (borrowed == null) borrowed = annotPos(it) }
        }
        return if (owned == null && borrowed == null) MAnnot.NONE else MAnnot(owned, borrowed)
    }

    private fun annotPos(c: IrConstructorCall): Pos {
        val p = pos(c)
        return p
    }

    private fun modeOf(arg: IrExpression?): MemMode? {
        val v = (arg as? IrGetEnumValue)?.symbol?.owner?.name?.asString() ?: return null
        return if (v == "Managed") MemMode.Managed else MemMode.Strict
    }

    private fun fileMode(f: IrFile?): MemMode {
        if (f == null) return MemMode.Managed
        val a = annos(f.annotations, MEMORY_MODE) ?: return MemMode.Managed
        return modeOf(a.arguments.firstOrNull()) ?: MemMode.Managed
    }

    // ---- classes and types -------------------------------------------------------------------

    internal fun classOf(c: IrClass): MClass {
        classes[c]?.let { return it }
        val file = c.fileOrNull
        var mode = if (file == null) MemMode.Managed else fileMode(file)
        annos(c.annotations, MEMORY_MODE)?.let { a -> modeOf(a.arguments.firstOrNull())?.let { mode = it } }
        val ab = annos(c.annotations, ALLOCATED_BY)?.let { a ->
            (a.arguments.firstOrNull() as? IrClassReference)?.classType?.classFqName?.asString()
        }
        val mc = MClass(c.kotlinFqName.asString(), mode, ab, isCloseable(c))
        classes[c] = mc
        classList += mc
        // fields (only for classes of this module)
        if (file != null) {
            val ctorParams = c.declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary }?.parameters.orEmpty()
            for (d in c.declarations) {
                if (d !is IrProperty) continue
                val bf = d.backingField ?: continue
                val ctorParam = ctorParams.firstOrNull { it.name == d.name }
                val borrowed = annos(d.annotations, BORROWED) != null || annos(bf.annotations, BORROWED) != null ||
                    (ctorParam != null && annos(ctorParam.annotations, BORROWED) != null) || annos(bf.type.annotations, BORROWED) != null
                mc.fields += MField(d.name.asString(), mapType(bf.type), borrowed, mc)
            }
        }
        return mc
    }

    private fun isCloseable(c: IrClass): Boolean {
        val seen = HashSet<IrClass>()
        fun rec(k: IrClass): Boolean {
            if (!seen.add(k)) return false
            val fq = k.kotlinFqName.asString()
            if (fq == "kotlin.AutoCloseable" || fq == "java.lang.AutoCloseable" || fq == "java.io.Closeable") return true
            return k.superTypes.any { t -> t.classOrNull?.owner?.let { rec(it) } == true }
        }
        return rec(c)
    }

    internal fun mapType(t: IrType): MType {
        val nullable = t.isMarkedNullable()
        val fq = t.classFqName?.asString()
        val cls = t.classOrNull?.owner
        if (fq == null || cls == null) return MType(TK.Other, nullable, display = "Any")
        PRIMS[fq]?.let { return MType(TK.Prim, nullable, display = it) }
        if (fq == "kotlin.String") return MType(TK.Str, nullable, display = "String")
        if (fq == "kotlin.Any" || fq.startsWith("kotlin.Function") || fq.startsWith("kotlin.reflect")) return MType(TK.Other, nullable, display = fq.substringAfterLast('.'))
        ALLOCS[fq]?.let { return MType(TK.Alloc, nullable, className = fq, alloc = it, display = fq.substringAfterLast('.')) }
        if (fq.startsWith("mini.memory.") && cls.kind == ClassKind.INTERFACE) {
            return MType(TK.Alloc, nullable, className = fq, alloc = AllocKind.Other, display = fq.substringAfterLast('.'))
        }
        val mc = classOf(cls)
        val display = fq.substringAfterLast('.')
        return if (mc.mode == MemMode.Strict && cls.fileOrNull != null) MType(TK.Strict, nullable, mc, className = fq, display = display)
        else MType(TK.Managed, nullable, mc, className = fq, display = display)
    }

    // ---- functions -------------------------------------------------------------------------------

    private fun newLocal(name: String, type: MType, pos: Pos): MLocal = MLocal(++localCounter, name, type, pos)

    private fun paramLocal(p: IrValueDeclaration, name: String, type: IrType): MLocal =
        paramLocals.getOrPut(p) { newLocal(name, mapType(type), pos(p)) }

    internal fun funOf(f: IrFunction): MFun {
        funs[f]?.let { return it }
        val file = f.fileOrNull
        val cls = f.parentClassOrNull
        var mode = if (file == null) MemMode.Managed else fileMode(file)
        if (cls != null) mode = classOf(cls).mode
        val external = file == null
        val ctor = f as? IrConstructor
        val ctorClass = ctor?.let { classOf(it.parentAsClass()) }
        val props = ctorClass?.fields?.map { it.name }?.toSet().orEmpty()
        var receiver: MParam? = null
        val params = ArrayList<MParam>()
        for (p in f.parameters) {
            val l = paramLocal(p, p.name.asString(), p.type)
            val annot = annotOf(p.annotations, p.type.annotations)
            when (p.kind) {
                IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> if (receiver == null) receiver = MParam(l, annot)
                IrParameterKind.Context -> params += MParam(l, annot, isContext = true)
                IrParameterKind.Regular -> params += MParam(l, annot, implicitOwned = ctor != null && p.name.asString() in props)
            }
        }
        val name = f.name.asString()
        val retAnnot = annotOf(f.annotations, f.returnType.annotations)
        val fq = (f.parent as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)?.packageFqName?.asString()?.let { "$it.$name" }
            ?: ((cls?.kotlinFqName?.asString() ?: "") + "." + name)
        val m = MFun(
            qname = fq,
            mode = mode,
            params = params,
            ret = MReturn(if (ctor != null) mapType(ctor.parentAsClass().defaultType) else mapType(f.returnType), retAnnot),
            receiver = receiver,
            noAlloc = annos(f.annotations, NO_ALLOC) != null,
            external = external,
            borrowsAll = external && (name in BORROWS_ALL || fq.startsWith("kotlin.internal.ir.")),
            ctorOf = ctorClass,
            pos = pos(f),
            throwsKnown = external && name in THROWERS,
        )
        funs[f] = m
        return m
    }

    private fun IrConstructor.parentAsClass(): IrClass = parent as IrClass

    private fun lowerFunction(f: IrSimpleFunction): MFunction? {
        val body = f.body ?: return null
        val m = funOf(f)
        if (m.mode == MemMode.Strict || true) {
            return try {
                val lw = Lowerer(f)
                MFunction(m, lw.lowerFunctionBody(body), true, fileName).also { m.body = it.body }
            } catch (e: Exception) {
                failures += "${m.qname}: ${e.message ?: e.toString()}"
                if (m.mode == MemMode.Strict) strictFailures += "${m.qname}: ${e.message ?: e.toString()}"
                if (System.getenv("MINIC_DEBUG") != null) e.printStackTrace()
                null
            }
        }
        return null
    }

    // ---- the body lowerer ------------------------------------------------------------------------

    private class CaptureFrame(val id: Int) {
        val captures = LinkedHashSet<MLocal>()
    }

    private inner class Lowerer(private val fn: IrFunction) {
        private val locals = HashMap<IrValueDeclaration, MLocal>()
        private val owner = HashMap<Int, Int>() // local id -> capture frame depth at creation
        private val subst = HashMap<IrValueDeclaration, MExpr>()
        private val loopLabels = HashMap<IrLoop, Int>()
        private val frames = ArrayList<CaptureFrame>()
        private val scopeIds = HashMap<org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol, Int>()
        private val scopeResults = HashMap<Int, MLocal?>()

        private fun local(v: IrValueDeclaration): MLocal {
            val l = locals[v] ?: (paramLocals[v] ?: run {
                val name = v.name.asString()
                val nl = newLocal(name, mapType(v.type), pos(v))
                owner[nl.id] = frames.size
                nl
            }).also { locals[v] = it }
            // capture bookkeeping
            val own = owner[l.id] ?: 0
            for (i in own until frames.size) frames[i].captures += l
            return l
        }

        private fun declareLocal(v: IrValueDeclaration, name: String = v.name.asString()): MLocal {
            val l = paramLocals[v] ?: newLocal(name, mapType(v.type), pos(v))
            owner[l.id] = frames.size
            locals[v] = l
            return l
        }

        private fun tmp(type: MType, p: Pos, name: String = "\$tmp"): MLocal {
            val l = newLocal(name, type, p)
            owner[l.id] = frames.size
            return l
        }

        fun lowerFunctionBody(body: org.jetbrains.kotlin.ir.expressions.IrBody): List<MStmt> {
            for (p in fn.parameters) { val l = paramLocal(p, p.name.asString(), p.type); locals[p] = l; owner[l.id] = 0 }
            val out = ArrayList<MStmt>()
            when (body) {
                is IrBlockBody -> for (s in body.statements) stmt(s, out)
                is IrExpressionBody -> {
                    val e = expr(body.expression, out)
                    out += MStmt.Return(e, 0, pos(body.expression))
                }
                else -> {}
            }
            return out
        }

        // ---- statements ---------------------------------------------------------------------------

        private fun stmtList(s: IrStatement?): List<MStmt> {
            val out = ArrayList<MStmt>()
            if (s != null) stmt(s, out)
            return out
        }

        fun stmt(s: IrStatement, out: MutableList<MStmt>) {
            when (s) {
                is IrVariable -> variable(s, out)
                is IrExpression -> stmtExpr(s, out)
                else -> {}
            }
        }

        private fun variable(v: IrVariable, out: MutableList<MStmt>) {
            val init = v.initializer
            if (v.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE && init != null) {
                val e = expr(init, out)
                if (e is MExpr.Local || e is MExpr.Null) {
                    subst[v] = e
                    return
                }
                val l = declareLocal(v)
                out += MStmt.Decl(l, e, MAnnot.NONE, pos(v))
                return
            }
            val l = declareLocal(v)
            val e = init?.let { expr(it, out) }
            out += MStmt.Decl(l, e, annotOf(v.annotations, v.type.annotations), pos(v))
        }

        /** Statement position: the value is discarded. */
        private fun stmtExpr(e: IrExpression, out: MutableList<MStmt>) {
            when (e) {
                is IrBlock -> {
                    if (e.origin?.debugName == "FOR_LOOP" || e.origin?.debugName == "SAFE_CALL") {
                        for (s in e.statements) stmt(s, out)
                    } else {
                        val inner = ArrayList<MStmt>()
                        for (s in e.statements) stmt(s, inner)
                        out += MStmt.Block(inner, pos(e))
                    }
                }
                is IrComposite -> for (s in e.statements) stmt(s, out)
                is IrWhen -> whenInto(e, out, null)
                is IrWhileLoop -> whileLoop(e, out)
                is IrDoWhileLoop -> doWhile(e, out)
                is IrBreak -> out += MStmt.Break(loopLabels[e.loop] ?: 0, pos(e))
                is IrContinue -> out += MStmt.Continue(loopLabels[e.loop] ?: 0, pos(e))
                is IrReturn -> ret(e, out)
                is IrThrow -> out += MStmt.Throw(expr(e.value, out), pos(e))
                is IrTry -> tryStmt(e, out, null)
                is IrSetValue -> {
                    val v = expr(e.value, out)
                    out += MStmt.Assign(local(e.symbol.owner), v, pos(e))
                }
                is IrSetField -> setField(e, out)
                is IrTypeOperatorCall -> stmtExpr(e.argument, out)
                is IrConst, is IrGetValue -> {}
                else -> {
                    val x = expr(e, out)
                    if (x !is MExpr.Opaque || x.children.isNotEmpty()) out += MStmt.Eval(x, pos(e))
                }
            }
        }

        private fun ret(e: IrReturn, out: MutableList<MStmt>) {
            val target = e.returnTargetSymbol
            val id = scopeIds[target] ?: 0
            val value = e.value
            val isNothing = value.type.classFqName?.asString() == "kotlin.Nothing"
            val isUnit = value.type.classFqName?.asString() == "kotlin.Unit"
            if (id == 0) {
                if (isUnit || isNothing) {
                    stmtExpr(value, out)
                    out += MStmt.Return(null, 0, pos(e))
                } else {
                    out += MStmt.Return(expr(value, out), 0, pos(e))
                }
            } else {
                val res = scopeResults[id]
                if (isUnit || isNothing || res == null) {
                    stmtExpr(value, out)
                } else {
                    out += MStmt.Assign(res, expr(value, out), pos(value))
                }
                out += MStmt.Return(null, id, pos(e))
            }
        }

        private fun whileLoop(l: IrWhileLoop, out: MutableList<MStmt>) {
            val label = ++labelCounter
            loopLabels[l] = label
            val condStmts = ArrayList<MStmt>()
            val cond = expr(l.condition, condStmts)
            val body = ArrayList<MStmt>()
            l.body?.let { stmt(it, body) }
            out += MStmt.While(label, condStmts, cond, body, pos(l))
        }

        private fun doWhile(l: IrDoWhileLoop, out: MutableList<MStmt>) {
            val label = ++labelCounter
            loopLabels[l] = label
            val body = ArrayList<MStmt>()
            l.body?.let { stmt(it, body) }
            val condStmts = ArrayList<MStmt>()
            val cond = expr(l.condition, condStmts)
            out += MStmt.DoWhile(label, body, condStmts, cond, pos(l))
        }

        private fun tryStmt(t: IrTry, out: MutableList<MStmt>, result: MLocal?) {
            val body = ArrayList<MStmt>()
            valueInto(t.tryResult, body, result)
            val catches = t.catches.map { c ->
                val l = declareLocal(c.catchParameter)
                val b = ArrayList<MStmt>()
                valueInto(c.result, b, result)
                MStmt.Catch(l, b)
            }
            val fin = t.finallyExpression?.let { stmtList(it) }
            out += MStmt.Try(body, catches, fin, pos(t))
        }

        /** Lowers [e] for its value into [target] (or as a statement when null). */
        private fun valueInto(e: IrExpression, out: MutableList<MStmt>, target: MLocal?) {
            if (target == null || e.type.classFqName?.asString() == "kotlin.Nothing") { stmtExpr(e, out); return }
            out += MStmt.Assign(target, expr(e, out), pos(e))
        }

        private fun whenInto(w: IrWhen, out: MutableList<MStmt>, target: MLocal?) {
            class Piece(val condStmts: List<MStmt>, val cond: MExpr?, val body: List<MStmt>, val pos: Pos)
            val pieces = w.branches.map { br ->
                val cs = ArrayList<MStmt>()
                val c = if (isConstTrue(br.condition)) null else expr(br.condition, cs)
                val body = ArrayList<MStmt>()
                valueInto(br.result, body, target)
                Piece(cs, c, body, pos(br))
            }
            fun build(i: Int): List<MStmt> {
                if (i >= pieces.size) return emptyList()
                val p = pieces[i]
                val c = p.cond ?: return p.body
                return p.condStmts + MStmt.If(c, p.body, build(i + 1), p.pos)
            }
            out += build(0)
        }

        private fun isConstTrue(e: IrExpression) = e is IrConst && e.value == true

        private fun setField(e: IrSetField, out: MutableList<MStmt>) {
            val f = e.symbol.owner
            val cls = f.parent as? IrClass
            val v = expr(e.value, out)
            val recv = e.receiver
            if (cls != null && recv != null) {
                val mf = classOf(cls).fields.firstOrNull { it.name == (f.correspondingPropertySymbol?.owner?.name?.asString() ?: f.name.asString()) }
                if (mf != null) { out += MStmt.SetField(expr(recv, out), mf, v, pos(e)); return }
            }
        }

        // ---- expressions ---------------------------------------------------------------------------

        private fun opaque(type: IrType, e: IrElement, children: List<MExpr> = emptyList(), lit: Boolean = false) =
            MExpr.Opaque(mapType(type), pos(e), children, lit)

        fun expr(e: IrExpression, out: MutableList<MStmt>): MExpr = when (e) {
            is IrConst -> if (e.value == null) MExpr.Null(mapType(e.type), pos(e)) else opaque(e.type, e, lit = e.value is String)
            is IrGetValue -> {
                val d = e.symbol.owner
                subst[d] ?: MExpr.Local(local(d), pos(e))
            }
            is IrTypeOperatorCall -> when (e.operator) {
                IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF -> opaque(e.type, e, listOf(expr(e.argument, out)))
                else -> {
                    val inner = expr(e.argument, out)
                    if (e.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) inner else inner
                }
            }
            is IrCall -> call(e, out)
            is IrConstructorCall -> newExpr(e, out)
            is IrDelegatingConstructorCall, is IrInstanceInitializerCall -> opaque(e.type, e)
            is IrWhen -> whenExpr(e, out)
            is IrBlock -> {
                val sts = e.statements
                if (sts.isEmpty()) opaque(e.type, e)
                else {
                    for (s in sts.dropLast(1)) stmt(s, out)
                    val last = sts.last()
                    if (last is IrExpression) expr(last, out) else { stmt(last, out); opaque(e.type, e) }
                }
            }
            is IrComposite -> {
                val sts = e.statements
                if (sts.isEmpty()) opaque(e.type, e)
                else {
                    for (s in sts.dropLast(1)) stmt(s, out)
                    val last = sts.last()
                    if (last is IrExpression) expr(last, out) else { stmt(last, out); opaque(e.type, e) }
                }
            }
            is IrStringConcatenation -> opaque(e.type, e, e.arguments.map { expr(it, out) })
            is IrFunctionExpression -> lambdaExpr(e)
            is IrGetField -> {
                val f = e.symbol.owner
                val cls = f.parent as? IrClass
                val recv = e.receiver
                val mf = if (cls != null && recv != null) classOf(cls).fields.firstOrNull { it.name == f.name.asString() || it.name == f.correspondingPropertySymbol?.owner?.name?.asString() } else null
                if (mf != null) MExpr.FieldGet(expr(recv!!, out), mf, mapType(e.type), pos(e)) else opaque(e.type, e)
            }
            is IrReturn, is IrThrow, is IrBreak, is IrContinue, is IrWhileLoop, is IrDoWhileLoop, is IrSetValue, is IrSetField -> {
                stmtExpr(e, out)
                opaque(e.type, e)
            }
            is IrTry -> {
                val ty = mapType(e.type)
                if (e.type.classFqName?.asString() == "kotlin.Unit" || e.type.classFqName?.asString() == "kotlin.Nothing") {
                    tryStmt(e, out, null)
                    opaque(e.type, e)
                } else {
                    val res = tmp(ty, pos(e))
                    out += MStmt.Decl(res, null, MAnnot.NONE, pos(e))
                    tryStmt(e, out, res)
                    MExpr.Move(MExpr.Local(res, pos(e)), true, pos(e))
                }
            }
            else -> opaque(e.type, e)
        }

        private fun whenExpr(w: IrWhen, out: MutableList<MStmt>): MExpr {
            val tn = w.type.classFqName?.asString()
            if (tn == "kotlin.Unit" || tn == "kotlin.Nothing") {
                whenInto(w, out, null)
                return opaque(w.type, w)
            }
            val origin = w.origin?.debugName
            if ((origin == "ANDAND" || origin == "OROR") && w.branches.size == 2) {
                val c = ArrayList<MStmt>(); val r = ArrayList<MStmt>()
                val a = expr(w.branches[0].condition, c)
                val b = expr(if (origin == "ANDAND") w.branches[0].result else w.branches[1].result, r)
                if (c.isEmpty() && r.isEmpty()) return opaque(w.type, w, listOf(a, b))
            }
            val ty = mapType(w.type)
            val res = tmp(ty, pos(w))
            out += MStmt.Decl(res, null, MAnnot.NONE, pos(w))
            whenInto(w, out, res)
            return if (ty.tracked) MExpr.Move(MExpr.Local(res, pos(w)), true, pos(w)) else MExpr.Local(res, pos(w))
        }

        // ---- lambdas ----------------------------------------------------------------------------------

        private fun lambdaExpr(fe: IrFunctionExpression): MExpr {
            val id = ++lambdaCounter
            val frame = CaptureFrame(id)
            frames += frame
            val f = fe.function
            val params = ArrayList<MLocal>()
            for (p in f.parameters) {
                val l = declareLocal(p)
                params += l
            }
            scopeIds[f.symbol] = id
            val body = ArrayList<MStmt>()
            when (val b = f.body) {
                is IrBlockBody -> for (s in b.statements) stmt(s, body)
                is IrExpressionBody -> body += MStmt.Return(expr(b.expression, body), id, pos(b.expression))
                else -> {}
            }
            frames.removeAt(frames.size - 1)
            val caps = frame.captures.toList()
            return MExpr.Lambda(id, params, body, caps.toList(), pos(fe))
        }

        /**
         * Lowers a lambda argument that is read like inline code. Returns the result local (or null for Unit) and
         * appends the Scope statement to [out].
         */
        private fun scope(
            kind: ScopeKind, fe: IrFunctionExpression, out: MutableList<MStmt>, target: MLocal?, ctxArgs: List<MLocal>,
            binds: List<MExpr?>, callPos: Pos,
        ): MLocal? {
            val id = ++lambdaCounter
            val f = fe.function
            val retType = f.returnType
            val isUnit = retType.classFqName?.asString().let { it == "kotlin.Unit" || it == "kotlin.Nothing" }
            val res = if (isUnit) null else tmp(mapType(retType), pos(fe)).also { out += MStmt.Decl(it, null, MAnnot.NONE, pos(fe)) }
            scopeIds[f.symbol] = id
            scopeResults[id] = res
            val frame = if (kind == ScopeKind.Thread) CaptureFrame(id).also { frames += it } else null
            val body = ArrayList<MStmt>()
            // bind lambda parameters
            var bi = 0
            for (p in f.parameters) {
                when (p.kind) {
                    IrParameterKind.Context -> {
                        // context(x) { ... }: the lambda's context parameter is the temp holding the argument
                        val src = ctxArgs.getOrNull(0)
                        if (src != null) subst[p] = MExpr.Local(src, pos(fe))
                    }
                    else -> {
                        val l = declareLocal(p)
                        val b = binds.getOrNull(bi++)
                        body += MStmt.Decl(l, b ?: MExpr.Opaque(l.type, pos(p)), MAnnot.NONE, pos(p))
                    }
                }
            }
            when (val b = f.body) {
                is IrBlockBody -> for (s in b.statements) stmt(s, body)
                is IrExpressionBody -> {
                    val e = b.expression
                    if (res != null) body += MStmt.Assign(res, expr(e, body), pos(e)) else stmtExpr(e, body)
                }
                else -> {}
            }
            var captures: List<MLocal> = emptyList()
            if (frame != null) {
                frames.removeAt(frames.size - 1)
                captures = frame.captures.toList()
            }
            out += MStmt.Scope(kind, id, target, ctxArgs, body, captures, pos(fe), callPos)
            return res
        }

        // ---- calls --------------------------------------------------------------------------------------

        private fun isAnyLambda(e: IrExpression?) = e is IrFunctionExpression

        private fun call(c: IrCall, out: MutableList<MStmt>): MExpr {
            val callee = c.symbol.owner
            val name = callee.name.asString()
            val pkg = (callee.parent as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)?.packageFqName?.asString()
            val parentCls = callee.parentClassOrNull
            val args = c.arguments
            val ps = callee.parameters
            val p = pos(c)

            // property accessors with default bodies: field access
            val prop = callee.correspondingPropertySymbol?.owner
            if (prop != null && callee.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                val isGetter = callee.parameters.count { it.kind == IrParameterKind.Regular } == 0
                val recvIdx = ps.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver || it.kind == IrParameterKind.ExtensionReceiver }
                val recv = if (recvIdx >= 0) args[recvIdx] else null
                if (recv != null && parentCls != null) {
                    val mf = classOf(parentCls).fields.firstOrNull { it.name == prop.name.asString() }
                    if (mf != null) {
                        val ov = expr(recv, out)
                        if (isGetter) return MExpr.FieldGet(ov, mf, mapType(c.type), p)
                        val vi = ps.indexOfFirst { it.kind == IrParameterKind.Regular }
                        val v = expr(args[vi]!!, out)
                        out += MStmt.SetField(ov, mf, v, p)
                        return opaque(c.type, c)
                    }
                } else if (recv == null) {
                    val gname = (callee.parent as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)?.packageFqName?.asString().orEmpty() + "." + prop.name.asString()
                    if (isGetter) return MExpr.GetGlobal(gname, mapType(c.type), p)
                    val vi = ps.indexOfFirst { it.kind == IrParameterKind.Regular }
                    val v = expr(args[vi]!!, out)
                    out += MStmt.SetGlobal(gname, mapType(prop.backingField?.type ?: c.type), v, false, p)
                    return opaque(c.type, c)
                }
            }

            // mini.memory.free / move
            if (pkg == "mini.memory" && name == "free" && ps.any { it.kind == IrParameterKind.ExtensionReceiver }) {
                val t = expr(args[ps.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }]!!, out)
                return MExpr.Free(t, null, FreeKind.Free, p)
            }
            if (pkg == "mini.memory" && name == "move" && ps.any { it.kind == IrParameterKind.ExtensionReceiver }) {
                val t = expr(args[ps.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }]!!, out)
                return MExpr.Move(t, false, p)
            }
            if (parentCls != null && name == "free" && ps.count { it.kind == IrParameterKind.Regular } == 1 &&
                ps.first { it.kind == IrParameterKind.Regular }.type.classFqName?.asString() != "mini.memory.NativePtr" &&
                implementsAllocator(parentCls)
            ) {
                val alloc = expr(args[ps.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }]!!, out)
                val t = expr(args[ps.indexOfFirst { it.kind == IrParameterKind.Regular }]!!, out)
                return MExpr.Free(t, alloc, FreeKind.Free, p)
            }
            if (parentCls != null && name == "allocate" && implementsAllocator(parentCls)) {
                val alloc = expr(args[ps.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }]!!, out)
                return MExpr.RawAlloc(alloc, p)
            }
            if (name == "close" && ps.count { it.kind == IrParameterKind.Regular } == 0 && parentCls != null) {
                val ri = ps.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
                val recv = args[ri]
                if (recv != null) {
                    val rt = mapType(recv.type)
                    if (rt.tracked || rt.tk == TK.Managed && false) {
                        return MExpr.Free(expr(recv, out), null, FreeKind.Close, p)
                    }
                }
            }

            if (pkg == "kotlin.internal.ir" && name == "CHECK_NOT_NULL" && args.isNotEmpty() && args[0] != null) return expr(args[0]!!, out)

            // null tests and negation
            if (pkg == "kotlin.internal.ir" && name == "EQEQ") {
                val a = args[0]; val b = args[1]
                if (b is IrConst && b.value == null && a != null) return MExpr.NullTest(expr(a, out), true, p)
                if (a is IrConst && a.value == null && b != null) return MExpr.NullTest(expr(b, out), true, p)
                return opaque(c.type, c, args.filterNotNull().map { expr(it, out) })
            }
            if (name == "not" && parentCls?.kotlinFqName?.asString() == "kotlin.Boolean") {
                return MExpr.Not(expr(args[0]!!, out), p)
            }

            // inline lambdas: use / context / thread boundary / other inline functions
            val lambdaIdx = ps.indices.filter { isAnyLambda(args.getOrNull(it)) }
            if (lambdaIdx.isNotEmpty()) {
                val special = lambdaCall(c, callee, name, pkg, out)
                if (special != null) return special
            }
            return generalCall(c, callee, out)
        }

        private fun implementsAllocator(c: org.jetbrains.kotlin.ir.declarations.IrClass): Boolean {
            val seen = HashSet<org.jetbrains.kotlin.ir.declarations.IrClass>()
            fun rec(k: org.jetbrains.kotlin.ir.declarations.IrClass): Boolean {
                if (!seen.add(k)) return false
                val fq = k.kotlinFqName.asString()
                if (fq == "mini.memory.Allocator" || fq == "mini.memory.Deallocating") return true
                return k.superTypes.any { t -> t.classOrNull?.owner?.let { rec(it) } == true }
            }
            return rec(c)
        }

        private fun generalCall(c: IrCall, callee: IrSimpleFunction, out: MutableList<MStmt>): MExpr {
            val m = funOf(callee)
            val ps = callee.parameters
            val args = c.arguments
            var recv: MExpr? = null
            val margs = ArrayList<MExpr>()
            for ((i, p) in ps.withIndex()) {
                val a = args[i]
                val v: MExpr = if (a == null) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out)
                when (p.kind) {
                    IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> if (recv == null) recv = v
                    else -> margs += v
                }
            }
            return MExpr.Call(m, recv, margs, mapType(c.type), pos(c))
        }

        private fun newExpr(c: IrConstructorCall, out: MutableList<MStmt>): MExpr {
            val ctor = c.symbol.owner
            val m = funOf(ctor)
            val margs = ArrayList<MExpr>()
            val isThread = (ctor.parent as? IrClass)?.kotlinFqName?.asString() == "java.lang.Thread"
            for ((i, p) in ctor.parameters.withIndex()) {
                val a = c.arguments[i]
                val fe = if (isThread) (a as? IrTypeOperatorCall)?.argument as? IrFunctionExpression ?: a as? IrFunctionExpression else null
                if (fe != null) {
                    scope(ScopeKind.Thread, fe, out, null, emptyList(), emptyList(), pos(c))
                    margs += MExpr.Opaque(mapType(p.type), pos(c))
                    continue
                }
                margs += if (a == null) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out)
            }
            val cls = ctor.parent as IrClass
            val mc = classOf(cls)
            return MExpr.New(m, mc, margs, mapType(c.type), pos(c))
        }

        /** Calls with lambda arguments read like code (use, context, run, let, repeat, thread boundaries...). */
        private fun lambdaCall(c: IrCall, callee: IrSimpleFunction, name: String, pkg: String?, out: MutableList<MStmt>): MExpr? {
            val ps = callee.parameters
            val args = c.arguments
            val p = pos(c)
            val fq = (pkg ?: "") + "." + name
            // thread boundary
            val tIdx = ps.indices.firstOrNull {
                (annos(ps[it].annotations, THREAD_BOUNDARY) != null || fq == "kotlin.concurrent.thread" && ps[it].name.asString() == "block") &&
                    args[it] is IrFunctionExpression
            }
            if (tIdx != null) {
                val rest = generalCallWithLambdasOpaque(c, callee, out)
                scope(ScopeKind.Thread, args[tIdx] as IrFunctionExpression, out, null, emptyList(), emptyList(), p)
                return rest
            }
            if (!callee.isInline) return null
            val lamIdx = ps.indices.filter { args[it] is IrFunctionExpression && !ps[it].isCrossinline && !ps[it].isNoinline }
            if (lamIdx.isEmpty()) return null
            val extIdx = ps.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
            val dispIdx = ps.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
            val recvIdx = if (extIdx >= 0) extIdx else dispIdx
            when {
                name == "use" && lamIdx.size == 1 && recvIdx >= 0 -> {
                    val recvE = expr(args[recvIdx]!!, out)
                    var target: MLocal
                    if (recvE is MExpr.Local) target = recvE.local else {
                        val pn = (args[lamIdx[0]] as IrFunctionExpression).function.parameters.firstOrNull { it.kind == IrParameterKind.Regular }?.name?.asString()
                        target = tmp(recvE.type, p, pn ?: "receiver")
                        out += MStmt.Decl(target, recvE, MAnnot.NONE, p)
                    }
                    val res = scope(ScopeKind.Use, args[lamIdx[0]] as IrFunctionExpression, out, target, emptyList(), listOf(MExpr.Local(target, p)), p)
                    return resultOf(res, c, p)
                }
                fq == "kotlin.context" -> {
                    val ctxLocals = ArrayList<MLocal>()
                    for ((i, pp) in ps.withIndex()) {
                        if (args[i] is IrFunctionExpression || args[i] == null) continue
                        val ae = expr(args[i]!!, out)
                        val t = tmp(ae.type, pos(args[i]!!))
                        out += MStmt.Decl(t, ae, MAnnot.NONE, pos(args[i]!!))
                        ctxLocals += t
                    }
                    val res = scope(ScopeKind.Context, args[lamIdx[0]] as IrFunctionExpression, out, null, ctxLocals, emptyList(), p)
                    return resultOf(res, c, p)
                }
                else -> {
                    // evaluate non-lambda arguments (receiver first); lambda parameters get the receiver / opaque values
                    val vals = ArrayList<MExpr>()
                    var recvVal: MExpr? = null
                    for ((i, pp) in ps.withIndex()) {
                        if (args[i] is IrFunctionExpression) continue
                        val a = args[i] ?: continue
                        val v = expr(a, out)
                        if (i == recvIdx) recvVal = v else vals += v
                    }
                    val once = name in ONCE_INLINE
                    var res: MLocal? = null
                    for (li in lamIdx) {
                        val binds: List<MExpr?> = when {
                            name == "with" -> listOf(vals.firstOrNull())
                            recvVal != null && name in RECEIVER_INLINE -> listOf(recvVal)
                            else -> emptyList()
                        }
                        res = scope(if (once) ScopeKind.Once else ScopeKind.Repeat, args[li] as IrFunctionExpression, out, null, emptyList(), binds, p)
                    }
                    if (name == "also" || name == "apply" || name == "onEach") return recvVal ?: opaque(c.type, c)
                    return resultOf(if (once) res else null, c, p)
                }
            }
        }

        private fun resultOf(res: MLocal?, c: IrCall, p: Pos): MExpr {
            if (res == null) return opaque(c.type, c)
            return if (res.type.tracked) MExpr.Move(MExpr.Local(res, p), true, p) else MExpr.Local(res, p)
        }

        /** The call itself (e.g. `spawn`) with its lambda arguments replaced by opaque values. */
        private fun generalCallWithLambdasOpaque(c: IrCall, callee: IrSimpleFunction, out: MutableList<MStmt>): MExpr {
            val m = funOf(callee)
            val ps = callee.parameters
            var recv: MExpr? = null
            val margs = ArrayList<MExpr>()
            for ((i, p) in ps.withIndex()) {
                val a = c.arguments[i]
                val v: MExpr = if (a == null || a is IrFunctionExpression) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out)
                when (p.kind) {
                    IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> if (recv == null) recv = v
                    else -> margs += v
                }
            }
            return MExpr.Call(m, recv, margs, mapType(c.type), pos(c))
        }
    }

    companion object {
        const val OWNED = "mini.memory.Owned"
        const val BORROWED = "mini.memory.Borrowed"
        const val MEMORY_MODE = "mini.memory.MemoryMode"
        const val ALLOCATED_BY = "mini.memory.AllocatedBy"
        const val NO_ALLOC = "mini.memory.NoAlloc"
        const val THREAD_BOUNDARY = "mini.memory.ThreadBoundary"

        private val PRIMS = mapOf(
            "kotlin.Int" to "Int", "kotlin.Long" to "Long", "kotlin.Short" to "Short", "kotlin.Byte" to "Byte",
            "kotlin.Boolean" to "Boolean", "kotlin.Char" to "Char", "kotlin.Double" to "Double", "kotlin.Float" to "Float",
            "kotlin.Unit" to "Unit", "kotlin.Nothing" to "Nothing",
        )
        private val ALLOCS = mapOf(
            "mini.memory.Heap" to AllocKind.Heap, "mini.memory.Arena" to AllocKind.Arena, "mini.memory.Pool" to AllocKind.Pool,
            "mini.memory.Rc" to AllocKind.Rc, "mini.memory.DebugAllocator" to AllocKind.Debug,
        )
        private val BORROWS_ALL = setOf("println", "print", "toString", "equals", "hashCode", "plus", "compareTo", "to", "assert")
        private val THROWERS = setOf("error", "require", "check", "requireNotNull", "checkNotNull", "TODO")
        private val ONCE_INLINE = setOf("run", "let", "also", "apply", "with", "synchronized", "runCatching", "takeIf", "takeUnless", "use")
        private val RECEIVER_INLINE = setOf("run", "let", "also", "apply", "takeIf", "takeUnless", "runCatching")
    }
}
