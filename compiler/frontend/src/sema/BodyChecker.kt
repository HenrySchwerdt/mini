package mini.frontend.sema

import mini.frontend.*

/** Where `return` goes: a function body or a lambda. */
class ReturnTarget(val label: String?, val expected: Type?, val fn: FunSym?, val isLambda: Boolean) {
    val returned = ArrayList<Type>()
    /** a lambda whose expected type is a suspend function type */
    var isSuspend = false
    /** a lambda passed for a non-noinline, non-crossinline function parameter of an `inline` function: it is expanded where it is called, so suspension points in it belong to the enclosing function */
    var inlinable = false
    /** the getter of a `suspend val`: a suspend function without a [fn] */
    var suspendAccessor = false
}

/**
 * Checks the bodies of declarations: resolves every expression of a body to a type (and callee), records the result
 * in [Records]. One checker instance works on one declaration (a function, a property initializer, a class' init
 * blocks); nested declarations that need their own checker (implicit return types) get a fresh one.
 * The structure follows K2's body resolve (FirDeclarationsResolveTransformer / FirExpressionsResolveTransformer),
 * with the call resolution simplified (see Calls.kt, Infer.kt).
 */
class BodyChecker(val sema: Sema, val file: FileInfo) {
    val ops: TypeOps = sema.ops
    val members: Members = sema.members
    val rec: Records = sema.records
    var scope: Scope = file.scope
    var flow: Flow = Flow(HashMap(), false)
    val targets = ArrayList<ReturnTarget>()
    var capturedNames: HashSet<String> = HashSet()
    var lambdaDepth = 0
    var pendingLabel: String? = null
    /** the next lambda is analysed only to learn its result type (overloads by lambda result, see byLambdaResult) */
    var probeLambda = false
    /** the next lambda analysed is the argument of an inlinable parameter of an `inline` function (see [ReturnTarget.inlinable]) */
    var inlineLambdaNext = false
    var inlineFunNext = false
    /** > 0 while checking the branches / operands of an if, when, try, elvis or `!!` */
    var synthDepth = 0
    /** the argument expression being checked: its expected type is a hint only (K2 resolves arguments independently) */
    var argNode: Expr? = null
    var lambdaLabel: String? = null

    // ---- small helpers ---------------------------------------------------------------------------------------------

    fun record(node: Ast, type: Type, callee: Sym?): ExprRec {
        if (callee is FunSym && sema.isLazyStubFun(callee)) sema.checkFun(callee)
        val isNew = rec.get(node) == null
        val r = rec.put(node, if (type is IntLiteralType) ops.literalDefault(type) else type, callee)
        if (isNew && callee is FunSym && node is CallExpr && (callee.invokeSuspend || callee.mods()?.has("suspend") == true)) checkSuspendContext(node.start, callee)
        if (isNew) {
            var l = sema.fileNodes[file.index]
            if (l == null) {
                l = ArrayList()
                sema.fileNodes[file.index] = l
            }
            l.add(node)
        }
        return r
    }

    /** a call of a suspend function is only legal in a suspend function or a suspend lambda */
    fun checkSuspendContext(pos: Int, callee: FunSym) {
        var i = targets.size - 1
        while (i >= 0) {
            val t = targets[i]
            if (t.inlinable) {
                i--
                continue
            }
            if (t.isLambda) {
                if (t.isSuspend) return
            } else {
                val f = t.fn
                if (f != null && f.mods()?.has("suspend") == true) return
                if (t.suspendAccessor) return
            }
            break
        }
        val what = if (callee.invokeSuspend) "Suspend function type" else "Suspend function '" + callee.name + "'"
        err(pos, what + " should be called only from a coroutine or another suspend function")
    }

    fun err(pos: Int, msg: String) {
        sema.error(file, pos, msg)
    }

    fun unsupported(pos: Int, what: String): Type {
        sema.unsupported(file, pos, what)
        return ErrType("unsupported $what")
    }

    /** the type of a literal-typed value as it is stored (declared variable, return type, ...) */
    fun fin(t: Type): Type = if (t is IntLiteralType) ops.literalDefault(t) else ops.approximate(t)

