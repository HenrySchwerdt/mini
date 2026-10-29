package mini.frontend.sema

import mini.frontend.*

/*
 * Expression checking: the dispatcher over the AST, literals, strings, operators, control flow and smart-cast
 * conditions. Calls and names are in Calls.kt / Names.kt.
 */

fun BodyChecker.expr(e: Expr, expected: Type?): Type {
    val t = expr0(e, expected)
    if (rec.get(e) == null) record(e, if (t is IntLiteralType) ops.literalDefault(t) else t, null)
    if (ops.isNothing(t)) flow.dead = true
    return t
}

private fun BodyChecker.expr0(e: Expr, expected: Type?): Type {
    when (e) {
        is LiteralExpr -> return literal(e, expected)
        is StringExpr -> return stringExpr(e)
        is NameExpr -> return nameExpr(e, expected)
        is ThisExpr -> return thisExpr(e)
        is SuperExpr -> return superExpr(e)
        is ParenExpr -> {
            val t = expr(e.inner, expected)
            val r = rec.get(e.inner)
            if (r != null) {
                val pr = record(e, r.type, r.callee)
                pr.smart = r.smart
                pr.calleeText = r.calleeText
            }
            return t
        }
        is BinaryExpr -> return binary(e, expected)
        is IsExpr -> {
            val t = expr(e.expr, null)
            val target = sema.resolveType(e.type, scope)
            val r = record(e, sema.booleanType, null)
            r.target = target
            return sema.booleanType
        }
        is AsExpr -> {
            expr(e.expr, null)
            val target = sema.resolveType(e.type, scope)
            val t = if (e.safe) target.withNullable(true) else target
            val r = record(e, t, null)
            r.target = target
            return t
        }
        is PrefixExpr -> return prefix(e, expected)
        is PostfixExpr -> return postfix(e, expected)
        is AnnotatedExpr -> {
            for (a in e.annotations) annotationArgs(a)
            return expr(e.expr, expected)
        }
        is LabeledExpr -> return labeled(e, expected)
        is CallExpr -> return callExpr(e, null, expected)
        is IndexExpr -> return indexExpr(e)
        is DotExpr -> return dotExpr(e, expected)
        is CallableRefExpr -> return callableRef(e, expected)
        is LambdaExpr -> return lambdaStandalone(e, expected)
        is AnonFunExpr -> return anonFun(e, expected)
        is BlockExpr -> return block(e.stmts, expected, true)
        is IfExpr -> return ifExpr(e, expected)
        is WhenExpr -> return whenExpr(e, expected)
        is TryExpr -> return tryExpr(e, expected)
        is ThrowExpr -> {
            val x = e.expr
            if (x != null) expr(x, sema.classNamed("kotlin.Throwable")?.let { sema.cls0(it) })
            return sema.nothingType
        }
        is ReturnExpr -> return returnExpr(e)
        is BreakExpr -> return sema.nothingType
        is ContinueExpr -> return sema.nothingType
        is ForExpr -> return forExpr(e)
        is WhileExpr -> return whileExpr(e)
        is DoWhileExpr -> return doWhile(e)
        is ObjectLiteral -> return objectLiteral(e, expected)
        is CollectionLiteral -> {
            for (x in e.elements) expr(x, null)
            return ErrType("collection literal")
        }
        is ErrorExpr -> return ErrType("syntax error")
    }
}

// ---- literals -------------------------------------------------------------------------------------------------------

fun parseIntLiteral(text0: String): Long? {
    var text = text0.replace("_", "")
    var radix = 10
    if (text.startsWith("0x") || text.startsWith("0X")) {
        radix = 16
        text = text.substring(2)
    } else if (text.startsWith("0b") || text.startsWith("0B")) {
        radix = 2
        text = text.substring(2)
    }
    if (text.isEmpty()) return null
    var v = 0L
    for (ch in text) {
        val d = when {
            ch >= '0' && ch <= '9' -> ch.code - '0'.code
            ch >= 'a' && ch <= 'f' -> ch.code - 'a'.code + 10
            ch >= 'A' && ch <= 'F' -> ch.code - 'A'.code + 10
            else -> return null
        }
        if (d >= radix) return null
        v = v * radix + d
    }
    return v
}

fun BodyChecker.literal(e: LiteralExpr, expected: Type?): Type {
    when (e.kind) {
        LitKind.TRUE, LitKind.FALSE -> return sema.booleanType
        LitKind.NULL -> return sema.nullableNothing
        LitKind.CHAR -> return sema.charType
        LitKind.REAL -> {
            val last = e.text[e.text.length - 1]
            if (last == 'f' || last == 'F') return sema.classTypeOf("kotlin.Float")
            return sema.doubleType
        }
        LitKind.INT -> {
            var text = e.text
            var isLong = false
            var unsigned = false
            if (text.endsWith("L")) {
                isLong = true
                text = text.substring(0, text.length - 1)
            }
            if (text.endsWith("u") || text.endsWith("U")) {
                unsigned = true
                text = text.substring(0, text.length - 1)
                if (text.endsWith("L")) text = text.substring(0, text.length - 1)
            }
            if (unsigned) return unsupported(e.start, "unsigned literal")
            val v = parseIntLiteral(text)
            if (v == null) return sema.longType
            if (isLong) return sema.longType
            val lit = IntLiteralType(v, v <= 2147483647L)
            if (expected != null && expected is ClassType) {
                val n = expected.cls.fqName()
                if ((n == "kotlin.Long" || n == "kotlin.Short" || n == "kotlin.Byte") && ops.isSubtype(lit, expected.withNullable(false))) {
                    record(e, expected.withNullable(false), null)
                    return expected.withNullable(false)
                }
            }
            record(e, ops.literalDefault(lit), null)
            return lit
        }
    }
}

