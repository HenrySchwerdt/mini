import java.util.concurrent.*

class Job(val name: String, private val atNanos: Long) : Delayed {
    override fun getDelay(unit: TimeUnit): Long = unit.convert(atNanos - System.nanoTime(), TimeUnit.NANOSECONDS)
    override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
}

fun main() {
    val dq = LinkedBlockingDeque<Int>(3)
    dq.addFirst(2); dq.addLast(3); dq.offerFirst(1)
    println(dq)
    println(dq.offerLast(4))
    println(dq.offerLast(4, 50, TimeUnit.MILLISECONDS))
    println("${dq.peekFirst()} ${dq.peekLast()} ${dq.remainingCapacity()}")
    println("${dq.pollFirst()} ${dq.pollLast()} ${dq.size}")
    dq.push(9)
    println("${dq.pop()} ${dq.takeLast()}")
    println(dq.pollFirst(10, TimeUnit.MILLISECONDS) as Any?)
    val producer = Thread { for (i in 1..3) dq.putLast(i * 10) }
    producer.start()
    println("${dq.takeFirst()} ${dq.takeFirst()} ${dq.takeFirst()}")
    producer.join()

    val cd = ConcurrentLinkedDeque<String>()
    cd.addFirst("b"); cd.addFirst("a"); cd.addLast("c")
    println("$cd ${cd.pollLast()} ${cd.peekFirst()}")
    println(cd.descendingIterator().asSequence().toList())

    val pq = PriorityBlockingQueue<Int>(11, Comparator.reverseOrder())
    for (x in listOf(5, 1, 9, 3)) pq.put(x)
    println("${pq.take()} ${pq.take()} ${pq.size} ${pq.peek()}")
    val drained = ArrayList<Int>()
    pq.drainTo(drained)
    println(drained)
    println(pq.poll(10, TimeUnit.MILLISECONDS) as Any?)
    val taker = Thread { println("taken ${pq.take()}") }
    taker.start()
    Thread.sleep(50)
    pq.put(42)
    taker.join()

    // a SynchronousQueue hands over from one thread to the other
    val sq = SynchronousQueue<String>()
    println(sq.offer("nobody waits"))
    println(sq.poll() as Any?)
    val sender = Thread { sq.put("hello"); sq.put("world") }
    sender.start()
    println(sq.take() + " " + sq.take())
    sender.join()
    println(sq.poll(20, TimeUnit.MILLISECONDS) as Any?)
    println(sq.offer("late", 20, TimeUnit.MILLISECONDS))
    println(sq.size)

    // a delay queue yields elements in the order of their delays
    val now = System.nanoTime()
    val delays = DelayQueue<Job>()
    delays.add(Job("third", now + 300_000_000L))
    delays.add(Job("first", now + 100_000_000L))
    delays.add(Job("second", now + 200_000_000L))
    println(delays.poll() as Any?)
    println("${delays.take().name} ${delays.take().name} ${delays.take().name}")
    println(delays.size)

    val cow = CopyOnWriteArraySet<Int>()
    println(cow.add(1))
    println(cow.add(1))
    cow.add(2)
    println(cow)
    println(cow.remove(1))
    println(cow.size)

    val sl = ConcurrentSkipListMap<String, Int>()
    val ts = (0 until 4).map { t -> Thread { for (i in 0 until 50) sl.merge("k" + (i % 10), 1) { a, b -> a + b } } }
    ts.forEach { it.start() }
    ts.forEach { it.join() }
    println(sl)
    println("${sl.firstKey()} ${sl.lastKey()} ${sl.floorKey("k35")} ${sl.ceilingKey("k35")} ${sl.higherKey("k9")}")
    println(sl.headMap("k3"))
    println(sl.pollFirstEntry() as Any?)
    println(sl.size)
    val ss = ConcurrentSkipListSet<Int>(listOf(5, 1, 3))
    println("$ss ${ss.first()} ${ss.last()} ${ss.higher(3)} ${ss.lower(3)}")
    println(ss.pollFirst() as Any?)
    println(ss.descendingSet())
}
