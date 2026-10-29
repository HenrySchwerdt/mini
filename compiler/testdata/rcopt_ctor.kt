// RC elision (RcOpt): constructor parameters with the owned convention. A constructor that stores a parameter
// takes over the caller's reference (a moved local) or one the caller retained for it (anything the caller still
// needs). Exception paths: a constructor that throws, and an argument expression that throws after an earlier
// argument already exists. Run under ASan/UBSan with the leak check by EndToEndTest.

class Leaf(val name: String, val size: Int)
class Pair2(val first: Leaf, val second: Leaf?)
class Tree(val left: Tree?, val right: Tree?, val tag: String)

class Checked(val value: Leaf) {
    init {
        if (value.size < 0) throw IllegalArgumentException("negative " + value.name)
    }
}

class Delegating(val leaf: Leaf, val extra: String) {
    constructor(leaf: Leaf) : this(leaf, "default")
}

class Two(var a: Leaf, var b: Leaf) {
    init {
        val tmp = this.a
        this.a = this.b
        this.b = tmp
    }
}

fun make(depth: Int): Tree? {
    if (depth == 0) return Tree(null, null, "leaf")
    return Tree(make(depth - 1), make(depth - 1), "n" + depth)
}

fun count(t: Tree?): Int = if (t == null) 0 else 1 + count(t.left) + count(t.right)

// a borrowed parameter passed (twice) to constructors: the caller retains for each
fun mixed(l: Leaf): Pair2 = Pair2(l, l)

// last uses: both locals move into the constructor
fun lastUse(): Pair2 {
    val a = Leaf("a", 1)
    val b = Leaf("b", 2)
    return Pair2(a, b)
}

// the local is used after it was passed: it must keep its own reference
fun usedAfter(): Int {
    val a = Leaf("a", 1)
    val p = Pair2(a, null)
    return p.first.size + a.size
}

fun constructorThrows(): String {
    return try {
        val l = Leaf("neg", -1)
        val c = Checked(l)
        "ok " + c.value.name
    } catch (e: IllegalArgumentException) {
        "caught " + e.message
    }
}

fun failing(): Int {
    throw IllegalStateException("boom")
}

fun throwsInArgs(): String {
    return try {
        Pair2(Leaf("x", 1), Leaf("y", failing())).first.name
    } catch (e: IllegalStateException) {
        "caught " + e.message
    }
}

fun main() {
    println(count(make(3)))
    val l = Leaf("l", 5)
    val m = mixed(l)
    println(m.first.name + m.second!!.name + (m.first === m.second))
    println(lastUse().first.size + lastUse().second!!.size)
    println(usedAfter())
    println(constructorThrows())
    println(throwsInArgs())
    println(Delegating(Leaf("d", 9)).extra)
    println(Delegating(Leaf("d", 9), "e").leaf.size)
    val t = Two(Leaf("p", 1), Leaf("q", 2))
    println(t.a.name + t.b.name)
}
