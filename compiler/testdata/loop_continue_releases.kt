// Found by the fuzzer (tools/fuzz): a local that is last used after a `continue` was released right after its last use, and the release at
// the end of the loop body (where `continue` jumps) was dropped: the object leaked once per skipped iteration (LeakSanitizer in the leak suites).
var calls = 0

fun skip(): Boolean { calls++; return calls % 3 != 0 }

class Tag(val v: Int)

fun main() {
    for (i in 0 until 6) {
        val s = StringBuilder().append(i)
        if (skip()) continue
        println("range " + s)
    }
    for (x in listOf(1, 2, 3, 4)) {
        val t = Tag(x)
        val s = StringBuilder().append(t.v)
        if (skip()) continue
        println("list " + s + t.v)
    }
    var j = 0
    while (j++ < 5) {
        val f = { j * 2 }
        if (skip()) continue
        println("while " + f())
    }
    for (k in 10 downTo 1 step 3) {
        val s = StringBuilder().append(k)
        if (skip()) continue
        println("down " + s)
    }
    outer@ for (a in 0 until 4) {
        val s = StringBuilder().append(a)
        for (b in 0 until 3) {
            val u = StringBuilder().append(b)
            if (skip()) continue@outer
            println("nested " + s + u)
        }
    }
    val seqs = ArrayList<Int>()
    for (i in 0 until 5) {
        val sq = sequence { yield(i) }
        if (skip()) continue
        seqs.addAll(sq.toList())
    }
    println(seqs)
    println(calls)
}
