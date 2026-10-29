// Generic classes, interfaces and functions. Reference type arguments share one implementation;
// primitive type arguments are boxed.

class Box<T>(var value: T) {
    fun get(): T = value
    fun set(v: T) { value = v }
    fun <R> map(f: (T) -> R): Box<R> = Box(f(value))
    override fun toString(): String = "Box($value)"
}

class Pair2<A, B>(val first: A, val second: B) {
    fun swap(): Pair2<B, A> = Pair2(second, first)
    override fun toString(): String = "($first, $second)"
}

class Node<T>(val value: T, var next: Node<T>?)

class Stack<T> {
    private var top: Node<T>? = null
    var size = 0
        private set

    fun push(x: T) {
        top = Node(x, top)
        size += 1
    }

    fun pop(): T? {
        val t = top ?: return null
        top = t.next
        size -= 1
        return t.value
    }

    fun peek(): T? = top?.value
    fun isEmpty(): Boolean = top == null
}

class MyList<T> {
    private var head: Node<T>? = null
    private var tail: Node<T>? = null
    var size = 0
        private set

    fun add(x: T) {
        val n = Node(x, null)
        val t = tail
        if (t == null) head = n else t.next = n
        tail = n
        size += 1
    }

    operator fun get(i: Int): T {
        var n = head
        var k = i
        while (k > 0) { n = n!!.next; k -= 1 }
        return n!!.value
    }

    fun forEach(f: (T) -> Unit) {
        var n = head
        while (n != null) {
            f(n.value)
            n = n.next
        }
    }

    override fun toString(): String {
        var s = "["
        var first = true
        forEach {
            if (!first) s += ", "
            first = false
            s += it.toString()
        }
        return s + "]"
    }
}

fun <T, R> MyList<T>.map(f: (T) -> R): MyList<R> {
    val out = MyList<R>()
    forEach { out.add(f(it)) }
    return out
}

fun <T> MyList<T>.filter(p: (T) -> Boolean): MyList<T> {
    val out = MyList<T>()
    forEach { if (p(it)) out.add(it) }
    return out
}

fun <T, A> MyList<T>.fold(init: A, f: (A, T) -> A): A {
    var acc = init
    forEach { acc = f(acc, it) }
    return acc
}

fun <T> listOfThree(a: T, b: T, c: T): MyList<T> {
    val l = MyList<T>()
    l.add(a); l.add(b); l.add(c)
    return l
}

interface Shape { fun area(): Int }
class Rect(val w: Int, val h: Int) : Shape { override fun area(): Int = w * h }
class Square(val s: Int) : Shape { override fun area(): Int = s * s }

fun <T : Shape> totalArea(xs: MyList<T>): Int = xs.fold(0) { acc, s -> acc + s.area() }
fun <T : Shape> biggest(a: T, b: T): T = if (a.area() >= b.area()) a else b

interface Source<T> { fun next(): T }
class CountDown(var n: Int) : Source<Int> {
    override fun next(): Int { n -= 1; return n + 1 }
}
class Echo(val s: String) : Source<String> {
    override fun next(): String = s
}
fun <T> take2(src: Source<T>): MyList<T> {
    val l = MyList<T>()
    l.add(src.next())
    l.add(src.next())
    return l
}

interface Sink<T> { fun put(x: T) }
class SumSink : Sink<Int> {
    var sum = 0
    override fun put(x: Int) { sum += x }
}
fun <T> feed(sink: Sink<T>, a: T, b: T) { sink.put(a); sink.put(b) }

fun <T> identity(x: T): T = x
fun <T : Comparable<T>> maxOf2(a: T, b: T): T = if (a.compareTo(b) >= 0) a else b

fun <T> countIf(xs: MyList<T>, p: (T) -> Boolean): Int {
    var n = 0
    xs.forEach { if (p(it)) n += 1 }
    return n
}

fun main() {
    val bi = Box(41)
    bi.set(bi.get() + 1)
    println(bi.get())
    println(bi)
    val bs = Box("text")
    println(bs.get().length)
    println(bs.map { it + "!" })
    println(bs.map { it.length * 2 }.get() + 1)
    val bd = Box(1.5)
    println(bd.get() * 2.0)
    val bc = Box('x')
    println(bc.get())
    val bb = Box(true)
    println(!bb.get())
    val bn: Box<String?> = Box(null)
    println(bn.get() == null)
    bn.set("now")
    println(bn.get())

    val p = Pair2(1, "one")
    println(p)
    println(p.swap())
    println(p.swap().first + "!")
    println(p.second + 1)

    val st = Stack<Int>()
    st.push(1); st.push(2); st.push(3)
    println(st.size)
    println(st.pop())
    println(st.peek())
    println(st.pop()!! + st.pop()!!)
    println(st.pop())
    println(st.isEmpty())
    val ss = Stack<String>()
    ss.push("a"); ss.push("b")
    println(ss.pop() + ss.pop())

    val l = listOfThree(1, 2, 3)
    println(l)
    println(l.map { it * it })
    println(l.filter { it % 2 == 1 })
    println(l.map { "n" + it })
    println(l.fold(0) { a, x -> a + x })
    println(l.fold("") { a, x -> a + x })
    println(l[1] + l[2])
    println(countIf(l) { it > 1 })
    println(l.size)

    val shapes = MyList<Shape>()
    shapes.add(Rect(2, 3)); shapes.add(Square(4))
    println(totalArea(shapes))
    val rects = MyList<Rect>()
    rects.add(Rect(1, 1)); rects.add(Rect(5, 5))
    println(totalArea(rects))
    println(biggest(Rect(2, 2), Rect(1, 9)).area())
    println(biggest<Shape>(Square(3), Rect(1, 2)).area())

    println(take2(CountDown(10)))
    println(take2(Echo("hi")))
    val sink = SumSink()
    feed(sink, 20, 22)
    println(sink.sum)

    println(identity(5) + 1)
    println(identity("s") + "t")
    println(maxOf2(3, 9))
    println(maxOf2("apple", "pear"))
    println(maxOf2(2.5, 1.5))
}
