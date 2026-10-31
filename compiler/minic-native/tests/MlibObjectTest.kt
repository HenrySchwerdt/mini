import mini.backend.Mangling
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirClass
import mini.backend.MirItable
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirWriter
import mini.backend.TInt
import mini.backend.TUnit
import mini.backend.TObject
import mini.lower.Mlib
import mini.lower.MlibDep
import mini.lower.MlibFailure
import mini.lower.MlibStub
import mini.lower.mlibObjectInput
import mini.lower.validateMlibFailures
import mini.lower.UnsupportedConstruct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MlibObjectTest {
    @Test
    fun splitKeepsInheritedDependencyImplementationsExternalEvenInTheSamePackage() {
        val ifaceName = "kc_shared_DValue"
        val baseName = "kc_shared_DBase"
        val derivedName = "kc_shared_DDerived"
        val valueType = TObject("shared.Base", baseName, false)
        val baseThis = MirLocal(0, "this", valueType)
        val value = MirFunction(
            "shared.Base.value", "${baseName}_Mvalue", listOf(baseThis), TInt,
            listOf(MirStmt.Return(MirExpr.IntConst(7))),
        )
        val baseClass = MirClass(
            "shared.Base", baseName, false, null, emptyList(), listOf(ifaceName),
            listOf(null, null, null, value.cName), listOf(MirItable(ifaceName, listOf(value.cName))),
        )
        val refName = "kc_mk_DRefI"
        val refType = TObject("mk.Ref", refName, false)
        val refThis = MirLocal(0, "this", refType)
        val refCtor = MirFunction(
            "mk.Ref.<init>", "${refName}_C", listOf(refThis), TUnit,
            listOf(MirStmt.Return(null)), refName,
        )
        val refClass = MirClass("mk.Ref", refName, false, null, emptyList(), emptyList(), listOf(null, null, null), emptyList(), refCtor.cName)
        val iface = MirClass("shared.Value", ifaceName, true, null, emptyList(), emptyList(), emptyList(), emptyList())
        val base = Mlib(
            "base", "0.0.0", "", "baseTag", emptyList(), listOf(MlibStub("Base.kt", "package shared\n")),
            MirWriter.write(MirModule("base.mlib", listOf(value, refCtor), listOf(iface, baseClass, refClass))), "baseHash",
        )

        val derivedType = TObject("shared.Derived", derivedName, false)
        val derivedCtor = MirFunction(
            "shared.Derived.<init>", "${derivedName}_C", listOf(MirLocal(0, "this", derivedType)), TUnit,
            listOf(MirStmt.Return(null)), derivedName,
        )
        val bridgeName = "${baseName}_Mvalue_bridge"
        val bridgeThis = MirLocal(0, "this", derivedType)
        val bridge = MirFunction(
            "synthetic.bridge", bridgeName, listOf(bridgeThis), TInt,
            listOf(MirStmt.Return(MirExpr.Call(value.cName, listOf(MirExpr.Local(bridgeThis)), TInt))),
        )
        val derivedClass = MirClass(
            "shared.Derived", derivedName, false, baseName, emptyList(), listOf(ifaceName),
            listOf(null, null, null, value.cName), listOf(MirItable(ifaceName, listOf(bridgeName))),
        )
        val duplicateRefThis = MirLocal(0, "this", refType)
        val duplicateRefCtor = MirFunction(
            "mk.Ref.<init>", refCtor.cName, listOf(duplicateRefThis), TUnit,
            listOf(MirStmt.Return(null)), refName,
        )
        val derived = Mlib(
            "derived", "0.0.0", "", "derivedTag", listOf(MlibDep("base", "baseHash")),
            listOf(MlibStub("Derived.kt", "package shared\n")),
            MirWriter.write(MirModule("derived.mlib", listOf(derivedCtor, bridge, duplicateRefCtor), listOf(derivedClass, refClass))), "derivedHash",
        )

        val baseInput = mlibObjectInput(base, emptyList())
        val derivedInput = mlibObjectInput(derived, listOf(base))
        assertTrue(value.cName in derivedInput.externalFunctions.map { it.cName })
        assertFalse(value.cName in derivedInput.module.functions.map { it.cName })
        assertTrue(refCtor.cName in derivedInput.externalFunctions.map { it.cName })
        assertFalse(refCtor.cName in derivedInput.module.functions.map { it.cName }, "shared synthetic constructor remains a dependency import")
        assertTrue(bridgeName in derivedInput.module.functions.map { it.cName }, "synthetic bridge defined by this library stays owned")
        assertTrue(derivedName in derivedInput.exportedClassNames)
        assertTrue(baseName in derivedInput.externalClassNames)
        assertTrue(refName in derivedInput.externalClassNames, "shared synthetic class is not exported again")
        var derivedLayout: MirClass? = null
        for (klass in derivedInput.module.classes) if (klass.cName == derivedName) derivedLayout = klass
        assertTrue(derivedLayout != null)
        assertEquals(value.cName, derivedLayout!!.vtable[3])
        assertEquals(bridgeName, derivedLayout!!.itables[0].entries[0])

        val linked = mini.backend.MirLink.link(listOf(baseInput.module, derivedInput.module), MirModule("app", emptyList()))
        assertEquals(1, linked.functions.count { it.cName == value.cName }, "the dependency implementation has one definition across both object inputs")
        assertEquals(1, linked.functions.count { it.cName == refCtor.cName }, "the shared synthetic constructor has one definition across both object inputs")
    }

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
