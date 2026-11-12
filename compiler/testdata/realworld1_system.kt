// realworld-1: java.lang.System (clocks, lineSeparator, arraycopy, exit) and Runtime.availableProcessors
fun main() {
    val t0 = System.currentTimeMillis()
    val n0 = System.nanoTime()
    println(t0 > 1_600_000_000_000L)
    println(System.nanoTime() >= n0)
    println(System.lineSeparator() == "\n")
    println(Runtime.getRuntime().availableProcessors() > 0)
    val a = intArrayOf(1, 2, 3, 4, 5)
    val b = IntArray(5)
    System.arraycopy(a, 1, b, 0, 3)
    println(b.toList())
    val s = arrayOf("a", "b", "c")
    val d = arrayOfNulls<String>(4)
    System.arraycopy(s, 0, d, 1, 3)
    println(d.toList())
}
