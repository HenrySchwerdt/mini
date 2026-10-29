class Res(val id: Int) {
    var other: Res? = null
    var tag: String = "none"
}

class Holder {
    var item: Res = Res(0)
    var name: String = "start"
}

fun make(id: Int): Res = Res(id)

fun pick(a: Res, b: Res, first: Boolean): Res = if (first) a else b

fun chain(n: Int): Res {
    val head = Res(0)
    var cur = head
    for (i in 1..n) {
        val r = Res(i)
        cur.other = r
        cur = r
    }
    return head
}

fun depth(r: Res?): Int {
    var d = 0
    var c = r
    while (c != null) {
        d++
        c = c.other
    }
    return d
}

fun findFirstEven(n: Int): Res? {
    for (i in 1..n) {
        val r = Res(i)
        if (i % 2 == 0) return r
    }
    return null
}

fun loops(): Int {
    var total = 0
    var i = 0
    while (i < 50) {
        i++
        val r = Res(i)
        val s = "item " + i
        if (i % 3 == 0) continue
        if (i > 40) break
        total += r.id + s.length
    }
    do {
        val t = Res(total)
        total += t.id % 7
    } while (total % 5 != 0)
    return total
}

fun strings(n: Int): String {
    var s = ""
    for (i in 0 until n) {
        s = s + i + ","
    }
    return s
}

fun main() {
    val h = Holder()
    for (i in 1..5) {
        h.item = Res(i)
        h.name = "n" + i
    }
    println(h.item.id)
    println(h.name)

    val a = make(1)
    val b = make(2)
    println(pick(a, b, true).id)
    println(pick(a, b, false).id)
    a.other = b
    b.other = null
    a.other = a.other
    println(depth(a))
    println(depth(chain(100)))
    println(findFirstEven(9)!!.id)
    println(findFirstEven(1) == null)
    println(loops())
    println(strings(10))
    val tmp = Res(7)
    tmp.tag = "t" + tmp.id + tmp.tag
    println(tmp.tag)
    val alias = tmp
    alias.tag = "changed"
    println(tmp.tag)
    var cur: Res? = chain(3)
    while (cur != null) {
        print(cur.id)
        print(" ")
        cur = cur.other
    }
    println()
    println(make(5).id + make(6).id)
}
