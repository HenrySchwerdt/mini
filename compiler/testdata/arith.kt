fun main() {
    var x = 2147483647
    x = x + 1
    println(x)
    println(x - 1)
    println(x * 2)
    println(-x)
    var y = 46341
    println(y * y)
    val big = 9223372036854775807L
    var b = big
    b = b + 1L
    println(b)
    println(b - 1L)
    println(7 / 2)
    println(-7 / 2)
    println(-7 % 3)
    println(7 % -3)
    println(10L * 3L)
    var m = -2147483647 - 1
    var minus1 = -1
    println(m / minus1)
    println(m % minus1)
    println(1 shl 33)
    println(-16 shr 2)
    println(-16 ushr 28)
    println(5 and 3)
    println(5 or 3)
    println(5 xor 3)
    println(5.inv())
    println(1L shl 40)
    var l = 5L
    l += 2
    l *= 3
    println(l)
    var i = 10
    i++
    i--
    --i
    println(i)
    println(i++ + ++i)
    println(i)
    println(1 + 2)
    println(2147483647 + 1)
    println((1L shl 62) * 4L)
    println(123456789L * 1000L)
    println(7.toLong() * 1000000000L)
    println(3000000000L.toInt())
}
