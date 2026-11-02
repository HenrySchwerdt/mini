import mini.backend.BinaryOp
import mini.backend.EmitError
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirClass
import mini.backend.MirField
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirWasmExport
import mini.backend.TString
import mini.backend.WasmGlue
import mini.backend.TBoolean
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TUnit
import mini.backend.TObject
import mini.backend.ThrowAnalysis
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
    fun importedThrowingNothingAndTransitiveFunctionsAreChecked() {
        val throwable = TObject("java.lang.Throwable", "MkThrowable", false)
        val importedNothing = MirFunction(
            "dep.throwNothing", "kt_dep_throwNothing", emptyList(), TNothing,
            listOf(MirStmt.Throw(MirExpr.NullConst(throwable))),
        )
        val importedInt = MirFunction(
            "dep.throwInt", "kt_dep_throwInt", emptyList(), TInt,
            listOf(MirStmt.Throw(MirExpr.NullConst(throwable))),
        )
        val importedForwarder = MirFunction(
            "dep.forward", "kt_dep_forward", emptyList(), TInt,
            listOf(MirStmt.Return(MirExpr.Call("kt_dep_throwInt", emptyList(), TInt))),
        )
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(
                MirStmt.Eval(MirExpr.Call("kt_dep_throwNothing", emptyList(), TNothing)),
                MirStmt.Eval(MirExpr.Call("kt_dep_forward", emptyList(), TInt)),
            ),
        )
        val ll = LlvmEmitter.emit(
            module(main), target = LlvmTarget.WASM32,
            externalFunctionSignatures = listOf(importedNothing, importedInt, importedForwarder),
            externalFunctions = true,
        )
        for (call in listOf("call void @kt_dep_throwNothing()", "call i32 @kt_dep_forward()")) {
            val tail = ll.substringAfter(call).substringBefore("\n}")
            assertTrue("load ptr, ptr @mk_pending_exception" in tail, "missing pending-exception check after $call:\n$tail")
        }
    }

    @Test
    fun unresolvedExternalConstructorIsConservativelyThrowing() {
        val externalType = TObject("dep.External", "kc_External", false)
        val constructor = MirExpr.New("kc_External_C", emptyList(), externalType)
        val caller = MirFunction("caller", "kt_caller", emptyList(), TUnit, listOf(MirStmt.Eval(constructor)))
        val analysis = ThrowAnalysis(module(caller)).run()
        assertTrue("kt_caller" in analysis.mayThrow)
        assertTrue(analysis.callMayThrow("kc_External_C"))
        val ll = LlvmEmitter.emit(module(caller), target = LlvmTarget.WASM32, externalFunctions = true)
        val emitted = ll.substringAfter("call ptr @kc_External_C_new()").substringBefore("\n}")
        assertTrue("load ptr, ptr @mk_pending_exception" in emitted, "missing pending-exception check after the external constructor:\n$emitted")
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
    fun stringFastPathsUseTargetLayoutAndPreserveRuntimeColdPaths() {
        val text = MirLocal(0, "text", TString)
        val index = MirLocal(1, "index", TInt)
        val at = MirFunction(
            "at", "kt_at", listOf(text, index), TChar,
            listOf(MirStmt.Return(MirExpr.RuntimeCall("mk_string_char_at", listOf(MirExpr.Local(text), MirExpr.Local(index)), TChar))),
        )
        val length = MirFunction(
            "length", "kt_length", listOf(text), TInt,
            listOf(MirStmt.Return(MirExpr.RuntimeCall("mk_string_length", listOf(MirExpr.Local(text)), TInt))),
        )

        for ((target, charsOffset) in listOf(LlvmTarget.HOST to 20, LlvmTarget.WASM32 to 16)) {
            val ll = LlvmEmitter.emit(module(at, length), target = target)
            val atBody = ll.substringAfter("define zeroext i16 @kt_at(").substringBefore("\n}")
            val lenBody = ll.substringAfter("define i32 @kt_length(").substringBefore("\n}")
            assertTrue("icmp eq ptr" in atBody, atBody)
            assertTrue("icmp uge i32" in atBody, "unsigned comparison covers negative indexes\n$atBody")
            assertTrue(", $charsOffset" in atBody, "UTF-16 data offset must follow target header + length\n$atBody")
            assertTrue("call zeroext i16 @mk_string_char_at" in atBody, "cold path must preserve runtime panic behavior\n$atBody")
            assertTrue("phi i16" in atBody, atBody)
            assertTrue("icmp eq ptr" in lenBody, lenBody)
            assertTrue("call i32 @mk_string_length" in lenBody, "null must preserve the runtime's zero result\n$lenBody")
            assertTrue("phi i32" in lenBody, lenBody)
            assertFalse("mk_pending_exception" in atBody || "mk_pending_exception" in lenBody, ll)
        }
    }

    @Test
    fun stringCharAtEvaluatesArgumentsOnceInSourceOrder() {
        val indexArg = MirFunction("indexArg", "kt_index_arg", emptyList(), TInt, listOf(MirStmt.Return(MirExpr.IntConst(0))))
        val text = MirLocal(0, "text", TString)
        val at = MirFunction(
            "at", "kt_at", listOf(text), TChar,
            listOf(MirStmt.Return(MirExpr.RuntimeCall(
                "mk_string_char_at",
                listOf(MirExpr.Local(text), MirExpr.Call("kt_index_arg", emptyList(), TInt)),
                TChar,
            ))),
        )
        val ll = LlvmEmitter.emit(module(indexArg, at))
        val body = ll.substringAfter("define zeroext i16 @kt_at(").substringBefore("\n}")
        assertTrue(body.indexOf("load ptr, ptr %v_text_0") < body.indexOf("call i32 @kt_index_arg"), body)
        assertEquals(1, occurrences(body, "call i32 @kt_index_arg"), body)
    }

    @Test
    fun nullableExternalStringCallsKeepTheirRuntimeNullSemantics() {
        val text = MirLocal(0, "text", TNString)
        val f = MirFunction(
            "length", "kt_nullable_length", listOf(text), TInt,
            listOf(MirStmt.Return(MirExpr.RuntimeCall("mk_string_length", listOf(MirExpr.Local(text)), TInt))),
        )
        val body = LlvmEmitter.emit(module(f)).substringAfter("define i32 @kt_nullable_length(").substringBefore("\n}")
        assertTrue("call i32 @mk_string_length" in body, body)
        assertFalse("icmp eq ptr" in body || "phi i32" in body, body)
    }

    @Test
    fun repeatedIsChecksUseBoundedTagHelperAndUnknownTypesFallback() {
        val baseType = TObject("Base", "kc_Base", true)
        val value = MirLocal(0, "value", baseType)
        val interfaceType = TObject("Named", "kc_Named", false, true)
        val test = MirFunction(
            "test", "kt_test", listOf(value), TBoolean,
            listOf(
                MirStmt.Eval(MirExpr.IsInstance(MirExpr.Local(value), TObject("A", "kc_A", false))),
                MirStmt.Eval(MirExpr.IsInstance(MirExpr.Local(value), TObject("B", "kc_B", false))),
                MirStmt.Return(MirExpr.IsInstance(MirExpr.Local(value), interfaceType)),
            ),
        )
        val classes = listOf(
            MirClass("Base", "kc_Base", false, null, emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("Named", "kc_Named", true, null, emptyList(), emptyList(), emptyList(), emptyList()),
            MirClass("A", "kc_A", false, "kc_Base", emptyList(), listOf("kc_Named"), listOf(null, null, null), emptyList()),
            MirClass("B", "kc_B", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("C", "kc_C", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("Foreign", "kc_Foreign", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList(), external = true),
        )
        val m = MirModule("test.kt", listOf(test), classes)

        for (target in listOf(LlvmTarget.HOST, LlvmTarget.WASM32)) {
            val ll = LlvmEmitter.emit(m, target = target)
            val helper = ll.substringAfter("define internal i32 @mkh_type_tag_kc_Base(").substringBefore("\n}")
            val body = ll.substringAfter("define zeroext i1 @kt_test(").substringBefore("\n}")
            assertTrue("icmp eq ptr" in helper && "@kc_A_ti" in helper && "@kc_B_ti" in helper && "@kc_C_ti" in helper, helper)
            assertFalse("@kc_Foreign_ti" in helper, helper)
            assertTrue("memory(none)" in ll, "the address-only classifier should be pure\n$ll")
            assertTrue("define internal i32 @mkh_type_tag_kc_Base(ptr %type) #5" in ll, ll)
            assertTrue("attributes #5 = { nounwind alwaysinline memory(none) }" in ll, ll)
            assertTrue("declare zeroext i1 @mk_is_instance(ptr, ptr) #4" in ll, ll)
            assertTrue("attributes #4 = { nounwind memory(read) }" in ll, ll)
            assertTrue("call i32 @mkh_type_tag_kc_Base" in body, body)
            assertTrue("icmp eq ptr" in body && "phi i1" in body, body)
            assertTrue("call zeroext i1 @mk_is_instance" in body, "unknown tags must retain the generic hierarchy test\n$body")
        }
    }

    @Test
    fun customMkIsInstanceSymbolKeepsRepeatedChecksOnTheOverride() {
        val valueType = TObject("Base", "kc_Base", true)
        val targets = listOf(
            TObject("A", "kc_A", false),
            TObject("B", "kc_B", false),
            TObject("C", "kc_C", false),
        )
        val value = MirLocal(0, "value", valueType)
        val caller = MirFunction(
            "caller", "kt_caller", listOf(value), TBoolean,
            listOf(
                MirStmt.Eval(MirExpr.IsInstance(MirExpr.Local(value), targets[0])),
                MirStmt.Eval(MirExpr.IsInstance(MirExpr.Local(value), targets[1])),
                MirStmt.Return(MirExpr.IsInstance(MirExpr.Local(value), targets[2])),
            ),
        )
        val runtimeObject = MirLocal(0, "object", valueType)
        val runtimeTarget = MirLocal(1, "target", TObject("TypeInfo", "kc_TypeInfo", true))
        val customRuntimeSymbol = MirFunction(
            "customIsInstance", "mk_is_instance", listOf(runtimeObject, runtimeTarget), TBoolean,
            listOf(MirStmt.Return(MirExpr.BooleanConst(false))),
        )
        val classes = listOf(
            MirClass("Base", "kc_Base", false, null, emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("A", "kc_A", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("B", "kc_B", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList()),
            MirClass("C", "kc_C", false, "kc_Base", emptyList(), emptyList(), listOf(null, null, null), emptyList()),
        )
        fun assertOverrideCalls(ll: String) {
            assertFalse("mkh_type_tag_kc_Base" in ll, ll)
            assertFalse("memory(read)" in ll, ll)
            val body = ll.substringAfter("define zeroext i1 @kt_caller(").substringBefore("\n}")
            assertEquals(3, body.split("call zeroext i1 @mk_is_instance").size - 1, body)
        }

        val localModule = MirModule("test.kt", listOf(customRuntimeSymbol, caller), classes)
        assertOverrideCalls(LlvmEmitter.emit(localModule))

        val importedModule = MirModule("test.kt", listOf(caller), classes)
        val importedLl = LlvmEmitter.emit(
            importedModule,
            externalFunctionSignatures = listOf(customRuntimeSymbol),
            externalFunctions = true,
        )
        assertOverrideCalls(importedLl)
    }

    @Test
    fun externalLibraryClassKeepsItsLayoutWithoutRedefiningItsTypeInfo() {
        val base = MirClass("dep.Base", "kc_Base", false, null, listOf(MirField("base", TInt)), emptyList(), emptyList(), emptyList())
        val child = MirClass("app.Child", "kc_Child", false, "kc_Base", listOf(MirField("child", TInt)), emptyList(), emptyList(), emptyList())
        val ll = LlvmEmitter.emit(
            MirModule("app.kt", emptyList(), listOf(base, child)),
            target = LlvmTarget.WASM32,
            externalClassNames = setOf("kc_Base"),
            exportedClassNames = setOf("kc_Child"),
        )
        assertTrue("@kc_Base_ti = external global %MkTypeInfo" in ll, ll)
        assertFalse("@kc_Base_name" in ll || "@kc_Base_vt" in ll, ll)
        assertTrue("@kc_Child_ti = constant %MkTypeInfo" in ll, ll)
        assertTrue("i32 20" in ll, "the dependency's field layout must be retained for the subclass\n$ll")
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
