// progressions at the limits of Int/Long/Char
fun main() {
    val mx = Int.MAX_VALUE; val mn = Int.MIN_VALUE
    println((mx - 2..mx).toList()); println((mx - 5..mx step 2).toList()); println((mx - 4..mx step 3).toList())
    println((mn + 2 downTo mn).toList()); println((mn + 5 downTo mn step 3).toList())
    println((mx - 1..mx).count()); println((mn..mn).toList()); println((mx..mx).toList())
    var n = 0; for (i in mx - 3..mx) n++; println(n)
    var m = 0; for (i in mn + 3 downTo mn) m++; println(m)
    var s = 0L; for (i in mx - 1 until mx) s += i; println(s)
    println((mx until mx).toList()); println((mn until mn).toList()); println((mn until mn + 2).toList())
    println((0..mx step mx).toList()); println((0..mx step mx / 2 + 1).toList())
    val lmx = Long.MAX_VALUE; println((lmx - 2..lmx).toList()); println((lmx - 4..lmx step 3L).toList()); println((Long.MIN_VALUE + 2 downTo Long.MIN_VALUE).count())
    println((Char.MAX_VALUE - 2..Char.MAX_VALUE).map { it.code }); println(('�'..'￿').count())
    println((mn..mx).first); println((mn..mx step 1000000000).toList())
    println((mx - 3..mx).reversed().toList()); println((mx downTo mx - 2).toList())
    println((mn..mn + 4).sum()); println((mx - 2..mx).sumOf { it.toLong() })
    val p = (mx - 10..mx step 4); println("" + p.first + " " + p.last + " " + p.step)
    println(1.rangeTo(mx).last); println(mx.downTo(mn).step(mx).toList())
    println((1..mx).step(1000000000).toList())
}
