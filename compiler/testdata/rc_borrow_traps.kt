// RcOpt: an alias of a field or of a collection element stays borrowed (no count) only while nothing can free what it points to.
// Events in the other arm of an `if` do not count (RcOpt.branchExclusion); events on the same path do. Every trap below frees the object an alias
// refers to while the alias is still used: a wrong elision is a use after free (the leak suites run the programs under AddressSanitizer).
class Item(val name: String)
class Holder(var item: Item?, var count: Int?)

fun elseUsesAlias(h: Holder, flag: Boolean): String {
    val a = h.item
    if (flag) {
        h.item = Item("replaced")         // frees the old item, but only on this path
        return "then"
    } else {
        return "else " + a?.name           // the alias is read on the other path: no event before it
    }
}

fun sameArmFrees(h: Holder, flag: Boolean): String {
    val a = h.item
    if (flag) {
        h.item = null                       // frees the old item ...
        return "then " + a?.name            // ... and the alias is used afterwards: it must hold a count
    }
    return "else " + a?.name
}

fun counter(h: Holder, n: Int): Int {
    var t = 0
    for (i in 0 until n) {
        val c = h.count
        if (c == null) h.count = 1 else h.count = c + 1
        t++
    }
    return t
}

fun removeInLoop(list: ArrayList<Item>): String {
    val sb = StringBuilder()
    var i = 0
    while (i < list.size) {
        val x = list[i]
        list.removeAt(i)                    // the list's reference is gone: x must keep the item alive
        sb.append(x.name).append(';')
    }
    return sb.toString()
}

fun storeAndClear(list: ArrayList<Item>, other: ArrayList<Item>): String {
    val x = list[0]
    other.add(x)                            // the element is stored: it needs its own count
    list.clear()
    return x.name + other.size
}

fun callbackClears(list: ArrayList<Item>, f: () -> Unit): String {
    val x = list[0]
    f()                                     // the callback empties the list
    return x.name
}

fun loopFreesOthers(list: ArrayList<Item>): String {
    val sb = StringBuilder()
    for (i in 0 until list.size) {
        val x = list[i]
        if (i == 0) list.clear()            // frees every element, x included, then x is read
        sb.append(x.name).append(',')
        if (list.isEmpty()) break
    }
    return sb.toString()
}

fun main() {
    println(elseUsesAlias(Holder(Item("old"), null), false))
    println(elseUsesAlias(Holder(Item("old"), null), true))
    println(sameArmFrees(Holder(Item("old"), null), true))
    println(sameArmFrees(Holder(Item("old"), null), false))
    val h = Holder(null, null)
    println(counter(h, 5)); println(h.count)
    val l1 = ArrayList<Item>(); l1.add(Item("a")); l1.add(Item("b")); l1.add(Item("c"))
    println(removeInLoop(l1))
    val l2 = ArrayList<Item>(); l2.add(Item("keep"))
    val o = ArrayList<Item>()
    println(storeAndClear(l2, o))
    val l3 = ArrayList<Item>(); l3.add(Item("cb"))
    println(callbackClears(l3) { l3.clear() })
    val l4 = ArrayList<Item>(); l4.add(Item("p")); l4.add(Item("q"))
    println(loopFreesOthers(l4))
}
