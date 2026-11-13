import java.io.*

fun main() {
    val n = readln().trim().toInt()
    val nums = readln().split(" ").map { it.toInt() }
    println(n.toString() + " " + nums.sum())
    val (a, b) = readln().split(",").map { it.trim() }
    println("[" + a + "][" + b + "]")
    println(readLine()!!.length)
    println(readLine()!!.isEmpty())
    val word = readlnOrNull()
    println(word?.uppercase())
    val three = List(3) { readln() }
    println(three)
    val rest = generateSequence(::readLine).take(2).toList()
    println(rest)
    val r = BufferedReader(InputStreamReader(System.`in`))
    println(r.readLine())
    println(r.lineSequence().map { it.length }.toList())
    println(readlnOrNull())
    println(readLine())
    try {
        readln()
    } catch (e: Exception) {
        println(e::class.simpleName)
    }
    println(System.`in`.read())
}
