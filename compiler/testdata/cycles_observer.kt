// Observer pattern: subjects keep their observers, observers keep their subject.
interface Observer {
    fun notify(value: Int)
}

class Subject {
    val observers = ArrayList<Observer>()
    var value = 0
    fun attach(o: Observer) { observers.add(o) }
    fun detach(o: Observer) { observers.remove(o) }
    fun set(v: Int) {
        value = v
        for (o in observers) o.notify(v)
    }
}

class Logger(val subject: Subject, val tag: String) : Observer {
    var seen = 0
    var last = 0
    init { subject.attach(this) }
    override fun notify(value: Int) { seen++; last = value + subject.value }
}

class Summer(val subject: Subject) : Observer {
    var sum = 0L
    init { subject.attach(this) }
    override fun notify(value: Int) { sum += value }
}

fun main() {
    var total = 0L
    for (round in 0 until 2000) {
        val s = Subject()
        val a = Logger(s, "a")
        val b = Logger(s, "b")
        val c = Summer(s)
        for (i in 1..5) s.set(i)
        total += a.seen + b.last + c.sum
    }
    println(total)
    val live = Subject()
    val l1 = Logger(live, "x")
    val sm = Summer(live)
    for (i in 0 until 1000) {
        val tmp = Logger(live, "t")
        live.set(i)
        live.detach(tmp)
    }
    println(l1.seen)
    println(sm.sum)
    println("done")
}
