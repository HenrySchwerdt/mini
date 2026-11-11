// Found by the fuzzer (tools/fuzz): the lowering of Int.compareTo, Boolean.compareTo and Long.hashCode used an operand twice, so an
// operand with a side effect ran twice.
var calls = 0

fun next(v: Int): Int { calls++; println("next($v)"); return v }
fun nextL(v: Long): Long { calls++; println("nextL($v)"); return v }
fun nextB(v: Boolean): Boolean { calls++; println("nextB($v)"); return v }
fun nextC(v: Char): Char { calls++; println("nextC($v)"); return v }

fun main() {
    println(7.compareTo(next(3)))
    println(next(1).compareTo(next(2)))
    println(next(5).compareTo(5))
    println(nextL(10L).compareTo(nextL(20L)))
    println(nextL(10L).compareTo(4))
    println(nextB(true).compareTo(nextB(false)))
    println(nextB(false).compareTo(true))
    println(nextC('a').compareTo(nextC('b')))
    println(nextL(1234567890123L).hashCode())
    println(nextL(-1L).hashCode())
    println(minOf(7, 65535).compareTo(next(1)))
    println(calls)
}
