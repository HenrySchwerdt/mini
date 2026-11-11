// Round 13: bound references to generic extensions take the receiver type, adapted references with a vararg followed by a
// defaulted parameter, a Unit-bounded lambda result, the Kotlin/Native worker and atomic API on threads.

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.atomicGet
import kotlin.concurrent.compareAndSet
import kotlin.concurrent.getAndAdd
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

inline fun <reified T> T.funWithExtensionNoArgs() = this

fun normalF(a: Int, vararg b: String, c: Int = 0) = a + b.size + c

fun varargConversion(ref: (Int, String, Int) -> Int) = ref
fun noConversion(ref: (Int, Array<String>, Int) -> Int) = ref

inline fun <T> remember(crossinline calculation: () -> T): T = calculation()

fun main() {
    val f2: (String) -> String = { it + "!" }
    val c3 = f2::funWithExtensionNoArgs
    println(c3()("x"))
    println(varargConversion(::normalF)(1, "a", 2))
    println(noConversion(::normalF)(1, arrayOf("a", "b"), 2))
    println(varargConversion(::normalF) == varargConversion(::normalF))
    println((varargConversion(::normalF) as Any) == (noConversion(::normalF) as Any))
    val u: Unit = remember { null }
    println(u)
    val sem = AtomicInt(0)
    val w1 = Worker.start()
    val w2 = Worker.start()
    val f1 = w1.execute(TransferMode.SAFE, { }) {
        sem.incrementAndGet()
        while (sem.value != 3) {
        }
        "O"
    }
    val fut2 = w2.execute(TransferMode.SAFE, { }) {
        sem.incrementAndGet()
        while (sem.value != 3) {
        }
        "K"
    }
    while (sem.value != 2) {
    }
    sem.value = 3
    println(f1.result + fut2.result)
    w1.requestTermination().result
    w2.requestTermination().result
    val arr = IntArray(4) { it * 10 }
    println(arr.compareAndSet(2, 20, 222).toString() + " " + arr.atomicGet(2) + " " + arr.getAndAdd(1, 5) + " " + arr[1])
}
