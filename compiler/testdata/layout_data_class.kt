// Data classes with fields the layout reorders (Long/reference/Int/Boolean/Byte): the DECLARATION order is what toString,
// componentN, copy, equals and hashCode see, not the memory order.
data class Rec(
    val flag: Boolean,
    val id: Int,
    val name: String,
    val big: Long,
    val b: Byte,
    val score: Double,
    val tag: String?,
    val on: Boolean,
    val ch: Char,
)

data class Pt(val flag: Boolean, val x: Int, val y: Long, val label: String)

data class Big(val a: Byte, val b: Long, val c: Short, val d: Int, val e: Boolean)

fun main() {
    val r = Rec(true, 7, "seven", 7_000_000_000L, 7, 7.5, null, false, 'q')
    println(r)
    val (f, id, name, big, b, score, tag, on, ch) = r
    println("$f $id $name $big $b $score $tag $on $ch")
    println("${r.component1()} ${r.component2()} ${r.component3()} ${r.component4()} ${r.component5()} ${r.component6()} ${r.component7()} ${r.component8()} ${r.component9()}")
    val r2 = r.copy(id = 8, big = -1L, tag = "t")
    println(r2)
    val r3 = r2.copy(id = 7, big = 7_000_000_000L, tag = null)
    println(r3 == r)
    println(r3.hashCode() == r.hashCode())
    println(r2 == r)
    println(r.copy(on = true) == r)
    println(r.copy(flag = false) == r)
    println(r.copy(ch = 'r') == r)
    println(r.copy(b = 8) == r)
    println(Pt(true, 1, 2L, "p"))
    println(Pt(true, 1, 2L, "p") == Pt(true, 1, 2L, "p"))
    println(Pt(true, 1, 2L, "p") == Pt(true, 1, 3L, "p"))
    println(Pt(false, 1, 2L, "p").hashCode() == Pt(false, 1, 2L, "p").hashCode())
    println(Big(1, 2, 3, 4, true))
    val (a, bb, c, d, e) = Big(-1, 1L shl 40, -3, 4, false)
    println("$a $bb $c $d $e")
    val set = hashSetOf(Pt(true, 1, 2L, "p"), Pt(true, 1, 2L, "p"), Pt(false, 1, 2L, "p"))
    println(set.size)
    val m = HashMap<Big, String>()
    m[Big(1, 2, 3, 4, true)] = "x"
    m[Big(1, 2, 3, 4, true)] = "y"
    println(m.size)
    println(m[Big(1, 2, 3, 4, true)])
    println(listOf(Pt(true, 3, 3L, "c"), Pt(false, 1, 1L, "a")).sortedBy { it.x })
}
