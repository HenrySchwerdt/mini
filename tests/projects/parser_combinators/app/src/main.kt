import parsekit.*

sealed class Expr {
    data class Num(val v: Int) : Expr()
    data class Var(val name: String) : Expr()
    data class Bin(val op: Char, val l: Expr, val r: Expr) : Expr()
    data class Call(val fn: String, val args: List<Expr>) : Expr()
}

fun expr(): Parser<Expr> = lazyParser { additive }

val primary: Parser<Expr> = lazyParser {
    integer.map<Int, Expr> { Expr.Num(it) } or
        identifier.then(optional(between(symbol("("), sepBy(expr(), symbol(",")), symbol(")")))).map<Pair<String, List<Expr>?>, Expr> { (n, args) ->
            if (args == null) Expr.Var(n) else Expr.Call(n, args)
        } or
        between(symbol("("), expr(), symbol(")"))
}

fun binary(vararg ops: Char): Parser<(Expr, Expr) -> Expr> {
    var p: Parser<(Expr, Expr) -> Expr>? = null
    for (c in ops) {
        val one = symbol(c.toString()).map<String, (Expr, Expr) -> Expr> { { l, r -> Expr.Bin(c, l, r) } }
        p = if (p == null) one else p or one
    }
    return p!!
}

val multiplicative: Parser<Expr> = chainl1(primary, binary('*', '/'))
val additive: Parser<Expr> = chainl1(multiplicative, binary('+', '-'))

fun eval(e: Expr, env: Map<String, Int>): Int = when (e) {
    is Expr.Num -> e.v
    is Expr.Var -> env[e.name] ?: throw IllegalStateException("unbound ${e.name}")
    is Expr.Bin -> {
        val a = eval(e.l, env)
        val b = eval(e.r, env)
        when (e.op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            else -> a / b
        }
    }
    is Expr.Call -> {
        val args = e.args.map { eval(it, env) }
        when (e.fn) {
            "max" -> args.max()
            "min" -> args.min()
            "sum" -> args.sum()
            else -> throw IllegalStateException("unknown function ${e.fn}")
        }
    }
}

fun show(e: Expr): String = when (e) {
    is Expr.Num -> e.v.toString()
    is Expr.Var -> e.name
    is Expr.Bin -> "(${show(e.l)} ${e.op} ${show(e.r)})"
    is Expr.Call -> e.fn + e.args.joinToString(", ", "(", ")") { show(it) }
}

fun main() {
    val env = mapOf("x" to 6, "y" to 7)
    val full = whitespace skipLeft expr()
    for (src in listOf("1 + 2 * 3", " (1 + 2) * 3 ", "x * y - 2", "max(1, x, y) + min(4, 2)", "sum() + -5", "10 / 3 / 2", "2 * (3 + ", "1 + * 2", "foo(1)", "12 34", "z + 1")) {
        val ast = full.tryRun(src)
        if (ast == null) {
            val msg = try {
                full.run(src)
                "?"
            } catch (e: ParseError) {
                e.message
            }
            println("'$src' -> parse error $msg")
            continue
        }
        val v = try {
            eval(ast, env).toString()
        } catch (e: IllegalStateException) {
            "error: ${e.message}"
        }
        println("'$src' -> ${show(ast)} = $v")
    }
    println(sepBy(integer, symbol(",")).run("1, 2,3 ,  4"))
    println(many(letter).map { it.joinToString("") }.run("hello"))
    println((string("ab") or string("ac")).tryRun("ac") + " " + (string("ab") or string("ac")).tryRun("ad"))
    println(identifier.run("abc123") + " " + integer.run("-42"))
}
