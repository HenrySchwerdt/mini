package sched

const val DEFAULT_PRIORITY = 5
val VERSION_TAG: String = "sched-" + 3

enum class Status(val terminal: Boolean) {
    PENDING(false), RUNNING(false), DONE(true), FAILED(true);

    fun next(ok: Boolean): Status = when (this) {
        PENDING -> RUNNING
        RUNNING -> if (ok) DONE else FAILED
        else -> this
    }
}

sealed class Outcome {
    data class Ok(val value: Int) : Outcome()
    data class Err(val reason: String) : Outcome()
    object Skipped : Outcome() {
        override fun toString() = "Skipped"
    }
}

interface Named {
    val name: String
    fun describe(): String = "<" + name + ">"
}

abstract class Task(override val name: String, val priority: Int = DEFAULT_PRIORITY) : Named, Comparable<Task> {
    var status = Status.PENDING
        internal set
    protected var attempts = 0

    protected abstract fun execute(): Outcome

    fun run(): Outcome {
        attempts++
        status = status.next(true)
        val o = execute()
        status = status.next(o is Outcome.Ok)
        return o
    }

    override fun compareTo(other: Task): Int {
        val c = other.priority.compareTo(priority)
        return if (c != 0) c else name.compareTo(other.name)
    }

    override fun toString() = describe() + "@" + priority + "/" + status
    fun attemptCount() = attempts
}

class Scheduler(private val log: (String) -> Unit = {}) {
    private val queue = ArrayList<Task>()
    private val results = LinkedHashMap<String, Outcome>()
    internal var ticks = 0

    operator fun plusAssign(t: Task) { queue.add(t) }
    operator fun get(name: String): Outcome? = results[name]

    fun runAll(): Int {
        queue.sort()
        var ok = 0
        for (t in queue) {
            ticks++
            val o = t.run()
            log(t.name + " -> " + o)
            results[t.name] = o
            if (o is Outcome.Ok) ok++
        }
        queue.clear()
        return ok
    }

    val tickCount: Int get() = ticks
    fun summary(): String = results.entries.joinToString(", ") { (k, v) ->
        k + "=" + when (v) {
            is Outcome.Ok -> "ok(" + v.value + ")"
            is Outcome.Err -> "err(" + v.reason + ")"
            Outcome.Skipped -> "skip"
        }
    }
}

fun task(name: String, priority: Int = DEFAULT_PRIORITY, body: () -> Outcome): Task = object : Task(name, priority) {
    override fun execute(): Outcome = body()
}

fun tasks(vararg names: String, priority: Int = 1, body: (String) -> Outcome): List<Task> = names.map { n -> task(n, priority) { body(n) } }

inline fun <T : Named> Iterable<T>.describeAll(sep: String = ","): String = joinToString(sep) { it.describe() }

inline fun <reified T : Task> List<Task>.countOf(): Int = count { it is T }

class Retrying(name: String, private val limit: Int, private val flaky: (Int) -> Boolean) : Task(name, 9) {
    override fun execute(): Outcome {
        while (attempts < limit) {
            if (flaky(attempts)) return Outcome.Ok(attempts)
            attempts++
        }
        return Outcome.Err("gave up after " + attempts)
    }
}