fun BodyChecker.stringExpr(e: StringExpr): Type {
    var template = false
    for (p in e.parts) if (p is StrShortRef || p is StrLongExpr) template = true
    val str = sema.stringType
    if (!template) {
        record(e, str, null)
        return str
    }
    var runStart = -1
    var runEnd = -1
    for (p in e.parts) {
        when (p) {
            is StrText, is StrEscape -> {
                flushRun(e, p.start, p.end)
            }
            is StrShortRef -> {
                flushRun(e, runStart, runEnd)
                runStart = -1
                if (p.name == "this") {
                    val n = ThisExpr(null)
                    n.start = p.start + 1
                    n.end = p.end
                    expr(n, null)
                } else {
                    val n = NameExpr(p.name)
                    n.start = p.start + 1
                    n.end = p.end
                    expr(n, null)
                }
            }
            is StrLongExpr -> {
                flushRun(e, runStart, runEnd)
                runStart = -1
                expr(p.expr, null)
            }
        }
    }
    flushRun(e, runStart, runEnd)
    record(e, str, null)
    return str
}

private fun BodyChecker.flushRun(e: StringExpr, s: Int, en: Int) {
    if (s < 0) return
    val n = LiteralExpr(LitKind.NULL, "")
    n.start = s
    n.end = en
    record(n, sema.stringType, null)
    sema.extraNodes.add(n)
}

fun BodyChecker.labeled(e: LabeledExpr, expected: Type?): Type {
    val inner = e.expr
    if (inner is LambdaExpr) {
        pendingLabel = e.label
        val t = expr(inner, expected)
        pendingLabel = null
        record(e, t, null)
        return t
    }
    val saved = pendingLabel
    pendingLabel = e.label
    val t = expr(inner, expected)
    pendingLabel = saved
    return t
}

// ---- this / super -----------------------------------------------------------------------------------------------------

class ImplRecv(val type: Type, val label: String?, val cls: ClassSym?, val isCompanion: Boolean, val fn: FunSym?)

/** the type of an implicit receiver with the smart casts known for `this` (innermost receiver) / `this@label` */
fun BodyChecker.narrowRecv(t: Type, label: String?, innermost: Boolean): Type {
    var types: List<Type>? = null
    if (innermost) types = flow.facts["this"]?.types
    if (label != null) {
        val f = flow.facts["this@$label"]
        if (f != null) {
            val l = ArrayList<Type>()
            if (types != null) l.addAll(types)
            l.addAll(f.types)
            types = l
        }
    }
    if (types == null || types.isEmpty()) return t
    return ops.narrow(t, types)
}

/** the implicit receivers visible from the current scope, innermost first */
fun BodyChecker.implicitReceivers(narrow: Boolean = true): List<ImplRecv> {
    val out = ArrayList<ImplRecv>()
    var s: Scope? = scope
    var first = true
    while (s != null) {
        when (s) {
            is ClassScope -> {
                if (s.instance) {
                    out.add(ImplRecv(if (narrow) narrowRecv(s.cls.thisType(), s.cls.name, first) else s.cls.thisType(), s.cls.name, s.cls, false, null))
                    first = false
                }
                val comp = s.cls.companion
                if (comp != null) out.add(ImplRecv(comp.thisType(), comp.name, comp, true, null))
                if (s.cls.isObject && !s.instance) {
                    // an object enclosing a nested class: its members are reachable through the object
                    out.add(ImplRecv(s.cls.thisType(), s.cls.name, s.cls, false, null))
                }
            }
            is ReceiverScope -> {
                out.add(ImplRecv(if (narrow) narrowRecv(s.type, s.label, first) else s.type, s.label, null, false, s.fn))
                first = false
            }
            else -> {}
        }
        s = s.parent
    }
    return out
}

fun BodyChecker.thisExpr(e: ThisExpr): Type {
    val rs = implicitReceivers(false)
    val lbl = e.label
    for (r in rs) {
        if (r.isCompanion) continue
        if (lbl == null || r.label == lbl) {
            // a lambda receiver / extension receiver / class
            val key = if (lbl == null) "this" else "this@$lbl"
            val rr = record(e, r.type, null)
            rr.thisKey = key
            val f = flow.facts[key]
            if (f != null) {
                val n = ops.narrow(r.type, f.types)
                if (n !== r.type) {
                    rr.smart = n
                    return n
                }
            }
            return r.type
        }
    }
    err(e.start, "unresolved this")
    return ErrType("this")
}

fun BodyChecker.superExpr(e: SuperExpr): Type {
    val rs = implicitReceivers()
    for (r in rs) {
        val c = r.cls ?: continue
        if (r.isCompanion) continue
        val ta = e.typeArg
        if (ta != null) return sema.resolveType(ta, scope)
        sema.ensureSupertypes(c)
        val sc = sema.superclassOf(c)
        if (sc != null) return sc
        for (st in c.supertypes) if (st is ClassType) return st
        return sema.anyType
    }
    return ErrType("super")
}

// ---- operators ------------------------------------------------------------------------------------------------------------

fun operatorName(op: String): String? = when (op) {
    "+" -> "plus"
    "-" -> "minus"
    "*" -> "times"
    "/" -> "div"
    "%" -> "rem"
    else -> null
}

