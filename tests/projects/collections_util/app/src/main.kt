import collx.*

data class Order(val customer: String, val item: String, val qty: Int)

fun main() {
    val orders = listOf(Order("ann", "pen", 3), Order("bob", "ink", 1), Order("ann", "ink", 2), Order("cy", "pen", 10), Order("bob", "pen", 4))
    val byCustomer = orders.toMultimap { it.customer }
    println(byCustomer.keys.joinToString { "$it:${byCustomer[it].sumOf { o -> o.qty }}" })
    println(byCustomer.size.toString() + " orders, ann has " + byCustomer["ann"].map { it.item })
    var lines = 0
    byCustomer.forEachEntry { _, _ -> lines++ }
    println("entries: $lines")
    val m = multimapOf(1 to "one", 2 to "two", 1 to "uno")
    println(m)
    m.remove(2, "two")
    println(m.toMap())
    println(listOf(1, 4, 9, 16).pairwise().map { (a, b) -> b - a })
    println(listOf("a", "b", "--", "c", "--", "--", "d").splitBy { it == "--" })
    println(listOf(3, 1, 3, 2, 1, 3).duplicates())
    println(listOf<Any>(1, "two", 3.0, "four").firstOfType<String>().toString() + " " + listOf<Any>(1, 2).firstOfType<Double>())
    println(orders.maxesBy { it.qty }.map { it.customer } + orders.maxesBy { it.item }.map { it.customer })
    val inc = { x: Int -> x + 1 }
    val pipeline = inc.then { it * 10 }.then { "<$it>" }
    println(pipeline(4))
    var calls = 0
    val slow = memoize<Int> { calls++; it * it }
    println(listOf(3, 3, 4, 3, 4).map(slow).toString() + " calls=" + calls)
    println(tagged(7))
    val c = Counter<String>()
    "the cat and the hat and the bat".split(" ").forEach { c.add(it) }
    println(c.top(3).toString() + " total=" + c.total + " the=" + c["the"] + " dog=" + c["dog"])
}
