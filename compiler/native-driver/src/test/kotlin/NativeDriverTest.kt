import mini.backend.*
import mini.nativedriver.main
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeDriverTest {
    private fun tinyModule(): MirModule {
        val x = MirLocal(1, "x", TInt)
        val body = listOf(
            MirStmt.Declare(x, MirExpr.IntConst(41)),
            MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Binary(BinaryOp.Add, MirExpr.Local(x), MirExpr.IntConst(1))), TUnit)),
            MirStmt.Eval(MirExpr.RuntimeCall("mk_println_string", listOf(MirExpr.StringConst("héllo \"w\"\n")), TUnit)),
        )
        return MirModule("tiny.kt", listOf(MirFunction("main", Mangling.MAIN, emptyList(), TUnit, body)))
    }

    @Test
    fun mirFileToCFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "native-driver-test-" + System.nanoTime()).also { it.mkdirs() }
        val mir = File(dir, "tiny.mir").also { it.writeText(MirWriter.write(tinyModule())) }
        val c = File(dir, "tiny.c")
        main(arrayOf(mir.path, "-o", c.path))
        assertEquals(CEmitter.emit(tinyModule()), c.readText())
        dir.deleteRecursively()
    }

    @Test
    fun usageAndIoErrorsAreReportedByExceptions() {
        assertFailsWith<IllegalArgumentException> { main(arrayOf()) }
        assertFailsWith<IllegalArgumentException> { main(arrayOf("a.mir")) }
        assertFailsWith<IllegalStateException> { main(arrayOf("/nonexistent-dir/none.mir", "-o", "/tmp/never.c")) }
    }
}
