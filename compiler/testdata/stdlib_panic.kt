// A failing stdlib call aborts with the Kotlin exception name and message (until exceptions exist natively).
fun main() {
    println("before")
    val n = "12x".toInt()
    println("after " + n)
}
