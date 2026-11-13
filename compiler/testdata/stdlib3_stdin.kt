import java.io.*

fun main() {
    println(readLine())
    println(readln().length)
    val br = BufferedReader(InputStreamReader(System.`in`))
    println(br.readLine())
    val c = br.read()
    println(c.toChar())
    println(br.readLine())
    println(br.lineSequence().toList())
    println(br.readLine())
    println(System.`in`.read())
    try {
        readln()
    } catch (e: RuntimeException) {
        println("eof")
    }
    println(readlnOrNull())
}
