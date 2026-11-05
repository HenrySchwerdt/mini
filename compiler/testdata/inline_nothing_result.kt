// An inline function whose lambda never completes (R = Nothing): the variable that holds the result has no storage.
inline fun <R> doCall(block: () -> R): R {
    val result = block()
    return result
}

fun early(): String {
    doCall { return "early" }
}

fun main() {
    println(early())
}
