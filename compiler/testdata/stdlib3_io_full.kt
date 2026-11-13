import java.io.*

class Res(val name: String, val failClose: Boolean = false) : Closeable {
    init { println("open $name") }
    override fun close() {
        println("close $name")
        if (failClose) throw IllegalStateException("close-$name")
    }
}

fun main() {
    val base = File("/tmp/mini_stdlib3_iofull_" + System.nanoTime())
    base.mkdirs()
    val f = File(base, "t.txt")
    f.writeText("alpha\nbeta\ngamma\n")
    println(f.readText())
    f.writeText("x", Charsets.UTF_8)
    f.appendText("yz", Charsets.UTF_8)
    println(f.readText(Charsets.UTF_8))
    f.writeBytes(byteArrayOf(1, 2, 3))
    f.appendBytes(byteArrayOf(4, 5))
    println(f.readBytes().toList())
    println(f.length())
    f.writeText("héllo wörld ✓\n")
    println(f.readText())
    println(f.length())
    println(f.readBytes().size)
    println(f.readLines(Charsets.UTF_8))
    f.writeText("a\nb\nc\nd\n")
    println(f.readLines().size)
    f.useLines { seq -> println(seq.take(2).toList()) }
    var cnt = 0
    f.forEachLine(Charsets.UTF_8) { cnt++ }
    println(cnt)
    println(f.bufferedReader().use { it.readLines() })
    println(f.bufferedReader().use { it.lineSequence().map { s -> s.uppercase() }.joinToString() })
    f.reader().use { r ->
        val buf = CharArray(3)
        val n = r.read(buf)
        println(n.toString() + " " + String(buf, 0, n).replace("\n", "|"))
    }
    f.inputStream().use { ins ->
        println(ins.read())
        println(ins.readBytes().size)
        println(ins.read())
    }
    f.outputStream().use { o ->
        o.write(65)
        o.write("BC".toByteArray())
    }
    println(f.readText())
    f.writer().use { it.write("w1") }
    println(f.readText())
    f.bufferedWriter().use { it.append("q").append('r').append("stu", 1, 3) }
    println(f.readText())
    f.printWriter().use { it.println(1); it.println("two"); it.print(3.5) }
    println(f.readLines())
    f.forEachBlock { buf, n -> println("block $n ${buf[0]}") }
    f.writeText("0123456789")
    f.forEachBlock(4) { buf, n -> println("block4 $n") }
    // use with Closeable
    Res("a").use { println("in a") }
    val r = Res("b").use { 42 }
    println(r)
    try {
        Res("c").use { throw RuntimeException("boom") }
    } catch (e: RuntimeException) {
        println(e.message)
    }
    try {
        Res("d", true).use { throw RuntimeException("boom2") }
    } catch (e: RuntimeException) {
        println(e.message + " " + e.suppressedExceptions.map { it.message })
    }
    try {
        Res("e", true).use { println("body") }
    } catch (e: IllegalStateException) {
        println(e.message)
    }
    Res("f").use { a -> Res("g").use { b -> println(a.name + b.name) } }
    val x = Res("h").use { res -> res.name.length }
    println(x)
    fun early(): Int {
        Res("i").use { return 7 }
    }
    println(early())
    (AutoCloseable { println("auto closed") }).use { println("auto") }
    // dirs
    File(base, "d1/d2").mkdirs()
    File(base, "d1/a.txt").writeText("A")
    File(base, "d1/d2/b.txt").writeText("BB")
    File(base, "d1/d2/c.kt").writeText("CCC")
    println(base.walk().filter { it.isFile }.map { it.relativeTo(base).path }.sorted().toList())
    println(base.walkTopDown().onEnter { it.name != "d2" }.map { it.name }.filter { !it.startsWith("mini_") }.sorted().toList())
    println(base.walk().filter { it.extension == "kt" }.map { it.name }.toList())
    println(base.walk().filter { it.isFile }.sumOf { it.length() })
    println(base.walkTopDown().maxDepth(2).map { it.relativeTo(base).path }.sorted().toList())
    println(base.walkBottomUp().map { it.name }.last().startsWith("mini_"))
    println(File(base, "d1").walk(FileWalkDirection.BOTTOM_UP).map { it.name }.toList().last())
    println(File(base, "d1").listFiles { fl -> fl.isFile }!!.map { it.name })
    println(File(base, "d1").listFiles { _, n -> n.endsWith("2") }!!.map { it.name })
    println(File(base, "d1").list { _, n -> n == "a.txt" }!!.toList())
    val dst = File(base, "copy")
    println(File(base, "d1").copyRecursively(dst))
    println(dst.walk().filter { it.isFile }.map { it.relativeTo(dst).path }.sorted().toList())
    println(File(dst, "d2/b.txt").readText())
    println(dst.deleteRecursively())
    println(dst.exists())
    println(File(base, "d1/a.txt").toRelativeString(base))
    println(File(base, "d1/a.txt").startsWith(base))
    println(File(base, "d1/a.txt").endsWith("a.txt"))
    println(File("a/b").toPath().toString())
    println(File("a/b/c.txt").isAbsolute)
    println(File("/a/b").isAbsolute)
    println(File("/a/b").invariantSeparatorsPath)
    println(File("/a/b/../c").canonicalPath)
    println(File(base, "d1").isDirectory.toString() + File(base, "d1").isFile)
    println(f.canRead())
    println(f.lastModified() > 0)
    println(File(base, "none").exists())
    println(File(base, "none").length())
    println(File(base, "none").listFiles())
    println(File(base, "a/b/c").mkdir())
    println(File(base, "x1").createNewFile())
    println(File(base, "x1").createNewFile())
    println(File(base, "x1").delete())
    val tmp = File.createTempFile("mini", ".tmp", base)
    println(tmp.exists().toString() + tmp.name.endsWith(".tmp"))
    tmp.writeText("t")
    println(tmp.readText())
    println(tmp.delete())
    base.deleteRecursively()
    println(base.exists())
    // in-memory
    val sw = StringWriter()
    sw.use { it.write("abc"); it.append('d') }
    println(sw)
    println(StringReader("line1\nline2").useLines { it.toList() })
    println(StringReader("a\nb").readLines())
    val br = BufferedReader(StringReader("1 2\n3 4\n"))
    println(br.readLine()!!.split(" ").map { it.toInt() }.sum())
    println(br.lineSequence().count())
    val baos = ByteArrayOutputStream()
    baos.use { it.write(byteArrayOf(72, 105)); it.writer().apply { write("!"); flush() } }
    println(baos.toString())
    println(baos.toByteArray().toList())
    println(ByteArrayInputStream("hello".toByteArray()).bufferedReader().readText())
    println(ByteArrayInputStream("hello".toByteArray()).readBytes().size)
    println("x\ny".byteInputStream().reader().readLines())
    println("héllo".byteInputStream().readBytes().size)
    try {
        File("/nonexistent_dir_zzz/a.txt").writeText("x")
    } catch (e: IOException) {
        println("io " + (e is FileNotFoundException))
    }
    try {
        File("/nonexistent_dir_zzz/a.txt").readLines()
    } catch (e: IOException) {
        println("io2")
    }
    try {
        FileInputStream("/nonexistent_dir_zzz/a.txt")
    } catch (e: FileNotFoundException) {
        println("fnf")
    }
    println(File("/nonexistent_dir_zzz").deleteRecursively())
}
