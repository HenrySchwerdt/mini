import mini.bridge.Cli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** `minic build a.kt b.kt -o out`: several input files, with the stdlib compiled from source and pruned. */
class MultiFileTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime/minirt")

    @Test
    fun twoFilesAndTheStdlib() {
        val dir = Files.createTempDirectory("minic-multi-").toFile().also { it.deleteOnExit() }
        val a = File(dir, "a.kt").also {
            it.writeText("fun main() {\n    println(greet(\"mini\"))\n    println(Counter().next() + Counter().next())\n}\n")
        }
        val b = File(dir, "b.kt").also {
            it.writeText(
                "fun greet(name: String): String = \"Hello, \" + name.uppercase().padEnd(6, '!')\n" +
                    "class Counter {\n    private var n = 0\n    fun next(): Int { n++; return n }\n}\n",
            )
        }
        val exe = File(dir, "out")
        val cFile = File(dir, "out.c")
        val rc = Cli.run(
            listOf(
                "build", a.path, b.path, "-o", exe.path, "--emit-c", cFile.path,
                "--runtime-src", File(runtime, "src").path, "--runtime-include", File(runtime, "include").path, "--cflag", "-Werror",
            ),
            repoRoot,
        )
        assertEquals(0, rc)
        val proc = ProcessBuilder(exe.path).redirectErrorStream(true).start()
        assertEquals("Hello, MINI!!\n2\n", proc.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(0, proc.waitFor())
        // reachability: the stdlib is pruned, nothing of the collections is in the output
        val c = cFile.readText()
        assertEquals(false, c.contains("ArrayList"), "unreachable stdlib code must not be emitted")
        assertEquals(true, c.contains("kt_kotlin_Dtext_Duppercase_AS"))
    }
}
