class ListNode(val value: Int, var next: ListNode?)

class IntList {
    private var head: ListNode? = null
    var size = 0

    fun addFirst(v: Int) {
        head = ListNode(v, head)
        size++
    }

    fun addLast(v: Int) {
        val node = ListNode(v, null)
        val h = head
        if (h == null) {
            head = node
        } else {
            var cur: ListNode = h
            while (cur.next != null) {
                cur = cur.next!!
            }
            cur.next = node
        }
        size++
    }

    fun removeFirst(): Int {
        val h = head!!
        head = h.next
        size--
        return h.value
    }

    fun reverse() {
        var prev: ListNode? = null
        var cur = head
        while (cur != null) {
            val nxt = cur.next
            cur.next = prev
            prev = cur
            cur = nxt
        }
        head = prev
    }

    fun sum(): Int {
        var s = 0
        var cur = head
        while (cur != null) {
            s += cur.value
            cur = cur.next
        }
        return s
    }

    fun contains(v: Int): Boolean {
        var cur = head
        while (cur != null) {
            if (cur.value == v) return true
            cur = cur.next
        }
        return false
    }

    override fun toString(): String {
        var s = "["
        var cur = head
        while (cur != null) {
            s += cur.value
            if (cur.next != null) s += ", "
            cur = cur.next
        }
        return s + "]"
    }
}

class Tree(val key: Int) {
    var left: Tree? = null
    var right: Tree? = null

    fun insert(k: Int) {
        if (k < key) {
            val l = left
            if (l == null) left = Tree(k) else l.insert(k)
        } else {
            val r = right
            if (r == null) right = Tree(k) else r.insert(k)
        }
    }

    fun height(): Int {
        val lh = left?.height() ?: 0
        val rh = right?.height() ?: 0
        return 1 + (if (lh > rh) lh else rh)
    }

    fun inorder(): String {
        val l = left
        val r = right
        var s = ""
        if (l != null) s += l.inorder() + " "
        s += key
        if (r != null) s += " " + r.inorder()
        return s
    }

    fun count(): Int = 1 + (left?.count() ?: 0) + (right?.count() ?: 0)
}

fun main() {
    val list = IntList()
    for (i in 1..5) list.addLast(i * i)
    list.addFirst(0)
    println(list)
    println(list.size)
    println(list.sum())
    list.reverse()
    println(list)
    println(list.removeFirst())
    println(list)
    println(list.contains(9))
    println(list.contains(10))

    val tree = Tree(50)
    val keys = intArrayLike()
    var k = 7
    for (i in 0 until 20) {
        k = (k * 31 + 11) % 97
        tree.insert(k)
    }
    println(tree.inorder())
    println(tree.height())
    println(tree.count())
    println(keys)
}

fun intArrayLike(): Int = 0
