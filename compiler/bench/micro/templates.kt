// String templates and concatenation (temporary strings).
class P(val name: String, val age: Int)

fun describe(p: P): String = "P(name=${p.name}, age=${p.age})"

fun main() {
    var len = 0L
    for (i in 0 until 300000) {
        val p = P("n" + (i % 10), i)
        val s = describe(p)
        len += s.length
        val t = "$i:${p.age + 1}/" + s
        len += t.length
    }
    println(len)
}
