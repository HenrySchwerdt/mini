// An interpreter for a small imperative language: lexer, recursive-descent parser to an AST, closures, recursion, errors.

enum class T { NUM, STR, ID, OP, KW, EOF }

data class Token(val type: T, val text: String, val line: Int)

class LangError(message: String) : Exception(message)

val KEYWORDS = setOf("let", "fn", "if", "else", "while", "return", "true", "false", "print")

fun lex(src: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    var line = 1
    while (i < src.length) {
        val c = src[i]
        when {
            c == '\n' -> { line++; i++ }
            c.isWhitespace() -> i++
            c == '#' -> while (i < src.length && src[i] != '\n') i++
            c.isDigit() -> {
                val s = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                out.add(Token(T.NUM, src.substring(s, i), line))
            }
            c.isLetter() || c == '_' -> {
                val s = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                val w = src.substring(s, i)
                out.add(Token(if (w in KEYWORDS) T.KW else T.ID, w, line))
            }
            c == '"' -> {
                val s = ++i
                while (i < src.length && src[i] != '"') i++
                if (i >= src.length) throw LangError("line $line: unterminated string")
                out.add(Token(T.STR, src.substring(s, i), line))
                i++
            }
            else -> {
                val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                if (two in setOf("==", "!=", "<=", ">=", "&&", "||")) {
                    out.add(Token(T.OP, two, line))
                    i += 2
                } else if (c in "+-*/%<>=!(){},;") {
                    out.add(Token(T.OP, c.toString(), line))
                    i++
                } else throw LangError("line $line: unexpected '$c'")
            }
        }
    }
    out.add(Token(T.EOF, "", line))
    return out
}

sealed class Expr {
    data class Num(val v: Double) : Expr()
    data class Str(val v: String) : Expr()
    data class Bool(val v: Boolean) : Expr()
    data class Var(val name: String) : Expr()
    data class Bin(val op: String, val l: Expr, val r: Expr) : Expr()
    data class Un(val op: String, val e: Expr) : Expr()
    data class Call(val fn: Expr, val args: List<Expr>) : Expr()
    data class Lambda(val params: List<String>, val body: List<Stmt>) : Expr()
}

sealed class Stmt {
    data class Let(val name: String, val e: Expr) : Stmt()
    data class Assign(val name: String, val e: Expr) : Stmt()
    data class Print(val e: Expr) : Stmt()
    data class If(val c: Expr, val a: List<Stmt>, val b: List<Stmt>) : Stmt()
    data class While(val c: Expr, val body: List<Stmt>) : Stmt()
    data class Return(val e: Expr) : Stmt()
    data class Eval(val e: Expr) : Stmt()
}

class Parser(private val toks: List<Token>) {
    private var p = 0
    private fun peek() = toks[p]
    private fun next() = toks[p++]
    private fun isOp(s: String) = peek().type == T.OP && peek().text == s
    private fun isKw(s: String) = peek().type == T.KW && peek().text == s
    private fun expectOp(s: String): Token {
        if (!isOp(s)) throw LangError("line ${peek().line}: expected '$s' but found '${peek().text}'")
        return next()
    }

    fun program(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (peek().type != T.EOF) out.add(stmt())
        return out
    }

    private fun block(): List<Stmt> {
        expectOp("{")
        val out = ArrayList<Stmt>()
        while (!isOp("}")) {
            if (peek().type == T.EOF) throw LangError("line ${peek().line}: missing '}'")
            out.add(stmt())
        }
        expectOp("}")
        return out
    }

