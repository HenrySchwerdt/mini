// classes that extend Number: the Number members dispatch to the user's methods; toChar has a default; equals / hashCode / toString of Number

class MyNumber(val value: Int) : Number() {
    override fun toInt(): Int = value
    override fun toByte(): Byte = toInt().toByte()
    override fun toDouble(): Double = toInt().toDouble()
    override fun toFloat(): Float = toInt().toFloat()
    override fun toLong(): Long = toInt().toLong()
    override fun toShort(): Short = toInt().toShort()
}

open class Plus(val value: Int) : Number() {
    override fun toChar(): Char = '+'
    override fun toInt(): Int = value
    override fun toByte(): Byte = toInt().toByte()
    override fun toDouble(): Double = toInt().toDouble()
    override fun toFloat(): Float = toInt().toFloat()
    override fun toLong(): Long = toInt().toLong()
    override fun toShort(): Short = toInt().toShort()
}

class PlusImpl(value: Int) : Plus(value) {
    override fun toChar(): Char = super.toChar()
}

interface Some {
    fun toChar(): Char = '#'
}

class Both(val value: Int) : Number(), Some {
    override fun toChar(): Char = super<Some>.toChar()
    override fun toInt(): Int = value
    override fun toByte(): Byte = toInt().toByte()
    override fun toDouble(): Double = toInt().toDouble()
    override fun toFloat(): Float = toInt().toFloat()
    override fun toLong(): Long = toInt().toLong()
    override fun toShort(): Short = toInt().toShort()
}

fun describe(n: Number): String = "${n.toInt()}/${n.toLong()}/${n.toDouble()}/${n.toChar().code}"

fun check(n: Number) {
    if (!n.equals(n)) throw Error("equals $n")
    if (n.equals(1)) throw Error("equals 1 $n")
    if (n.hashCode() != n.toInt()) throw Error("hashCode $n")
    if (n.toString() != "$n") throw Error("toString $n")
}

fun main() {
    println(MyNumber('*'.code).toChar())
    println(PlusImpl(1).toChar())
    println(Both(3).toChar())
    println(describe(MyNumber(65)))
    println(describe(Plus(66)))
    println(describe(7))
    println(describe(7L))
    val any: Any = MyNumber(1)
    println(any is Number)
    check(234)
    check(546L)
}
