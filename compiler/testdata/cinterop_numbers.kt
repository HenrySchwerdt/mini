// The C interop scalar types: Byte, Short and Float behave like the JVM's (wrapping conversions, widening arithmetic,
// float rounding), and they print like it.
fun bytes() {
    val a: Byte = 100
    val b: Byte = 50
    println(a + b)
    println((a + b).toByte())
    println(a - b)
    println(a * 2)
    println(-a)
    println(a.toInt() + b.toInt())
    println(a > b)
    println(a == 100.toByte())
    var c: Byte = 127
    c++
    println(c)
    c--
    println(c)
    println(c.toLong() + 1L)
    println((200).toByte())
    println((-129).toByte())
    println(300L.toByte())
    println(3.9.toInt().toByte())
    println((-3.9).toInt().toByte())
    println(1e10.toInt().toByte())
    println(a.toInt() and b.toInt())
    println(a.toInt() or b.toInt())
    println(a.toInt() xor b.toInt())
    println(Byte.MAX_VALUE)
    println(Byte.MIN_VALUE)
    val big: Byte = (-128).toByte()
    println(-big)
    println(big.toInt().toChar().code)
}

fun shorts() {
    val a: Short = 30000
    val b: Short = 10000
    println(a + b)
    println((a + b).toShort())
    println(a * b)
    println(a.toByte())
    println(70000.toShort())
    println((-32769).toShort())
    println(a > b)
    var s: Short = 32767
    s++
    println(s)
    println(a.toLong() * 100000L)
    println(a.toDouble() / 7)
    println(Short.MAX_VALUE.toInt() + 1)
    println(a % 7)
    println(a / b)
}

fun div(x: Float, y: Float): Float = x / y

fun floats() {
    val a = 1.1f
    val b = 2.2f
    println(a + b)
    println(a * b)
    println(a / b)
    println(b - a)
    println(a.toDouble())
    println(0.1f + 0.2f)
    println(0.1 + 0.2)
    println(1.0f / 3.0f)
    println(16777217)
    println(16777217.toFloat())
    println(1e10f)
    println(1.5e-5f)
    println(123456.789f)
    println(3.4028235E38f)
    println(1.4E-45f)
    println(-0.0f)
    println(0.0f == -0.0f)
    println(Float.NaN == Float.NaN)
    println(div(0.0f, 0.0f).isNaN())
    println(div(1.0f, 0.0f))
    println(div(-1.0f, 0.0f))
    println(2.5f.toInt())
    println((-2.5f).toInt())
    println(3.99f.toLong())
    println(1e20f.toInt())
    println(Float.NaN.toInt())
    println(5.toFloat() / 2)
    println(7L.toFloat() / 2)
    println(a < b)
    println(a >= b)
    println(a == 1.1f)
    println(10 % 3.5f)
    println(-a)
    var f = 0.5f
    f++
    println(f)
    f += 2
    println(f)
    f *= 3
    println(f)
    println(f.toString() + "!")
    println("f=$f, a=$a")
    println(2 * a)
    println(a * 2.0)
    println(100 / 8f)
}

fun mixed() {
    val i = 7
    val l = 1L shl 40
    val d = 2.5
    val f = 1.5f
    val b: Byte = 3
    val s: Short = 4
    println(i + f)
    println(l + f)
    println(f + d)
    println(b + f)
    println(s * f)
    println(f.toInt().toByte())
    println((f * 100).toInt().toShort())
    println(b + s)
    println(b.toDouble() + s)
}

fun main() {
    bytes()
    println("--")
    shorts()
    println("--")
    floats()
    println("--")
    mixed()
}
