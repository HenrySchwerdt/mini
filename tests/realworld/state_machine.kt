// State machines: a vending machine with sealed states and transitions, a traffic light with an enum and a transition table,
// and a small builder DSL for declaring machines with guards and actions.

sealed class VendState {
    object Idle : VendState() {
        override fun toString() = "Idle"
    }
    data class HasMoney(val cents: Int) : VendState()
    data class Dispensing(val item: String, val change: Int) : VendState()
    data class OutOfOrder(val reason: String) : VendState()
}

sealed class VendInput {
    data class Coin(val cents: Int) : VendInput()
    data class Select(val item: String) : VendInput()
    object Refund : VendInput()
    object Take : VendInput()
    object Kick : VendInput()
}

class VendingMachine(private val prices: Map<String, Int>, private val stock: MutableMap<String, Int>) {
    var state: VendState = VendState.Idle
        private set
    val log = ArrayList<String>()

    fun handle(input: VendInput) {
        val before = state
        state = when (val s = state) {
            is VendState.OutOfOrder -> s
            is VendState.Idle -> when (input) {
                is VendInput.Coin -> VendState.HasMoney(input.cents)
                is VendInput.Kick -> VendState.OutOfOrder("kicked while idle")
                else -> s
            }
            is VendState.HasMoney -> when (input) {
                is VendInput.Coin -> VendState.HasMoney(s.cents + input.cents)
                is VendInput.Refund -> {
                    log.add("refund ${s.cents}")
                    VendState.Idle
                }
                is VendInput.Select -> {
                    val price = prices[input.item]
                    val left = stock[input.item] ?: 0
                    when {
                        price == null -> { log.add("unknown item ${input.item}"); s }
                        left == 0 -> { log.add("${input.item} sold out"); s }
                        s.cents < price -> { log.add("need ${price - s.cents} more for ${input.item}"); s }
                        else -> {
                            stock[input.item] = left - 1
                            VendState.Dispensing(input.item, s.cents - price)
                        }
                    }
                }
                is VendInput.Kick -> VendState.OutOfOrder("kicked with money inside")
                else -> s
            }
            is VendState.Dispensing -> when (input) {
                is VendInput.Take -> {
                    log.add("took ${s.item}, change ${s.change}")
                    VendState.Idle
                }
                else -> s
            }
        }
        if (before != state) log.add("$before -> $state")
    }
}

enum class Light(val seconds: Int) {
    RED(30), GREEN(25), YELLOW(5);

    fun next(): Light = when (this) {
        RED -> GREEN
        GREEN -> YELLOW
        YELLOW -> RED
    }
}

// --- a tiny DSL for declaring machines -------------------------------------------------------------------------------

class Transition<S, E>(val from: S, val event: E, val to: S, val guard: ((Int) -> Boolean)?, val action: ((Int) -> Unit)?)

class MachineBuilder<S : Any, E> {
    val transitions = ArrayList<Transition<S, E>>()
    lateinit var initial: S

    inner class FromScope(private val from: S) {
        fun on(event: E, to: S, guard: ((Int) -> Boolean)? = null, action: ((Int) -> Unit)? = null) {
            transitions.add(Transition(from, event, to, guard, action))
        }
    }

    fun from(state: S, init: FromScope.() -> Unit) = FromScope(state).init()
}

class Machine<S : Any, E>(private val b: MachineBuilder<S, E>) {
    var current: S = b.initial
    var counter = 0
    val trace = ArrayList<String>()

    fun fire(event: E): Boolean {
        val t = b.transitions.firstOrNull { it.from == current && it.event == event && (it.guard?.invoke(counter) ?: true) }
        if (t == null) {
            trace.add("$current --$event--> (ignored)")
            return false
        }
        t.action?.invoke(counter)
        trace.add("$current --$event--> ${t.to}")
        current = t.to
        counter++
        return true
    }
}

fun <S : Any, E> machine(init: MachineBuilder<S, E>.() -> Unit): Machine<S, E> = Machine(MachineBuilder<S, E>().apply(init))

enum class Door { OPEN, CLOSED, LOCKED }
enum class Act { PUSH, PULL, LOCK, UNLOCK }

fun main() {
    val vm = VendingMachine(mapOf("cola" to 150, "chips" to 100, "gum" to 50), mutableMapOf("cola" to 1, "chips" to 0, "gum" to 5))
    val script = listOf(
        VendInput.Select("cola"), VendInput.Coin(100), VendInput.Select("cola"), VendInput.Coin(100), VendInput.Select("chips"),
        VendInput.Select("water"), VendInput.Select("cola"), VendInput.Coin(5), VendInput.Take, VendInput.Coin(100), VendInput.Select("cola"),
        VendInput.Refund, VendInput.Coin(50), VendInput.Select("gum"), VendInput.Take, VendInput.Coin(10), VendInput.Kick, VendInput.Coin(100),
    )
    for (i in script) vm.handle(i)
    vm.log.forEach { println(it) }
    println("final: ${vm.state}")
    println()
    var light = Light.RED
    var elapsed = 0
    val sb = StringBuilder()
    repeat(7) {
        sb.append("$light(${light.seconds}s) ")
        elapsed += light.seconds
        light = light.next()
    }
    println(sb.toString().trim())
    println("elapsed $elapsed s, cycle ${Light.values().sumOf { it.seconds }} s")
    println()
    val door = machine<Door, Act> {
        initial = Door.CLOSED
        from(Door.CLOSED) {
            on(Act.PUSH, Door.OPEN)
            on(Act.PULL, Door.OPEN)
            on(Act.LOCK, Door.LOCKED, guard = { it >= 1 }, action = { println("  (locking after $it moves)") })
        }
        from(Door.OPEN) {
            on(Act.PUSH, Door.CLOSED)
        }
        from(Door.LOCKED) {
            on(Act.UNLOCK, Door.CLOSED)
        }
    }
    val results = listOf(Act.LOCK, Act.PUSH, Act.PULL, Act.PUSH, Act.LOCK, Act.PUSH, Act.UNLOCK, Act.PULL).map { door.fire(it) }
    println(results)
    door.trace.forEach { println(it) }
    println("moves: ${door.counter}, state: ${door.current}")
}
