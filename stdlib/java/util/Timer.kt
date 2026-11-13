/*
 * java.util.Timer / TimerTask on a single-thread scheduled pool: the tasks of one timer run one after the other on one thread.
 */
package java.util

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

public abstract class TimerTask : Runnable {
    internal var state = 0 // 0 virgin, 1 scheduled, 2 executed (one-shot), 3 cancelled
    internal var future: ScheduledFuture<*>? = null
    internal var lastRun = 0L
    internal var periodic = false

    public abstract override fun run()

    public open fun cancel(): Boolean {
        val result = state == 1
        state = 3
        val f = future
        if (f != null) f.cancel(false)
        return result
    }

    public open fun scheduledExecutionTime(): Long = lastRun
}

public class Timer {
    private val pool: ThreadPoolExecutor
    private var cancelled = false

    public constructor() : this("Timer-0", false)
    public constructor(isDaemon: Boolean) : this("Timer-0", isDaemon)
    public constructor(name: String) : this(name, false)

    public constructor(name: String, isDaemon: Boolean) {
        val p = ThreadPoolExecutor(1, 1, 0L)
        p.threadFactory = java.util.concurrent.ThreadFactory { r ->
            val t = Thread(r, name)
            t.isDaemon = isDaemon
            t
        }
        pool = p
    }

    private fun check(task: TimerTask, delay: Long) {
        if (delay < 0L) throw IllegalArgumentException("Negative delay.")
        if (cancelled) throw IllegalStateException("Timer already cancelled.")
        if (task.state != 0) throw IllegalStateException("Task already scheduled or cancelled")
    }

    private fun runner(task: TimerTask): Runnable = Runnable {
        if (task.state != 3) {
            task.lastRun = System.currentTimeMillis()
            if (!task.periodic) task.state = 2
            task.run()
        }
    }

    public fun schedule(task: TimerTask, delay: Long) {
        check(task, delay)
        task.state = 1
        task.future = pool.schedule(runner(task), delay, TimeUnit.MILLISECONDS)
    }

    public fun schedule(task: TimerTask, delay: Long, period: Long) {
        check(task, delay)
        if (period <= 0L) throw IllegalArgumentException("Non-positive period.")
        task.state = 1
        task.periodic = true
        task.future = pool.scheduleWithFixedDelay(runner(task), delay, period, TimeUnit.MILLISECONDS)
    }

    public fun scheduleAtFixedRate(task: TimerTask, delay: Long, period: Long) {
        check(task, delay)
        if (period <= 0L) throw IllegalArgumentException("Non-positive period.")
        task.state = 1
        task.periodic = true
        task.future = pool.scheduleAtFixedRate(runner(task), delay, period, TimeUnit.MILLISECONDS)
    }

    public fun cancel() {
        cancelled = true
        pool.shutdownNow()
    }

    public fun purge(): Int = 0
}
