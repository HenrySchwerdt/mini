import java.io.*
import java.nio.file.Files
import kotlin.io.path.*

fun main() {
    val dir = File("/tmp/mini_stdlib3_bin_" + System.nanoTime())
    dir.mkdirs()
    val f = File(dir, "all.bin")
    val all = ByteArray(256) { it.toByte() }
    f.writeBytes(all)
    println(f.length())
    println(f.readBytes().contentEquals(all))
    println(f.readBytes().sumOf { it.toInt() and 0xFF })
    f.appendBytes(byteArrayOf(0, 0, -1))
    println(f.length())
    println(f.readBytes().takeLast(4))
    // stream round trip with binary data
    FileOutputStream(f).use { o ->
        o.write(0xFF)
        o.write(byteArrayOf(0, 1, 2, -128, 127))
        o.write(byteArrayOf(9, 8, 7, 6), 1, 2)
    }
    println(f.readBytes().toList())
    FileOutputStream(f, true).use { it.write(42) }
    println(f.readBytes().toList())
    FileInputStream(f).use { i ->
        println(i.read())
        val b = ByteArray(4)
        println(i.read(b))
        println(b.toList())
        println(i.readBytes().toList())
        println(i.read())
    }
    // copy keeps the bytes
    val g = File(dir, "copy.bin")
    f.copyTo(g)
    println(g.readBytes().toList())
    println(f.inputStream().use { it.copyTo(ByteArrayOutputStream()) })
    // charsets
    val t = File(dir, "t.txt")
    t.writeText("héllo ✓", Charsets.UTF_8)
    println(t.length())
    println(t.readBytes().toList())
    t.writeText("héllo", Charsets.ISO_8859_1)
    println(t.length())
    println(t.readBytes().toList())
    println(t.readText(Charsets.ISO_8859_1))
    println(t.readText(Charsets.ISO_8859_1).length)
    t.writeText("AB", Charsets.UTF_16LE)
    println(t.readBytes().toList())
    println(t.readText(Charsets.UTF_16LE))
    t.writeText("AB", Charsets.UTF_16)
    println(t.readBytes().toList())
    println(t.readText(Charsets.UTF_16))
    t.writeText("x\ny\n", Charsets.UTF_16BE)
    println(t.readLines(Charsets.UTF_16BE))
    t.bufferedWriter(Charsets.ISO_8859_1).use { it.write("é\n"); it.write("ü") }
    println(t.readBytes().toList())
    println(t.bufferedReader(Charsets.ISO_8859_1).use { it.readLines() })
    t.writer(Charsets.UTF_16LE).use { it.write("hi") }
    println(t.readBytes().toList())
    println(t.reader(Charsets.UTF_16LE).readText())
    println(ByteArrayInputStream(byteArrayOf(-23, 65)).reader(Charsets.ISO_8859_1).readText())
    println(ByteArrayInputStream(byteArrayOf(-61, -87)).reader().readText())
    println("é".byteInputStream(Charsets.ISO_8859_1).readBytes().toList())
    println("é".byteInputStream().readBytes().toList())
    val bo = ByteArrayOutputStream()
    OutputStreamWriter(bo, Charsets.ISO_8859_1).use { it.write("éa") }
    println(bo.toByteArray().toList())
    // invalid UTF-8 decodes to replacement characters
    t.writeBytes(byteArrayOf(104, -1, 105))
    println(t.readText().length)
    println(t.readText().map { it.code })
    // nio
    val p = dir.toPath().resolve("n.bin")
    Files.write(p, byteArrayOf(0, -1, 2))
    println(Files.readAllBytes(p).toList())
    println(Files.size(p))
    p.writeBytes(byteArrayOf(5, 6))
    println(p.readBytes().toList())
    p.writeText("pä")
    println(p.readText())
    println(p.readBytes().size)
    println(Files.readAllBytes(p).decodeToString())
    // rename keeps bytes, modification time
    val h = File(dir, "h.bin")
    h.writeBytes(all)
    println(h.lastModified() > 1_000_000_000_000L)
    println(File(dir, "nothing").lastModified())
    println(h.renameTo(File(dir, "h2.bin")))
    println(File(dir, "h2.bin").readBytes().contentEquals(all))
    println(File(dir, "h2.bin").length())
    dir.deleteRecursively()
    println(dir.exists())
}