    fun checkAnnotations(m: Modifiers?) {
        if (m == null) return
        for (a in m.annotations()) annotationArgs(a)
    }

    fun annotationArgs(a: AnnotationEntry) {
        val args = a.args ?: return
        for (x in args) expr(x.value, null)
    }

    /** records a synthetic node (operator token, argument list) at an explicit range */
    fun recordAt(start: Int, end: Int, type: Type, callee: Sym?, text: String?): ExprRec {
        val n = NameExpr("")
        n.start = start
        n.end = end
        val r = record(n, type, callee)
        r.calleeText = text
        return r
    }

    /** like [recordAt] for a declaration (a pattern `(a, b)`, a subject variable) */
    fun recordDeclAt(start: Int, end: Int, type: Type): ExprRec {
        val n = DestructEntry(null, false, "", null)
        n.start = start
        n.end = end
        return record(n, type, null)
    }

    inline fun <T> withScope(s: Scope, body: () -> T): T {
        val saved = scope
        scope = s
        try {
            return body()
        } finally {
            scope = saved
        }
    }

    fun newLocal(): LocalScope {
        val l = LocalScope(scope)
        scope = l
        return l
    }

    fun declareVar(l: LocalScope, v: VarSym) {
        l.vars[v.name] = v
    }

    // ---- entry points -------------------------------------------------------------------------------------------------

    fun checkFileDecls() {
        for (a in file.ast.fileAnnotations) annotationArgs(a)
        for (d in file.ast.declarations) checkTopLevel(d)
    }

    private fun checkTopLevel(d: Decl) {
        when (d) {
            is ClassDecl -> {
                val c = sema.declSyms[d] as? ClassSym
                if (c != null) sema.checkClass(c)
            }
            is FunDecl -> {
                val f = sema.declSyms[d] as? FunSym
                if (f != null) { if (sema.isLazyStubFun(f)) sema.checkStubFunSignature(f) else sema.checkFun(f) }
            }
            is PropertyDecl -> {
                val p = sema.declSyms[d] as? PropSym
                if (p != null) sema.checkProp(p)
            }
            else -> {}
        }
    }

    // ---- function bodies ---------------------------------------------------------------------------------------------

    /** scope in which the body of [f] is checked: parameters over (receiver over) type parameters over the declaration scope */
    fun functionScope(f: FunSym): LocalScope {
        sema.ensureSig(f)
        var base: Scope = sema.funScope(f)
        val r = f.receiver
        if (r != null) {
            val rs = ReceiverScope(r, f.name, base)
            rs.fn = f
            base = rs
        }
        val l = LocalScope(base)
        val cps = f.decl?.mods?.contextParams
        if (cps != null) {
            for (cp in cps) {
                val t = if (cp.type != null) sema.resolveType(cp.type, base) else ErrType("context parameter type")
                val v = VarSym(cp.name, t, false, true, cp.start)
                l.vars[cp.name] = v
                record(cp, t, v)
            }
        }
        for (p in f.params) l.vars[p.name] = p
        return l
    }

    /** `operator fun matmul` / `matmulAssign` (Mini's `@`) takes exactly one parameter, `matmulAssign` returns Unit */
    private fun checkMatmulOperator(f: FunSym, d: FunDecl) {
        if (f.name != "matmul" && f.name != "matmulAssign") return
        if (!f.isOperator) return
        if (d.params.size != 1) {
            err(d.start, "operator function '" + f.name + "' must have exactly one parameter")
        }
        val rt = f.returnType
        if (f.name == "matmulAssign" && rt != null && !typeEquals(rt, sema.unitType)) {
            err(d.start, "operator function 'matmulAssign' must return Unit")
        }
    }

