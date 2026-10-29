// staticCFunction cannot capture: a C function pointer has no place for state.
import kotlinx.cinterop.*

fun main() {
    var counter = 0
    val f = staticCFunction { x: Int ->
        counter += x
        counter
    }
    println(f(1))
}
