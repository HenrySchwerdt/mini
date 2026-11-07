// an inner class of a value class keeps the unboxed outer instance
@JvmInline
value class Z(val x: Int) {
    inner class Inner(val y: Int) {
        val xx = x
        fun sum() = x + y
    }
}

@JvmInline
value class S(val s: String) {
    inner class Inner(val n: Int) {
        fun show() = s + n
    }
}

fun main() {
    val zi = Z(42).Inner(100)
    println(zi.xx)
    println(zi.y)
    println(zi.sum())
    println(S("a").Inner(1).show())
}
