import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun main() {
    val data = "Hello, Base64!".encodeToByteArray()
    val enc = Base64.encode(data)
    println(enc)
    println(Base64.decode(enc).decodeToString())
    println(Base64.encode(byteArrayOf(-1, -2, -3, -4)))
    println(Base64.UrlSafe.encode(byteArrayOf(-5, -1, -65, 62)))
    println(Base64.encode(byteArrayOf()))
    println(Base64.encode("a".encodeToByteArray()))
    println(Base64.encode("ab".encodeToByteArray()))
    println(Base64.encode("abc".encodeToByteArray()))
    println(Base64.withPadding(Base64.PaddingOption.ABSENT).encode("ab".encodeToByteArray()))
    println(Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode("YWI").decodeToString())
    val long = ByteArray(100) { it.toByte() }
    val mime = Base64.Mime.encode(long)
    println(mime.replace("\r\n", "|"))
    println(Base64.Mime.decode(mime).contentEquals(long))
    println(Base64.encode(long, 10, 20))
    println(Base64.decode("QUJD", 0, 4).decodeToString())
    println(Base64.encodeToByteArray("xyz".encodeToByteArray()).decodeToString())
    val sb = StringBuilder("[")
    Base64.encodeToAppendable("hi".encodeToByteArray(), sb)
    println(sb.append("]"))
    try {
        Base64.decode("a$")
    } catch (e: IllegalArgumentException) {
        println("IAE " + e.message)
    }
    try {
        Base64.decode("YWJj=")
    } catch (e: IllegalArgumentException) {
        println("IAE " + e.message)
    }
    try {
        Base64.decode("YWJ")
    } catch (e: IllegalArgumentException) {
        println("IAE " + e.message)
    }
    val dst = ByteArray(8)
    val n = Base64.encodeIntoByteArray("abc".encodeToByteArray(), dst, 2)
    println(n.toString() + " " + dst.decodeToString(2, 6))
    println(Base64.decodeIntoByteArray("YWJj", dst, 1))
    println(dst.decodeToString(1, 4))
}
