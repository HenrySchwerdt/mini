class Outer3(val x: Int) {
    inner class Inner() {
        inner class InnerInner() {
            lateinit var s: String

            constructor(s: String) : this() {
                this.s = s
            }
        }
    }
}

fun main() {
    Outer3(42).Inner().InnerInner("zzz")
    println("done")
}