fun BodyChecker.binary(e: BinaryExpr, expected: Type?): Type {
    val op = e.op
    when (op) {
        "=" -> return assign(e)
        "+=", "-=", "*=", "/=", "%=" -> return compoundAssign(e)
        "&&", "||" -> return andOr(e)
        "==", "!=", "===", "!==" -> {
            val l = expr(e.left, null)
            val r = expr(e.right, if (l is IntLiteralType) null else fin(l))
            record(e, sema.booleanType, null)
            return sema.booleanType
        }
        "<", ">", "<=", ">=" -> {
            val l = fin(expr(e.left, null))
            val r = expr(e.right, null)
            resolveOperator("compareTo", l, listOf(r), e.start, null)
            record(e, sema.booleanType, null)
            return sema.booleanType
        }
        "in", "!in" -> {
            val l = expr(e.left, null)
            val r = fin(expr(e.right, null))
            val res = resolveOperator("contains", r, listOf(l), e.start, null)
            val rr = record(e, sema.booleanType, res?.callee)
            if (res != null) rr.calleeText = res.calleeText
            return sema.booleanType
        }
        ".." , "..<" -> {
            val l = fin(expr(e.left, null))
            val r = expr(e.right, null)
            val res = resolveOperator(if (op == "..") "rangeTo" else "rangeUntil", l, listOf(r), e.start, null)
            if (res == null) return ErrType("rangeTo")
            val rr = record(e, res.ret, res.callee)
            rr.calleeText = res.calleeText
            return res.ret
        }
        "?:" -> return elvis(e, expected)
    }
    val on = operatorName(op)
    if (on != null) {
        val l = fin(expr(e.left, null))
        val r = expr(e.right, null)
        val res = resolveOperator(on, l, listOf(r), e.start, null)
        if (res == null) {
            err(e.start, "no operator " + on + " for " + renderType(l))
            return ErrType("operator $on")
        }
        completeLiteralArg(e.right, res.paramTypes, 0)
        val rr = record(e, res.ret, res.callee)
        rr.calleeText = res.calleeText
        return res.ret
    }
    // infix function call: a name f b  == a.f(b)
    return infixCall(e, expected)
}

fun BodyChecker.completeLiteralArg(arg: Expr, paramTypes: List<Type?>, index: Int) {
    if (index >= paramTypes.size) return
    val pt = paramTypes[index] ?: return
    val r = rec.get(arg) ?: return
    val at = r.type
    // a literal whose type was open takes the parameter type when integral
    if (arg is LiteralExpr && arg.kind == LitKind.INT && pt is ClassType) {
        val n = pt.cls.fqName()
        if (n == "kotlin.Long" || n == "kotlin.Short" || n == "kotlin.Byte" || n == "kotlin.Int") r.type = pt.withNullable(false)
    }
}

fun BodyChecker.andOr(e: BinaryExpr): Type {
    expr(e.left, sema.booleanType)
    val c = condFacts(e.left)
    val saved = flow
    flow = flow.copy()
    flow.addAll(if (e.op == "&&") c.onTrue else c.onFalse)
    expr(e.right, sema.booleanType)
    flow = saved
    record(e, sema.booleanType, null)
    return sema.booleanType
}

fun BodyChecker.elvis(e: BinaryExpr, expected: Type?): Type {
    val top = synthDepth == 0
    synthDepth++
    try {
        return elvisBody(e, expected, top)
    } finally {
        synthDepth--
    }
}

private fun BodyChecker.elvisBody(e: BinaryExpr, expected: Type?, top: Boolean): Type {
    val l = fin(expr(e.left, null))
    val saved = flow
    flow = flow.copy()
    // right side runs when the left is null
    val r0 = expr(e.right, expected)
    val rflow = flow
    flow = saved
    // after the elvis, the left side is known non-null unless the right side may complete normally
    var res: Type
    val lnn = if (ops.mayBeNull(l)) ops.makeNotNull(l) else l
    var r = r0
    if (containsError(r) && !containsError(lnn) && expected == null && argNode == null) {
        val sv = flow
        flow = saved.copy()
        r = expr(e.right, lnn)
        flow = sv
    }
    if (ops.isNothing(r)) {
        res = lnn
    } else {
        val list = ArrayList<Type>()
        list.add(lnn)
        list.add(fin(r))
        res = ops.commonSuperType(list)
    }
    if (ops.isNothing(r)) {
        // `x ?: return`: x is not null afterwards
        val k = stableKey(e.left)
        if (k != null) flow.add(FactAdd(k.key, k.root, sema.anyType))
    }
    res = preferExpected(res, expected, e, top, 1)
    record(e, res, null)
    return res
}

fun BodyChecker.infixCall(e: BinaryExpr, expected: Type?): Type {
    val l = fin(expr(e.left, null))
    val args = ArrayList<Arg>()
    val a = Arg(e.right, null, false)
    args.add(a)
    val groups = memberAndExtensionGroups(l, e.op, false)
    val res = resolveCallGroups(e, groups, args, null, expected, false)
    if (res == null) {
        err(e.start, "unresolved infix function " + e.op)
        return ErrType("infix " + e.op)
    }
    val rr = record(e, res.ret, res.callee)
    rr.calleeText = res.calleeText
    return res.ret
}

