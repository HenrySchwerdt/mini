import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Like StdlibJvmTest.intrinsicsAreInSync, for the B1 intrinsics (stdlib/mini/internal/B1Intrinsics.kt). */
class B1IntrinsicsSyncTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))

    @Test
    fun b1IntrinsicsAreInSync() {
        val native = File(repoRoot, "stdlib/mini/internal/B1Intrinsics.kt").readText()
        val jvm = File(repoRoot, "stdlib-test/jvm/mini/internal/B1Intrinsics.kt").readText()
        val header = File(repoRoot, "runtime/include/minirt.h").readText()
        val b1 = header.substringAfter("/* ---- B1:").substringBefore("/* ---- end B1")
        val decl = Regex("@CSymbol\\(\"(\\w+)\"\\)\\s*public external fun (\\w+)\\(([^)]*)\\)(?::\\s*(\\w+\\??))?")
        val all = decl.findAll(native).toList()
        assertTrue(all.size >= 2)
        for (m in all) {
            val (symbol, name, params, ret) = m.destructured
            val jvmDecl = Regex("fun $name\\(([^)]*)\\)(?::\\s*(\\w+\\??))?").find(jvm)
            assertTrue(jvmDecl != null, "JVM implementation of $name missing")
            assertEquals(params.trim(), jvmDecl!!.groupValues[1].trim(), "parameters of $name")
            assertEquals(ret, jvmDecl.groupValues[2], "result of $name")
            assertTrue(Regex("\\b$symbol\\(").containsMatchIn(b1), "$symbol is not declared in the B1 block of minirt.h")
        }
    }
}
