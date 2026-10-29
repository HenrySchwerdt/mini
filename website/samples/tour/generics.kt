// Generic classes and functions, bounds, and reified type parameters via `inline`.

class Box<T>(var value: T) {
    fun <R> map(f: (T) -> R): Box<R> = Box(f(value))
    override fun toString(): String = "Box($value)"
}

class Stack<T> {
    private class Node<T>(val value: T, val next: Node<T>?)

    private var top: Node<T>? = null
    var size = 0
        private set

    fun push(x: T) {
        top = Node(x, top)
        size++
    }

    fun pop(): T? {
        val t = top ?: return null
        top = t.next
        size--
        return t.value
    }
}

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T {
    var best = a
    if (b > best) best = b
    if (c > best) best = c
    return best
}

inline fun <reified T> describe(x: Any?): String =
    if (x is T) "is a ${T::class.simpleName}" else "is not a ${T::class.simpleName}"

fun main() {
    val b = Box(20)                 // Box<Int>: the Int is boxed inside the object
    println(b.map { it + 1 })
    println(b.map { "n=$it" })

    val s = Stack<String>()
    s.push("a")
    s.push("b")
    println(s.pop())
    println(s.size)
    println(s.pop())
    println(s.pop())

    println(maxOf3(3, 9, 4))
    println(maxOf3("pear", "apple", "zebra"))

    println(describe<String>("hi"))
    println(describe<Int>("hi"))
}
