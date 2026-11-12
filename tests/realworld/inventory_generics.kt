// An inventory system built on generics: a typed repository with indexes, sorting with comparators, grouping, and
// extension functions with bounded type parameters.

interface Identified<ID : Comparable<ID>> {
    val id: ID
}

interface Priced {
    val priceCents: Int
}

enum class Category { TOOLS, FOOD, TOYS, BOOKS }

data class Product(override val id: Int, val name: String, val category: Category, override val priceCents: Int, val stock: Int, val tags: Set<String> = emptySet()) :
    Identified<Int>, Priced

data class Customer(override val id: String, val name: String, val vip: Boolean) : Identified<String>

class Store<ID : Comparable<ID>, T : Identified<ID>> {
    private val byId = HashMap<ID, T>()
    private val listeners = ArrayList<(String, T) -> Unit>()

    fun onChange(l: (String, T) -> Unit) {
        listeners.add(l)
    }

    fun put(item: T): Boolean {
        val isNew = item.id !in byId
        byId[item.id] = item
        listeners.forEach { it(if (isNew) "added" else "updated", item) }
        return isNew
    }

    fun remove(id: ID): T? = byId.remove(id)?.also { item -> listeners.forEach { it("removed", item) } }
    operator fun get(id: ID): T? = byId[id]
    operator fun contains(id: ID) = id in byId
    val size: Int get() = byId.size
    fun items(): List<T> = byId.values.sortedBy { it.id }

    inline fun <R : Comparable<R>> indexBy(crossinline key: (T) -> R): Map<R, List<T>> = items().groupBy { key(it) }.toSortedMap()
    fun update(id: ID, change: (T) -> T): Boolean {
        val cur = byId[id] ?: return false
        put(change(cur))
        return true
    }
}

fun <T : Priced> Iterable<T>.totalCents(): Long = sumOf { it.priceCents.toLong() }
fun <T> Iterable<T>.topBy(n: Int, selector: (T) -> Int): List<T> = sortedByDescending(selector).take(n)
fun <K, V : Comparable<V>> Map<K, V>.maxEntry(): Map.Entry<K, V>? = entries.maxByOrNull { it.value }
fun <T, R> List<T>.mapNotEmpty(f: (T) -> R?): List<R> = mapNotNull(f)
fun Int.asMoney(): String = "$" + this / 100 + "." + (this % 100).toString().padStart(2, '0')

class Pair3<A, B, C>(val a: A, val b: B, val c: C) {
    override fun toString() = "($a, $b, $c)"
}

fun <T : Comparable<T>> List<T>.isSortedAscending(): Boolean = zipWithNext().all { (x, y) -> x <= y }

fun <T> List<T>.chunkedBy(pred: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty()) return emptyList()
    val out = ArrayList<MutableList<T>>()
    out.add(mutableListOf(first()))
    for ((prev, cur) in zipWithNext()) {
        if (pred(prev, cur)) out.last().add(cur) else out.add(mutableListOf(cur))
    }
    return out
}

fun main() {
    val products = Store<Int, Product>()
    val events = ArrayList<String>()
    products.onChange { what, p -> events.add("$what ${p.name}") }
    val seed = listOf(
        Product(7, "hammer", Category.TOOLS, 1899, 12, setOf("steel", "heavy")),
        Product(3, "apple", Category.FOOD, 59, 200, setOf("fresh")),
        Product(9, "robot", Category.TOYS, 4999, 3, setOf("battery")),
        Product(1, "novel", Category.BOOKS, 1250, 25),
        Product(5, "saw", Category.TOOLS, 2450, 0, setOf("steel", "sharp")),
        Product(4, "bread", Category.FOOD, 325, 40, setOf("fresh")),
    )
    println("new: " + seed.map { products.put(it) })
    println("duplicate: " + products.put(seed[0].copy(stock = 11)))
    println("size ${products.size}, ids ${products.items().map { it.id }}")
    println("update: ${products.update(9) { it.copy(priceCents = it.priceCents - 1000) }}, missing: ${products.update(100) { it }}")
    println("removed: ${products.remove(4)?.name}, again: ${products.remove(4)}")
    println(events)
    println("by category:")
    for ((cat, list) in products.indexBy { it.category }) println("  $cat: ${list.joinToString { it.name }} = ${list.totalCents().toInt().asMoney()}")
    println("by tag count: " + products.indexBy { it.tags.size }.mapValues { e -> e.value.map { it.name } })
    val items = products.items()
    println("top 2 by value: " + items.topBy(2) { it.priceCents * it.stock }.map { it.name })
    println("most expensive: " + items.associate { it.name to it.priceCents }.maxEntry())
    println("out of stock: " + items.filter { it.stock == 0 }.map { it.name })
    println("tags: " + items.flatMap { it.tags }.toSortedSet())
    println("inventory value: " + items.sumOf { it.priceCents.toLong() * it.stock }.toInt().asMoney())
    println("sorted by name: " + items.sortedWith(compareBy<Product> { it.category }.thenByDescending { it.priceCents }).map { it.name })
    val customers = Store<String, Customer>()
    customers.put(Customer("c2", "bea", true))
    customers.put(Customer("c1", "ned", false))
    println(customers.items().map { "${it.id}:${it.name}${if (it.vip) "*" else ""}" })
    println("c1 in store: ${"c1" in customers}, c9: ${customers["c9"]}")
    println(Pair3(1, "x", listOf(2.5)))
    println(listOf(1, 2, 2, 5).isSortedAscending().toString() + " " + listOf(3, 1).isSortedAscending())
    println(listOf(1, 2, 4, 5, 6, 9).chunkedBy { a, b -> b == a + 1 })
    println(items.map { it.name }.chunkedBy { a, b -> a.length == b.length })
}
