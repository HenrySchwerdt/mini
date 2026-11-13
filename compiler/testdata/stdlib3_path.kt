import java.nio.file.*
import kotlin.io.path.*

@OptIn(ExperimentalPathApi::class)
fun main() {
    val dir = Path("/tmp/mini_stdlib3_path_" + System.nanoTime())
    println(dir.exists())
    dir.createDirectories()
    println(dir.isDirectory())
    val f = dir / "a.txt"
    println(f.name + " " + f.extension + " " + f.nameWithoutExtension)
    println(f.parent == dir)
    f.writeText("alpha\nbeta\n")
    f.appendText("gamma")
    println(f.readText().replace("\n", "|"))
    println(f.readLines())
    println(f.fileSize())
    println(f.isRegularFile())
    val g = dir.resolve("sub").resolve("b.txt")
    g.parent!!.createDirectories()
    g.writeLines(listOf("x", "y"))
    println(g.readLines())
    println(dir.listDirectoryEntries().map { it.name }.sorted())
    println(Files.walk(dir).map { dir.relativize(it).toString() }.sorted().toList())
    println(Paths.get("a", "b", "c").toString())
    println(Path.of("a/b/../c").normalize())
    println(Path.of("a/b").resolveSibling("c"))
    println(Path.of("/a/b").startsWith("/a"))
    println(Files.exists(f))
    println(Files.readString(f).length)
    Files.copy(f, dir / "c.txt")
    try {
        Files.copy(f, dir / "c.txt")
    } catch (e: FileAlreadyExistsException) {
        println("exists")
    }
    Files.move(dir / "c.txt", dir / "d.txt")
    println(Files.exists(dir / "c.txt") to Files.exists(dir / "d.txt"))
    try {
        (dir / "nope").readText()
    } catch (e: NoSuchFileException) {
        println("nosuch")
    }
    println(f.deleteIfExists())
    println(f.deleteIfExists())
    println(f.toFile().exists())
    dir.deleteRecursively()
    println(dir.exists())
}
