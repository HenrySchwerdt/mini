import mini.backend.Mangling
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirWriter
import mini.backend.TInt
import mini.backend.TUnit
import mini.lower.Mlib
import mini.lower.MlibFailure
import mini.lower.validateMlibFailures
import mini.lower.UnsupportedConstruct
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MlibObjectTest {
    @Test
    fun splitLibraryStillRejectsReachableFailuresButAllowsUnusedOnes() {
        val bad = MirFunction("bad", "bad_cname", emptyList(), TInt, listOf(MirStmt.Return(MirExpr.IntConst(0))))
        val good = MirFunction("good", "good_cname", emptyList(), TInt, listOf(MirStmt.Return(MirExpr.IntConst(7))))
        val libraryMir = MirWriter.write(MirModule("broken.mlib", listOf(bad, good)))
        val library = Mlib(
            "broken", "0.0.0", "", "tag", emptyList(), emptyList(), libraryMir, "hash",
            listOf(MlibFailure("bad_cname", "test unsupported library body")),
        )
        val unreachable = MirFunction(
            "main", Mangling.MAIN, emptyList(), TUnit,
            listOf(MirStmt.Eval(MirExpr.Call("good_cname", emptyList(), TInt))),
        )
        validateMlibFailures(MirModule("app.kt", listOf(unreachable)), listOf(library))

        val reachable = MirFunction(
            "main", Mangling.MAIN, emptyList(), TUnit,
            listOf(MirStmt.Eval(MirExpr.Call("bad_cname", emptyList(), TInt))),
        )
        assertFailsWith<UnsupportedConstruct> {
            validateMlibFailures(MirModule("app.kt", listOf(reachable)), listOf(library))
        }
    }
}
