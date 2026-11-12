import money.*

data class Line(val description: String, val unit: Money, val qty: Int) {
    val total: Money get() = unit * qty
}

fun main() {
    val lines = listOf(Line("keyboard", money("49.99"), 2), Line("cable", money("3.50"), 5), Line("monitor", money("189"), 1))
    for (l in lines) println("${l.description.padEnd(10)} ${l.unit.toString().padStart(8)} x${l.qty} = ${l.total}")
    val subtotal = lines.map { it.total }.total(Currency.USD)
    val tax = subtotal.percent(19)
    println("subtotal $subtotal, tax $tax, total ${subtotal + tax}")
    println("split in 3: " + (subtotal + tax).split(3) + " sums to " + (subtotal + tax).split(3).total(Currency.USD))
    println("half: ${money("0.05") / 2} ${money("0.15") / 2} ${money("-0.05") / 2} ${money("1.00") / 3}")
    println(money("1234.5", Currency.EUR).toString() + " " + money("500", Currency.JPY) + " " + money("1.234", Currency.KWD) + " " + (-money("2.5")))
    println(listOf(5.usd, 2.usd, 9.usd).sorted() + " max=" + listOf(5.usd, 2.usd, 9.usd).max() + " " + (3.usd == money("3.00")) + " " + (3.usd == 3.eur))
    for (bad in listOf("abc", "1.234", "1..2", "")) {
        try {
            money(bad)
        } catch (e: IllegalArgumentException) {
            println("rejected: ${e.message}")
        }
    }
    try {
        println(5.usd + 5.eur)
    } catch (e: CurrencyMismatch) {
        println("error: ${e.message}")
    }
    try {
        println(5.usd < 3.eur)
    } catch (e: CurrencyMismatch) {
        println("error: ${e.message}")
    }
    println(Currency.values().map { it.code + "/" + it.decimals })
}
