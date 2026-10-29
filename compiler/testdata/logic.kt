fun t(name: String): Boolean {
    println("t:" + name)
    return true
}

fun f(name: String): Boolean {
    println("f:" + name)
    return false
}

fun v(n: Int): Int {
    println("v" + n)
    return n
}

fun main() {
    println(f("a") && t("b"))
    println(t("a") && t("b"))
    println(t("a") || f("b"))
    println(f("a") || f("b"))
    println(!t("x"))
    println(true and false)
    println(true or false)
    println(true xor true)
    val x = 5
    println(x > 3 && x < 10)
    println(x < 3 || x == 5)
    println(x != 5)
    println(!(x == 5))
    println(v(1) + v(2) * v(3))
    val r = v(10) + if (x > 3) v(20) else v(30)
    println(r)
    val q = v(1) + when {
        f("w1") -> v(2)
        t("w2") -> v(3)
        else -> v(4)
    }
    println(q)
    println(v(1) < v(2) && v(3) > v(2))
    var ok = t("p") && (f("q") || t("r"))
    println(ok)
}
