// Charset / Charsets and the conversions between String and ByteArray with a charset.
fun main() {
    val s = "héllo wörld €"
    val u8 = s.toByteArray(Charsets.UTF_8)
    println(u8.size)
    println(String(u8, Charsets.UTF_8) == s)
    println(u8.toString(Charsets.UTF_8))
    println(s.toByteArray().size)
    val l1 = "héllo".toByteArray(Charsets.ISO_8859_1)
    println(l1.size)
    println(l1.map { it.toInt() and 0xFF })
    println(String(l1, Charsets.ISO_8859_1))
    val asc = "hé".toByteArray(Charsets.US_ASCII)
    println(asc.toList())
    val u16 = "ab".toByteArray(Charsets.UTF_16)
    println(u16.map { it.toInt() and 0xFF })
    println(String(u16, Charsets.UTF_16))
    println("ab".toByteArray(Charsets.UTF_16LE).map { it.toInt() })
    println(String("ab".toByteArray(Charsets.UTF_16BE), Charsets.UTF_16BE))
    println(Charsets.UTF_8.name())
    println(Charsets.UTF_8)
    println(Charset.forName("utf-8") == Charsets.UTF_8)
    println(String(u8, 0, 5, Charsets.UTF_8))
    println(String(byteArrayOf(104, 105)))
    try {
        Charset.forName("nope")
    } catch (e: IllegalArgumentException) {
        println("unsupported")
    }
}
