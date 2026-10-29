// C pointers (and Byte, Short, Float) are plain machine values: they cannot be boxed into Any or generic collections.
import kotlinx.cinterop.*

fun main() {
    memScoped {
        val x = alloc<IntVar>()
        val list = listOf(x.ptr)
        println(list.size)
    }
}
