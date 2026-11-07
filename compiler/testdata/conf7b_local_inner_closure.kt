// an inner class of a local class reads the captured variables of the outer instance, not the ones of its own creation
fun main() {
    var log = ""
    var s: Any? = null
    for (t in arrayOf("1", "2", "3")) {
        class C() {
            val y = t

            inner class D() {
                fun foo() = "($y;$t)"
                fun copyOuter() = C()
            }
        }

        if (s == null) {
            s = C()
        }

        log += (s as C).D().foo()
        log += (s as C).D().copyOuter().y
    }
    println(log)
}