    private fun stmt(): Stmt {
        if (isKw("let")) {
            next()
            val name = next().text
            expectOp("=")
            val e = expr()
            expectOp(";")
            return Stmt.Let(name, e)
        }
        if (isKw("print")) {
            next()
            val e = expr()
            expectOp(";")
            return Stmt.Print(e)
        }
        if (isKw("return")) {
            next()
            val e = expr()
            expectOp(";")
            return Stmt.Return(e)
        }
        if (isKw("if")) {
            next()
            val c = expr()
            val a = block()
            var b: List<Stmt> = emptyList()
            if (isKw("else")) {
                next()
                b = if (isKw("if")) listOf(stmt()) else block()
            }
            return Stmt.If(c, a, b)
        }
        if (isKw("while")) {
            next()
            val c = expr()
            return Stmt.While(c, block())
        }
        if (peek().type == T.ID && toks[p + 1].type == T.OP && toks[p + 1].text == "=") {
            val name = next().text
            next()
            val e = expr()
            expectOp(";")
            return Stmt.Assign(name, e)
        }
        val e = expr()
        expectOp(";")
        return Stmt.Eval(e)
    }

    private val levels = listOf(listOf("||"), listOf("&&"), listOf("==", "!="), listOf("<", "<=", ">", ">="), listOf("+", "-"), listOf("*", "/", "%"))

    private fun expr(level: Int = 0): Expr {
        if (level == levels.size) return unary()
        var l = expr(level + 1)
        while (peek().type == T.OP && peek().text in levels[level]) {
            val op = next().text
            l = Expr.Bin(op, l, expr(level + 1))
        }
        return l
    }

    private fun unary(): Expr {
        if (isOp("-") || isOp("!")) return Expr.Un(next().text, unary())
        return postfix()
    }

    private fun postfix(): Expr {
        var e = primary()
        while (isOp("(")) {
            next()
            val args = ArrayList<Expr>()
            if (!isOp(")")) {
                do args.add(expr()) while (isOp(",") && next().text == ",")
            }
            expectOp(")")
            e = Expr.Call(e, args)
        }
        return e
    }

    private fun primary(): Expr {
        val t = next()
        return when {
            t.type == T.NUM -> Expr.Num(t.text.toDouble())
            t.type == T.STR -> Expr.Str(t.text)
            t.type == T.KW && t.text == "true" -> Expr.Bool(true)
            t.type == T.KW && t.text == "false" -> Expr.Bool(false)
            t.type == T.KW && t.text == "fn" -> {
                expectOp("(")
                val params = ArrayList<String>()
                if (!isOp(")")) {
                    do params.add(next().text) while (isOp(",") && next().text == ",")
                }
                expectOp(")")
                Expr.Lambda(params, block())
            }
            t.type == T.ID -> Expr.Var(t.text)
            t.type == T.OP && t.text == "(" -> expr().also { expectOp(")") }
            else -> throw LangError("line ${t.line}: unexpected '${t.text}'")
        }
    }
}

class Env(val parent: Env?) {
    val vars = HashMap<String, Any?>()
    fun lookup(n: String): Any? {
        var e: Env? = this
        while (e != null) {
            if (n in e.vars) return e.vars[n]
            e = e.parent
        }
        throw LangError("undefined variable '$n'")
    }

    fun assign(n: String, v: Any?) {
        var e: Env? = this
        while (e != null) {
            if (n in e.vars) {
                e.vars[n] = v
                return
            }
            e = e.parent
        }
        throw LangError("assignment to undefined variable '$n'")
    }
}

class Closure(val params: List<String>, val body: List<Stmt>, val env: Env)
class ReturnSignal(val value: Any?) : Exception()

class Interpreter {
    val out = ArrayList<String>()
    var calls = 0

    fun show(v: Any?): String = when (v) {
        is Double -> if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Closure -> "<fn/${v.params.size}>"
        else -> v.toString()
    }

    fun exec(body: List<Stmt>, env: Env) {
        for (s in body) when (s) {
            is Stmt.Let -> env.vars[s.name] = eval(s.e, env)
            is Stmt.Assign -> env.assign(s.name, eval(s.e, env))
            is Stmt.Print -> out.add(show(eval(s.e, env)))
            is Stmt.Eval -> eval(s.e, env)
            is Stmt.Return -> throw ReturnSignal(eval(s.e, env))
            is Stmt.If -> exec(if (truthy(eval(s.c, env))) s.a else s.b, Env(env))
            is Stmt.While -> while (truthy(eval(s.c, env))) exec(s.body, Env(env))
        }
    }

