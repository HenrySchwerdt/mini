class Box { var n = 0; override fun toString() = "Box$n" }

fun main() {
    val b = Box()
    println("$b ${b.n++} $b")
    val l = mutableListOf(1)
    println("$l ${l.add(2)} $l")
    println("" + l + l.add(3) + l)
    var x = 0
    println("$x ${x++} $x")
    var s: String? = "a"
    println("$s ${run { s = null; 1 }} $s")
    val sb = StringBuilder("p")
    println("$sb ${sb.append("q")} ${sb.append("r")}")
}
