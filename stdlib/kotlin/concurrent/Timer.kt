/*
 * Shape of kotlin-stdlib jvm/concurrent/Timer.kt: `timer`, `fixedRateTimer`, `timerTask` and the `Timer.schedule` extensions, on java.util.Timer.
 */
package kotlin.concurrent

import java.util.Timer
import java.util.TimerTask

public inline fun timerTask(crossinline action: TimerTask.() -> Unit): TimerTask = object : TimerTask() {
    override fun run() = action()
}

public fun Timer.schedule(delay: Long, action: TimerTask.() -> Unit): TimerTask {
    val task = timerTask(action)
    schedule(task, delay)
    return task
}

public fun Timer.schedule(delay: Long, period: Long, action: TimerTask.() -> Unit): TimerTask {
    val task = timerTask(action)
    schedule(task, delay, period)
    return task
}

public fun Timer.scheduleAtFixedRate(delay: Long, period: Long, action: TimerTask.() -> Unit): TimerTask {
    val task = timerTask(action)
    scheduleAtFixedRate(task, delay, period)
    return task
}

public fun timer(
    name: String? = null,
    daemon: Boolean = false,
    initialDelay: Long = 0L,
    period: Long,
    action: TimerTask.() -> Unit,
): Timer {
    val timer = if (name == null) Timer(daemon) else Timer(name, daemon)
    timer.schedule(initialDelay, period, action)
    return timer
}

public fun fixedRateTimer(
    name: String? = null,
    daemon: Boolean = false,
    initialDelay: Long = 0L,
    period: Long,
    action: TimerTask.() -> Unit,
): Timer {
    val timer = if (name == null) Timer(daemon) else Timer(name, daemon)
    timer.scheduleAtFixedRate(initialDelay, period, action)
    return timer
}
