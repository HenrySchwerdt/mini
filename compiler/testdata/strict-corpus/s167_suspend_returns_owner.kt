// ACCEPT: a suspend function returns an owner (`@Owned`); the caller takes it over, passes it on from another suspend function, frees it after join
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun make(n: Int): @Owned Box {
    delay(1)
    return Box(n)
}

suspend fun makeNoSuspend(n: Int): @Owned Box = Box(n)

suspend fun relay(n: Int): @Owned Box {
    val b = make(n)
    delay(1)
    b.v = b.v + 1
    return b
}

suspend fun pick(flag: Boolean): @Owned Box {
    val a = make(1)
    val b = make(2)
    if (flag) {
        b.free()
        return a
    }
    a.free()
    return b
}

fun main() {
    runBlocking {
        val a = make(10)
        println(a.v)
        a.free()
        val r = relay(20)
        println(r.v)
        r.free()
        val x = make(30)
        val j = launch { delay(1) }
        println(x.v)
        j.join()
        x.free()
        val y = makeNoSuspend(40)
        println(y.v)
        y.free()
        val p = pick(true)
        println(p.v)
        p.free()
    }
}
