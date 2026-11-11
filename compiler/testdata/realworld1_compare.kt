// a comparison of a value without compareTo is a compile error, not an internal compiler error
fun main() {
    val p = 1 to 2
    println(p < "a")
}
