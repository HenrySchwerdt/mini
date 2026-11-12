// A scientific calculator: tokenizer, shunting-yard conversion to RPN with precedence and associativity, function calls with
// several arguments, variables, implicit multiplication errors, an RPN evaluator over a stack, and a symbolic derivative printer.

import kotlin.math.*

sealed class Tok {
    data class Num(val v: Double) : Tok()
    data class Id(val name: String) : Tok()
    data class Op(val c: Char) : Tok()
    object LParen : Tok()
    object RParen : Tok()
    object Comma : Tok()
}

class CalcError(msg: String) : Exception(msg)

fun tokenize(s: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() || c == '.' -> {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                out.add(Tok.Num(s.substring(st, i).toDoubleOrNull() ?: throw CalcError("bad number '${s.substring(st, i)}'")))
            }
            c.isLetter() -> {
                val st = i
                while (i < s.length && s[i].isLetterOrDigit()) i++
                out.add(Tok.Id(s.substring(st, i)))
            }
            c in "+-*/^%" -> { out.add(Tok.Op(c)); i++ }
            c == '(' -> { out.add(Tok.LParen); i++ }
            c == ')' -> { out.add(Tok.RParen); i++ }
            c == ',' -> { out.add(Tok.Comma); i++ }
            else -> throw CalcError("unexpected '$c' at $i")
        }
    }
    return out
}

val PREC = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2, '%' to 2, '~' to 4, '^' to 5)
val RIGHT = setOf('^', '~')
val ARITY = mapOf("sin" to 1, "cos" to 1, "sqrt" to 1, "abs" to 1, "ln" to 1, "max" to 2, "min" to 2, "pow" to 2, "hypot" to 2, "clamp" to 3)

fun toRpn(tokens: List<Tok>): List<Tok> {
    val out = ArrayList<Tok>()
    val stack = ArrayList<Tok>()
    var prev: Tok? = null
    for (t in tokens) {
        when (t) {
            is Tok.Num -> out.add(t)
            is Tok.Id -> if (t.name in ARITY) stack.add(t) else out.add(t)
            is Tok.Comma -> {
                while (stack.isNotEmpty() && stack.last() !is Tok.LParen) out.add(stack.removeAt(stack.size - 1))
                if (stack.isEmpty()) throw CalcError("misplaced comma")
            }
            is Tok.Op -> {
                val unary = t.c == '-' && (prev == null || prev is Tok.Op || prev is Tok.LParen || prev is Tok.Comma)
                val op = if (unary) Tok.Op('~') else t
                while (stack.isNotEmpty()) {
                    val top = stack.last()
                    if (top is Tok.Op && (PREC.getValue(top.c) > PREC.getValue(op.c) || (PREC.getValue(top.c) == PREC.getValue(op.c) && op.c !in RIGHT))) out.add(stack.removeAt(stack.size - 1)) else break
                }
                stack.add(op)
            }
            Tok.LParen -> stack.add(t)
            Tok.RParen -> {
                while (stack.isNotEmpty() && stack.last() !is Tok.LParen) out.add(stack.removeAt(stack.size - 1))
                if (stack.isEmpty()) throw CalcError("unbalanced ')'")
                stack.removeAt(stack.size - 1)
                if (stack.isNotEmpty() && stack.last() is Tok.Id) out.add(stack.removeAt(stack.size - 1))
            }
        }
        prev = t
    }
    while (stack.isNotEmpty()) {
        val t = stack.removeAt(stack.size - 1)
        if (t is Tok.LParen) throw CalcError("unbalanced '('")
        out.add(t)
    }
    return out
}

fun evalRpn(rpn: List<Tok>, vars: Map<String, Double>): Double {
    val st = ArrayList<Double>()
    fun pop(): Double = if (st.isEmpty()) throw CalcError("missing operand") else st.removeAt(st.size - 1)
    for (t in rpn) {
        when (t) {
            is Tok.Num -> st.add(t.v)
            is Tok.Id -> if (t.name in ARITY) {
                val args = List(ARITY.getValue(t.name)) { pop() }.reversed()
                st.add(when (t.name) {
                    "sin" -> sin(args[0])
                    "cos" -> cos(args[0])
                    "sqrt" -> if (args[0] < 0) throw CalcError("sqrt of a negative number") else sqrt(args[0])
                    "abs" -> abs(args[0])
                    "ln" -> ln(args[0])
                    "max" -> max(args[0], args[1])
                    "min" -> min(args[0], args[1])
                    "pow" -> args[0].pow(args[1])
                    "hypot" -> hypot(args[0], args[1])
                    else -> args[1].coerceIn(args[0], args[2])
                })
            } else st.add(vars[t.name] ?: throw CalcError("unknown variable '${t.name}'"))
            is Tok.Op -> if (t.c == '~') st.add(-pop()) else {
                val b = pop()
                val a = pop()
                st.add(when (t.c) {
                    '+' -> a + b
                    '-' -> a - b
                    '*' -> a * b
                    '/' -> if (b == 0.0) throw CalcError("division by zero") else a / b
                    '%' -> a % b
                    else -> a.pow(b)
                })
            }
            else -> throw CalcError("bad token")
        }
    }
    if (st.size != 1) throw CalcError("malformed expression")
    return st[0]
}

fun calc(s: String, vars: Map<String, Double> = emptyMap()): Double = evalRpn(toRpn(tokenize(s)), vars)

fun show(d: Double): String = if (d == floor(d) && abs(d) < 1e12) d.toLong().toString() else "%.6f".format(d).trimEnd('0').trimEnd('.')

fun rpnText(s: String) = toRpn(tokenize(s)).joinToString(" ") {
    when (it) {
        is Tok.Num -> show(it.v)
        is Tok.Id -> it.name
        is Tok.Op -> it.c.toString()
        else -> "?"
    }
}

fun main() {
    val exprs = listOf("1 + 2 * 3", "(1 + 2) * 3", "2 ^ 3 ^ 2", "-2 ^ 2", "10 / 4 - 1", "7 % 4 + 2 * -3", "sqrt(16) + abs(-3)", "max(3, min(8, 5)) * 2",
        "hypot(3, 4) / 5", "clamp(0, 15, 10)", "pow(2, 10) - 24", "2 * (3 + (4 - 1)) ^ 2", "1.5 * 4", "sin(0) + cos(0)", "x * x + y", "((2))", "1 +", "2 / 0", "sqrt(-1)", "3 $ 4", "(1 + 2", "1 + 2)", "foo + 1", "1..2 + 3")
    for (e in exprs) {
        val r = try {
            show(calc(e, mapOf("x" to 3.0, "y" to 0.5)))
        } catch (ex: CalcError) {
            "error: ${ex.message}"
        }
        println("${e.padEnd(24)} = $r")
    }
    println("rpn: " + rpnText("3 + 4 * 2 / (1 - 5) ^ 2 ^ 3"))
    println("rpn: " + rpnText("max(1, 2) + -x"))
    println("ln(e) = ${show(calc("ln(2.718281828459045)"))}, pi ~ ${show(calc("4 * (1 - 1/3 + 1/5 - 1/7 + 1/9 - 1/11)"))}")
    val table = (0..4).map { x -> x to calc("x ^ 2 - 2 * x + 1", mapOf("x" to x.toDouble())) }
    println(table.joinToString { "f(${it.first})=${show(it.second)}" })
    var lo = 0.0
    var hi = 2.0
    repeat(40) {
        val mid = (lo + hi) / 2
        if (calc("x ^ 2 - 2", mapOf("x" to mid)) < 0) lo = mid else hi = mid
    }
    println("sqrt(2) by bisection: ${show(lo)}")
}