    /**
     * [part]: 0 = everything, 1 = only what a declaration needs without its body (the default values of the parameters, for a
     * function of a stub whose body is deferred, see [isLazyStubFun]), 2 = only the body (the rest was done by part 1).
     */
    fun checkFunBody(f: FunSym, part: Int = 0) {
        val d = f.decl ?: return
        sema.ensureSig(f)
        if (part != 2) checkMatmulOperator(f, d)
        val bodyOrNull = d.body
        sema.ensureSig(f)
        scope = functionScope(f)
        flow = Flow(HashMap(), false)
        val names = HashSet<String>()
        if (bodyOrNull != null) AstWalk.capturedAssigned(bodyOrNull, names)
        capturedNames = names
        // default values of parameters (also of abstract and interface functions, which have no body)
        if (part != 2) for (p in d.params) {
            val dv = p.default
            if (dv != null) {
                val pt = paramType(f, p.name)
                val t = expr(dv, pt)
                expectCompatible(dv, t, pt)
            }
        }
        if (part == 1) return
        val body = bodyOrNull ?: return
        if (file.declOnly && !StubRules.keepsBody(d)) return
        val declared = if (d.returnType != null || !d.expressionBody) f.returnType else null
        val target = ReturnTarget(null, declared, f, false)
        targets.add(target)
        if (d.expressionBody) {
            val t = expr(body, declared)
            if (declared == null) {
                val all = ArrayList<Type>()
                all.add(fin(t))
                all.addAll(target.returned)
                val rt = if (ops.isNothing(t) && target.returned.isEmpty()) t else ops.commonSuperType(all)
                f.returnType = rt
            } else {
                expectCompatible(body, t, declared)
            }
        } else {
            val bt = blockBody(body, declared)
            if (declared != null) {
                // an implicit `Unit` result needs no check
            }
        }
        targets.removeAt(targets.size - 1)
    }

    fun paramType(f: FunSym, name: String): Type? {
        for (p in f.params) if (p.name == name) return p.varargElement ?: p.type
        return null
    }

    /** checks a block used as a function body, returns the type of its last expression (Unit when none) */
    fun blockBody(b: Expr, expected: Type?): Type {
        if (b is BlockExpr) return blockNode(b, expected, false)
        return expr(b, expected)
    }

    /** checks the statements of a block node and records the block with the type of its last statement */
    fun blockNode(b: BlockExpr, expected: Type?, newScope: Boolean): Type {
        val t = block(b.stmts, expected, newScope)
        record(b, t, null)
        return t
    }

    fun expectCompatible(node: Ast, actual: Type, expected: Type?) {
        if (expected == null) return
        if (actual is ErrType || expected is ErrType) return
        if (!ops.isSubtype(actual, expected)) {
            err(node.start, "type mismatch: expected " + renderType(expected) + " but was " + renderType(actual))
        }
    }

    // ---- blocks and statements -----------------------------------------------------------------------------------------

    /** Checks statements in a new local scope; the value is the type of the last statement when it is an expression. */
    fun block(stmts: List<Stmt>, expected: Type?, newScope: Boolean): Type {
        val saved = scope
        val savedFlow = flow
        if (newScope) newLocal()
        var last: Type = sema.unitType
        var i = 0
        val depth0 = synthDepth
        try {
            for (s in stmts) {
                val isLast = i == stmts.size - 1
                if (!isLast) synthDepth = 0 else synthDepth = depth0
                if (s is Expr) {
                    last = expr(s, if (isLast) expected else null)
                    if (!isLast && ops.isNothing(last)) flow.dead = true
                } else {
                    stmtDecl(s as Decl)
                    last = sema.unitType
                }
                i++
            }
        } finally {
            scope = saved
            synthDepth = depth0
        }
        return last
    }

    fun stmtDecl(d: Decl) {
        when (d) {
            is PropertyDecl -> localProperty(d)
            is DestructuringDecl -> localDestructuring(d)
            is FunDecl -> localFun(d)
            is ClassDecl -> localClass(d)
            is TypeAliasDecl -> unsupported(d.start, "local typealias")
            else -> unsupported(d.start, "declaration in block")
        }
    }

    // ---- local declarations ----------------------------------------------------------------------------------------------

    private fun localScopeOf(): LocalScope {
        val s = scope
        if (s is LocalScope) return s
        return newLocal()
    }

