import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun main() {
    val u = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    println(u)
    println(u.toHexString())
    println(u.toHexDashString())
    println(u.toByteArray().joinToString())
    println(u.toLongs { msb, lsb -> "$msb $lsb" })
    println(Uuid.fromLongs(1L, 2L))
    println(Uuid.NIL)
    println(Uuid.SIZE_BYTES.toString() + " " + Uuid.SIZE_BITS)
    println(Uuid.parseHex("123e4567e89b12d3a456426614174000") == u)
    println(Uuid.parseHexDash("123e4567-e89b-12d3-a456-426614174000") == u)
    println(Uuid.fromByteArray(u.toByteArray()) == u)
    println(u.hashCode() == Uuid.parse("123e4567-e89b-12d3-a456-426614174000").hashCode())
    println(Uuid.parse("00000000-0000-0000-0000-000000000001") < Uuid.parse("00000000-0000-0000-0000-000000000002"))
    println(u.compareTo(Uuid.NIL) > 0)
    println(listOf(Uuid.fromLongs(5, 5), Uuid.fromLongs(1, 9), Uuid.fromLongs(1, 3)).sorted().joinToString())
    val r = Uuid.random()
    val s = r.toString()
    println(s.length)
    println(s[8].toString() + s[13] + s[18] + s[23])
    println(s[14])
    println(s[19] in "89ab")
    println(Uuid.random() == r)
    try {
        Uuid.parse("not-a-uuid")
    } catch (e: IllegalArgumentException) {
        println("IAE " + e.message)
    }
    try {
        Uuid.parse("123e4567-e89b-12d3-a456-42661417400g")
    } catch (e: NumberFormatException) {
        println("NFE " + e.message)
    }
    try {
        Uuid.fromByteArray(ByteArray(3))
    } catch (e: IllegalArgumentException) {
        println("IAE " + e.message)
    }
    println(u.toULongs { a, b -> a.toString() + " " + b })
}
