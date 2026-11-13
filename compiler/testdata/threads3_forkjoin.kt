import java.util.concurrent.*

class Fib(val n: Int) : RecursiveTask<Long>() {
    override fun compute(): Long {
        if (n <= 12) return seq(n)
        val a = Fib(n - 1)
        a.fork()
        val b = Fib(n - 2).compute()
        return a.join() + b
    }
    private fun seq(k: Int): Long = if (k < 2) k.toLong() else seq(k - 1) + seq(k - 2)
}

class SumTask(val data: IntArray, val from: Int, val to: Int) : RecursiveTask<Long>() {
    override fun compute(): Long {
        if (to - from <= 1000) {
            var s = 0L
            for (i in from until to) s += data[i]
            return s
        }
        val mid = (from + to) / 2
        val l = SumTask(data, from, mid)
        val r = SumTask(data, mid, to)
        invokeAll(l, r)
        return l.join() + r.join()
    }
}

class Fill(val data: IntArray, val from: Int, val to: Int) : RecursiveAction() {
    override fun compute() {
        if (to - from <= 500) {
            for (i in from until to) data[i] = i * 2
        } else {
            val mid = (from + to) / 2
            invokeAll(Fill(data, from, mid), Fill(data, mid, to))
        }
    }
}

class Failing : RecursiveTask<Int>() {
    override fun compute(): Int = throw IllegalStateException("fork-join failure")
}

fun main() {
    val pool = ForkJoinPool(4)
    println(pool.invoke(Fib(25)))
    val data = IntArray(100000) { it }
    println(pool.invoke(SumTask(data, 0, data.size)))
    val filled = IntArray(5000)
    pool.invoke(Fill(filled, 0, filled.size))
    println("${filled[0]} ${filled[4999]} ${filled.sum()}")
    println(pool.parallelism)
    val f = pool.submit(SumTask(data, 0, 10000))
    println(f.get())
    println(f.isDone)
    try {
        pool.invoke(Failing())
    } catch (e: IllegalStateException) {
        println("caught " + e.javaClass.simpleName)
    }
    val failing = Failing()
    try {
        pool.submit(failing).get()
    } catch (e: ExecutionException) {
        println("execution: " + e.cause!!.javaClass.simpleName)
    }
    println(failing.isCompletedAbnormally)
    pool.shutdown()
    println(pool.awaitTermination(5, TimeUnit.SECONDS))

    println(ForkJoinPool.commonPool().invoke(Fib(20)))
    println(Fib(18).invoke())
    println(ForkJoinPool.getCommonPoolParallelism() >= 1)

    val cf = CompletableFuture.supplyAsync { 20 }.thenApply { it + 1 }.thenCombine(CompletableFuture.supplyAsync { 21 }) { a, b -> a + b }
    println(cf.get())
    val ws = Executors.newWorkStealingPool(2)
    println(ws.submit(Callable { 7 }).get())
    ws.shutdown()
}