    fun localProperty(d: PropertyDecl) {
        checkAnnotations(d.mods)
        val l = localScopeOf()
        val declared: Type? = if (d.type != null) sema.resolveType(d.type, scope) else null
        var t: Type? = declared
        val init = d.initializer
        val deleg = d.delegate
        if (init != null) {
            val sd = synthDepth
            synthDepth = 0
            val it = expr(init, declared)
            synthDepth = sd
            if (declared == null) {
                // K2 keeps the declared type of the initializer; smart cast information is carried by the new variable
                val orig = rec.get(AstWalk.unparen(init))?.type
                t = if (orig != null && rec.get(AstWalk.unparen(init))?.smart != null) fin(orig) else fin(it)
            } else expectCompatible(init, it, declared)
            if (declared != null && init !is LambdaExpr) {
                // `val x: Any = ...` keeps the declared type; flow knows the initializer's more precise type
            }
        } else if (deleg != null) {
            val dt = expr(deleg, null)
            if (declared == null) t = delegatedType(dt, d.start)
        }
        val v = VarSym(d.name, t ?: ErrType("untyped local"), d.isVar, false, d.start)
        if (d.isVar && capturedNames.contains(d.name)) v.capturedAndModified = true
        val ps = PropSym(d.name, d, null, null, file)
        ps.isLocal = true
        sema.declSyms[d] = v
        val lr = record(d, v.type ?: ErrType("?"), v)
        if (deleg != null) {
            val dtp = rec.get(deleg)?.type
            if (dtp != null) lr.extra = delegateInfo(fin(dtp), d.isVar, v.type ?: sema.nullableAny, d.start)
        }
        declareVar(l, v)
        if (init != null && d.isVar && declared != null && !(v.isVar && v.capturedAndModified)) {
            // `var x: Int? = 5`: after the initialisation x is smart cast to the type of the initializer
            val a = fin(rec.get(AstWalk.unparen(init))?.type ?: sema.nullableAny)
            if (a !is ErrType && !typeEquals(a, declared) && ops.isSubtype(a, declared) && !a.nullable && declared.nullable) flow.add(FactAdd("v" + v.id, v.name, a))
        }
        if (init != null && !d.isVar) {
            val ct = rec.get(init)?.type
            if (ct is ClassType && ct.cls === sema.booleanType.cls) {
                val cf = condFacts(init)
                if (cf.onTrue.isNotEmpty() || cf.onFalse.isNotEmpty()) {
                    v.condTrue = cf.onTrue
                    v.condFalse = cf.onFalse
                }
            }
        }
        if (init != null && !d.isVar && declared == null) {
            val k = stableKey(init)
            if (k != null) {
                val f = flow.facts[k.key]
                if (f != null) for (ty in f.types) flow.add(FactAdd("v" + v.id, v.name, ty))
            }
        }
        // flow: the initializer's type is known when it is more precise
        val g = d.getter
        val s = d.setter
        if (g != null || s != null) unsupported(d.start, "local property accessors")
    }

    /** the argument types of `getValue(thisRef, property)` / `setValue(thisRef, property, value)`: the owner (null: no receiver) and `KProperty<*>` */
    fun delegateArgs(thisRef: Type?): List<Type> {
        val kp = sema.classNamed("kotlin.reflect.KProperty")
        val pt: Type = if (kp != null) ClassType(kp, listOf(TArg(null, Variance.INV)), false) else sema.nullableAny
        return listOf(thisRef ?: sema.nullableNothing, pt)
    }

    /** resolves `getValue` / `setValue` of the delegate type of a property of type [propType] */
    fun delegateInfo(delegateType0: Type, isVar: Boolean, propType: Type, pos: Int, thisRef: Type? = null): DelegateInfo {
        // `provideDelegate` turns the expression after `by` into the delegate
        val pd = resolveOperator("provideDelegate", delegateType0, delegateArgs(thisRef), pos, null)
        val delegateType = if (pd != null && pd.ret !is ErrType) fin(pd.ret) else delegateType0
        val g = resolveOperator("getValue", delegateType, delegateArgs(thisRef), pos, null)
        var s: OpResult? = null
        if (isVar) s = resolveOperator("setValue", delegateType, delegateArgs(thisRef) + listOf(propType), pos, null)
        return DelegateInfo(delegateType, g?.info, s?.info, if (pd != null) pd.info else null)
    }

