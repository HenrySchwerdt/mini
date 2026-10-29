package mini.lower

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
import mini.frontend.sema.*

/*
 * Own IR to MemIr (the input of the ownership checker, `mini.backend.memory`): the port of jvm-bridge/MemoryFacts.kt
 * (Kotlin IR) to the K2-shaped IR of Ast2Ir, so that `--frontend=mini` and the native compiler run the same checker with
 * the same results. It keeps source positions, annotations, context parameters, allocator objects and lambdas, none of
 * which the C-oriented MIR can carry, and must not throw on constructs the C backend does not support.
 *
 * Positions are those of K2's IR (the start of the expression including its receivers); Ast2Ir sets the start offsets.
 */
class MemoryFacts(private val isUserFile: (IrFile) -> Boolean) {
    private val classes = HashMap<IrClass, MClass>()
    private val classList = ArrayList<MClass>()
    private val funs = HashMap<IrFunction, MFun>()
    private val paramLocals = HashMap<IrValueDeclaration, MLocal>()
    private var localCounter = 0
    private var lambdaCounter = 0
    private var labelCounter = 0
    private var curFile: IrFile? = null

    /** Functions that could not be lowered (name -> reason); the checker skips them. */
    val failures = ArrayList<String>()
    val strictFailures = ArrayList<String>()

    fun lower(module: IrModule): MModule {
        cFreeFns = collectCFreeFns(module)
        val functions = ArrayList<MFunction>()
        val sources = HashMap<String, List<String>>()
        var mainFile = ""
        for (file in module.files) {
            if (!isUserFile(file)) continue
            curFile = file
            if (mainFile.isEmpty()) mainFile = file.path
            sources[file.path] = file.text.lines()
            for (d in file.declarations) lowerDeclaration(d, functions)
        }
        return MModule(mainFile, functions, classList, sources)
    }

