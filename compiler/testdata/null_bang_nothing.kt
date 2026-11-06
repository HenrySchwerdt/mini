fun t4(s: String?): String {
    val v = s ?: null!!
    return v
}
fun t5(): Int {
    val x: Int = null!!
    return x
}
fun main() {
    try { println(t4(null)) } catch (e: NullPointerException) { println("npe") }
    try { println(t5()) } catch (e: NullPointerException) { println("npe5") }
}