fun BodyChecker.prefix(e: PrefixExpr, expected: Type?): Type {
    when (e.op) {
        "!" -> {
            val t = fin(expr(e.expr, sema.booleanType))
            val res = resolveOperator("not", t, emptyList(), e.start, null)
            val rr = record(e, sema.booleanType, res?.callee)
            if (res != null) rr.calleeText = res.calleeText
            return sema.booleanType
        }
        "-", "+" -> {
            val operand = e.expr
            if (e.op == "-" && operand is LiteralExpr && operand.kind == LitKind.INT && !operand.text.endsWith("L") && !operand.text.endsWith("u") && !operand.text.endsWith("U")) {
                val lt = literal(operand, null)
                if (lt is IntLiteralType) {
                    val v = -lt.value
                    val nl = IntLiteralType(v, v >= -2147483648L && v <= 2147483647L)
                    if (expected != null && expected is ClassType) {
                        val n = expected.cls.fqName()
                        if ((n == "kotlin.Long" || n == "kotlin.Short" || n == "kotlin.Byte") && ops.isSubtype(nl, expected.withNullable(false))) {
                            record(e, expected.withNullable(false), null)
                            return expected.withNullable(false)
                        }
                    }
                    record(e, ops.literalDefault(nl), null)
                    return nl
                }
                record(e, lt, null)
                return lt
            }
            val t = expr(e.expr, expected)
            val name = if (e.op == "-") "unaryMinus" else "unaryPlus"
            val res = resolveOperator(name, fin(t), emptyList(), e.start, null)
            if (res == null) return ErrType(name)
            var rt = res.ret
            if (t is IntLiteralType) {
                val v = if (e.op == "-") -t.value else t.value
                val lt = IntLiteralType(v, v >= -2147483648L && v <= 2147483647L)
                val rr = record(e, ops.literalDefault(lt), res.callee)
                rr.calleeText = res.calleeText
                if (expected != null && expected is ClassType && (expected.cls.fqName() == "kotlin.Long")) {
                    rr.type = expected.withNullable(false)
                    return expected.withNullable(false)
                }
                return lt
            }
            val rr = record(e, rt, res.callee)
            rr.calleeText = res.calleeText
            return rt
        }
        "++", "--" -> {
            val t = fin(expr(e.expr, null))
            val name = if (e.op == "++") "inc" else "dec"
            val res = resolveOperator(name, t, emptyList(), e.start, null)
            if (res != null) recordAt(e.start, e.start + 2, res.ret, res.callee, res.calleeText)
            invalidate(e.expr)
            return t
        }
    }
    return unsupported(e.start, "prefix " + e.op)
}

fun BodyChecker.postfix(e: PostfixExpr, expected: Type?): Type {
    if (e.op == "!!") {
        val top = synthDepth == 0
        synthDepth++
        val t = fin(expr(e.expr, null))
        synthDepth--
        val nn = if (ops.mayBeNull(t)) ops.makeNotNull(t) else t
        val k = stableKey(e.expr)
        if (k != null) flow.add(FactAdd(k.key, k.root, sema.anyType))
        val pe = preferExpected(nn, expected, e, top, 2)
        record(e, pe, null)
        return pe
    }
    val t = fin(expr(e.expr, null))
    val name = if (e.op == "++") "inc" else "dec"
    val res = resolveOperator(name, t, emptyList(), e.start, null)
    if (res != null) recordAt(e.end - 2, e.end, res.ret, res.callee, res.calleeText)
    invalidate(e.expr)
    record(e, t, null)
    return t
}

/** `x` was assigned: forget what is known about it */
fun BodyChecker.invalidate(target: Expr) {
    val k = stableKeyAny(target)
    if (k != null) flow.killRoot(k.root)
    val x = AstWalk.unparen(target)
    if (x is NameExpr) flow.killRoot(x.name)
}

fun BodyChecker.assign(e: BinaryExpr): Type {
    val l = AstWalk.unparen(e.left)
    if (l is IndexExpr) {
        val recvT = fin(expr(l.receiver, null))
        val idx = ArrayList<Type>()
        for (i in l.indices) idx.add(expr(i, null))
        val vt = expr(e.right, null)
        val all = ArrayList<Type>()
        all.addAll(idx)
        all.add(vt)
        val res = resolveOperator("set", recvT, all, e.start, null)
        val rr = record(e, res?.ret ?: sema.unitType, res?.callee)
        if (res != null) rr.calleeText = res.calleeText
        return sema.unitType
    }
    val lt = fin(expr(l, null))
    var declared = rec.get(l)?.type ?: lt
    // the declared type of a variable, without smart casts
    val rt = expr(e.right, declared)
    invalidate(l)
    // after an assignment the variable has the type of the assigned value (smart cast to it)
    val k = stableKey(l)
    if (k != null && declared !is ErrType) {
        val a = fin(rt)
        if (!typeEquals(a, declared) && ops.isSubtype(a, declared) && l is NameExpr) {
            flow.add(FactAdd(k.key, k.root, a))
        }
    }
    record(e, sema.unitType, null)
    return sema.unitType
}

fun BodyChecker.compoundAssign(e: BinaryExpr): Type {
    val l = AstWalk.unparen(e.left)
    val lt = fin(expr(l, null))
    val rt = expr(e.right, null)
    val base = operatorName(e.op.substring(0, 1)) ?: "plus"
    val assignName = base + "Assign"
    var res = resolveOperator(assignName, lt, listOf(rt), e.start, null)
    if (res == null) res = resolveOperator(base, lt, listOf(rt), e.start, null)
    if (res != null) {
        val rr = record(e, sema.unitType, res.callee)
        rr.calleeText = res.calleeText
    }
    invalidate(l)
    return sema.unitType
}