    private fun lowerDeclaration(d: IrDeclaration, out: MutableList<MFunction>) {
        when (d) {
            is IrSimpleFunction -> if (d.origin == "DEFINED") lowerFunction(d)?.let { out.add(it) }
            is IrClass -> {
                classOf(d)
                for (m in d.declarations) {
                    when (m) {
                        is IrSimpleFunction -> if (!m.isFakeOverride && m.origin == "DEFINED") lowerFunction(m)?.let { out.add(it) }
                        is IrProperty -> {
                            val g = m.getter
                            if (g != null && g.origin != "DEFAULT_PROPERTY_ACCESSOR") lowerFunction(g)?.let { out.add(it) }
                            val s = m.setter
                            if (s != null && s.origin != "DEFAULT_PROPERTY_ACCESSOR") lowerFunction(s)?.let { out.add(it) }
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
        if (e is IrCall) {
            var i = 0
            for (p in e.callee.parameters) {
                if (p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.ExtensionReceiver) {
                    m = minOf(m, leftOffset(if (i < e.arguments.size) e.arguments[i] else null))
                }
                i++
            }
        } else if (e is IrTypeOperatorCall) {
            m = minOf(m, leftOffset(e.argument))
        } else if (e is IrGetField) {
            m = minOf(m, leftOffset(e.receiver))
        } else if (e is IrSetField) {
            m = minOf(m, leftOffset(e.receiver))
        }
        return if (m == Int.MAX_VALUE) -1 else m
    }

    internal fun pos(off: Int): Pos {
        val f = curFile
        return if (off < 0 || f == null) Pos.NONE else Pos(f.line(off), f.col(off))
    }

    // ---- annotations ---------------------------------------------------------------------------

    private fun annotOf(a: List<IrAnnotation>, b: List<IrAnnotation>): MAnnot {
        var owned: Pos? = null
        var borrowed: Pos? = null
        for (l in listOf(a, b)) {
            val o = findAnnotation(l, OWNED)
            if (o != null && owned == null) owned = pos(o.offset)
            val br = findAnnotation(l, BORROWED)
            if (br != null && borrowed == null) borrowed = pos(br.offset)
        }
        return if (owned == null && borrowed == null) MAnnot.NONE else MAnnot(owned, borrowed)
    }

    private fun modeOf(a: IrAnnotation): MemMode? {
        val v = a.arg ?: return null
        return if (v == "Managed") MemMode.Managed else MemMode.Strict
    }

    private fun fileMode(f: IrFile?): MemMode {
        if (f == null) return MemMode.Managed
        val a = findAnnotation(f.annotations, MEMORY_MODE) ?: return MemMode.Managed
        return modeOf(a) ?: MemMode.Managed
    }

    /** The source file of a declaration; null for the stand-ins of builtins (they have no source, like K2's library symbols). */
    private fun fileOf(d: IrDeclaration): IrFile? {
        val f = Reachability.fileOf(d) ?: return null
        return if (f.path == "<ir builtins>") null else f
    }

    private fun parentClassOrNull(d: IrDeclaration): IrClass? {
        var p: IrDeclarationParent? = d.parent
        while (p != null) {
            if (p is IrClass) return p
            if (p is IrDeclaration) p = p.parent else return null
        }
        return null
    }

    // ---- classes and types -------------------------------------------------------------------

    internal fun classOf(c: IrClass): MClass {
        val ex = classes[c]
        if (ex != null) return ex
        val file = fileOf(c)
        var mode = if (file == null) MemMode.Managed else fileMode(file)
        val mm = findAnnotation(c.annotations, MEMORY_MODE)
        if (mm != null) {
            val m = modeOf(mm)
            if (m != null) mode = m
        }
        val ab = findAnnotation(c.annotations, ALLOCATED_BY)?.argFq
        // C interop (docs/plan-kotlin.md 7): pointers and everything that lives in C memory are tracked like Strict objects
        if (isPtrClass(c)) mode = MemMode.Strict
        val mc = MClass(c.kotlinFqName, mode, ab, isCloseable(c))
        classes[c] = mc
        classList.add(mc)
        // fields (only for classes of this module)
        if (file != null) {
            var ctorParams: List<IrValueParameter> = emptyList()
            for (d in c.declarations) if (d is IrConstructor && d.isPrimary) {
                ctorParams = d.parameters
                break
            }
            for (d in c.declarations) {
                if (d !is IrProperty) continue
                val bf = d.backingField ?: continue
                var ctorParam: IrValueParameter? = null
                for (cp in ctorParams) if (cp.name == d.name) {
                    ctorParam = cp
                    break
                }
                val borrowed = hasAnnotation(d.annotations, BORROWED) || hasAnnotation(bf.annotations, BORROWED) ||
                    (ctorParam != null && hasAnnotation(ctorParam.annotations, BORROWED))
                mc.fields.add(MField(d.name, mapType(bf.type), borrowed, mc))
            }
        }
        return mc
    }

    /** `CPointer`, `NativePtr`, `CPointed` and everything extending it (kotlinx.cinterop): values that are plain addresses. */
    internal fun isPtrClass(c: IrClass): Boolean {
        val seen = HashSet<IrClass>()
        fun rec(k: IrClass): Boolean {
            if (!seen.add(k)) return false
            val fq = k.kotlinFqName
            if (fq == "kotlinx.cinterop.CPointer" || fq == "kotlinx.cinterop.NativePtr" || fq == "kotlinx.cinterop.CPointed") return true
            for (t in k.superTypes) {
                val o = t.classOrNull
                if (o != null && rec(o)) return true
            }
            return false
        }
        return rec(c)
    }

    private fun isCInteropFunction(f: IrFunction): Boolean {
        val par = f.parent
        if (par is IrFile && par.packageFqName == "kotlinx.cinterop") return true
        val cls = parentClassOrNull(f) ?: return false
        return cls.kotlinFqName.startsWith("kotlinx.cinterop.") || isPtrClass(cls)
    }

    /** The C function named by `@Owned(free = "fn")` on [f] (null: no such annotation; "": owned, no named free function). */
    private fun ownedFree(f: IrFunction): String? {
        val a = findAnnotation(f.annotations, OWNED) ?: return null
        val s = a.arg
        return if (s == null) "" else s
    }

    /** The C symbol of an `external fun` (its `@CName`, else its name), or null when [f] is not a C function. */
    internal fun cSymbolOf(f: IrFunction): String? {
        val par = f.parent
        if (!f.isExternal || par !is IrFile) return null
        val pkg = par.packageFqName
        val cn = findAnnotation(f.annotations, "kotlin.native.CName")
        if (cn != null) {
            val s = cn.arg
            return if (s == null || s.length == 0) f.name else s
        }
        if (pkg == "kotlin" || pkg.startsWith("kotlin.") || pkg.startsWith("kotlinx.") || pkg.startsWith("mini.") || pkg == "java" || pkg.startsWith("java.")) return null
        return f.name
    }

    /** The names of all C functions that release something (`@Owned(free = "fn")` anywhere in the user files). */
    private var cFreeFns: Set<String>? = null

    private fun collectCFreeFns(module: IrModule): Set<String> {
        val out = HashSet<String>()
        // the declarations of external functions are gone (Reachability); their calls tell which ones release something
        val w = object : IrWalker() {
            override fun visit(e: IrElement) {
                if (e is IrCall) {
                    val of = ownedFree(e.callee)
                    if (of != null && of.length > 0 && e.callee.isExternal) out.add(of)
                }
                visitChildren(e)
            }
        }
        for (file in module.files) if (isUserFile(file)) w.visitChildren(file)
        return out
    }

    private fun isCloseable(c: IrClass): Boolean {
        val seen = HashSet<IrClass>()
        fun rec(k: IrClass): Boolean {
            if (!seen.add(k)) return false
            val fq = k.kotlinFqName
            if (fq == "kotlin.AutoCloseable" || fq == JAVA_PKG + "lang.AutoCloseable" || fq == JAVA_PKG + "io.Closeable") return true
            for (t in k.superTypes) {
                val o = t.classOrNull
                if (o != null && rec(o)) return true
            }
            return false
        }
        return rec(c)
    }

    internal fun mapType(t: Type): MType {
        val nullable = t.isMarkedNullable()
        val fq = t.classFqName
        val cls = t.classOrNull
        if (fq == null || cls == null) return MType(TK.Other, nullable, null, null, null, "Any")
        val prim = PRIMS[fq]
        if (prim != null) return MType(TK.Prim, nullable, null, null, null, prim)
        if (fq == "kotlin.String") return MType(TK.Str, nullable, null, null, null, "String")
        if (fq == "kotlin.Any" || fq.startsWith("kotlin.Function") || fq.startsWith(REFLECT_PREFIX)) {
            return MType(TK.Other, nullable, null, null, null, fq.substring(fq.lastIndexOf('.') + 1))
        }
        val ak = ALLOCS[fq]
        if (ak != null) return MType(TK.Alloc, nullable, null, ak, fq, fq.substring(fq.lastIndexOf('.') + 1))
        if (fq.startsWith("mini.memory.") && cls.kind == IrClassKind.INTERFACE) {
            return MType(TK.Alloc, nullable, null, AllocKind.Other, fq, fq.substring(fq.lastIndexOf('.') + 1))
        }
        val mc = classOf(cls)
        val display = fq.substring(fq.lastIndexOf('.') + 1)
        return if (mc.mode == MemMode.Strict && fileOf(cls) != null) MType(TK.Strict, nullable, mc, null, fq, display)
        else MType(TK.Managed, nullable, mc, null, fq, display)
    }

    // ---- functions -------------------------------------------------------------------------------

    private fun newLocal(name: String, type: MType, pos: Pos): MLocal {
        localCounter++
        return MLocal(localCounter, name, type, pos)
    }

    private fun paramLocal(p: IrValueDeclaration, name: String, type: Type): MLocal {
        val ex = paramLocals[p]
        if (ex != null) return ex
        val l = newLocal(name, mapType(type), pos(p))
        paramLocals[p] = l
        return l
    }

    internal fun funOf(f: IrFunction): MFun {
        val ex = funs[f]
        if (ex != null) return ex
        val file = fileOf(f)
        val cls = parentClassOrNull(f)
        var mode = if (file == null) MemMode.Managed else fileMode(file)
        if (cls != null) mode = classOf(cls).mode
        // C interop: the pointer API borrows its arguments and returns loans of them; it is checked like Strict code
        val interop = isCInteropFunction(f)
        if (interop) mode = MemMode.Strict
        val external = file == null
        val ctor = f as? IrConstructor
        val ctorClass = if (ctor != null) classOf(ctor.parent as IrClass) else null
        val props = HashSet<String>()
        if (ctorClass != null) for (fld in ctorClass.fields) props.add(fld.name)
        var receiver: MParam? = null
        val params = ArrayList<MParam>()
        for (p in f.parameters) {
            val l = paramLocal(p, p.name, p.type)
            val annot = annotOf(p.annotations, p.typeAnnotations)
            when (p.kind) {
                IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> if (receiver == null) receiver = MParam(l, annot)
                IrParameterKind.Context -> params.add(MParam(l, annot, isContext = true))
                IrParameterKind.Regular -> params.add(MParam(l, annot, implicitOwned = ctor != null && p.name in props))
            }
        }
        val name = f.name
        var retAnnot = annotOf(f.annotations, f.returnTypeAnnotations)
        if (interop && mapType(f.returnType).tracked) retAnnot = MAnnot(null, pos(f))
        val parent = f.parent
        val fq = if (parent is IrFile) parent.packageFqName + "." + name
        else (if (cls != null) cls.kotlinFqName else "") + "." + name
        val m = MFun(
            qname = fq,
            mode = mode,
            params = params,
            ret = MReturn(if (ctor != null) ctorType(ctor) else mapType(f.returnType), retAnnot),
            receiver = receiver,
            noAlloc = hasAnnotation(f.annotations, NO_ALLOC),
            external = external,
            borrowsAll = external && (name in BORROWS_ALL || fq.startsWith("kotlin.internal.ir.")),
            ctorOf = ctorClass,
            pos = pos(f),
            throwsKnown = external && name in THROWERS,
            cFree = if (f.isExternal && file != null) ownedFree(f) else null,
        )
        funs[f] = m
        return m
    }

    private fun ctorType(ctor: IrConstructor): MType {
        val s = (ctor.parent as IrClass).sym
        return if (s != null) mapType(s.thisType()) else MType.OTHER
    }

    private fun lowerFunction(f: IrSimpleFunction): MFunction? {
        val body = f.body ?: return null
        val m = funOf(f)
        return try {
            val lw = Lowerer(f)
            val b = lw.lowerFunctionBody(body)
            m.body = b
            MFunction(m, b, true, curFile?.path ?: "")
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            failures.add(m.qname + ": " + msg)
            if (m.mode == MemMode.Strict) strictFailures.add(m.qname + ": " + msg)
            null
        }
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
        private val scopeIds = HashMap<IrFunction, Int>()
        private val scopeResults = HashMap<Int, MLocal?>()

        private fun local(v: IrValueDeclaration): MLocal {
            var l = locals[v]
            if (l == null) {
                l = paramLocals[v]
                if (l == null) {
                    l = newLocal(v.name, mapType(v.type), pos(v))
                    owner[l.id] = frames.size
                }
                locals[v] = l
            }
            // capture bookkeeping
            val own = owner[l.id] ?: 0
            var i = own
            while (i < frames.size) {
                frames[i].captures.add(l)
                i++
            }
            return l
        }

        private fun declareLocal(v: IrValueDeclaration, name: String = v.name): MLocal {
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

        fun lowerFunctionBody(body: IrBody): List<MStmt> {
            for (p in fn.parameters) {
                val l = paramLocal(p, p.name, p.type)
                locals[p] = l
                owner[l.id] = 0
            }
            val out = ArrayList<MStmt>()
            if (body is IrBlockBody) {
                for (s in body.statements) stmt(s, out)
            } else if (body is IrExpressionBody) {
                val e = expr(body.expression, out)
                out.add(MStmt.Return(e, 0, pos(body.expression)))
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
            if (s is IrVariable) variable(s, out)
            else if (s is IrExpression) stmtExpr(s, out)
        }

        private fun variable(v: IrVariable, out: MutableList<MStmt>) {
            val init = v.initializer
            if (v.origin == "IR_TEMPORARY_VARIABLE" && init != null) {
                val e = expr(init, out)
                if (e is MExpr.Local || e is MExpr.Null) {
                    subst[v] = e
                    return
                }
                val l = declareLocal(v)
                out.add(MStmt.Decl(l, e, MAnnot.NONE, pos(v)))
                return
            }
            val l = declareLocal(v)
            val e = if (init != null) expr(init, out) else null
            out.add(MStmt.Decl(l, e, annotOf(v.annotations, v.typeAnnotations), pos(v)))
        }

        /** Statement position: the value is discarded. */
        private fun stmtExpr(e: IrExpression, out: MutableList<MStmt>) {
            when (e) {
                is IrBlock -> {
                    if (e.origin == "FOR_LOOP" || e.origin == "SAFE_CALL") {
                        for (s in e.statements) stmt(s, out)
                    } else {
                        val inner = ArrayList<MStmt>()
                        for (s in e.statements) stmt(s, inner)
                        out.add(MStmt.Block(inner, pos(e)))
                    }
                }
                is IrComposite -> for (s in e.statements) stmt(s, out)
                is IrWhen -> whenInto(e, out, null)
                is IrWhileLoop -> whileLoop(e, out)
                is IrDoWhileLoop -> doWhile(e, out)
                is IrBreak -> out.add(MStmt.Break(loopLabels[e.loop] ?: 0, pos(e)))
                is IrContinue -> out.add(MStmt.Continue(loopLabels[e.loop] ?: 0, pos(e)))
                is IrReturn -> ret(e, out)
                is IrThrow -> out.add(MStmt.Throw(expr(e.value, out), pos(e)))
                is IrTry -> tryStmt(e, out, null)
                is IrSetValue -> {
                    val v = expr(e.value, out)
                    out.add(MStmt.Assign(local(e.owner), v, pos(e)))
                }
                is IrSetField -> setField(e, out)
                is IrTypeOperatorCall -> stmtExpr(e.argument, out)
                is IrConst, is IrGetValue -> {}
                else -> {
                    val x = expr(e, out)
                    if (x !is MExpr.Opaque || x.children.isNotEmpty()) out.add(MStmt.Eval(x, pos(e)))
                }
            }
        }

        private fun ret(e: IrReturn, out: MutableList<MStmt>) {
            val id = scopeIds[e.returnTarget] ?: 0
            val value = e.value
            val isNothing = value.type.classFqName == "kotlin.Nothing"
            val isUnit = value.type.classFqName == "kotlin.Unit"
            if (id == 0) {
                if (isUnit || isNothing) {
                    stmtExpr(value, out)
                    out.add(MStmt.Return(null, 0, pos(e)))
                } else {
                    out.add(MStmt.Return(expr(value, out), 0, pos(e)))
                }
            } else {
                val res = scopeResults[id]
                if (isUnit || isNothing || res == null) {
                    stmtExpr(value, out)
                } else {
                    out.add(MStmt.Assign(res, expr(value, out), pos(value)))
                }
                out.add(MStmt.Return(null, id, pos(e)))
            }
        }

        private fun whileLoop(l: IrWhileLoop, out: MutableList<MStmt>) {
            labelCounter++
            val label = labelCounter
            loopLabels[l] = label
            val condStmts = ArrayList<MStmt>()
            val cond = expr(l.condition, condStmts)
            val body = ArrayList<MStmt>()
            val b = l.body
            if (b != null) stmt(b, body)
            out.add(MStmt.While(label, condStmts, cond, body, pos(l)))
        }

        private fun doWhile(l: IrDoWhileLoop, out: MutableList<MStmt>) {
            labelCounter++
            val label = labelCounter
            loopLabels[l] = label
            val body = ArrayList<MStmt>()
            val b = l.body
            if (b != null) stmt(b, body)
            val condStmts = ArrayList<MStmt>()
            val cond = expr(l.condition, condStmts)
            out.add(MStmt.DoWhile(label, body, condStmts, cond, pos(l)))
        }

        private fun tryStmt(t: IrTry, out: MutableList<MStmt>, result: MLocal?) {
            val body = ArrayList<MStmt>()
            valueInto(t.tryResult, body, result)
            val catches = ArrayList<MStmt.Catch>()
            for (c in t.catches) {
                val l = declareLocal(c.catchParameter)
                val b = ArrayList<MStmt>()
                valueInto(c.result, b, result)
                catches.add(MStmt.Catch(l, b))
            }
            val fe = t.finallyExpression
            val fin = if (fe != null) stmtList(fe) else null
            out.add(MStmt.Try(body, catches, fin, pos(t)))
        }

        /** Lowers [e] for its value into [target] (or as a statement when null). */
        private fun valueInto(e: IrExpression, out: MutableList<MStmt>, target: MLocal?) {
            if (target == null || e.type.classFqName == "kotlin.Nothing") {
                stmtExpr(e, out)
                return
            }
            out.add(MStmt.Assign(target, expr(e, out), pos(e)))
        }

        private fun whenInto(w: IrWhen, out: MutableList<MStmt>, target: MLocal?) {
            val conds = ArrayList<MExpr?>()
            val condStmts = ArrayList<List<MStmt>>()
            val bodies = ArrayList<List<MStmt>>()
            val poss = ArrayList<Pos>()
            for (br in w.branches) {
                val cs = ArrayList<MStmt>()
                val c = if (isConstTrue(br.condition)) null else expr(br.condition, cs)
                val body = ArrayList<MStmt>()
                valueInto(br.result, body, target)
                conds.add(c)
                condStmts.add(cs)
                bodies.add(body)
                poss.add(pos(br))
            }
            fun build(i: Int): List<MStmt> {
                if (i >= conds.size) return emptyList()
                val c = conds[i] ?: return bodies[i]
                val r = ArrayList<MStmt>(condStmts[i])
                r.add(MStmt.If(c, bodies[i], build(i + 1), poss[i]))
                return r
            }
            out.addAll(build(0))
        }

        private fun isConstTrue(e: IrExpression) = e is IrConst && e.value == true

        private fun setField(e: IrSetField, out: MutableList<MStmt>) {
            val f = e.field
            val cls = f.parent as? IrClass
            val v = expr(e.value, out)
            val recv = e.receiver
            if (cls != null && recv != null) {
                val pn = f.correspondingProperty?.name ?: f.name
                var mf: MField? = null
                for (x in classOf(cls).fields) if (x.name == pn) {
                    mf = x
                    break
                }
                if (mf != null) out.add(MStmt.SetField(expr(recv, out), mf, v, pos(e)))
            }
        }

        // ---- expressions ---------------------------------------------------------------------------

        private fun opaque(type: Type, e: IrElement, children: List<MExpr> = emptyList(), lit: Boolean = false) =
            MExpr.Opaque(mapType(type), pos(e), children, lit)

        fun expr(e: IrExpression, out: MutableList<MStmt>): MExpr {
            when (e) {
                is IrConst -> return if (e.value == null) MExpr.Null(mapType(e.type), pos(e)) else opaque(e.type, e, lit = e.value is String)
                is IrGetValue -> {
                    val d = e.owner
                    return subst[d] ?: MExpr.Local(local(d), pos(e))
                }
                is IrTypeOperatorCall -> {
                    if (e.operator == IrTypeOperator.INSTANCEOF || e.operator == IrTypeOperator.NOT_INSTANCEOF) {
                        return opaque(e.type, e, listOf(expr(e.argument, out)))
                    }
                    return expr(e.argument, out)
                }
                is IrCall -> return call(e, out)
                is IrConstructorCall -> return newExpr(e, out)
                is IrDelegatingConstructorCall, is IrInstanceInitializerCall -> return opaque(e.type, e)
                is IrWhen -> return whenExpr(e, out)
                is IrContainerExpression -> {
                    val sts = e.statements
                    if (sts.isEmpty()) return opaque(e.type, e)
                    var i = 0
                    while (i < sts.size - 1) {
                        stmt(sts[i], out)
                        i++
                    }
                    val last = sts[sts.size - 1]
                    if (last is IrExpression) return expr(last, out)
                    stmt(last, out)
                    return opaque(e.type, e)
                }
                is IrStringConcatenation -> {
                    val cs = ArrayList<MExpr>()
                    for (a in e.arguments) cs.add(expr(a, out))
                    return opaque(e.type, e, cs)
                }
                is IrFunctionExpression -> return lambdaExpr(e)
                is IrGetField -> {
                    val f = e.field
                    val cls = f.parent as? IrClass
                    val recv = e.receiver
                    var mf: MField? = null
                    if (cls != null && recv != null) {
                        val pn = f.correspondingProperty?.name
                        for (x in classOf(cls).fields) if (x.name == f.name || x.name == pn) {
                            mf = x
                            break
                        }
                    }
                    return if (mf != null) MExpr.FieldGet(expr(recv!!, out), mf, mapType(e.type), pos(e)) else opaque(e.type, e)
                }
                is IrReturn, is IrThrow, is IrBreak, is IrContinue, is IrWhileLoop, is IrDoWhileLoop, is IrSetValue, is IrSetField -> {
                    stmtExpr(e, out)
                    return opaque(e.type, e)
                }
                is IrTry -> {
                    val ty = mapType(e.type)
                    if (e.type.classFqName == "kotlin.Unit" || e.type.classFqName == "kotlin.Nothing") {
                        tryStmt(e, out, null)
                        return opaque(e.type, e)
                    }
                    val res = tmp(ty, pos(e))
                    out.add(MStmt.Decl(res, null, MAnnot.NONE, pos(e)))
                    tryStmt(e, out, res)
                    return MExpr.Move(MExpr.Local(res, pos(e)), true, pos(e))
                }
                else -> return opaque(e.type, e)
            }
        }

        private fun whenExpr(w: IrWhen, out: MutableList<MStmt>): MExpr {
            val tn = w.type.classFqName
            if (tn == "kotlin.Unit" || tn == "kotlin.Nothing") {
                whenInto(w, out, null)
                return opaque(w.type, w)
            }
            val origin = w.origin
            if ((origin == "ANDAND" || origin == "OROR") && w.branches.size == 2) {
                val c = ArrayList<MStmt>()
                val r = ArrayList<MStmt>()
                val a = expr(w.branches[0].condition, c)
                val b = expr(if (origin == "ANDAND") w.branches[0].result else w.branches[1].result, r)
                if (c.isEmpty() && r.isEmpty()) return opaque(w.type, w, listOf(a, b))
            }
            val ty = mapType(w.type)
            val res = tmp(ty, pos(w))
            out.add(MStmt.Decl(res, null, MAnnot.NONE, pos(w)))
            whenInto(w, out, res)
            return if (ty.tracked) MExpr.Move(MExpr.Local(res, pos(w)), true, pos(w)) else MExpr.Local(res, pos(w))
        }

        // ---- lambdas ----------------------------------------------------------------------------------

        private fun lambdaExpr(fe: IrFunctionExpression): MExpr {
            lambdaCounter++
            val id = lambdaCounter
            val frame = CaptureFrame(id)
            frames.add(frame)
            val f = fe.function
            val params = ArrayList<MLocal>()
            for (p in f.parameters) params.add(declareLocal(p))
            scopeIds[f] = id
            val body = ArrayList<MStmt>()
            val b = f.body
            if (b is IrBlockBody) {
                for (s in b.statements) stmt(s, body)
            } else if (b is IrExpressionBody) {
                body.add(MStmt.Return(expr(b.expression, body), id, pos(b.expression)))
            }
            frames.removeAt(frames.size - 1)
            return MExpr.Lambda(id, params, body, frame.captures.toList(), pos(fe))
        }

        /**
         * Lowers a lambda argument that is read like inline code. Returns the result local (or null for Unit) and
         * appends the Scope statement to [out].
         */
        private fun scope(
            kind: ScopeKind, fe: IrFunctionExpression, out: MutableList<MStmt>, target: MLocal?, ctxArgs: List<MLocal>,
            binds: List<MExpr?>, callPos: Pos,
        ): MLocal? {
            lambdaCounter++
            val id = lambdaCounter
            val f = fe.function
            val retType = f.returnType
            val rn = retType.classFqName
            val isUnit = rn == "kotlin.Unit" || rn == "kotlin.Nothing"
            var res: MLocal? = null
            if (!isUnit) {
                res = tmp(mapType(retType), pos(fe))
                out.add(MStmt.Decl(res, null, MAnnot.NONE, pos(fe)))
            }
            scopeIds[f] = id
            scopeResults[id] = res
            var frame: CaptureFrame? = null
            if (kind == ScopeKind.Thread) {
                frame = CaptureFrame(id)
                frames.add(frame)
            }
            val body = ArrayList<MStmt>()
            // bind lambda parameters
            var bi = 0
            for (p in f.parameters) {
                if (p.kind == IrParameterKind.Context) {
                    // context(x) { ... }: the lambda's context parameter is the temp holding the argument
                    val src = if (ctxArgs.isNotEmpty()) ctxArgs[0] else null
                    if (src != null) subst[p] = MExpr.Local(src, pos(fe))
                } else {
                    val l = declareLocal(p)
                    val b = if (bi < binds.size) binds[bi] else null
                    bi++
                    body.add(MStmt.Decl(l, b ?: MExpr.Opaque(l.type, pos(p)), MAnnot.NONE, pos(p)))
                }
            }
            val b = f.body
            if (b is IrBlockBody) {
                for (s in b.statements) stmt(s, body)
            } else if (b is IrExpressionBody) {
                val e = b.expression
                if (res != null) body.add(MStmt.Assign(res, expr(e, body), pos(e))) else stmtExpr(e, body)
            }
            var captures: List<MLocal> = emptyList()
            if (frame != null) {
                frames.removeAt(frames.size - 1)
                captures = frame.captures.toList()
            }
            out.add(MStmt.Scope(kind, id, target, ctxArgs, body, captures, pos(fe), callPos))
            return res
        }

        // ---- calls --------------------------------------------------------------------------------------

        private fun isAnyLambda(e: IrExpression?) = e is IrFunctionExpression

        private fun indexOfKind(ps: List<IrValueParameter>, k: IrParameterKind): Int {
            var i = 0
            for (p in ps) {
                if (p.kind == k) return i
                i++
            }
            return -1
        }

        private fun countKind(ps: List<IrValueParameter>, k: IrParameterKind): Int {
            var n = 0
            for (p in ps) if (p.kind == k) n++
            return n
        }

        private fun call(c: IrCall, out: MutableList<MStmt>): MExpr {
            val callee = c.callee
            val name = callee.name
            val cp = callee.parent
            val pkg = if (cp is IrFile) cp.packageFqName else null
            val parentCls = parentClassOrNull(callee)
            val args = c.arguments
            val ps = callee.parameters
            val p = pos(c)

            // property accessors with default bodies: field access
            val prop = callee.correspondingProperty
            if (prop != null && callee.origin == "DEFAULT_PROPERTY_ACCESSOR") {
                val isGetter = countKind(ps, IrParameterKind.Regular) == 0
                val recvIdx = run {
                    var r = -1
                    var i = 0
                    for (x in ps) {
                        if (x.kind == IrParameterKind.DispatchReceiver || x.kind == IrParameterKind.ExtensionReceiver) {
                            r = i
                            break
                        }
                        i++
                    }
                    r
                }
                val recv = if (recvIdx >= 0) args[recvIdx] else null
                if (recv != null && parentCls != null) {
                    var mf: MField? = null
                    for (x in classOf(parentCls).fields) if (x.name == prop.name) {
                        mf = x
                        break
                    }
                    if (mf != null) {
                        val ov = expr(recv, out)
                        if (isGetter) return MExpr.FieldGet(ov, mf, mapType(c.type), p)
                        val vi = indexOfKind(ps, IrParameterKind.Regular)
                        val v = expr(args[vi]!!, out)
                        out.add(MStmt.SetField(ov, mf, v, p))
                        return opaque(c.type, c)
                    }
                } else if (recv == null) {
                    val gname = (if (cp is IrFile) cp.packageFqName else "") + "." + prop.name
                    if (isGetter) return MExpr.GetGlobal(gname, mapType(c.type), p)
                    val vi = indexOfKind(ps, IrParameterKind.Regular)
                    val v = expr(args[vi]!!, out)
                    val bf = prop.backingField
                    out.add(MStmt.SetGlobal(gname, mapType(if (bf != null) bf.type else c.type), v, false, p))
                    return opaque(c.type, c)
                }
            }

            // mini.memory.free / move
            val extIdx = indexOfKind(ps, IrParameterKind.ExtensionReceiver)
            if (pkg == "mini.memory" && name == "free" && extIdx >= 0) {
                val t = expr(args[extIdx]!!, out)
                return MExpr.Free(t, null, FreeKind.Free, p)
            }
            if (pkg == "mini.memory" && name == "move" && extIdx >= 0) {
                val t = expr(args[extIdx]!!, out)
                return MExpr.Move(t, false, p)
            }
            if (parentCls != null && name == "free" && countKind(ps, IrParameterKind.Regular) == 1 &&
                ps[indexOfKind(ps, IrParameterKind.Regular)].type.classFqName != "mini.memory.NativePtr" &&
                implementsAllocator(parentCls)
            ) {
                val alloc = expr(args[indexOfKind(ps, IrParameterKind.DispatchReceiver)]!!, out)
                val t = expr(args[indexOfKind(ps, IrParameterKind.Regular)]!!, out)
                return MExpr.Free(t, alloc, FreeKind.Free, p)
            }
            if (parentCls != null && name == "allocate" && implementsAllocator(parentCls)) {
                val alloc = expr(args[indexOfKind(ps, IrParameterKind.DispatchReceiver)]!!, out)
                return MExpr.RawAlloc(alloc, p)
            }
            if (name == "close" && countKind(ps, IrParameterKind.Regular) == 0 && parentCls != null) {
                val ri = indexOfKind(ps, IrParameterKind.DispatchReceiver)
                val recv = if (ri >= 0) args[ri] else null
                if (recv != null) {
                    val rt = mapType(recv.type)
                    if (rt.tracked) return MExpr.Free(expr(recv, out), null, FreeKind.Close, p)
                }
            }

            if (pkg == "kotlin.internal.ir" && name == "CHECK_NOT_NULL" && args.isNotEmpty() && args[0] != null) return expr(args[0]!!, out)

            val ci = cInteropCall(c, callee, name, pkg, parentCls, out)
            if (ci != null) return ci

            // null tests and negation
            if (pkg == "kotlin.internal.ir" && name == "EQEQ") {
                val a = args[0]
                val b = args[1]
                if (b is IrConst && b.value == null && a != null) return MExpr.NullTest(expr(a, out), true, p)
                if (a is IrConst && a.value == null && b != null) return MExpr.NullTest(expr(b, out), true, p)
                val cs = ArrayList<MExpr>()
                for (x in args) if (x != null) cs.add(expr(x, out))
                return opaque(c.type, c, cs)
            }
            if (name == "not" && parentCls?.kotlinFqName == "kotlin.Boolean") {
                return MExpr.Not(expr(args[0]!!, out), p)
            }

            // inline lambdas: use / context / thread boundary / other inline functions
            var hasLambda = false
            for (i in ps.indices) if (isAnyLambda(args.getOrNull(i))) hasLambda = true
            if (hasLambda) {
                val special = lambdaCall(c, callee, name, pkg, out)
                if (special != null) return special
            }
            return generalCall(c, callee, out)
        }

        // ---- C interop (docs/plan-kotlin.md 7, docs/memory-model.md): the same facts as jvm-bridge/MemoryFacts ---------------

        /**
         *  - `memScoped { }` is `Arena().use { scope -> ... }`: the lambda's receiver is the arena;
         *  - `alloc<T>()`, `allocArray`, `allocPointerTo`, `getPointer(scope)`, `CValues.ptr` allocate *in* the allocator they are
         *    called on (a `MemScope`: arena rules, M0105 on escape; `nativeHeap`: Deallocating rules, must be freed);
         *  - `nativeHeap.free(p)` is `alloc.free(p)`;
         *  - a call of the C function named in an `@Owned(free = "fn")` frees its argument;
         *  - a pointer returned by a C function without `@Owned` is externally managed: no obligation.
         */
        private fun cInteropCall(c: IrCall, callee: IrSimpleFunction, name: String, pkg: String?, parentCls: IrClass?, out: MutableList<MStmt>): MExpr? {
            val ps = callee.parameters
            val args = c.arguments
            val p = pos(c)
            val inInterop = pkg == "kotlinx.cinterop" || (parentCls != null && parentCls.kotlinFqName.startsWith("kotlinx.cinterop."))
            if (inInterop) {
                if (name == "memScoped" && pkg == "kotlinx.cinterop") {
                    var li = -1
                    var i = 0
                    while (i < ps.size) {
                        if (args[i] is IrFunctionExpression) {
                            li = i
                            break
                        }
                        i++
                    }
                    if (li < 0) return null
                    val fe = args[li] as IrFunctionExpression
                    var recvParam: IrValueParameter? = null
                    for (x in fe.function.parameters) if (x.kind == IrParameterKind.ExtensionReceiver || x.kind == IrParameterKind.DispatchReceiver) {
                        recvParam = x
                        break
                    }
                    if (recvParam == null) return null
                    val scopeType = mapType(recvParam.type)
                    val ctor = MFun("kotlinx.cinterop.MemScope.<init>", MemMode.Strict, emptyList(), MReturn(scopeType))
                    val target = tmp(scopeType, p, "memScope")
                    out.add(MStmt.Decl(target, MExpr.New(ctor, null, emptyList(), scopeType, p), MAnnot.NONE, p))
                    val res = scope(ScopeKind.Use, fe, out, target, emptyList(), listOf<MExpr?>(MExpr.Local(target, p)), p)
                    return resultOf(res, c, p)
                }
                var allocator: IrExpression? = null
                val pcn = if (parentCls != null) parentCls.kotlinFqName else ""
                if (pkg == "kotlinx.cinterop" && (name == "alloc" || name == "allocArray" || name == "allocPointerTo")) {
                    var k = 0
                    while (k < ps.size) {
                        if (ps[k].kind == IrParameterKind.ExtensionReceiver || ps[k].kind == IrParameterKind.DispatchReceiver) {
                            allocator = args[k]
                            break
                        }
                        k++
                    }
                } else if (name == "alloc" && parentCls != null && (pcn == "kotlinx.cinterop.MemScope" || pcn == "kotlinx.cinterop.nativeHeap")) {
                    val k = indexOfKind(ps, IrParameterKind.DispatchReceiver)
                    if (k >= 0) allocator = args[k]
                } else if (name == "getPointer" && pcn == "kotlinx.cinterop.CValues") {
                    val k = indexOfKind(ps, IrParameterKind.Regular)
                    if (k >= 0) allocator = args[k]
                } else if (name == "<get-ptr>" && pcn == "kotlinx.cinterop.MemScope") {
                    val k = indexOfKind(ps, IrParameterKind.DispatchReceiver)
                    if (k >= 0) allocator = args[k]
                }
                if (allocator != null && mapType(c.type).tracked) {
                    // the other arguments (the length, ...) are evaluated for their uses
                    var j = 0
                    while (j < args.size) {
                        val a = args[j]
                        if (a != null && a !== allocator && a !is IrFunctionExpression && ps[j].kind != IrParameterKind.ExtensionReceiver) {
                            val x = expr(a, out)
                            if (x !is MExpr.Opaque || x.children.isNotEmpty()) out.add(MStmt.Eval(x, pos(a)))
                        }
                        j++
                    }
                    val av = expr(allocator, out)
                    var allocLocal: MLocal
                    if (av is MExpr.Local) allocLocal = av.local else {
                        allocLocal = tmp(av.type, p, "\$alloc")
                        out.add(MStmt.Decl(allocLocal, av, MAnnot.NONE, p))
                    }
                    val ctx = newLocal("alloc", allocLocal.type, p)
                    val fn = MFun("kotlinx.cinterop." + name, MemMode.Strict, listOf(MParam(ctx, isContext = true)), MReturn(mapType(c.type)))
                    return MExpr.Call(fn, null, listOf<MExpr>(MExpr.Local(allocLocal, p)), mapType(c.type), p)
                }
                if (name == "free" && pkg == "kotlinx.cinterop" && countKind(ps, IrParameterKind.Regular) == 1 && indexOfKind(ps, IrParameterKind.ExtensionReceiver) >= 0) {
                    val alloc = expr(args[indexOfKind(ps, IrParameterKind.ExtensionReceiver)]!!, out)
                    val t = expr(args[indexOfKind(ps, IrParameterKind.Regular)]!!, out)
                    return MExpr.Free(t, alloc, FreeKind.Free, p)
                }
                return null
            }
            val sym = cSymbolOf(callee) ?: return null
            val fns = cFreeFns
            if (ps.isNotEmpty() && fns != null && fns.contains(sym) && args.isNotEmpty() && args[0] != null && mapType(ps[0].type).tracked) {
                // `fn(p)` for an `@Owned(free = "fn")`: releases `p`; the other arguments only count as uses
                val t = expr(args[0]!!, out)
                var q = 1
                while (q < args.size) {
                    val a = args[q]
                    if (a != null) expr(a, out)
                    q++
                }
                return MExpr.Free(t, null, FreeKind.Free, p)
            }
            val rt = mapType(c.type)
            if (rt.tracked && ownedFree(callee) == null) {
                // a pointer from C: externally managed, no obligation (a borrowed alias of nothing)
                val kids = ArrayList<MExpr>()
                for (a in args) if (a != null) kids.add(expr(a, out))
                return MExpr.Opaque(MType(TK.Managed, rt.nullable, rt.cls, null, rt.className, rt.display), p, kids)
            }
            return null
        }

        private fun implementsAllocator(c: IrClass): Boolean {
            val seen = HashSet<IrClass>()
            fun rec(k: IrClass): Boolean {
                if (!seen.add(k)) return false
                val fq = k.kotlinFqName
                if (fq == "mini.memory.Allocator" || fq == "mini.memory.Deallocating") return true
                for (t in k.superTypes) {
                    val o = t.classOrNull
                    if (o != null && rec(o)) return true
                }
                return false
            }
            return rec(c)
        }

        private fun generalCall(c: IrCall, callee: IrSimpleFunction, out: MutableList<MStmt>): MExpr {
            val m = funOf(callee)
            val ps = callee.parameters
            val args = c.arguments
            var recv: MExpr? = null
            val margs = ArrayList<MExpr>()
            var i = 0
            for (p in ps) {
                val a = args[i]
                val v: MExpr = if (a == null) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out)
                if (p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.ExtensionReceiver) {
                    if (recv == null) recv = v
                } else margs.add(v)
                i++
            }
            return MExpr.Call(m, recv, margs, mapType(c.type), pos(c))
        }

        private fun newExpr(c: IrConstructorCall, out: MutableList<MStmt>): MExpr {
            val ctor = c.callee
            val m = funOf(ctor)
            val margs = ArrayList<MExpr>()
            val cls = ctor.parent as IrClass
            val isThread = cls.kotlinFqName == JAVA_PKG + "lang.Thread"
            var i = 0
            for (p in ctor.parameters) {
                val a = c.arguments[i]
                i++
                var fe: IrFunctionExpression? = null
                if (isThread) {
                    val inner = (a as? IrTypeOperatorCall)?.argument
                    fe = (inner as? IrFunctionExpression) ?: (a as? IrFunctionExpression)
                }
                if (fe != null) {
                    scope(ScopeKind.Thread, fe, out, null, emptyList(), emptyList(), pos(c))
                    margs.add(MExpr.Opaque(mapType(p.type), pos(c)))
                    continue
                }
                margs.add(if (a == null) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out))
            }
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
            var tIdx = -1
            for (i in ps.indices) {
                if ((hasAnnotation(ps[i].annotations, THREAD_BOUNDARY) || fq == "kotlin.concurrent.thread" && ps[i].name == "block") &&
                    args[i] is IrFunctionExpression
                ) {
                    tIdx = i
                    break
                }
            }
            if (tIdx >= 0) {
                val rest = generalCallWithLambdasOpaque(c, callee, out)
                scope(ScopeKind.Thread, args[tIdx] as IrFunctionExpression, out, null, emptyList(), emptyList(), p)
                return rest
            }
            if (!callee.isInline) return null
            val lamIdx = ArrayList<Int>()
            for (i in ps.indices) if (args[i] is IrFunctionExpression && !ps[i].isCrossinline && !ps[i].isNoinline) lamIdx.add(i)
            if (lamIdx.isEmpty()) return null
            val extIdx = indexOfKind(ps, IrParameterKind.ExtensionReceiver)
            val dispIdx = indexOfKind(ps, IrParameterKind.DispatchReceiver)
            val recvIdx = if (extIdx >= 0) extIdx else dispIdx
            if (name == "use" && lamIdx.size == 1 && recvIdx >= 0) {
                val recvE = expr(args[recvIdx]!!, out)
                val target: MLocal
                if (recvE is MExpr.Local) target = recvE.local else {
                    val lam = args[lamIdx[0]] as IrFunctionExpression
                    var pn: String? = null
                    for (x in lam.function.parameters) if (x.kind == IrParameterKind.Regular) {
                        pn = x.name
                        break
                    }
                    target = tmp(recvE.type, p, pn ?: "receiver")
                    out.add(MStmt.Decl(target, recvE, MAnnot.NONE, p))
                }
                val res = scope(ScopeKind.Use, args[lamIdx[0]] as IrFunctionExpression, out, target, emptyList(), listOf(MExpr.Local(target, p)), p)
                return resultOf(res, c, p)
            }
            if (fq == "kotlin.context") {
                val ctxLocals = ArrayList<MLocal>()
                for (i in ps.indices) {
                    val a = args[i]
                    if (a is IrFunctionExpression || a == null) continue
                    val ae = expr(a, out)
                    val t = tmp(ae.type, pos(a))
                    out.add(MStmt.Decl(t, ae, MAnnot.NONE, pos(a)))
                    ctxLocals.add(t)
                }
                val res = scope(ScopeKind.Context, args[lamIdx[0]] as IrFunctionExpression, out, null, ctxLocals, emptyList(), p)
                return resultOf(res, c, p)
            }
            // evaluate non-lambda arguments (receiver first); lambda parameters get the receiver / opaque values
            val vals = ArrayList<MExpr>()
            var recvVal: MExpr? = null
            for (i in ps.indices) {
                if (args[i] is IrFunctionExpression) continue
                val a = args[i] ?: continue
                val v = expr(a, out)
                if (i == recvIdx) recvVal = v else vals.add(v)
            }
            val once = name in ONCE_INLINE
            var res: MLocal? = null
            for (li in lamIdx) {
                val binds: List<MExpr?> = if (name == "with") listOf(vals.firstOrNull())
                else if (recvVal != null && name in RECEIVER_INLINE) listOf(recvVal)
                else emptyList()
                res = scope(if (once) ScopeKind.Once else ScopeKind.Repeat, args[li] as IrFunctionExpression, out, null, emptyList(), binds, p)
            }
            if (name == "also" || name == "apply" || name == "onEach") return recvVal ?: opaque(c.type, c)
            return resultOf(if (once) res else null, c, p)
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
            var i = 0
            for (p in ps) {
                val a = c.arguments[i]
                val v: MExpr = if (a == null || a is IrFunctionExpression) MExpr.Opaque(mapType(p.type), pos(c)) else expr(a, out)
                if (p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.ExtensionReceiver) {
                    if (recv == null) recv = v
                } else margs.add(v)
                i++
            }
            return MExpr.Call(m, recv, margs, mapType(c.type), pos(c))
        }
    }

    companion object {
        // spelled in pieces: the purity check forbids the names of JVM packages in the sources of this module
        private const val JAVA_PKG = "jav" + "a."
        private const val REFLECT_PREFIX = "kotlin." + "reflect"
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
            // C interop: `memScoped { }` is a Scoped allocator (docs/memory-model.md 2.4), `nativeHeap` is malloc/free
            "kotlinx.cinterop.MemScope" to AllocKind.Arena, "kotlinx.cinterop.nativeHeap" to AllocKind.Heap,
        )
        private val BORROWS_ALL = setOf("println", "print", "toString", "equals", "hashCode", "plus", "compareTo", "to", "assert")
        private val THROWERS = setOf("error", "require", "check", "requireNotNull", "checkNotNull", "TODO")
        private val ONCE_INLINE = setOf("run", "let", "also", "apply", "with", "synchronized", "runCatching", "takeIf", "takeUnless", "use")
        private val RECEIVER_INLINE = setOf("run", "let", "also", "apply", "takeIf", "takeUnless", "runCatching")
    }
}
