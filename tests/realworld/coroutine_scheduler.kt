// Coroutine pipelines on the plain kotlin.coroutines API: a cooperative scheduler (launch / yield / delay in virtual time /
// join), channels between producer and consumer stages, and a pipeline of suspending transformations.

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

class Task(val name: String) {
    var done = false
    val waiters = ArrayList<Continuation<Unit>>()
}

class Scheduler {
    private val ready = ArrayDeque<() -> Unit>()
    private val timers = ArrayList<Triple<Long, Int, () -> Unit>>()
    private var seq = 0
    var now = 0L
        private set
    val trace = ArrayList<String>()

    fun post(block: () -> Unit) {
        ready.addLast(block)
    }

    fun launch(name: String, body: suspend Scheduler.() -> Unit): Task {
        val task = Task(name)
        post {
            body.startCoroutine(this, object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    result.onFailure { trace.add("[$now] $name failed: ${it.message}") }
                    task.done = true
                    trace.add("[$now] $name finished")
                    for (w in task.waiters) post { w.resume(Unit) }
                    task.waiters.clear()
                }
            })
        }
        return task
    }

    suspend fun yieldNow() = suspendCoroutine<Unit> { c -> post { c.resume(Unit) } }

    suspend fun delay(ms: Long) = suspendCoroutine<Unit> { c ->
        timers.add(Triple(now + ms, seq++, { c.resume(Unit) }))
    }

    suspend fun join(t: Task) {
        if (t.done) return
        suspendCoroutine<Unit> { c -> t.waiters.add(c) }
    }

    fun run() {
        while (true) {
            while (ready.isNotEmpty()) ready.removeFirst()()
            if (timers.isEmpty()) break
            val next = timers.minWithOrNull(compareBy({ it.first }, { it.second }))!!
            timers.remove(next)
            now = next.first
            next.third()
        }
    }
}

class Channel<T>(private val sched: Scheduler, private val capacity: Int) {
    private val buffer = ArrayDeque<T>()
    private val receivers = ArrayList<Continuation<T?>>()
    private val senders = ArrayList<Pair<T, Continuation<Unit>>>()
    var closed = false
        private set

    suspend fun send(x: T) {
        if (receivers.isNotEmpty()) {
            val r = receivers.removeAt(0)
            sched.post { r.resume(x) }
            return
        }
        if (buffer.size < capacity) {
            buffer.addLast(x)
            return
        }
        suspendCoroutine<Unit> { c -> senders.add(x to c) }
    }

    suspend fun receive(): T? {
        if (buffer.isNotEmpty()) {
            val x = buffer.removeFirst()
            if (senders.isNotEmpty()) {
                val (v, c) = senders.removeAt(0)
                buffer.addLast(v)
                sched.post { c.resume(Unit) }
            }
            return x
        }
        if (senders.isNotEmpty()) {
            val (v, c) = senders.removeAt(0)
            sched.post { c.resume(Unit) }
            return v
        }
        if (closed) return null
        return suspendCoroutine { c -> receivers.add(c) }
    }

    fun close() {
        closed = true
        for (r in receivers) sched.post { r.resume(null) }
        receivers.clear()
    }
}

suspend fun <T, R> Scheduler.stage(input: Channel<T>, output: Channel<R>, transform: suspend (T) -> R) {
    while (true) {
        val x = input.receive() ?: break
        output.send(transform(x))
    }
    output.close()
}

fun main() {
    val s = Scheduler()
    val a = s.launch("A") {
        for (i in 1..3) {
            trace.add("[$now] A step $i")
            delay(10)
        }
    }
    val b = s.launch("B") {
        trace.add("[$now] B waits for A")
        join(a)
        trace.add("[$now] B resumes after A")
        yieldNow()
        trace.add("[$now] B done")
    }
    s.launch("C") {
        delay(5)
        trace.add("[$now] C tick")
        delay(20)
        trace.add("[$now] C tock")
        throw IllegalStateException("boom")
    }
    s.run()
    s.trace.forEach { println(it) }
    println("virtual time: ${s.now}, b done: ${b.done}")
    println()

    val p = Scheduler()
    val numbers = Channel<Int>(p, 2)
    val squares = Channel<Int>(p, 1)
    val labels = Channel<String>(p, 0)
    val results = ArrayList<String>()
    p.launch("producer") {
        for (i in 1..8) {
            numbers.send(i)
            trace.add("sent $i")
        }
        numbers.close()
    }
    p.launch("square") { stage(numbers, squares) { it * it } }
    p.launch("label") {
        stage(squares, labels) { v ->
            delay(1)
            if (v % 2 == 0) "even($v)" else "odd($v)"
        }
    }
    p.launch("consumer") {
        while (true) {
            val l = labels.receive() ?: break
            results.add(l)
        }
        trace.add("consumer drained at t=$now")
    }
    p.run()
    println(results)
    println(p.trace.joinToString("; "))
    println("virtual time: ${p.now}")
}