fun BodyChecker.indexExpr(e: IndexExpr): Type {
    val recvT = fin(expr(e.receiver, null))
    val idx = ArrayList<Type>()
    for (i in e.indices) idx.add(expr(i, null))
    val res = resolveOperator("get", recvT, idx, e.start, null)
    if (res == null) {
        err(e.start, "no get operator for " + renderType(recvT))
        return ErrType("get")
    }
    val rr = record(e, res.ret, res.callee)
    rr.calleeText = res.calleeText
    return res.ret
}

// ---- smart cast conditions --------------------------------------------------------------------------------------------------

class StableRef(val key: String, val root: String)

fun BodyChecker.stableKey(e0: Expr): StableRef? {
    val k = stableKeyAny(e0)
    return k
}

fun BodyChecker.isStableProp(p: PropSym): Boolean {
    if (p.isVar) return false
    if (p.hasCustomGetter || p.isDelegated) return false
    if (p.has("open") || p.has("abstract")) return false
    if (p.has("override") && p.owner != null && (p.owner.has("open") || p.owner.has("abstract") || p.owner.isInterface)) {
        if (!p.has("final")) return false
    }
    return true
}

fun BodyChecker.stableKeyAny(e0: Expr): StableRef? {
    val e = AstWalk.unparen(e0)
    when (e) {
        is NameExpr -> {
            val r = rec.get(e) ?: return null
            val c = r.callee
            if (c is VarSym) {
                if (c.isVar && c.capturedAndModified) return null
                return StableRef("v" + c.id, c.name)
            }
            if (c is PropSym) {
                if (c.isLocal) return null
                if (!isStableProp(c)) return null
                return StableRef("p" + c.id, c.name)
            }
            return null
        }
        is ThisExpr -> {
            val r = rec.get(e) ?: return null
            val k = r.thisKey ?: return null
            return StableRef(k, "this")
        }
        is DotExpr -> {
            val sel = e.selector
            if (sel !is NameExpr) return null
            val r = rec.get(e) ?: return null
            val c = r.callee
            if (c !is PropSym || !isStableProp(c)) return null
            val base = stableKeyAny(e.receiver) ?: return null
            return StableRef(base.key + ".p" + c.id, base.root)
        }
        else -> return null
    }
}

fun BodyChecker.condFacts(e0: Expr): BodyChecker.Cond {
    val e = AstWalk.unparen(e0)
    when (e) {
        is PrefixExpr -> {
            if (e.op == "!") {
                val c = condFacts(e.expr)
                return BodyChecker.Cond(c.onFalse, c.onTrue)
            }
        }
        is IsExpr -> {
            val k = stableKey(e.expr) ?: return noFacts()
            val target = rec.get(e)?.target ?: return noFacts()
            val f = FactAdd(k.key, k.root, target)
            val c = noFacts()
            if (e.negated) c.onFalse.add(f) else c.onTrue.add(f)
            return c
        }
        is BinaryExpr -> {
            if (e.op == "&&") {
                val a = condFacts(e.left)
                val b = condFacts(e.right)
                val t = ArrayList<FactAdd>()
                t.addAll(a.onTrue)
                t.addAll(b.onTrue)
                val fb = ArrayList<FactAdd>()
                fb.addAll(a.onTrue)
                fb.addAll(b.onFalse)
                return BodyChecker.Cond(t, FlowOps.commonAdds(ops, a.onFalse, fb))
            }
            if (e.op == "||") {
                val a = condFacts(e.left)
                val b = condFacts(e.right)
                val f = ArrayList<FactAdd>()
                f.addAll(a.onFalse)
                f.addAll(b.onFalse)
                val tb = ArrayList<FactAdd>()
                tb.addAll(a.onFalse)
                tb.addAll(b.onTrue)
                return BodyChecker.Cond(FlowOps.commonAdds(ops, a.onTrue, tb), f)
            }
            if (e.op == "==" || e.op == "!=" || e.op == "===" || e.op == "!==") {
                val neg = e.op == "!=" || e.op == "!=="
                val l = AstWalk.unparen(e.left)
                val r = AstWalk.unparen(e.right)
                val subject: Expr? = if (isNullLiteral(r)) l else if (isNullLiteral(l)) r else null
                if (subject != null) {
                    val c = noFacts()
                    val adds = nonNullFacts(subject)
                    if (neg) c.onTrue.addAll(adds) else c.onFalse.addAll(adds)
                    return c
                }
                // x == <non-null expression>: x is non-null when equal
                val c = noFacts()
                if (!neg) {
                    val rt = rec.get(r)?.type
                    if (rt != null && !ops.mayBeNull(rt)) c.onTrue.addAll(nonNullFacts(l))
                    val lt = rec.get(l)?.type
                    if (lt != null && !ops.mayBeNull(lt)) c.onTrue.addAll(nonNullFacts(r))
                }
                return c
            }
        }
        else -> {}
    }
    return noFacts()
}

fun isNullLiteral(e: Expr): Boolean = e is LiteralExpr && e.kind == LitKind.NULL

/** facts that hold when [e] is not null: e itself, and the receiver chain of a safe call */
fun BodyChecker.nonNullFacts(e0: Expr): ArrayList<FactAdd> {
    val out = ArrayList<FactAdd>()
    val e = AstWalk.unparen(e0)
    val k = stableKey(e)
    if (k != null) out.add(FactAdd(k.key, k.root, sema.anyType))
    if (e is DotExpr && e.safe) {
        out.addAll(nonNullFacts(e.receiver))
    }
    return out
}

// ---- control flow ---------------------------------------------------------------------------------------------------------------

