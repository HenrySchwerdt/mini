sealed class Expr {
    data class Num(val v: Int) : Expr()
    data class Add(val l: Expr, val r: Expr) : Expr()
    data class Mul(val l: Expr, val r: Expr) : Expr()
    data class Neg(val e: Expr) : Expr()
    object Zero : Expr()
}

fun eval(e: Expr): Int = when (e) {
    is Expr.Num -> e.v
    is Expr.Add -> eval(e.l) + eval(e.r)
    is Expr.Mul -> eval(e.l) * eval(e.r)
    is Expr.Neg -> -eval(e.e)
    Expr.Zero -> 0
}

fun show(e: Expr): String = when (e) {
    is Expr.Num -> "${e.v}"
    is Expr.Add -> "(${show(e.l)} + ${show(e.r)})"
    is Expr.Mul -> "${show(e.l)} * ${show(e.r)}"
    is Expr.Neg -> "-${show(e.e)}"
    Expr.Zero -> "0"
}

fun simplify(e: Expr): Expr = when {
    e is Expr.Add && e.l == Expr.Zero -> simplify(e.r)
    e is Expr.Mul && (e.l == Expr.Zero || e.r == Expr.Zero) -> Expr.Zero
    e is Expr.Neg && e.e is Expr.Neg -> simplify(e.e.e)
    else -> e
}

sealed interface Result<out T> {
    data class Ok<T>(val v: T) : Result<T>
    data class Err(val msg: String) : Result<Nothing>
}

fun <T> Result<T>.getOr(d: T): T = when (this) { is Result.Ok -> v; is Result.Err -> d }

fun main() {
    val e = Expr.Add(Expr.Mul(Expr.Num(2), Expr.Num(3)), Expr.Neg(Expr.Num(4)))
    println(eval(e))
    println(show(e))
    println(simplify(Expr.Add(Expr.Zero, Expr.Num(5))))
    println(simplify(Expr.Neg(Expr.Neg(Expr.Num(1)))))
    println(simplify(Expr.Mul(Expr.Num(2), Expr.Zero)) == Expr.Zero)
    println(Result.Ok(3).getOr(0) + Result.Err("x").getOr(10))
    val rs: List<Result<Int>> = listOf(Result.Ok(1), Result.Err("bad"), Result.Ok(5))
    println(rs.filterIsInstance<Result.Ok<Int>>().sumOf { it.v })
    println(rs.map { when (it) { is Result.Ok -> "ok${it.v}"; is Result.Err -> it.msg } })
    println(Expr.Num(1) == Expr.Num(1))
}
