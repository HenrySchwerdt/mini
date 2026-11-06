// list.addAll(list) appends the elements that were there (the loop did not stop: the iterator kept seeing the growing list).
fun main() {
    val l = mutableListOf(1, 2, 3)
    println(l.addAll(l))
    println(l)
    val s = ArrayList<String>()
    s.add("a")
    s.addAll(s)
    s.addAll(s)
    println(s)
    val e = ArrayList<Int>()
    println(e.addAll(e))
    val sub = mutableListOf(1, 2, 3, 4)
    sub.addAll(sub.subList(1, 3))
    println(sub)
}
