// assert, AssertionError(Any?), Char.toByte / toShort
fun main() {
    assert(true)
    assert(1 + 1 == 2) { "math" }
    try {
        assert(false)
    } catch (e: AssertionError) {
        println("1: " + e.message)
    }
    try {
        assert(false) { "custom " + 42 }
    } catch (e: AssertionError) {
        println("2: " + e.message)
    }
    val e = AssertionError(404)
    println(e.message)
    println('a'.toByte() + 'b'.toShort())
}
