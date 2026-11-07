interface B<T> { val bar: T }
class C {
    fun String.ext() = this.length
    fun String.ff() = object : B<String> { override val bar: String = ext().toString() }
    fun ff(s: String) = s.ff().bar
}
fun main() { println(C().ff("Hello")) }
