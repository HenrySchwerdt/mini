import mini.backend.*
import mini.io.createDirectories
import mini.io.deleteRecursively
import mini.io.readTextFile
import mini.io.writeTextFile
import mini.nativedriver.run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeDriverTest {
    private fun tinyModule(): MirModule {
        val x = MirLocal(1, "x", TInt)
        val body = listOf(
            MirStmt.Declare(x, MirExpr.IntConst(41)),
            MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Binary(BinaryOp.Add, MirExpr.Local(x), MirExpr.IntConst(1))), TUnit)),
            MirStmt.Eval(MirExpr.RuntimeCall("mk_println_string", listOf(MirExpr.StringConst("h\u00e9llo \"w\"\n")), TUnit)),
        )
        return MirModule("tiny.kt", listOf(MirFunction("main", Mangling.MAIN, emptyList(), TUnit, body)))
    }

    @Test
    fun mirFileToCFile() {
        // `mini test` runs in the package directory
        val dir = "build/native-driver-test"
        deleteRecursively(dir)
        createDirectories(dir)
        writeTextFile("$dir/tiny.mir", MirWriter.write(tinyModule()))
        run(arrayOf("$dir/tiny.mir", "-o", "$dir/tiny.c"))
        assertEquals(CEmitter.emit(tinyModule()), readTextFile("$dir/tiny.c"))
        deleteRecursively(dir)
    }

    @Test
    fun usageAndIoErrorsAreReportedByExceptions() {
        assertFailsWith<IllegalArgumentException> { run(arrayOf()) }
        assertFailsWith<IllegalArgumentException> { run(arrayOf("a.mir")) }
        assertFailsWith<IllegalStateException> { run(arrayOf("/nonexistent-dir/none.mir", "-o", "/tmp/never.c")) }
    }
}
