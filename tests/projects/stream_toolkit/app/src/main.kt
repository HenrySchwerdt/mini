import streamk.*

class AppFailure(msg: String) : StreamException(msg, 0)

fun main() {
    println(primes().take(10).toList())
    println(primes().dropWhile { it < 50 }.first())
    println(primes().chunkedBy { it % 10 == 1 }.take(3).toList())
    println(primes().zipWith(sequenceOf("a", "b", "c")) { p, s -> s + p }.toList())
    val w = Window<Int>(3)
    for (i in 1..5) w.push(i * i)
    println(w.map { it.sum() })
    println(twice("x", "y").toList())

    println(evalRpn("3 4 + 2 *"))
    for (bad in listOf("1 +", "1 2 /", "1 2")) {
        try {
            println(evalRpn(bad))
        } catch (e: ParseFailure) {
            println("parse " + e.message + " at " + e.position + " " + e.expected)
        } catch (e: StreamException) {
            println("stream " + e.message + " at " + e.position)
        } catch (e: IllegalArgumentException) {
            println("other " + e.message)
        } catch (e: NoSuchElementException) {
            println("none")
        } catch (e: IllegalStateException) {
            println("state " + e.message)
        }
    }

    val journal = ArrayList<String>()
    val r = withResources(journal, "a", "b") { rs -> rs.joinToString("+") { it.read() } }
    println(r)
    try {
        withResources(journal, "c") { throw AppFailure("boom") }
    } catch (e: StreamException) {
        println("caught " + e.message + " " + (e is AppFailure))
    }
    println(journal)
    Resource("d", journal).use { println(it.read()) }
    println(journal.takeLast(2))

    val ws = words("The cat and the hat; THE end, and so on")
    println(ws.histogram { it })
    println(ws.histogram { it.length })
    println(Token.entries.map { it.symbol })
    println(Token.valueOf("TIMES").symbol)
}
