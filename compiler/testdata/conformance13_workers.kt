// Round 13: the Kotlin/Native worker and atomic API on threads (not on wasm: no threads).

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.atomicGet
import kotlin.concurrent.compareAndSet
import kotlin.concurrent.getAndAdd
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

fun main() {
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
