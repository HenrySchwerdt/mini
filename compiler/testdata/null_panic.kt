class Box(val label: String?)

fun find(flag: Boolean): Box? = if (flag) Box("found") else null

fun main() {
    println(find(true)!!.label)
    println("before")
    val b = find(false)
    println(b!!.label)
    println("not reached")
}