fun BodyChecker.ifExpr(e: IfExpr, expected: Type?): Type {
    val top = synthDepth == 0
    expr(e.cond, sema.booleanType)
    synthDepth++
    try {
        return ifBody(e, expected, top)
    } finally {
        synthDepth--
    }
}

private fun BodyChecker.ifBody(e: IfExpr, expected: Type?, top: Boolean): Type {
    val c = condFacts(e.cond)
    val base = flow
    val thenE = e.then
    val elseE = e.otherwise
    flow = base.copy()
    flow.addAll(c.onTrue)
    var tt: Type = sema.unitType
    if (thenE != null) tt = branchValue(thenE, expected)
    val thenFlow = flow
    flow = base.copy()
    flow.addAll(c.onFalse)
    var et: Type? = null
    if (elseE != null) et = branchValue(elseE, expected)
    val elseFlow = flow
    if (elseE != null && thenE != null && expected == null && et != null && argNode == null) {
        // a branch whose type could not be inferred on its own (`emptyList()`) takes the other branch' type
        val te = containsError(tt)
        val ee = containsError(et)
        if (te != ee) {
            val hint = if (te) et else tt
            if (te) tt = recheck(thenE, hint, base, c.onTrue) else et = recheck(elseE, hint, base, c.onFalse)
        }
    }
    flow = FlowOps.merge(ops, thenFlow, elseFlow)
    if (elseE == null) {
        return sema.unitType
    }
    return preferExpected(joinBranches(listOf(tt, et!!)), expected, e, top)
}

fun BodyChecker.recheck(b: Expr, hint: Type, base: Flow, facts: List<FactAdd>): Type {
    val sv = flow
    flow = base.copy()
    flow.addAll(facts)
    val t = branchValue(b, hint)
    flow = sv
    return t
}

fun BodyChecker.branchValue(b: Expr, expected: Type?): Type {
    if (b is BlockExpr) return block(b.stmts, expected, true)
    return expr(b, expected)
}

/**
 * `if`, `when`, `try`, `?:` and `!!` are resolved by K2 as calls of a synthetic generic function; with a proper expected
 * type (other than `Any`) their result has exactly that type when all branches fit.
 */
fun BodyChecker.preferExpected(res: Type, expected: Type?, node: Ast, top: Boolean, kind: Int = 0): Type {
    if (node === argNode || !top) return res
    if (expected == null || res is ErrType || expected is ErrType) return res
    if (expected is ClassType && expected.cls === sema.anyType.cls) return res
    if (expected is VarType || containsVar(expected)) return res
    if (ops.isNothing(res)) return res
    if (res is IntLiteralType) return res
    if (res is TypeParamType && res.definitelyNotNull) return res
    if (!ops.isSubtype(res, expected)) return res
    // `!!` with a nullable expected type keeps its own type; `?:` yields the non-null part of the expected type
    if (kind == 2 && expected.nullable) return res
    if (kind == 1 && !res.nullable) return ops.makeNotNull(expected)
    return expected
}

fun containsVar(t: Type): Boolean {
    when (t) {
        is VarType -> return true
        is ClassType -> {
            for (a in t.args) {
                val x = a.type
                if (x != null && containsVar(x)) return true
            }
            return false
        }
        is FunType -> {
            val r = t.receiver
            if (r != null && containsVar(r)) return true
            for (p in t.params) if (containsVar(p)) return true
            return containsVar(t.ret)
        }
        else -> return false
    }
}

fun BodyChecker.joinBranches(types: List<Type>): Type {
    val ts = ArrayList<Type>()
    for (t in types) ts.add(fin(t))
    return ops.commonSuperType(ts)
}

fun BodyChecker.tryExpr(e: TryExpr, expected: Type?): Type {
    val top = synthDepth == 0
    synthDepth++
    try {
        return tryBody(e, expected, top)
    } finally {
        synthDepth--
    }
}

private fun BodyChecker.tryBody(e: TryExpr, expected: Type?, top: Boolean): Type {
    val before = flow
    val assigned = HashSet<String>()
    AstWalk.assignedNames(e.body, assigned)
    flow = before.copy()
    val types = ArrayList<Type>()
    types.add(block(e.body.stmts, expected, true))
    val bodyFlow = flow
    var merged = bodyFlow
    for (c in e.catches) {
        flow = before.copy()
        for (n in assigned) flow.killRoot(n)
        val saved = scope
        val l = newLocal()
        val pt = if (c.param.type != null) sema.resolveType(c.param.type, scope) else ErrType("catch")
        val v = VarSym(c.param.name, pt, false, false, c.param.start)
        declareVar(l, v)
        record(c.param, pt, v)
        types.add(block(c.body.stmts, expected, true))
        scope = saved
        merged = FlowOps.merge(ops, merged, flow)
    }
    flow = merged
    val fin = e.finallyBlock
    if (fin != null) {
        for (n in assigned) flow.killRoot(n)
        block(fin.stmts, null, true)
    }
    return preferExpected(joinBranches(types), expected, e, top)
}

fun BodyChecker.returnExpr(e: ReturnExpr): Type {
    var target: ReturnTarget? = null
    val lbl = e.label
    var i = targets.size - 1
    while (i >= 0) {
        val t = targets[i]
        if (lbl == null) {
            if (!t.isLambda || t.label == null || true) {
                // an unlabeled return leaves the nearest function (not a lambda)
                if (!t.isLambda) {
                    target = t
                    break
                }
            }
        } else if (t.label == lbl) {
            target = t
            break
        }
        i--
    }
    val x = e.expr
    if (x != null) {
        val t = expr(x, target?.expected)
        if (target != null) target.returned.add(fin(t))
    } else {
        target?.returned?.add(sema.unitType)
    }
    return sema.nothingType
}

