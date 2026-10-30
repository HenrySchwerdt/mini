import mini.backend.CEmitter
import mini.backend.MirReader
import mini.backend.MirWriter
import mini.bridge.Cli
import mini.bridge.Stdlib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files

/**
 * Bootstrap B1: for every program of compiler/testdata, MIR -> text -> MIR -> C must be byte-identical
 * to MIR -> C, and the text format must be a fixed point (write(read(write(m))) == write(m)).
 */
class MirSerialTest {
    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))

    @TestFactory
    fun roundTrip(): List<DynamicTest> {
        val sources = testdata.listFiles { f -> f.isFile && f.extension == "kt" && !File(f.parentFile, f.nameWithoutExtension + ".nok2").exists() }!!.sortedBy { it.name }
        assertTrue(sources.size >= 10)
        val stdlib = Stdlib.sources(Stdlib.findDir(null, repoRoot)!!)
        return sources.map { src ->
            DynamicTest.dynamicTest(src.nameWithoutExtension) {
                val module = try {
                    Cli.frontendToMir(listOf(src), stdlib, emptyList(), false)
                } catch (e: Exception) {
                    // programs that must fail to compile (.cerr) have no MIR
                    if (File(src.parentFile, src.nameWithoutExtension + ".cerr").isFile) return@dynamicTest
                    throw e
                }
                val text = MirWriter.write(module)
                val back = MirReader.read(text)
                assertEquals(CEmitter.emit(module), CEmitter.emit(back), "C differs after the MIR round trip of ${src.name}")
                assertEquals(text, MirWriter.write(back), "text format is not a fixed point for ${src.name}")
                // the native driver (running on the JVM here): .mir file in, .c file out, same C as the direct path
                val dir = Files.createTempDirectory("native-driver-").toFile()
                try {
                    val mirFile = File(dir, "p.mir").also { it.writeText(text) }
                    val cFile = File(dir, "p.c")
                    mini.nativedriver.main(arrayOf(mirFile.path, "-o", cFile.path))
                    assertEquals(CEmitter.emit(module), cFile.readText(), "native-driver output differs for ${src.name}")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
    }
}
