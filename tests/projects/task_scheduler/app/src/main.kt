import sched.*

class Counting(name: String) : Task(name, 7) {
    override fun execute(): Outcome = listOf(1).map { attempts * 10 * it }.first().let { Outcome.Ok(it) }
}

object Greeter : Named {
    override val name = "greeter"
}

fun main() {
    println(VERSION_TAG + " " + DEFAULT_PRIORITY)
    val lines = ArrayList<String>()
    val s = Scheduler { lines.add(it) }
    s += task("compile", 8) { Outcome.Ok(1) }
    s += task("lint") { Outcome.Err("style") }
    s += task("docs", priority = 2) { Outcome.Skipped }
    s += Counting("count")
    s += Retrying("fetch", 5) { it >= 3 }
    for (t in tasks("a", "b", priority = 3) { n -> if (n == "a") Outcome.Ok(n.length) else Outcome.Err("bad " + n) }) s += t
    println(s.runAll())
    lines.forEach { println(it) }
    println(s.summary())
    println(s.tickCount)
    println(s["compile"])
    println(s["nothing"])

    val ts = listOf(task("x", 1) { Outcome.Skipped }, Counting("y"), Retrying("z", 1) { false })
    println(ts.sorted())
    println(ts.describeAll("|"))
    println(ts.countOf<Counting>())
    println(ts.countOf<Retrying>())
    println(listOf(Greeter).describeAll())
    println(Status.values().filter { it.terminal })
    println(Status.PENDING.next(true).next(false))
    val r = Retrying("w", 3) { it == 1 }
    val out = r.run()
    println(out)
    println(r.status)
    val msg = when (out) {
        is Outcome.Ok -> "value " + out.value
        is Outcome.Err -> out.reason
        Outcome.Skipped -> "skipped"
    }
    println(msg)
    println(Outcome.Ok(3) == Outcome.Ok(3))
}
