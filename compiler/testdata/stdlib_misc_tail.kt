import kotlin.collections.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.properties.ObservableProperty
import kotlin.properties.Delegates
import kotlin.random.Random
import kotlin.random.nextUBytes
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty

class Box(var v: Int)

class Counter {
    var log = StringBuilder()
    var n: Int by object : ObservableProperty<Int>(0) {
        override fun beforeChange(property: KProperty<*>, oldValue: Int, newValue: Int): Boolean = newValue >= 0
        override fun afterChange(property: KProperty<*>, oldValue: Int, newValue: Int) { log.append("${property.name}:$oldValue->$newValue;") }
    }
    var m: Int by Delegates.observable(5) { p, o, nw -> log.append("m:$o->$nw;") }
    var k: Int by Delegates.vetoable(1) { _, _, nw -> nw < 10 }
}

fun testVersion() {
    println("== KotlinVersion")
    val v = KotlinVersion(1, 9, 20)
    println(v)
    println(KotlinVersion(1, 9))
    println(v.major)
    println(v.minor)
    println(v.patch)
    println(v.isAtLeast(1, 9))
    println(v.isAtLeast(1, 9, 21))
    println(v.isAtLeast(1, 8, 99))
    println(v < KotlinVersion(2, 0))
    println(v.compareTo(KotlinVersion(1, 9, 20)))
    println(v == KotlinVersion(1, 9, 20))
    println(v.hashCode() == KotlinVersion(1, 9, 20).hashCode())
    println(KotlinVersion.MAX_COMPONENT_VALUE)
    println(KotlinVersion.CURRENT.isAtLeast(2, 1))
    try { KotlinVersion(256, 0) } catch (e: IllegalArgumentException) { println(e.message) }
}

@OptIn(ExperimentalUnsignedTypes::class)
fun testRanges() {
    println("== ranges")
    println((1u..5u))
    println((1u..5u).endExclusive)
    println((1uL..5uL))
    println((1uL..5uL).endExclusive)
    println(1u..5u == 1u..5u)
    println((5u..1u).isEmpty())
    println(UIntRange.EMPTY)
    println(ULongRange.EMPTY)
    println(UIntProgression.fromClosedRange(1u, 10u, 3))
    println(ULongProgression.fromClosedRange(1uL, 10uL, 3L))
    println((1..5).endExclusive)
    println((1L..5L).endExclusive)
    println(('a'..'e').endExclusive)
    try { (1..Int.MAX_VALUE).endExclusive } catch (e: IllegalStateException) { println("ISE") }
    println(7u.coerceIn(1u..5u))
    println(0u.coerceIn(1u..5u))
    println(3u.coerceIn(1u..5u))
    println(7uL.coerceIn(1uL..5uL))
    println(3uL.coerceIn(1uL..5uL))
    val r = Random(1)
    println(r.nextUBytes(4).size)
    println(r.nextUBytes(UByteArray(3)).size)
    println(r.nextUBytes(UByteArray(5), 1, 3).size)
}

fun testMisc() {
    println("== misc")
    val cmp = compareBy<String> { it.length }.thenByDescending(naturalOrder<Char>()) { it[0] }
    println(listOf("bb", "a", "cc", "b", "aa").sortedWith(cmp))
    val cmp2 = compareBy<String> { it.length }.thenDescending(naturalOrder<String>())
    println(listOf("bb", "a", "cc", "b", "aa").sortedWith(cmp2))
    val e1 = NoWhenBranchMatchedException("w")
    println(e1.message)
    println(NoWhenBranchMatchedException().message)
    println(NoWhenBranchMatchedException("w", IllegalStateException("c")).cause?.message)
    println(TypeCastException("tc").message)
    val ce = CancellationException("msg", IllegalStateException("inner"))
    println(ce.message)
    println(ce.cause?.message)
    println(CancellationException(IllegalStateException("inner2")).message)
    println(CancellationException("plain").cause)
    println(ce is IllegalStateException)
    @Suppress("DEPRECATION")
    run {
        println('A'.toByte())
        println('A'.toShort())
        println('A'.toFloat())
        println('A'.toChar())
    }
    var closed = 0
    AutoCloseable { closed++ }.use { println("in use") }
    println(closed)
    val it = Iterable { listOf(1, 2, 3).iterator() }
    println(it.map { it * 2 })
    println(arrayOf("a" to 1, "b" to 2).toMap())
    println(arrayOf("a" to 1).toMap())
    println(arrayOf<Pair<String, Int>>().toMap())
    println(sequenceOf("a" to 1, "b" to 2, "a" to 3).toMap())
    println(arrayOf("a" to 1, "b" to 2).toMap(HashMap<String, Int>()))
    val na: Array<String>? = null
    println(na.orEmpty().size)
    println(na.isNullOrEmpty())
    println(arrayOf("x").isNullOrEmpty())
    println(arrayOf("x").orEmpty().toList())
    val mm = mutableMapOf<String, Any?>("z" to 7)
    val z: Int by mm
    println(z)
    println(sequenceOf(1, 2, 3, 4, 5).shuffled(Random(3)).toList().sorted())
    val rm = mutableMapOf("a" to 1, "b" to 2)
    println(rm.remove("a", 2))
    println(rm.remove("a", 1))
    println(rm.remove("z", 1))
    println(rm)
    val dq = ArrayDeque(listOf(1, 2, 3))
    println(dq.toArray().toList())
    println(dq.toArray(arrayOfNulls<Int>(5)).toList())
    println(dq.toArray(arrayOfNulls<Int>(2)).toList())
    val b = Box(1)
    val kp: KMutableProperty1<Box, Int> = Box::v
    kp.setValue(b, kp, 42)
    println(b.v)
}

fun testObservable() {
    println("== ObservableProperty")
    val c = Counter()
    c.n = 3
    c.n = -1
    c.n = 4
    c.m = 6
    c.k = 5
    c.k = 50
    println(c.n)
    println(c.m)
    println(c.k)
    println(c.log)
}

@OptIn(ExperimentalUnsignedTypes::class)
fun main() {
    testVersion()
    testRanges()
    testMisc()
    testObservable()
}