    fun delegatedType(delegateType0: Type, pos: Int, thisRef: Type? = null): Type {
        val pd = resolveOperator("provideDelegate", delegateType0, delegateArgs(thisRef), pos, null)
        val delegateType = if (pd != null && pd.ret !is ErrType) fin(pd.ret) else delegateType0
        // operator getValue on the delegate
        val r = resolveOperator("getValue", delegateType, delegateArgs(thisRef), pos, null)
        return r?.ret ?: ErrType("delegate getValue")
    }

    fun localDestructuring(d: DestructuringDecl) {
        val l = localScopeOf()
        val init = d.initializer
        val it = if (init != null) fin(expr(init, null)) else ErrType("destructuring without initializer")
        destructure(d.entries, it, l, d.start)
        record(d, it, null)
    }

    fun destructure(entries: List<DestructEntry>, source: Type, l: LocalScope, pos: Int) {
        var i = 1
        for (e in entries) {
            val r = resolveOperator("component$i", source, emptyList(), e.start, null)
            var t: Type = r?.ret ?: ErrType("component$i")
            if (e.type != null) t = sema.resolveType(e.type, scope)
            val v = VarSym(e.name, t, e.isVar, false, e.start)
            record(e, t, v)
            declareVar(l, v)
            if (r != null && r.callee != null) rec.get(e)?.calleeText = null
            rec.get(e)?.call = r?.info
            i++
        }
    }

    fun localFun(d: FunDecl) {
        checkAnnotations(d.mods)
        val l = localScopeOf()
        val f = sema.collector.collectFun(d, null, file, scope)
        f.kind = FunKind.LOCAL
        sema.declSyms[d] = f
        l.addFun(f)
        sema.ensureSig(f)
        // the body is checked right away, with the current flow of captured values
        val sub = BodyChecker(sema, file)
        sub.scope = scope
        sub.flow = flow.copy()
        sub.capturedNames = capturedNames
        sub.checkLocalFunBody(f)
        f.bodyChecked = true
        var i = 0
        for (p in d.params) {
            record(p, f.params[i].type ?: ErrType("param"), f.params[i])
            i++
        }
        record(d, f.returnType ?: ErrType("return"), f)
    }

    fun checkLocalFunBody(f: FunSym) {
        val d = f.decl ?: return
        val saved = scope
        val body = d.body ?: return
        sema.ensureSig(f)
        val outer = scope
        val l = LocalScope(sema.funScope(f).let { fs ->
            // keep the enclosing local scope as the parent chain
            var base: Scope = TypeParamScope(f.typeParams, outer)
            val r = f.receiver
            if (r != null) {
                val rs = ReceiverScope(r, f.name, base)
                rs.fn = f
                base = rs
            }
            base
        })
        for (p in f.params) l.vars[p.name] = p
        scope = l
        for (p in d.params) {
            val dv = p.default
            if (dv != null) {
                val pt = paramType(f, p.name)
                expr(dv, pt)
            }
        }
        val declared = if (d.returnType != null || !d.expressionBody) f.returnType else null
        val target = ReturnTarget(f.name, declared, f, false)
        target.inlinable = inlineFunNext
        inlineFunNext = false
        targets.add(target)
        lambdaDepth++
        if (d.expressionBody) {
            val t = expr(body, declared)
            if (declared == null) {
                val all = ArrayList<Type>()
                all.add(fin(t))
                all.addAll(target.returned)
                f.returnType = ops.commonSuperType(all)
            }
        } else {
            blockBody(body, declared)
        }
        lambdaDepth--
        targets.removeAt(targets.size - 1)
        f.retState = 2
        scope = saved
    }

    fun localClass(d: ClassDecl) {
        val l = localScopeOf()
        val c = sema.collector.collectClass(d, null, file.pkg, file, scope, true)
        l.classes[c.name] = c
        sema.declSyms[d] = c
        sema.checkClass(c)
    }

    // ---- statements shared by expressions ---------------------------------------------------------------------------------

    /** the facts that hold after [e] evaluated to true / false */
    class Cond(val onTrue: ArrayList<FactAdd>, val onFalse: ArrayList<FactAdd>)

    fun noFacts(): Cond = Cond(ArrayList(), ArrayList())
}
