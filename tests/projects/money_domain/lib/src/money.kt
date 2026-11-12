package money

/** The currencies the library knows; the number of minor units is part of the currency. */
enum class Currency(val code: String, val decimals: Int, val symbol: String) {
    USD("USD", 2, "$"), EUR("EUR", 2, "€"), JPY("JPY", 0, "¥"), KWD("KWD", 3, "KD");

    val factor: Long get() = when (decimals) {
        0 -> 1L
        2 -> 100L
        else -> 1000L
    }
}

class CurrencyMismatch(a: Currency, b: Currency) : Exception("cannot combine ${a.code} and ${b.code}")

/** An amount in minor units. The constructor is internal: values are made with [money] / [Money.of], which validate. */
class Money internal constructor(val minor: Long, val currency: Currency) : Comparable<Money> {
    operator fun plus(o: Money) = Money(minor + same(o).minor, currency)
    operator fun minus(o: Money) = Money(minor - same(o).minor, currency)
    operator fun times(n: Int) = Money(minor * n, currency)
    operator fun unaryMinus() = Money(-minor, currency)
    operator fun div(n: Int): Money {
        require(n != 0) { "division by zero" }
        return Money(Math2.roundHalfEven(minor, n.toLong()), currency)
    }

    private fun same(o: Money): Money {
        if (o.currency != currency) throw CurrencyMismatch(currency, o.currency)
        return o
    }

    fun split(parts: Int): List<Money> {
        require(parts > 0)
        val base = minor / parts
        val extra = (minor % parts).toInt()
        return List(parts) { i -> Money(base + if (i < extra) 1 else 0, currency) }
    }

    fun percent(p: Int) = Money(Math2.roundHalfEven(minor * p, 100L), currency)
    override fun compareTo(other: Money) = minor.compareTo(same(other).minor)
    override fun equals(other: Any?) = other is Money && other.minor == minor && other.currency == currency
    override fun hashCode() = minor.hashCode() * 31 + currency.ordinal

    override fun toString(): String {
        val abs = if (minor < 0) -minor else minor
        val whole = abs / currency.factor
        val frac = if (currency.decimals == 0) "" else "." + (abs % currency.factor).toString().padStart(currency.decimals, '0')
        return (if (minor < 0) "-" else "") + currency.symbol + whole + frac
    }

    companion object {
        fun of(amount: String, currency: Currency): Money {
            val parts = amount.trim().split('.')
            require(parts.size <= 2 && parts[0].removePrefix("-").all { it.isDigit() } && parts[0].isNotEmpty()) { "not an amount: '$amount'" }
            val neg = parts[0].startsWith("-")
            val whole = parts[0].removePrefix("-").toLong()
            val fracText = if (parts.size == 2) parts[1] else ""
            require(fracText.length <= currency.decimals && fracText.all { it.isDigit() }) { "too many decimals for ${currency.code}: '$amount'" }
            val frac = fracText.padEnd(currency.decimals, '0').ifEmpty { "0" }.toLong()
            val m = whole * currency.factor + frac
            return Money(if (neg) -m else m, currency)
        }

        fun zero(c: Currency) = Money(0, c)
    }
}

fun money(amount: String, c: Currency = Currency.USD) = Money.of(amount, c)
val Int.usd: Money get() = Money(this * 100L, Currency.USD)
val Int.eur: Money get() = Money(this * 100L, Currency.EUR)

fun Iterable<Money>.total(c: Currency): Money = fold(Money.zero(c)) { a, b -> a + b }

internal object Math2 {
    fun roundHalfEven(num: Long, den: Long): Long {
        val q = num / den
        val r = num % den
        val twice = (if (r < 0) -r else r) * 2
        val sign = if ((num < 0) != (den < 0)) -1 else 1
        return when {
            twice > den -> q + sign
            twice < den -> q
            else -> if (q % 2 == 0L) q else q + sign
        }
    }
}
