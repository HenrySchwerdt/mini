import bus.*

sealed class ShopEvent : Event {
    data class ItemAdded(val sku: String, val qty: Int) : ShopEvent()
    data class ItemRemoved(val sku: String) : ShopEvent()
    data class CheckedOut(val total: Int) : ShopEvent()
}

data class Heartbeat(val n: Int) : Event

class Cart {
    val items = LinkedHashMap<String, Int>()
    fun on(e: ShopEvent) {
        when (e) {
            is ShopEvent.ItemAdded -> items[e.sku] = (items[e.sku] ?: 0) + e.qty
            is ShopEvent.ItemRemoved -> items.remove(e.sku)
            is ShopEvent.CheckedOut -> items.clear()
        }
    }
}

fun main() {
    val bus = EventBus()
    val cart = Cart()
    val log = ArrayList<String>()
    bus.subscribe<ShopEvent> { cart.on(it) }
    bus.subscribe<ShopEvent>(priority = 10) { log.add("audit " + it::class.simpleName) }
    val added = bus.subscribe<ShopEvent.ItemAdded>(priority = 5) { log.add("added ${it.sku} x${it.qty}") }
    bus.once<Heartbeat> { log.add("first heartbeat ${it.n}") }
    println("handlers: ${bus.handlerCount()}")
    val delivered = bus.publishAll(listOf(
        ShopEvent.ItemAdded("pen", 2), ShopEvent.ItemAdded("ink", 1), ShopEvent.ItemAdded("pen", 3),
        Heartbeat(1), Heartbeat(2), ShopEvent.ItemRemoved("ink"),
    ))
    println("delivered $delivered, cart ${cart.items}")
    added.cancel()
    added.cancel()
    println("handlers after cancel: ${bus.handlerCount()}, active=${added.active}")
    bus.publish(ShopEvent.ItemAdded("gum", 1))
    println(bus.publish(ShopEvent.CheckedOut(99)).toString() + " cart=" + cart.items)
    println("published ${bus.published}, dead letters ${bus.deadLetterCount()}")
    log.forEach { println("  $it") }
}
