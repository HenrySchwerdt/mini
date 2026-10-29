// An external function can only use types that have a C representation.
import kotlin.native.CName

@CName("puts")
external fun puts(s: String): Int

fun main() {
    puts("hello")
}