fun BodyChecker.loopKill(body: Ast, cond: Ast?) {
    val names = HashSet<String>()
    AstWalk.assignedNames(body, names)
    if (cond != null) AstWalk.assignedNames(cond, names)
    for (n in names) flow.killRoot(n)
}

fun BodyChecker.whileExpr(e: WhileExpr): Type {
    val body = e.body
    if (body != null) loopKill(body, e.cond) else loopKill(e.cond, null)
    expr(e.cond, sema.booleanType)
    val c = condFacts(e.cond)
    val base = flow
    flow = base.copy()
    flow.addAll(c.onTrue)
    if (body != null) branchValue(body, null)
    flow = base
    return sema.unitType
}

fun BodyChecker.doWhile(e: DoWhileExpr): Type {
    val body = e.body
    if (body != null) loopKill(body, e.cond)
    val base = flow
    flow = base.copy()
    if (body != null) branchValue(body, null)
    expr(e.cond, sema.booleanType)
    flow = base
    loopKill(e.cond, null)
    if (body != null) loopKill(body, null)
    return sema.unitType
}

fun BodyChecker.forExpr(e: ForExpr): Type {
    val it = fin(expr(e.iterable, null))
    val iterRes = resolveOperator("iterator", it, emptyList(), e.iterable.start, null)
    var elem: Type = ErrType("iterator")
    if (iterRes != null) {
        val iterT = iterRes.ret
        resolveOperator("hasNext", iterT, emptyList(), e.iterable.start, null)
        val nx = resolveOperator("next", iterT, emptyList(), e.iterable.start, null)
        if (nx != null) elem = nx.ret
    } else {
        err(e.iterable.start, "for-loop range must have an iterator() method")
    }
    val body = e.body
    if (body != null) loopKill(body, null)
    val saved = scope
    val savedFlow = flow
    val l = newLocal()
    if (e.destructuring) {
        destructure(e.entries, elem, l, e.start)
        // K2 reports the whole `(a, b)` pattern with the element type
        if (e.entries.isNotEmpty()) {
            var s = e.entries[0].start
            while (s > 0 && file.text[s] != '(' && file.text[s] != '[') s--
            var en = e.entries[e.entries.size - 1].end
            while (en < file.text.length && file.text[en] != ')' && file.text[en] != ']') en++
            recordDeclAt(s, en + 1, elem)
        }
    } else {
        for (en in e.entries) {
            var t = elem
            if (en.type != null) t = sema.resolveType(en.type, scope)
            var vs = en.start
            while (vs < file.text.length && (file.text[vs] == '(' || file.text[vs].isWhitespace())) vs++
            val v = VarSym(en.name, fin(t), false, false, vs)
            val rr = record(en, fin(t), v)
            declareVar(l, v)
        }
    }
    flow = savedFlow.copy()
    if (body != null) branchValue(body, null)
    flow = savedFlow
    scope = saved
    return sema.unitType
}

// ---- when -----------------------------------------------------------------------------------------------------------------------

fun BodyChecker.whenExpr(e: WhenExpr, expected: Type?): Type {
    val top = synthDepth == 0
    synthDepth++
    try {
        return whenBody(e, expected, top)
    } finally {
        synthDepth--
    }
}

