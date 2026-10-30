// Mini extension `@`: Plain declares no `operator fun matmul`, so the use is rejected.
class Plain(val x: Int)

fun main() {
    val a = Plain(1)
    val b = Plain(2)
    val c = a @ b
    println(c)
}
