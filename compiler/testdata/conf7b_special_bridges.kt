// the JVM-style special bridges: contains / indexOf / remove / get of a collection or map implementation whose
// parameter is narrower than Any? answer "not found" for an argument of another type (or null)
interface Element { val isContained: Boolean }
object ContainedElement : Element { override val isContained = true }
object NotContainedElement : Element { override val isContained = false }

internal class MySet<E : Element> : Set<E> {
    override fun contains(element: E): Boolean = element.isContained
    override fun equals(other: Any?): Boolean = TODO()
    override fun hashCode(): Int = TODO()
    override fun toString(): String = "MySet"
    override val size: Int get() = TODO()
    override fun isEmpty(): Boolean = TODO()
    override fun containsAll(elements: Collection<E>): Boolean = TODO()
    override fun iterator(): Iterator<E> = TODO()
}

private object NotEmptyList : MutableList<Any> {
    override fun contains(element: Any): Boolean = true
    override fun indexOf(element: Any): Int = 0
    override fun lastIndexOf(element: Any): Int = 0
    override fun remove(element: Any): Boolean = true
    override val size: Int get() = throw UnsupportedOperationException()
    override fun containsAll(elements: Collection<Any>): Boolean = elements.isEmpty()
    override fun isEmpty(): Boolean = throw UnsupportedOperationException()
    override fun get(index: Int): Any = throw UnsupportedOperationException()
    override fun add(element: Any): Boolean = throw UnsupportedOperationException()
    override fun addAll(elements: Collection<Any>): Boolean = throw UnsupportedOperationException()
    override fun addAll(index: Int, elements: Collection<Any>): Boolean = throw UnsupportedOperationException()
    override fun removeAll(elements: Collection<Any>): Boolean = throw UnsupportedOperationException()
    override fun retainAll(elements: Collection<Any>): Boolean = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()
    override fun set(index: Int, element: Any): Any = throw UnsupportedOperationException()
    override fun add(index: Int, element: Any): Unit = throw UnsupportedOperationException()
    override fun removeAt(index: Int): Any = throw UnsupportedOperationException()
    override fun listIterator(): MutableListIterator<Any> = throw UnsupportedOperationException()
    override fun listIterator(index: Int): MutableListIterator<Any> = throw UnsupportedOperationException()
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<Any> = throw UnsupportedOperationException()
    override fun iterator(): MutableIterator<Any> = throw UnsupportedOperationException()
}

private object NotEmptyMap : MutableMap<Any, Int> {
    override fun containsKey(key: Any): Boolean = true
    override fun containsValue(value: Int): Boolean = true
    override fun get(key: Any): Int = 1
    override fun remove(key: Any): Int = 1
    override val size: Int get() = 0
    override fun isEmpty(): Boolean = true
    override fun put(key: Any, value: Int): Int? = throw UnsupportedOperationException()
    override fun putAll(from: Map<out Any, Int>): Unit = throw UnsupportedOperationException()
    override fun clear(): Unit = throw UnsupportedOperationException()
    override val entries: MutableSet<MutableMap.MutableEntry<Any, Int>> get() = null!!
    override val keys: MutableSet<Any> get() = null!!
    override val values: MutableCollection<Int> get() = null!!
}

fun main() {
    val s: Set<Any> = MySet<Element>()
    println(s.contains(Any()))
    println(s.contains(NotContainedElement))
    println(s.contains(ContainedElement))

    val n = NotEmptyList as MutableList<Any?>
    println(n.contains(null))
    println(n.indexOf(null))
    println(n.lastIndexOf(null))
    println(n.contains(""))
    println(n.indexOf(""))
    println(n.remove(null))
    println(n.remove(""))

    val m = NotEmptyMap as MutableMap<Any?, Any?>
    println(m.get(null))
    println(m.containsKey(null))
    println(m.containsValue(null))
    println(m.remove(null))
    println(m.get(1))
    println(m.containsKey(""))
    println(m.containsValue(3))
    println(m.containsValue("x"))
    println(m.remove(""))
}
