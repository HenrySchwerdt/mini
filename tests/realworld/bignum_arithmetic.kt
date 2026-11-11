// Arbitrary-precision natural numbers stored as base-10^9 limbs: addition, subtraction, multiplication, division by small numbers,
// comparison, power, factorials, Fibonacci numbers, digit sums and decimal printing, all operator overloaded.

class Big private constructor(private val limbs: IntArray) : Comparable<Big> {
    // limbs are little endian, base 1_000_000_000, no leading zero limbs (zero is the empty array)

    constructor(n: Long) : this(fromLong(n))
    constructor(s: String) : this(parseLimbs(s))

    val isZero get() = limbs.isEmpty()

    operator fun plus(o: Big): Big {
        val out = IntArray(maxOf(limbs.size, o.limbs.size) + 1)
        var carry = 0L
        for (i in out.indices) {
            val s = (limbs.getOrElse(i) { 0 }).toLong() + o.limbs.getOrElse(i) { 0 } + carry
            out[i] = (s % BASE).toInt()
            carry = s / BASE
        }
        return Big(trim(out))
    }

    operator fun minus(o: Big): Big {
        require(this >= o) { "negative result" }
        val out = IntArray(limbs.size)
        var borrow = 0L
        for (i in out.indices) {
            var d = limbs[i].toLong() - borrow - o.limbs.getOrElse(i) { 0 }
            if (d < 0) {
                d += BASE
                borrow = 1
            } else borrow = 0
            out[i] = d.toInt()
        }
        return Big(trim(out))
    }

    operator fun times(o: Big): Big {
        if (isZero || o.isZero) return ZERO
        val out = LongArray(limbs.size + o.limbs.size + 1)
        for (i in limbs.indices) {
            var carry = 0L
            for (j in 0..o.limbs.size) {
                val cur = out[i + j] + carry + (if (j < o.limbs.size) limbs[i].toLong() * o.limbs[j] else 0L)
                out[i + j] = cur % BASE
                carry = cur / BASE
            }
        }
        return Big(trim(IntArray(out.size) { out[it].toInt() }))
    }

    operator fun times(n: Int): Big = this * Big(n.toLong())

    operator fun div(d: Int): Big = divMod(d).first
    operator fun rem(d: Int): Int = divMod(d).second

    fun divMod(d: Int): Pair<Big, Int> {
        require(d > 0)
        val out = IntArray(limbs.size)
        var rem = 0L
        for (i in limbs.indices.reversed()) {
            val cur = rem * BASE + limbs[i]
            out[i] = (cur / d).toInt()
            rem = cur % d
        }
        return Big(trim(out)) to rem.toInt()
    }

    infix fun pow(e: Int): Big {
        var result = ONE
        var base = this
        var n = e
        while (n > 0) {
            if (n and 1 == 1) result *= base
            base *= base
            n = n shr 1
        }
        return result
    }

    fun digitSum(): Int = toString().sumOf { it - '0' }

    override fun compareTo(other: Big): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) if (limbs[i] != other.limbs[i]) return limbs[i].compareTo(other.limbs[i])
        return 0
    }

    override fun equals(other: Any?) = other is Big && limbs.contentEquals(other.limbs)
    override fun hashCode() = limbs.contentHashCode()

    override fun toString(): String {
        if (isZero) return "0"
        val sb = StringBuilder(limbs.last().toString())
        for (i in limbs.size - 2 downTo 0) sb.append(limbs[i].toString().padStart(9, '0'))
        return sb.toString()
    }

    companion object {
        private const val BASE = 1_000_000_000L
        val ZERO = Big(IntArray(0))
        val ONE = Big(1L)

        private fun trim(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        private fun fromLong(n: Long): IntArray {
            require(n >= 0)
            val l = ArrayList<Int>()
            var v = n
            while (v > 0) {
                l.add((v % BASE).toInt())
                v /= BASE
            }
            return l.toIntArray()
        }

        private fun parseLimbs(s: String): IntArray {
            require(s.isNotEmpty() && s.all { it.isDigit() }) { "not a number: $s" }
            val t = s.trimStart('0')
            val out = ArrayList<Int>()
            var end = t.length
            while (end > 0) {
                out.add(t.substring(maxOf(0, end - 9), end).toInt())
                end -= 9
            }
            return out.toIntArray()
        }

        fun factorial(n: Int): Big = (2..n).fold(ONE) { acc, i -> acc * i }

        fun fibonacci(n: Int): Big {
            var a = ZERO
            var b = ONE
            repeat(n) {
                val t = a + b
                a = b
                b = t
            }
            return a
        }
    }
}

fun main() {
    println(Big("123456789012345678901234567890") + Big("987654321098765432109876543210"))
    println(Big("1000000000000000000") - Big(1))
    println(Big("99999999999999999999") * Big("99999999999999999999"))
    println(Big.factorial(30))
    println("100! has ${Big.factorial(100).toString().length} digits, digit sum ${Big.factorial(100).digitSum()}")
    println("2^200 = ${Big(2) pow 200}")
    println("fib(100) = ${Big.fibonacci(100)}")
    println("fib(300) = ${Big.fibonacci(300)}")
    val (q, r) = Big("1000000000000000000000007").divMod(97)
    println("division: $q remainder $r check ${q * 97 + Big(r.toLong())}")
    println(Big.factorial(25) / 1000000 to Big.factorial(25) % 1000007)
    println(listOf(Big("5"), Big("123"), Big("99999999999"), Big("100000000000")).sorted().joinToString(" < "))
    println(Big("000123") == Big(123) to Big.ZERO.isZero to (Big(5) - Big(5)).isZero to Big("0"))
    println(((Big(10) pow 50) - Big(1)).digitSum())
    try {
        Big(3) - Big(5)
    } catch (e: IllegalArgumentException) {
        println("error: ${e.message}")
    }
    try {
        Big("12x")
    } catch (e: IllegalArgumentException) {
        println("error: ${e.message}")
    }
    var x = Big(1)
    for (i in 1..40) x = x * 7 + Big(i.toLong())
    println("rolling: $x")
}
