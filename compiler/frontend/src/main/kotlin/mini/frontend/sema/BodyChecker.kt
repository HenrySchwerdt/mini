package mini.frontend.sema

import mini.frontend.*

/** Where `return` goes: a function body or a lambda. */
class ReturnTarget(val label: String?, val expected: Type?, val fn: FunSym?, val isLambda: Boolean) {
    val returned = ArrayList<Type>()
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
    /** > 0 while checking the branches / operands of an if, when, try, elvis or `!!` */
    var synthDepth = 0
    /** the argument expression being checked: its expected type is a hint only (K2 resolves arguments independently) */
    var argNode: Expr? = null
    var lambdaLabel: String? = null

    // ---- small helpers ---------------------------------------------------------------------------------------------

    fun record(node: Ast, type: Type, callee: Sym?): ExprRec {
        val isNew = rec.get(node) == null
        val r = rec.put(node, if (type is IntLiteralType) ops.literalDefault(type) else type, callee)
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
                if (f != null) sema.checkFun(f)
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

    fun checkFunBody(f: FunSym) {
        val d = f.decl ?: return
        val body = d.body ?: return
        sema.ensureSig(f)
        scope = functionScope(f)
        flow = Flow(HashMap(), false)
        val names = HashSet<String>()
        AstWalk.capturedAssigned(body, names)
        capturedNames = names
        // default values of parameters
        for (p in d.params) {
            val dv = p.default
            if (dv != null) {
                val pt = paramType(f, p.name)
                val t = expr(dv, pt)
                expectCompatible(dv, t, pt)
            }
        }
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
        record(d, v.type ?: ErrType("?"), v)
        declareVar(l, v)
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

    fun delegatedType(delegateType: Type, pos: Int): Type {
        // operator getValue on the delegate
        val r = resolveOperator("getValue", delegateType, listOf(sema.nullableAny, sema.nullableAny), pos, null)
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
