// try expressions whose branches have different types, and `null!!` having type Nothing.
fun side(): Unit {}

fun t1(b: Boolean): Any {
    val r = try {
        if (b) throw Exception("x")
        side()
    } catch (e: Exception) {
        "caught"
    }
    return r
}

fun t2(): String {
    val x: String = try { "a" } catch (e: Exception) { null!! }
    return x
}

fun t3(): Int {
    val r = try { 1 } finally { println("fin") }
    return r
}

fun t4(s: String?): String {
    val v = s ?: null!!
    return v
}

fun main() {
    println(t1(true))
    println(t1(false))
    println(t2())
    println(t3())
    println(t4("q"))
    try {
        t4(null)
    } catch (e: NullPointerException) {
        println("npe")
    }
    val u = try { side() } catch (e: Exception) { "no" }
    println(u)
}
