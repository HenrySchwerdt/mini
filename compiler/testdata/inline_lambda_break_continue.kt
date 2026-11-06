// Non-local break / continue from the lambda of an inline function.
inline fun run1(block: () -> Unit) = block()
inline fun <T> each(xs: List<T>, f: (T) -> Unit) { for (x in xs) f(x) }

fun main() {
    var s = ""
    for (i in 1..6) {
        run1 {
            if (i == 2) continue
            if (i == 5) break
            s += i
        }
    }
    println(s)
    var n = 0
    var rounds = 0
    outer@ while (true) {
        rounds++
        if (rounds > 3) break
        for (j in 0..3) {
            run1 {
                n++
                if (j == 1) continue@outer
            }
        }
    }
    println(n)
    println(rounds)
    val r = StringBuilder()
    for (k in 1..3) {
        each(listOf("a", "b", "c")) { x ->
            if (x == "b") continue
            r.append(k).append(x).append(' ')
        }
    }
    println(r.toString().trim())
    fun f(): String {
        while (true) {
            run1 {
                try { continue } finally { return "fin" }
            }
        }
    }
    println(f())
}
