// plusAssign vs plus on val/var, MutableList vs List, custom classes
class Acc(var total: Int = 0) { operator fun plusAssign(x: Int) { total += x }; operator fun minusAssign(x: Int) { total -= x }
    operator fun unaryMinus() = Acc(-total); operator fun inc() = Acc(total + 1); operator fun dec() = Acc(total - 1) }
class V(val x: Int) { operator fun plus(o: V) = V(x + o.x); operator fun times(k: Int) = V(x * k); operator fun rem(k: Int) = V(x % k); operator fun div(k: Int) = V(x / k); override fun toString() = "V($x)" }
fun main() {
    val acc = Acc(); acc += 5; acc += 3; acc -= 1; println(acc.total)
    var p = Acc(1); p += 2; println(p.total)
    val l = mutableListOf(1); l += 2; l += listOf(3, 4); l -= 1; println(l)
    var ro = listOf(1); ro += 2; ro += listOf(9); println(ro)
    val s = mutableSetOf("a"); s += "b"; s -= "a"; println(s)
    val m = mutableMapOf(1 to "a"); m += 2 to "b"; m += mapOf(3 to "c"); m -= 1; println(m)
    var v = V(2); v += V(3); println(v); v = v * 3; println(v); println(v % 4); println(v / 2)
    var c = Acc(5); c++; println(c.total); c--; --c; println(c.total); println((-c).total)
    val arr = arrayOf(1, 2); var a2 = arr; a2 += 3; println(a2.toList()); println(arr.size)
    var str = "a"; str += 1; str += 'c'; str += null; println(str)
    var n = 5; n += 2.5.toInt(); n *= 2; n %= 5; n = -n; println(n)
}