private fun BodyChecker.whenBody(e: WhenExpr, expected: Type?, top: Boolean): Type {
    val subj = e.subject
    var subjType: Type? = null
    var subjKey: StableRef? = null
    val saved = scope
    var subjVar: VarSym? = null
    if (subj != null) {
        subjType = fin(expr(subj.expr, null))
        subjKey = stableKey(subj.expr)
        if (subj.valName != null) {
            val l = newLocal()
            val vt = if (subj.valType != null) sema.resolveType(subj.valType, scope) else subjType
            var vs = subj.start
            while (vs < file.text.length && (file.text[vs] == '(' || file.text[vs].isWhitespace())) vs++
            val v = VarSym(subj.valName, vt, false, false, vs)
            declareVar(l, v)
            recordDeclAt(vs, subj.expr.end, vt)
            subjVar = v
            subjType = vt
            subjKey = StableRef("v" + v.id, v.name)
        }
    }
    val base = flow
    var failed = ArrayList<FactAdd>() // facts from the conditions of earlier entries that did not match
    val types = ArrayList<Type>()
    var hasElse = false
    var merged: Flow? = null
    val covered = ArrayList<Any>()
    var coveredTrue = false
    var coveredFalse = false
    var anyNull = false
    for (en in e.entries) {
        flow = base.copy()
        flow.addAll(failed)
        var trueFacts: ArrayList<FactAdd>? = null
        val falseFacts = ArrayList<FactAdd>()
        if (en.isElse) hasElse = true
        for (c in en.conds) {
            val one: BodyChecker.Cond
            when (c) {
                is WhenExprCond -> {
                    if (subj != null) {
                        val ct = expr(c.expr, subjType)
                        one = noFacts()
                        val ce = AstWalk.unparen(c.expr)
                        if (isNullLiteral(ce)) {
                            anyNull = true
                            if (subjKey != null) {
                                val nn = FactAdd(subjKey.key, subjKey.root, sema.anyType)
                                one.onFalse.add(nn)
                            }
                        } else {
                            if (ce is LiteralExpr) {
                                if (ce.kind == LitKind.TRUE) coveredTrue = true
                                if (ce.kind == LitKind.FALSE) coveredFalse = true
                            }
                            val callee = rec.get(c.expr)?.callee
                            if (callee is EnumEntrySym) covered.add(callee)
                            val tgt = rec.get(c.expr)?.target
                            if (tgt is ClassType && tgt.cls.isObject) covered.add(tgt.cls)
                            // `when (x) { Obj -> }`: equal to a non-null value
                            val ctt = rec.get(c.expr)?.type
                            if (subjKey != null && ctt != null && !ops.mayBeNull(ctt)) one.onTrue.add(FactAdd(subjKey.key, subjKey.root, sema.anyType))
                        }
                    } else {
                        expr(c.expr, sema.booleanType)
                        one = condFacts(c.expr)
                    }
                }
                is WhenInCond -> {
                    val ct = fin(expr(c.expr, null))
                    if (subjType != null) {
                        val res = resolveOperator("contains", ct, listOf(subjType), c.start, null)
                        if (res != null) {
                            val rr = record(c, sema.booleanType, res.callee)
                            rr.calleeText = res.calleeText
                        }
                    }
                    one = noFacts()
                }
                is WhenIsCond -> {
                    val target = sema.resolveType(c.type, scope)
                    one = noFacts()
                    if (subjKey != null) {
                        val f = FactAdd(subjKey.key, subjKey.root, target)
                        if (c.negated) one.onFalse.add(f) else one.onTrue.add(f)
                    }
                    if (!c.negated) covered.add(target)
                    val rr = record(c, sema.booleanType, null)
                    rr.target = target
                }
            }
            trueFacts = if (trueFacts == null) one.onTrue else FlowOps.commonAdds(ops, trueFacts, one.onTrue)
            falseFacts.addAll(one.onFalse)
        }
        if (trueFacts != null && en.conds.size > 0) flow.addAll(trueFacts)
        val g = en.guard
        if (g != null) {
            expr(g, sema.booleanType)
            flow.addAll(condFacts(g).onTrue)
        }
        val bt = branchValue(en.body, expected)
        types.add(bt)
        merged = if (merged == null) flow else FlowOps.merge(ops, merged, flow)
        failed = ArrayList(failed)
        if (en.conds.size == 1 && g == null) failed.addAll(falseFacts)
    }
    if (expected == null && argNode == null) {
        var hint: Type? = null
        val good = ArrayList<Type>()
        var anyErr = false
        for (t in types) if (containsError(t)) anyErr = true else good.add(t)
        if (anyErr && good.isNotEmpty()) {
            hint = joinBranches(good)
            var idx = 0
            for (en in e.entries) {
                if (containsError(types[idx])) {
                    val sv = flow
                    flow = base.copy()
                    types[idx] = branchValue(en.body, hint)
                    flow = sv
                }
                idx++
            }
        }
    }
    var exhaustive = hasElse
    if (!exhaustive && subj != null && subjType != null) exhaustive = isExhaustive(subjType, covered, coveredTrue, coveredFalse, anyNull)
    if (!exhaustive) {
        // the implicit fall-through keeps the facts of the failed conditions
        flow = base.copy()
        flow.addAll(failed)
        merged = if (merged == null) flow else FlowOps.merge(ops, merged, flow)
    }
    flow = merged ?: base
    scope = saved
    if (!exhaustive) return sema.unitType
    if (types.isEmpty()) return sema.unitType
    return preferExpected(joinBranches(types), expected, e, top)
}

fun BodyChecker.isExhaustive(subj: Type, covered: List<Any>, t: Boolean, f: Boolean, hasNull: Boolean): Boolean {
    var st = subj
    if (st.nullable && !hasNull) return false
    st = st.withNullable(false)
    if (st is ClassType) {
        if (st.cls === sema.booleanType.cls) return t && f
        return coversClass(st.cls, covered, 0)
    }
    return false
}

private fun BodyChecker.coversClass(c: ClassSym, covered: List<Any>, depth: Int): Boolean {
    if (depth > 6) return false
    for (x in covered) {
        if (x is ClassType) {
            // `is C` covers every subtype of C
            if (ops.asSuper(c.thisType(), x.cls) != null) return true
        }
        if (x is ClassSym && x === c) return true
    }
    if (c.isEnum) {
        var all = true
        for (en in c.entries) {
            var has = false
            for (x in covered) if (x === en) has = true
            if (!has) all = false
        }
        return all && c.entries.isNotEmpty()
    }
    if (c.isSealed || (c.has("sealed"))) {
        val subs = sealedSubclasses(c)
        if (subs.isEmpty()) return false
        for (s in subs) if (!coversClass(s, covered, depth + 1)) return false
        return true
    }
    return false
}

fun BodyChecker.sealedSubclasses(c: ClassSym): List<ClassSym> {
    val out = ArrayList<ClassSym>()
    for (f in sema.files) {
        collectSubclasses(f.ast.declarations, f, c, out, null)
    }
    return out
}

private fun BodyChecker.collectSubclasses(ds: List<Decl>, f: FileInfo, target: ClassSym, out: ArrayList<ClassSym>, outer: ClassSym?) {
    for (d in ds) {
        if (d is ClassDecl) {
            val cs = sema.declSyms[d] as? ClassSym ?: continue
            if (cs !== target) {
                sema.ensureSupertypes(cs)
                for (st in cs.supertypes) {
                    if (st is ClassType && st.cls === target) {
                        out.add(cs)
                        break
                    }
                }
            }
            collectSubclasses(d.members, f, target, out, cs)
        }
    }
}
