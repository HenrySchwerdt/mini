import java.io.*

fun main() {
    val base = File("/tmp/mini_stdlib3_io_" + System.nanoTime())
    println(base.exists())
    println(base.mkdirs())
    println(base.isDirectory)
    val f = File(base, "a.txt")
    println(f.name + " " + f.extension + " " + f.nameWithoutExtension)
    println(f.parentFile == base)
    println(f.exists())
    f.writeText("one\ntwo\r\nthree\n\nfive")
    println(f.exists() && f.isFile)
    println(f.length())
    println(f.readText().length)
    println(f.readLines())
    f.appendText("\nsix")
    println(f.readLines().size)
    var n = 0
    f.forEachLine { n += it.length }
    println(n)
    println(f.useLines { it.filter { l -> l.isNotEmpty() }.map { l -> l.uppercase() }.toList() })
    f.bufferedReader().use { r ->
        println(r.readLine())
        println(r.readLine())
        println(r.readText().length)
        println(r.readLine())
    }
    f.bufferedWriter().use { w ->
        w.write("x")
        w.newLine()
        w.write("y")
    }
    println(f.readLines())
    f.printWriter().use { p ->
        p.println("hello")
        p.print(42)
        p.printf("%5.2f|%s%n", 3.14159, "z")
    }
    println(f.readText().replace("\n", "|"))
    FileWriter(f, true).use { it.write("++") }
    println(f.readText().replace("\n", "|"))
    val sub = File(base, "sub/deeper")
    println(sub.mkdirs())
    File(sub, "b.kt").writeText("b")
    File(base, "sub/c.txt").writeText("c")
    println(base.list()!!.sorted())
    println(base.listFiles()!!.map { it.name }.sorted())
    println(base.walkTopDown().map { it.relativeTo(base).path }.sorted().toList())
    println(base.walkTopDown().first() == base)
    println(base.walkBottomUp().map { it.relativeTo(base).path }.sorted().toList())
    println(base.walkBottomUp().last() == base)
    println(base.walk().filter { it.isFile }.map { it.name }.sorted().toList())
    println(base.walk().maxDepth(1).count())
    println(File("a/b/../c").normalize().path)
    println(File("a//b/").path)
    println(File("/").parent)
    println(File("x").parent)
    println(File("/x").parent)
    println(File("a/b").resolve("c").path)
    println(File("a/b").resolve("/c").path)
    println(File("a/b/c.tar.gz").extension)
    println(File("a/b/c.tar.gz").nameWithoutExtension)
    println(File("a/b/c").extension.isEmpty())
    val copy = File(base, "copy.txt")
    f.copyTo(copy)
    println(copy.readText() == f.readText())
    try {
        f.copyTo(copy)
    } catch (e: IOException) {
        println("exists")
    }
    f.copyTo(copy, overwrite = true)
    println(f.renameTo(File(base, "moved.txt")))
    println(f.exists())
    try {
        File(base, "nope.txt").readText()
    } catch (e: FileNotFoundException) {
        println("fnf")
    }
    try {
        FileReader(File(base, "nope.txt"))
    } catch (e: IOException) {
        println("fnf2")
    }
    println(File(base, "nope.txt").delete())
    println(base.delete())
    println(base.deleteRecursively())
    println(base.exists())
    val sw = StringWriter()
    PrintWriter(sw).use { it.println("a"); it.print(1); it.print('c') }
    println(sw.toString().replace("\n", "|"))
    val br = BufferedReader(StringReader("l1\nl2\r\nl3\rl4"))
    println(br.readLines())
    println(StringReader("abc").readText())
    println("a b\nc".reader().buffered().lineSequence().toList())
    val bos = ByteArrayOutputStream()
    PrintStream(bos).use { it.println("hello"); it.printf("%03d", 7) }
    println(bos.size())
    println(bos.toString().replace("\n", "|"))
    println(ByteArrayInputStream(byteArrayOf(104, 105)).reader().readText())
    System.out.println("out")
    System.out.print('c')
    System.out.printf("%d-%s%n", 5, "x")
    System.err.println("to stderr")
    System.out.flush()
}
