// An event system: sealed event hierarchy, data classes, handlers selected with `when`, an event bus with typed subscriptions,
// an event-sourced aggregate that is rebuilt by replaying its history.

sealed class Event {
    abstract val id: Int

    data class UserRegistered(override val id: Int, val name: String, val email: String) : Event()
    data class UserRenamed(override val id: Int, val userId: Int, val newName: String) : Event()
    data class OrderPlaced(override val id: Int, val userId: Int, val items: List<LineItem>) : Event()
    data class OrderCancelled(override val id: Int, val orderId: Int, val reason: String) : Event()
    data class PaymentReceived(override val id: Int, val orderId: Int, val cents: Long) : Event()
}

data class LineItem(val sku: String, val quantity: Int, val unitCents: Long) {
    val totalCents: Long get() = quantity * unitCents
}

fun describe(e: Event): String = when (e) {
    is Event.UserRegistered -> "user ${e.name} <${e.email}> registered"
    is Event.UserRenamed -> "user #${e.userId} is now ${e.newName}"
    is Event.OrderPlaced -> "order placed by #${e.userId}: ${e.items.sumOf { it.quantity }} items, ${cents(e.items.sumOf { it.totalCents })}"
    is Event.OrderCancelled -> "order #${e.orderId} cancelled (${e.reason})"
    is Event.PaymentReceived -> "payment of ${cents(e.cents)} for order #${e.orderId}"
}

fun cents(c: Long): String = "$" + (c / 100) + "." + (c % 100).toString().padStart(2, '0')

class EventBus {
    private val handlers = ArrayList<Pair<(Event) -> Boolean, (Event) -> Unit>>()
    private val log = ArrayList<String>()

    inline fun <reified E : Event> on(noinline handler: (E) -> Unit) {
        register({ it is E }) { handler(it as E) }
    }

    fun register(filter: (Event) -> Boolean, handler: (Event) -> Unit) {
        handlers.add(filter to handler)
    }

    fun publish(e: Event): Int {
        var n = 0
        for ((filter, handler) in handlers) {
            if (filter(e)) {
                handler(e)
                n++
            }
        }
        log.add("${e::class.simpleName}#${e.id} -> $n handler(s)")
        return n
    }

    fun history(): List<String> = log
}

enum class OrderStatus { PLACED, PAID, CANCELLED }

data class Order(val id: Int, val userId: Int, val total: Long, val status: OrderStatus, val paid: Long = 0)

class Shop {
    val users = LinkedHashMap<Int, String>()
    val orders = LinkedHashMap<Int, Order>()
    var revenue = 0L

    fun apply(e: Event) {
        when (e) {
            is Event.UserRegistered -> users[e.id] = e.name
            is Event.UserRenamed -> users[e.userId] = e.newName
            is Event.OrderPlaced -> orders[e.id] = Order(e.id, e.userId, e.items.sumOf { it.totalCents }, OrderStatus.PLACED)
            is Event.OrderCancelled -> orders[e.orderId]?.let { orders[e.orderId] = it.copy(status = OrderStatus.CANCELLED) }
            is Event.PaymentReceived -> orders[e.orderId]?.let {
                val paid = it.paid + e.cents
                orders[e.orderId] = it.copy(paid = paid, status = if (paid >= it.total) OrderStatus.PAID else it.status)
                revenue += e.cents
            }
        }
    }

    fun summary(): String = buildString {
        appendLine("users: " + users.entries.joinToString { "#${it.key}=${it.value}" })
        for (o in orders.values) appendLine("order #${o.id} by ${users[o.userId] ?: "?"}: ${cents(o.total)} ${o.status} (paid ${cents(o.paid)})")
        append("revenue: ${cents(revenue)}")
    }
}

fun main() {
    val events = listOf(
        Event.UserRegistered(1, "ada", "ada@example.org"),
        Event.UserRegistered(2, "alan", "alan@example.org"),
        Event.OrderPlaced(3, 1, listOf(LineItem("book", 2, 1250), LineItem("pen", 10, 199))),
        Event.PaymentReceived(4, 3, 2000),
        Event.OrderPlaced(5, 2, listOf(LineItem("lamp", 1, 4599))),
        Event.UserRenamed(6, 2, "alan turing"),
        Event.PaymentReceived(7, 3, 490),
        Event.OrderCancelled(8, 5, "changed mind"),
        Event.PaymentReceived(9, 99, 100),
    )
    val bus = EventBus()
    var payments = 0L
    val cancelled = ArrayList<Int>()
    val shop = Shop()
    bus.on<Event.PaymentReceived> { payments += it.cents }
    bus.on<Event.OrderCancelled> { cancelled.add(it.orderId) }
    bus.on<Event.OrderPlaced> { println("  [audit] ${it.items.size} line items") }
    bus.register({ true }) { shop.apply(it) }
    for (e in events) {
        println(describe(e))
        bus.publish(e)
    }
    println()
    println(shop.summary())
    println("payments seen by handler: ${cents(payments)}, cancelled: $cancelled")
    println(bus.history().joinToString("\n"))
    val replay = Shop()
    events.forEach { replay.apply(it) }
    println("replay identical: ${replay.summary() == shop.summary()}")
    val byType = events.groupBy { it::class.simpleName }.mapValues { it.value.size }
    println(byType)
    println(events.filterIsInstance<Event.PaymentReceived>().maxByOrNull { it.cents })
}
