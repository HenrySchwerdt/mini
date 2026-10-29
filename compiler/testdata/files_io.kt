import mini.io.readTextFile
import mini.io.writeTextFile

// B1: `fun main(args)` and the mini.io file API (UTF-8 round trip, error on a missing file).
// Mini-only (mini.io does not exist in the real Kotlin stdlib): the .out file is written by hand.
fun main(args: Array<String>) {
    println("args: " + args.size)
    val path = "/tmp/mini_files_io_testdata.txt"
    val text = "line one\nä€ \"quoted\"\nlast"
    writeTextFile(path, text)
    val back = readTextFile(path)
    println(back == text)
    println(back.length)
    println(back)
    try {
        readTextFile("/nonexistent-dir/none.txt")
        println("unexpected")
    } catch (e: IllegalStateException) {
        println("caught: " + e.message)
    }
}
