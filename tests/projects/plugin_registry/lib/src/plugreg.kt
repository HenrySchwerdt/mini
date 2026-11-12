package plugreg

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

typealias Hook<T> = (T) -> T

interface Plugin<in I, out O> {
    val id: String
    fun process(input: I): O
    fun <X : I> processAll(inputs: List<X>): List<O> = inputs.map { process(it) }
}

abstract class BasePlugin<I, O>(final override val id: String) : Plugin<I, O> {
    private var calls = 0
    protected open fun before(input: I) {}
    protected abstract fun transform(input: I): O
    final override fun process(input: I): O {
        calls++
        before(input)
        return transform(input)
    }
    fun callCount() = calls
    override fun toString() = id + "#" + calls
}

class Registry<I, O> {
    private val plugins = LinkedHashMap<String, Plugin<I, O>>()
    private val hooks = ArrayList<Hook<I>>()
    val size get() = plugins.size

    fun register(p: Plugin<I, O>): Registry<I, O> {
        require(p.id !in plugins) { "duplicate plugin " + p.id }
        plugins[p.id] = p
        return this
    }

    fun hook(h: Hook<I>) { hooks.add(h) }

    fun runAll(input: I): Map<String, O> {
        var cur = input
        for (h in hooks) cur = h(cur)
        val out = LinkedHashMap<String, O>()
        for ((id, p) in plugins) out[id] = p.process(cur)
        return out
    }

    operator fun get(id: String): Plugin<I, O>? = plugins[id]
    operator fun contains(id: String) = id in plugins

    inner class Cursor {
        private var pos = 0
        fun hasNext() = pos < plugins.size
        fun next(): Plugin<I, O> = plugins.values.toList()[pos++]
    }
    fun cursor() = Cursor()
}

object Global {
    var counter = 0
        private set
    val log = ArrayList<String>()
    fun note(s: String): Int { counter++; log.add(s); return counter }
    val banner: String by lazy { "global-banner-" + note("banner") }
}

val startup: Int = Global.note("startup")

class Tracked<T>(private var value: T, private val onChange: (T, T) -> Unit) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        onChange(this.value, value)
        this.value = value
    }
}

fun <T> tracked(initial: T, onChange: (T, T) -> Unit) = Tracked(initial, onChange)

class Version private constructor(val major: Int, val minor: Int) : Comparable<Version> {
    companion object {
        const val SEP = '.'
        val ZERO = Version(0, 0)
        fun parse(s: String): Version {
            val (a, b) = s.split(SEP).map { it.toInt() }
            return Version(a, b)
        }
        fun of(major: Int, minor: Int = 0) = Version(major, minor)
    }
    operator fun component1() = major
    operator fun component2() = minor
    override fun compareTo(other: Version) = compareValuesBy(this, other, { it.major }, { it.minor })
    override fun equals(other: Any?) = other is Version && other.major == major && other.minor == minor
    override fun hashCode() = major * 31 + minor
    override fun toString() = "" + major + SEP + minor
}

inline fun <T : Comparable<T>> maxOfAll(first: T, vararg rest: T): T {
    var m = first
    for (r in rest) if (r > m) m = r
    return m
}

fun <T> T.describe(): String where T : Plugin<*, *>, T : Comparable<T> = "comparable plugin " + id

val <T> List<T>.second: T? get() = if (size > 1) this[1] else null

fun interface Formatter {
    operator fun invoke(s: String): String
}

fun makeFormatter(prefix: String): Formatter {
    class Local(val p: String) : Formatter {
        override fun invoke(s: String) = p + s
    }
    return Local(prefix)
}

tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
