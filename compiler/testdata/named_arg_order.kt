// Named arguments are evaluated in the order they are written.
val log = StringBuilder()

fun <T> t(tag: String, v: T): T {
    log.append(tag)
    return v
}

fun f(a: String, b: String, c: String = "-"): String = a + b + c

class C(val x: String) {
    fun m(a: String, b: String): String = x + a + b
}

class K(val a: String, val b: String)

fun String.ext(a: String, b: String): String = this + a + b

fun main() {
    println(f(b = t("b", "B"), a = t("a", "A")))
    println(log); log.clear()
    println(f(c = t("c", "C"), b = t("b", "B"), a = t("a", "A")))
    println(log); log.clear()
    println(C(t("r", "x")).m(b = t("b", "B"), a = t("a", "A")))
    println(log); log.clear()
    println(t("r", "e").ext(b = t("b", "B"), a = t("a", "A")))
    println(log); log.clear()
    val k = K(b = t("b", "B"), a = t("a", "A"))
    println(k.a + k.b)
    println(log); log.clear()
    println(f(t("a", "A"), t("b", "B")))
    println(log)
}
