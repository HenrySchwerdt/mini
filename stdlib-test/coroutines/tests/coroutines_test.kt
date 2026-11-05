import kotlin.coroutines.*
import kotlin.test.*
import kotlin.concurrent.thread
import mini.concurrent.*
import mini.coroutines.*
import mini.coroutinestests.*

class SequenceTest {
    @Test
    fun yieldAndLaziness() {
        val log = EventLog()
        val s = sequence<Int> {
            log.add("a")
            yield(1)
            log.add("b")
            yield(2)
            log.add("c")
        }
        val it = s.iterator()
        assertEquals("", log.toString())
        assertTrue(it.hasNext())
        assertEquals(1, it.next())
        assertEquals("a", log.toString())
        assertEquals(2, it.next())
        assertFalse(it.hasNext())
        assertEquals("a,b,c", log.toString())
    }

    @Test
    fun exceptionsReachTheIterator() {
        val s = sequence<Int> {
            yield(1)
            throw IllegalStateException("boom")
        }
        val it = s.iterator()
        assertEquals(1, it.next())
        val e = assertFailsWith<IllegalStateException> { it.hasNext() }
        assertEquals("boom", e.message)
    }

    @Test
    fun operators() {
        val nat = generateSequence(1) { it + 1 }
        assertEquals(listOf(4, 16, 36), nat.filter { it % 2 == 0 }.map { it * it }.take(3).toList())
        assertEquals(listOf(1, 1, 2, 2), sequenceOf(1, 2).flatMap { sequenceOf(it, it) }.toList())
    }
}

class SuspendTest {
    @Test
    fun suspendCoroutineResumesLater() {
        var saved: Continuation<String>? = null
        var result = ""
        val body: suspend () -> String = {
            val a = suspendCoroutine<String> { c -> saved = c }
            a + "!"
        }
        body.startCoroutine(object : Continuation<String> {
            override val context: CoroutineContext get() = EmptyCoroutineContext
            override fun resumeWith(r: Result<String>) {
                result = r.getOrThrow()
            }
        })
        assertEquals("", result)
        saved!!.resume("hi")
        saved = null
        assertEquals("hi!", result)
    }
}

class CoroutineTest {
    @Test
    fun launchAsyncAndDelayOrder() {
        val log = EventLog()
        val r = runBlocking {
            val a = async { delay(30); log.add("a"); 1 }
            val b = async { delay(10); log.add("b"); 2 }
            launch { delay(20); log.add("l") }
            a.await() + b.await()
        }
        assertEquals(3, r)
        assertEquals("b,l,a", log.toString())
    }

    @Test
    fun cancellationEndsTheCoroutineAtItsSuspensionPoint() {
        val log = EventLog()
        runBlocking {
            val j = launch {
                try {
                    delay(10000)
                    log.add("not reached")
                } finally {
                    log.add("finally")
                }
            }
            delay(5)
            j.cancel()
            j.join()
            assertTrue(j.isCancelled)
        }
        assertEquals("finally", log.toString())
    }

    @Test
    fun failureOfAChildCancelsTheScope() {
        val e = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                coroutineScope {
                    launch { delay(1000) }
                    launch { throw IllegalArgumentException("child") }
                }
            }
        }
        assertEquals("child", e.message)
    }

    @Test
    fun timeout() {
        runBlocking {
            assertNull(withTimeoutOrNull(10) { delay(1000); 1 })
            assertEquals(2, withTimeoutOrNull(1000) { delay(1); 2 })
            var timedOut = false
            try {
                withTimeout(5) { delay(1000) }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
            }
            assertTrue(timedOut)
        }
    }

    @Test
    fun channelsAndMutex() {
        val sum = runBlocking {
            val ch = Channel<Int>(3)
            launch {
                for (i in 1..10) ch.send(i)
                ch.close()
            }
            var s = 0
            for (x in ch) s += x
            s
        }
        assertEquals(55, sum)
        var counter = 0
        runBlocking {
            val m = Mutex()
            val js = ArrayList<Job>()
            for (k in 0 until 4) js.add(launch { for (i in 0 until 25) { m.withLock { counter++ }; yield() } })
            for (j in js) j.join()
        }
        assertEquals(100, counter)
    }

    @Test
    fun flows() {
        val l = runBlocking {
            flow<Int> { for (i in 1..5) emit(i) }.map { it * 3 }.filter { it % 2 == 1 }.toList()
        }
        assertEquals(listOf(3, 9, 15), l)
    }

    @Test
    fun threadsAndDispatchers() {
        val n = AtomicInt(0)
        val total = runBlocking {
            val ds = ArrayList<Deferred<Int>>()
            for (i in 1..4) ds.add(async(Dispatchers.Default) { n.incrementAndGet(); i * 10 })
            var s = 0
            for (d in ds) s += d.await()
            s
        }
        assertEquals(100, total)
        assertEquals(4, n.get())
        val t = thread { n.addAndGet(10) }
        t.join()
        assertEquals(14, n.get())
    }
}
