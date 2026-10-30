import mini.backend.BinaryOp
import mini.backend.EmitError
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirWasmExport
import mini.backend.TString
import mini.backend.WasmGlue
import mini.backend.TBoolean
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TUnit
import mini.backend.llvm.LlvmEmitter
import mini.backend.llvm.LlvmTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shape of the textual IR (the programs of compiler/testdata are run through clang in jvm-bridge's LlvmEndToEndTest). */
class LlvmEmitterTest {
    private fun module(vararg f: MirFunction) = MirModule("test.kt", f.toList())

    private fun println(e: MirExpr) = MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(e), TUnit))

    @Test
    fun intArithmeticWrapsAndMainGetsItsWrapper() {
        val x = MirLocal(0, "x", TInt)
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(
                MirStmt.Declare(x, MirExpr.IntConst(Int.MAX_VALUE)),
                println(MirExpr.Binary(BinaryOp.Add, MirExpr.Local(x), MirExpr.IntConst(1))),
            ),
        )
        val ll = LlvmEmitter.emit(module(main))
        assertTrue("define void @mkh_user_main() #0 {" in ll, ll)
        assertTrue("define void @mk_main() #0 {" in ll, ll)
        assertTrue("%v_x_0 = alloca i32" in ll, ll)
        assertTrue("add i32 " in ll && "nsw" !in ll, "plain add: Kotlin Int arithmetic wraps\n$ll")
        assertTrue("declare void @mk_println_int(i32)" in ll, ll)
    }

    @Test
    fun divisionByNonConstantIsCheckedAfterTheCall() {
        val a = MirLocal(0, "a", TInt)
        val f = MirFunction(
            "f", "kt_f_AI", listOf(a), TInt,
            listOf(MirStmt.Return(MirExpr.Binary(BinaryOp.Div, MirExpr.IntConst(1), MirExpr.Local(a)))),
        )
        val ll = LlvmEmitter.emit(module(f))
        assertTrue("call i32 @mkh_div_int(i32 1, i32 " in ll, ll)
        assertTrue("load ptr, ptr @mk_pending_exception" in ll, "the exception ABI check must follow\n$ll")
        val g = MirFunction("g", "kt_g", emptyList(), TInt, listOf(MirStmt.Return(MirExpr.Binary(BinaryOp.Div, MirExpr.IntConst(1), MirExpr.IntConst(2)))))
        assertFalse("load ptr, ptr @mk_pending_exception" in LlvmEmitter.emit(module(g)).substringAfter("define i32 @kt_g"))
    }

    @Test
    fun booleansAndCharsAreZeroExtendedAtTheAbi() {
        val b = MirLocal(0, "b", TBoolean)
        val f = MirFunction("f", "kt_f_Z", listOf(b), TBoolean, listOf(MirStmt.Return(MirExpr.Local(b))))
        val ll = LlvmEmitter.emit(module(f))
        assertTrue("define zeroext i1 @kt_f_Z(i1 zeroext %in_v_b_0)" in ll, ll)
    }

    @Test
    fun doubleLiteralsAreDecimalAndSpecialValuesHex() {
        val f = MirFunction(
            "f", "kt_f", emptyList(), TDouble,
            listOf(MirStmt.Return(MirExpr.Binary(BinaryOp.Add, MirExpr.DoubleConst(0.1), MirExpr.DoubleConst(1.0e10)))),
        )
        val ll = LlvmEmitter.emit(module(f))
        assertTrue("fadd double 0.1, 1.0e10" in ll, ll)
        val g = MirFunction("g", "kt_g", emptyList(), TDouble, listOf(MirStmt.Return(MirExpr.DoubleConst(Double.NaN))))
        assertTrue("ret double 0x7FF8000000000000" in LlvmEmitter.emit(module(g)))
    }

    @Test
    fun loopsGetOneBreakBlockEach() {
        val l = MirLabel(7)
        val f = MirFunction("f", "kt_f", emptyList(), TUnit, listOf(MirStmt.Loop(l, listOf(MirStmt.Break(l)))))
        val ll = LlvmEmitter.emit(module(f))
        assertEquals(1, occurrences(ll, "\nbrk_7:"), ll)
        assertTrue("br label %brk_7" in ll)
    }

    @Test
    fun outputIsDeterministicAndTargetDependent() {
        val f = MirFunction("f", "kt_f", emptyList(), TLong, listOf(MirStmt.Return(MirExpr.LongConst(5))))
        val m = module(f)
        assertEquals(LlvmEmitter.emit(m), LlvmEmitter.emit(m))
        val wasm = LlvmEmitter.emit(m, target = LlvmTarget.WASM32)
        assertTrue("target triple = \"wasm32-unknown-wasi\"" in wasm)
        assertTrue("%MkTypeInfo = type <{ ptr, i32, ptr, ptr, ptr, i32, ptr, i32 }>" in wasm, wasm)
    }

    @Test
    fun wasmLibraryExportsItsFunctionsAndHasNoEntryPoint() {
        val a = MirLocal(0, "a", TInt)
        val s = MirLocal(1, "text", TString)
        val add = MirFunction("add", "kt_add", listOf(a, a), TInt, listOf(MirStmt.Return(MirExpr.Binary(BinaryOp.Add, MirExpr.Local(a), MirExpr.Local(a)))))
        val echo = MirFunction("echo", "kt_echo", listOf(s), TString, listOf(MirStmt.Return(MirExpr.Local(s))))
        val m = MirModule("lib.kt", listOf(add, echo), wasmExports = listOf(MirWasmExport("add", "kt_add"), MirWasmExport("echo", "kt_echo")))
        val ll = LlvmEmitter.emit(m, target = LlvmTarget.WASM32_LIB)
        assertTrue("\"wasm-export-name\"=\"add\"" in ll, ll)
        assertTrue("define ptr @mkw_echo(ptr %w0p, i32 %w0n)" in ll, ll)
        assertTrue("\"wasm-export-name\"=\"mini_init\"" in ll, ll)
        assertFalse("@mk_main" in ll, ll)
        val js = WasmGlue.mjs(m, "lib.wasm")
        assertTrue("add(a, a_) {" in js, js)
        assertTrue("echo(text) {" in js && "takeString(inst, res)" in js, js)
        val dts = WasmGlue.dts(m)
        assertTrue("add(a: number, a_: number): number" in dts, dts)
        assertTrue("echo(text: string): string" in dts, dts)
    }

    @Test
    fun wasmLibraryWithMainIsAnEmitError() {
        val main = MirFunction("main", "mk_main", emptyList(), TUnit, emptyList())
        assertFailsWith<EmitError> { LlvmEmitter.emit(MirModule("x.kt", listOf(main)), target = LlvmTarget.WASM32_LIB) }
    }

    @Test
    fun unknownCalleeIsAnEmitError() {
        val f = MirFunction("f", "kt_f", emptyList(), TUnit, listOf(MirStmt.Eval(MirExpr.Call("kt_missing", emptyList(), TUnit))))
        assertFailsWith<EmitError> { LlvmEmitter.emit(module(f)) }
    }
}
