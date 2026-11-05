// Closures that capture the object that holds them (a cycle through the lambda), callbacks that refer back.
class Button(val label: String) {
    var onClick: (() -> Unit)? = null
    var clicks = 0
    fun click() { onClick?.invoke() }
}

class Counter {
    var count = 0
    val button = Button("inc")
    var step: () -> Int = { 0 }

    init {
        wire()
    }

    fun wire() {
        // the closure captures `this`, `this` holds the button, the button holds the closure
        val self = this
        button.onClick = { self.count += 1; self.button.clicks += 1 }
        step = { self.count + self.button.clicks }
    }
}

class Timer {
    val handlers = ArrayList<() -> Int>()
    var base = 7
    fun register() {
        val self = this
        handlers.add { self.base * 2 }
    }
    fun runAll(): Int { var s = 0; for (h in handlers) s += h(); return s }
}

fun main() {
    var total = 0L
    for (i in 0 until 5000) {
        val c = Counter()
        c.button.click()
        c.button.click()
        total += c.step()
    }
    println(total)
    var t2 = 0L
    for (i in 0 until 3000) {
        val t = Timer()
        t.register()
        t.register()
        t2 += t.runAll()
    }
    println(t2)
    val live = Counter()
    for (i in 0 until 10) live.button.click()
    println(live.count)
    println(live.step())
    println("done")
}
