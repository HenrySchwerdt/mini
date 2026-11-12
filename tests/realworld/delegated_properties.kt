// Delegated properties: lazy, observable, vetoable, notNull, map-backed objects, custom read-write delegates with a provider,
// delegation of interfaces with `by`, and local delegated variables.

import kotlin.properties.Delegates
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Logged<T>(private var value: T, private val log: MutableList<String>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        log.add("get ${property.name} = $value")
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        log.add("set ${property.name}: ${this.value} -> $value")
        this.value = value
    }
}

class Range(val min: Int, val max: Int) : ReadWriteProperty<Any?, Int> {
    private var v = min
    override fun getValue(thisRef: Any?, property: KProperty<*>) = v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        v = value.coerceIn(min, max)
    }
}

class Validated<T : Any>(private val check: (T) -> Boolean, private val message: String) {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ReadWriteProperty<Any?, T> {
        println("  provideDelegate for ${prop.name}")
        return object : ReadWriteProperty<Any?, T> {
            private var current: T? = null
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = current ?: error("${property.name} is not set")
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                require(check(value)) { "${property.name}: $message" }
                current = value
            }
        }
    }
}

class Counter : ReadOnlyProperty<Any?, Int> {
    private var n = 0
    override fun getValue(thisRef: Any?, property: KProperty<*>) = ++n
}

class Settings(map: MutableMap<String, Any?>) {
    var host: String by map
    var port: Int by map
    val debug: Boolean by map
}

class Person {
    val log = ArrayList<String>()
    var name: String by Logged("anon", log)
    var level by Range(1, 10)
    var email: String by Validated<String>({ '@' in it }, "must contain @")
    var age: Int by Delegates.vetoable(18) { _, old, new -> new >= old }
    var title: String by Delegates.observable("none") { prop, old, new -> log.add("${prop.name} changed $old -> $new") }
    var nick: String by Delegates.notNull()
    val ticket by Counter()
    val expensive: List<Int> by lazy {
        log.add("computing expensive")
        (1..5).map { it * it }
    }
}

interface Greeter {
    fun greet(name: String): String
    val language: String
}

class English : Greeter {
    override fun greet(name: String) = "Hello, $name!"
    override val language = "en"
}

class Shouting(private val inner: Greeter) : Greeter by inner {
    override fun greet(name: String) = inner.greet(name).uppercase()
}

class Polite(private val g: Greeter) : Greeter by g {
    override val language: String get() = "polite-" + g.language
}

class Cache {
    private val store = HashMap<Int, Long>()
    var hits = 0
    fun fib(n: Int): Long {
        store[n]?.let { hits++; return it }
        val v = if (n < 2) n.toLong() else fib(n - 1) + fib(n - 2)
        store[n] = v
        return v
    }
}

fun main() {
    val p = Person()
    println("-- logged")
    p.name = "ada"
    println(p.name)
    p.name += " lovelace"
    println(p.log)
    p.log.clear()
    println("-- range")
    p.level = 50
    print("${p.level} ")
    p.level = -3
    println(p.level)
    println("-- validated")
    p.email = "a@b.c"
    println(p.email)
    try {
        p.email = "nope"
    } catch (e: IllegalArgumentException) {
        println("rejected: ${e.message}")
    }
    println("-- vetoable")
    p.age = 30
    p.age = 25
    println(p.age)
    println("-- observable / notNull")
    p.title = "dr"
    p.title = "prof"
    try {
        println(p.nick)
    } catch (e: IllegalStateException) {
        println("nick: ${e.message}")
    }
    p.nick = "ace"
    println(p.nick)
    println(p.log)
    p.log.clear()
    println("-- lazy / counter")
    println(p.expensive)
    println(p.expensive.sum())
    println(p.log)
    println("tickets: ${p.ticket} ${p.ticket} ${p.ticket}")
    println("-- map")
    val map = mutableMapOf<String, Any?>("host" to "localhost", "port" to 8080, "debug" to true)
    val s = Settings(map)
    s.port += 1
    s.host = "example.org"
    println("${s.host}:${s.port} debug=${s.debug} map=${map.toSortedMap()}")
    println("-- interface delegation")
    val g: Greeter = Shouting(English())
    println(g.greet("bob") + " " + g.language)
    println(Polite(English()).let { it.greet("amy") + " " + it.language })
    println("-- local delegates")
    var calls = 0
    val once by lazy { calls++; "computed" }
    println("$once $once calls=$calls")
    val c = Cache()
    println("fib(60)=${c.fib(60)} hits=${c.hits}")
    val readOnly: Int by Counter()
    println(readOnly + readOnly)
}
