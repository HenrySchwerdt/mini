interface Expr
class Num(val value: Int) : Expr
class Add(val left: Expr, val right: Expr) : Expr
class Mul(val left: Expr, val right: Expr) : Expr
class Neg(val inner: Expr) : Expr

fun eval(e: Expr): Int = when (e) {
    is Num -> e.value
    is Add -> eval(e.left) + eval(e.right)
    is Mul -> eval(e.left) * eval(e.right)
    is Neg -> -eval(e.inner)
    else -> 0
}

fun show(e: Expr): String {
    if (e is Num) return "" + e.value
    if (e is Add) return "(" + show(e.left) + " + " + show(e.right) + ")"
    if (e is Mul) return show(e.left) + " * " + show(e.right)
    if (e is Neg) return "-" + show(e.inner)
    return "?"
}

fun classify(x: Any): String = when (x) {
    is Int -> "Int " + (x + 1)
    is String -> "String of length " + x.length
    is Boolean -> if (x) "yes" else "no"
    is Num -> "Num " + x.value
    else -> "other"
}

fun castTests(x: Any) {
    val s = x as? String
    println(s)
    if (x !is Num) {
        println("not a Num")
    } else {
        println("Num with " + x.value)
    }
    val n = x as? Num
    if (n != null) println(n.value + 100)
}

fun main() {
    val e: Expr = Add(Num(2), Mul(Num(3), Neg(Num(4))))
    println(show(e))
    println(eval(e))
    println(classify(41))
    println(classify("hello"))
    println(classify(true))
    println(classify(Num(7)))
    println(classify(2.5))
    castTests("text")
    castTests(Num(5))
    castTests(12)
    val any: Any = Num(9)
    val num = any as Num
    println(num.value)
}
