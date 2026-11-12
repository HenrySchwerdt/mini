package bus

import kotlin.reflect.KClass

/** Marker for everything that can be published. */
interface Event

class Subscription internal constructor(private val bus: EventBus, internal val id: Int) {
    var active = true
        private set

    fun cancel() {
        if (active) {
            active = false
            bus.unsubscribe(id)
        }
    }
}

class Handler<E : Event> internal constructor(val id: Int, val type: KClass<E>, val priority: Int, internal val fn: (E) -> Unit)

class EventBus {
    private val handlers = ArrayList<Handler<*>>()
    private var nextId = 1
    private val deadLetters = ArrayList<Event>()
    val published: Int get() = count
    private var count = 0

    @PublishedApi
    internal fun <E : Event> addHandler(type: KClass<E>, priority: Int, fn: (E) -> Unit): Subscription {
        val h = Handler(nextId++, type, priority, fn)
        handlers.add(h)
        handlers.sortByDescending { it.priority }
        return Subscription(this, h.id)
    }

    inline fun <reified E : Event> subscribe(priority: Int = 0, noinline handler: (E) -> Unit): Subscription = addHandler(E::class, priority, handler)

    internal fun unsubscribe(id: Int) {
        handlers.removeAll { it.id == id }
    }

    @Suppress("UNCHECKED_CAST")
    fun publish(event: Event): Int {
        count++
        var delivered = 0
        for (h in ArrayList(handlers)) {
            if (h.type.isInstance(event)) {
                (h as Handler<Event>).fn(event)
                delivered++
            }
        }
        if (delivered == 0) deadLetters.add(event)
        return delivered
    }

    fun deadLetterCount() = deadLetters.size
    fun handlerCount() = handlers.size
}

inline fun <reified E : Event> EventBus.once(crossinline handler: (E) -> Unit): Subscription {
    var sub: Subscription? = null
    sub = subscribe<E> {
        sub?.cancel()
        handler(it)
    }
    return sub
}

fun EventBus.publishAll(events: List<Event>): Int = events.sumOf { publish(it) }
