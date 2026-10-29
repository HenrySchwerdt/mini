package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/* Ast2Ir: constant folding of `const val` reads (K2 replaces them by their value). */

/** the constant a `const val` property stands for, or null when [sym] is not a constant that can be folded */
fun Ast2Ir.constantOf(sym: PropSym, type: Type): IrExpression? {
    if (!sym.has("const")) return null
    val d = sym.decl ?: return null
    val init = d.initializer ?: return null
    val v = evalConst(init) ?: return null
    val t = irType(sema.typeOfProp(sym))
    return IrConst(t, convertConst(v, t))
}

/** adapts the computed value to the declared type of the constant */
fun Ast2Ir.convertConst(v: Any, t: Type): Any? {
    val n = t.classFqName
    if (n == "kotlin.Long") {
        if (v is Int) return v.toLong()
        return v
    }
    if (n == "kotlin.Int" || n == "kotlin.Short" || n == "kotlin.Byte") {
        if (v is Long) return v.toInt()
        return v
    }
    if (n == "kotlin.Double") {
        if (v is Int) return v.toDouble()
        if (v is Long) return v.toDouble()
        return v
    }
    return v
}

/** whether [e0] is built from literals and at least one `const val` */
fun Ast2Ir.mentionsConst(e0: Expr): Boolean {
    val e = unparen(e0)
    if (e is BinaryExpr) return mentionsConst(e.left) || mentionsConst(e.right)
    if (e is PrefixExpr) return mentionsConst(e.expr)
    if (e is NameExpr || e is DotExpr) {
        val c = rec(e)?.callee
        return c is PropSym && c.has("const")
    }
    return false
}

fun Ast2Ir.evalConst(e0: Expr): Any? {
    val e = unparen(e0)
    when (e) {
        is LiteralExpr -> {
            when (e.kind) {
                LitKind.TRUE -> return true
                LitKind.FALSE -> return false
                LitKind.CHAR -> return unescape(e.text.substring(1, e.text.length - 1))[0]
                LitKind.REAL -> {
                    val text = e.text.replace("_", "")
                    val last = text[text.length - 1]
                    if (last == 'f' || last == 'F') return null
                    return text.toDouble()
                }
                LitKind.INT -> {
                    var text = e.text
                    var isLong = false
                    if (text.endsWith("L")) {
                        isLong = true
                        text = text.substring(0, text.length - 1)
                    }
                    val v = parseIntLiteral(text) ?: return null
                    if (isLong || v > 2147483647L) return v
                    return v.toInt()
                }
                else -> return null
            }
        }
        is StringExpr -> {
            val sb = StringBuilder()
            for (p in e.parts) {
                if (p is StrText) sb.append(p.text) else if (p is StrEscape) sb.append(if (e.raw) p.text else unescape(p.text)) else return null
            }
            return sb.toString()
        }
        is PrefixExpr -> {
            val v = evalConst(e.expr) ?: return null
            if (e.op == "-") {
                if (v is Int) return -v
                if (v is Long) return -v
                if (v is Double) return -v
                return null
            }
            if (e.op == "+") return v
            return null
        }
        is BinaryExpr -> {
            val a = evalConst(e.left) ?: return null
            val b = evalConst(e.right) ?: return null
            if (a is String && e.op == "+") {
                if (b is String || b is Int || b is Long || b is Char || b is Boolean) return a + b.toString()
                return null
            }
            if (a is Int && b is Int) {
                return when (e.op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0) null else a / b
                    "%" -> if (b == 0) null else a % b
                    else -> null
                }
            }
            if ((a is Int || a is Long) && (b is Int || b is Long)) {
                val x = if (a is Int) a.toLong() else a as Long
                val y = if (b is Int) b.toLong() else b as Long
                return when (e.op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> if (y == 0L) null else x / y
                    "%" -> if (y == 0L) null else x % y
                    else -> null
                }
            }
            if (a is Double && b is Double) {
                return when (e.op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> a / b
                    else -> null
                }
            }
            return null
        }
        is NameExpr, is DotExpr -> {
            val r = rec(e) ?: return null
            val c = r.callee
            if (c is PropSym && c.has("const")) {
                val d = c.decl ?: return null
                val init = d.initializer ?: return null
                return evalConst(init)
            }
            return null
        }
        else -> return null
    }
}
