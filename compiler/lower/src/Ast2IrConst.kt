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
    if (n == "kotlin.Double" || n == "kotlin.Float") {
        if (v is Int) return v.toDouble()
        if (v is Long) return v.toDouble()
        return v
    }
    return v
}

/**
 * The value of a Float literal as a Double: [d] rounded to the nearest float (ties to even), computed with Double
 * arithmetic only (the compiler sources do not use Float). K2 hands over a Float constant; both paths end up with the same
 * `Convert(DoubleConst(x), Float)` in the MIR. (The decimal text is rounded twice, to a Double and to a float: this can
 * differ from the JVM's direct parse in the rare case of a literal on the midpoint of two floats.)
 */
fun roundToFloat(d: Double): Double {
    if (d != d || d == 0.0 || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY) return d
    val neg = d < 0.0
    val a = if (neg) -d else d
    // a = m * 2^e with 1 <= m < 2
    var m = a
    var e = 0
    while (m >= 2.0) {
        m /= 2.0
        e++
    }
    while (m < 1.0) {
        m *= 2.0
        e--
    }
    val s = if (e >= -126) e - 23 else -149
    // q = a / 2^s (exact), rounded to an integer with ties to even
    var q = a
    var k = s
    while (k > 0) {
        q /= 2.0
        k--
    }
    while (k < 0) {
        q *= 2.0
        k++
    }
    var fl = q.toLong().toDouble()
    val frac = q - fl
    if (frac > 0.5 || (frac == 0.5 && (fl.toLong() and 1L) == 1L)) fl += 1.0
    var r = fl
    k = s
    while (k > 0) {
        r *= 2.0
        k--
    }
    while (k < 0) {
        r /= 2.0
        k++
    }
    if (r > 3.4028234663852886E38) r = Double.POSITIVE_INFINITY
    return if (neg) -r else r
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
                    if (last == 'f' || last == 'F') return roundToFloat(text.substring(0, text.length - 1).toDouble())
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
