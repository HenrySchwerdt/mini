import mini.bridge.Cli
import mini.bridge.MemoryCheck
import mini.bridge.Stdlib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * The Strict-mode memory checker on the own frontend (`--frontend=mini`, mini.lower.MemoryFacts) must give exactly the
 * diagnostics of the K2 path for the whole memory corpus (compiler/testdata/memory and memory-extra): same codes,
 * positions, messages, secondary positions and help (the rendered text), and the `.diag` expectations.
 * Also: `minic build|check` analyse all user files, not only the first (both frontends).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MiniMemoryCheckTest {
    private val corpus = File(System.getProperty("mini.memoryCorpus") ?: error("mini.memoryCorpus not set"))
    private val extra = File(System.getProperty("mini.memoryExtra") ?: error("mini.memoryExtra not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val stdlib = Cli.defaultStdlib(null, repoRoot) ?: error("Mini stdlib not found")
    private val programs = (corpus.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name } +
        extra.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name })

    private fun expected(src: File): List<String> {
        val d = File(src.parentFile, src.nameWithoutExtension + ".diag")
        return if (d.isFile) d.readLines().map { it.trim() }.filter { it.isNotEmpty() }.sorted() else emptyList()
    }

    private fun mini(srcs: List<File>): MemoryCheck.Result {
        val inputs = ArrayList<mini.lower.LowerInput>()
        for (f in srcs) inputs.add(mini.lower.LowerInput(f.path, f.readText(), false))
        for (f in stdlib) inputs.add(mini.lower.LowerInput(f.path, f.readText(), true))
        return MemoryCheck.resultOf(mini.lower.checkMemory(inputs))
    }

    @TestFactory
    fun corpusEqualsK2(): List<DynamicTest> = programs.map { src ->
        DynamicTest.dynamicTest(src.nameWithoutExtension) {
            val k2 = MemoryCheck.check(src, emptyList(), stdlib)
            val own = mini(listOf(src))
            assertEquals(expected(src), own.brief().sorted(), "diagnostics of ${src.name} (own frontend)\n" + own.render())
            assertEquals(k2.render(), own.render(), "rendered diagnostics of ${src.name} differ (K2 first)")
            assertEquals(k2.failures, own.failures, "functions skipped by the checker in ${src.name}")
        }
    }

    private fun runCli(args: List<String>): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val old = System.err
        val rc = try {
            System.setErr(PrintStream(buf, true, "UTF-8"))
            Cli.run(args, repoRoot)
        } finally {
            System.setErr(old)
        }
        return rc to buf.toString("UTF-8")
    }

    @Test
    fun allUserFilesAreChecked() {
        val dir = Files.createTempDirectory("minic-multi-").toFile().also { it.deleteOnExit() }
        val a = File(dir, "a.kt")
        val b = File(dir, "b.kt")
        a.writeText("@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\n@AllocatedBy(Heap::class)\nclass Box(val v: Int)\n\nfun main() {\n    val b = Box(1)\n    b.free()\n    leak()\n}\n")
        b.writeText("@file:MemoryMode(Mode.Strict)\nimport mini.memory.*\n\nfun leak() {\n    val c = Box(2)\n    println(c.v)\n}\n")
        for (fe in listOf("--frontend=k2", "--frontend=mini")) {
            val (rc, err) = runCli(listOf("check", a.path, b.path, fe))
            assertEquals(1, rc, "$fe: the leak in the second file must be reported\n$err")
            assertTrue("M0102" in err && "b.kt:" in err, "$fe: diagnostic of b.kt expected:\n$err")
            assertTrue("a.kt" !in err.substringBefore("b.kt"), "$fe: no diagnostic for a.kt expected:\n$err")
            val exe = File(dir, "prog")
            val (rcb, errb) = runCli(listOf("build", a.path, b.path, "-o", exe.path, fe))
            assertEquals(1, rcb, "$fe: build must fail on the memory error in b.kt\n$errb")
            assertTrue("M0102" in errb, "$fe: build output\n$errb")
        }
    }

    @Test
    fun annotationsAreResolvedByQualifiedName() {
        // a user class named like a memory annotation must not act as one; the qualified name works without an import
        val dir = Files.createTempDirectory("minic-annot-").toFile().also { it.deleteOnExit() }
        val f = File(dir, "q.kt")
        f.writeText(
            "@file:mini.memory.MemoryMode(mini.memory.Mode.Strict)\n\nclass Box(val v: Int)\n\n" +
                "fun consume(b: @mini.memory.Owned Box) {\n    println(b.v)\n}\n\nfun main() {\n    consume(Box(1))\n}\n",
        )
        val own = mini(listOf(f))
        assertTrue(own.brief().any { "M0102" in it }, "the owned parameter is leaked: ${own.brief()}\n" + own.render())
        val g = File(dir, "n.kt")
        g.writeText(
            "@file:MemoryMode(Mode.Strict)\n\nimport mini.memory.MemoryMode\nimport mini.memory.Mode\n\nannotation class Owned\n\nclass Box(val v: Int)\n\n" +
                "fun consume(b: @Owned Box) {\n    println(b.v)\n}\n\nfun main() {\n    consume(Box(1))\n}\n",
        )
        // `Owned` here is a local annotation class (no mini.memory import): not an ownership annotation
        val own2 = mini(listOf(g))
        assertTrue(own2.brief().none { "M0102" in it && it.startsWith("9:") }, "local @Owned must not count: ${own2.brief()}")
    }
}