    private fun truthy(v: Any?) = v != false && v != null

    fun eval(e: Expr, env: Env): Any? = when (e) {
        is Expr.Num -> e.v
        is Expr.Str -> e.v
        is Expr.Bool -> e.v
        is Expr.Var -> env.lookup(e.name)
        is Expr.Lambda -> Closure(e.params, e.body, env)
        is Expr.Un -> {
            val v = eval(e.e, env)
            if (e.op == "-") -(v as? Double ?: throw LangError("cannot negate $v")) else !truthy(v)
        }
        is Expr.Bin -> binary(e, env)
        is Expr.Call -> {
            val f = eval(e.fn, env) as? Closure ?: throw LangError("not a function")
            if (f.params.size != e.args.size) throw LangError("expected ${f.params.size} arguments but got ${e.args.size}")
            val local = Env(f.env)
            for ((n, a) in f.params.zip(e.args)) local.vars[n] = eval(a, env)
            calls++
            try {
                exec(f.body, local)
                null
            } catch (r: ReturnSignal) {
                r.value
            }
        }
    }

    private fun binary(e: Expr.Bin, env: Env): Any? {
        if (e.op == "&&") return truthy(eval(e.l, env)) && truthy(eval(e.r, env))
        if (e.op == "||") return truthy(eval(e.l, env)) || truthy(eval(e.r, env))
        val l = eval(e.l, env)
        val r = eval(e.r, env)
        return when (e.op) {
            "==" -> l == r
            "!=" -> l != r
            "+" -> if (l is Double && r is Double) l + r else show(l) + show(r)
            else -> {
                if (l !is Double || r !is Double) throw LangError("operator ${e.op} needs numbers")
                when (e.op) {
                    "-" -> l - r
                    "*" -> l * r
                    "/" -> if (r == 0.0) throw LangError("division by zero") else l / r
                    "%" -> l % r
                    "<" -> l < r
                    "<=" -> l <= r
                    ">" -> l > r
                    else -> l >= r
                }
            }
        }
    }
}

fun runProgram(name: String, src: String) {
    println("--- $name")
    val interp = Interpreter()
    try {
        interp.exec(Parser(lex(src)).program(), Env(null))
    } catch (e: LangError) {
        println("error: ${e.message}")
    }
    interp.out.forEach { println(it) }
    if (interp.calls > 0) println("(${interp.calls} calls)")
}

fun main() {
    runProgram("arithmetic", "print 1 + 2 * 3; print (1 + 2) * 3; print 10 / 4; print 7 % 3; print -2 * -3; print \"a\" + 1 + true;")
    runProgram("fib", """
        let fib = fn(n) { if n < 2 { return n; } return fib(n - 1) + fib(n - 2); };
        let i = 0;
        while i < 12 { print fib(i); i = i + 1; }
    """)
    runProgram("closures", """
        let counter = fn() { let n = 0; return fn() { n = n + 1; return n; }; };
        let a = counter(); let b = counter();
        print a(); print a(); print b(); print a();
        let compose = fn(f, g) { return fn(x) { return f(g(x)); }; };
        let inc = fn(x) { return x + 1; }; let dbl = fn(x) { return x * 2; };
        print compose(inc, dbl)(5); print compose(dbl, inc)(5);
        print a;
    """)
    runProgram("control", """
        let n = 27; let steps = 0;
        while n != 1 {
            if n % 2 == 0 { n = n / 2; } else if n > 0 { n = 3 * n + 1; }
            steps = steps + 1;
        }
        print "collatz steps: " + steps;
        print 1 < 2 && "yes" == "yes"; print !(1 == 1) || false;
    """)
    runProgram("errors-1", "print 1 / 0;")
    runProgram("errors-2", "let f = fn(a) { return a; }; print f(1, 2);")
    runProgram("errors-3", "print missing;")
    runProgram("errors-4", "let x = ;")
    runProgram("errors-5", "print \"abc")
    runProgram("errors-6", "let f = fn() { print 1; ")
}
