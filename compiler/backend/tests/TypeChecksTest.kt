import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.TBoolean
import mini.backend.TObject
import mini.backend.TypeChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeChecksTest {
    private fun clazz(name: String, parent: String? = null, interfaces: List<String> = emptyList(), external: Boolean = false) =
        MirClass(name, name, false, parent, emptyList(), interfaces, listOf(null, null, null), emptyList(), external = external)

    private fun iface(name: String, parents: List<String> = emptyList(), external: Boolean = false) =
        MirClass(name, name, true, null, emptyList(), parents, emptyList(), emptyList(), external = external)

    private fun checks(base: MirLocal, targets: List<TObject>): MirFunction {
        val body = ArrayList<MirStmt>()
        for (index in targets.indices) {
            val target = targets[index]
            val test = MirExpr.IsInstance(MirExpr.Local(base), target)
            if (index == targets.lastIndex) body.add(MirStmt.Return(test)) else body.add(MirStmt.Eval(test))
        }
        return MirFunction("checks", "kt_checks", listOf(base), TBoolean, body)
    }

    @Test
    fun plansRepeatedChecksForOpenBaseAndKnownClassAndInterfaceRelations() {
        val base = MirLocal(0, "value", TObject("Base", "Base", true))
        val a = TObject("A", "A", false)
        val b = TObject("B", "B", false)
        val c = TObject("C", "C", false)
        val i = TObject("I", "I", false, true)
        val j = TObject("J", "J", false, true)
        val module = MirModule(
            "checks.kt", listOf(checks(base, listOf(a, b, c, i, j))),
            listOf(clazz("Base"), iface("I", listOf("J")), iface("J"), clazz("A", "Base", listOf("I")), clazz("B", "Base"), clazz("C", "Base")),
        )

        val plan = TypeChecks.analyze(module, emptySet())

        assertEquals(1, plan.families.size)
        assertEquals(hashSetOf("Base", "A", "B", "C"), plan.families.single().tags.map { it.cName }.toHashSet())
        assertEquals(5, plan.uses.size)
        assertTrue(plan.uses.values.all { it.helperName == "mkh_type_tag_Base" })
        val interfaceTest = module.functions.single().body[3] as MirStmt.Eval
        val interfaceExpr = interfaceTest.expr as MirExpr.IsInstance
        val interfaceMask = plan.uses[interfaceExpr]!!.targetMask
        val classATagIndex = plan.families.single().tags.indexOfFirst { it.cName == "A" }
        assertTrue((interfaceMask and (1 shl classATagIndex)) != 0)
        val inheritedInterfaceExpr = (module.functions.single().body.last() as MirStmt.Return).value as MirExpr.IsInstance
        val inheritedInterfaceMask = plan.uses[inheritedInterfaceExpr]!!.targetMask
        assertTrue((inheritedInterfaceMask and (1 shl classATagIndex)) != 0)
    }

    @Test
    fun requiresThreeDistinctTargetsOnTheSameLocal() {
        val base = MirLocal(0, "value", TObject("Base", "Base", false))
        val a = TObject("A", "A", false)
        val b = TObject("B", "B", false)
        val repeatedOnly = checks(base, listOf(a, a, b))
        val module = MirModule("checks.kt", listOf(repeatedOnly), listOf(clazz("Base"), clazz("A", "Base"), clazz("B", "Base"), clazz("C", "Base")))

        val plan = TypeChecks.analyze(module, emptySet())

        assertTrue(plan.families.isEmpty())
        assertTrue(plan.uses.isEmpty())
    }

    @Test
    fun foreignChildrenRemainUnknownAndFallBack() {
        val base = MirLocal(0, "value", TObject("Base", "Base", true))
        val targets = listOf(TObject("A", "A", false), TObject("B", "B", false), TObject("C", "C", false))
        val foreign = MirClass("Foreign", "Foreign", false, "Base", emptyList(), emptyList(), listOf(null, null, null), emptyList(), external = true)
        val module = MirModule(
            "checks.kt", listOf(checks(base, targets)),
            listOf(clazz("Base"), clazz("A", "Base"), clazz("B", "Base"), clazz("C", "Base"), foreign),
        )

        val plan = TypeChecks.analyze(module, emptySet())

        assertEquals(hashSetOf("Base", "A", "B", "C"), plan.families.single().tags.map { it.cName }.toHashSet())
        assertFalse(plan.families.single().tags.any { it.cName == "Foreign" })
    }

    @Test
    fun externalParentsAndInterfacesKeepChecksGeneric() {
        val base = MirLocal(0, "value", TObject("Base", "Base", true))
        val targets = listOf(TObject("A", "A", false), TObject("B", "B", false), TObject("C", "C", false))
        val module = MirModule(
            "checks.kt", listOf(checks(base, targets)),
            listOf(clazz("Base"), clazz("A", "Base", listOf("ForeignI")), clazz("B", "Base"), clazz("C", "Base"), iface("ForeignI", external = true)),
        )

        val plan = TypeChecks.analyze(module, emptySet())

        assertTrue(plan.families.isEmpty())
        assertTrue(plan.uses.isEmpty())
    }

    @Test
    fun familyBudgetAndCyclesUseTheGenericPath() {
        val base = MirLocal(0, "value", TObject("Base", "Base", false))
        val many = (0 until 8).map { clazz("Child$it", "Base") }
        val wide = MirModule("wide.kt", listOf(checks(base, listOf(TObject("X", "X", false), TObject("Y", "Y", false), TObject("Z", "Z", false)))), listOf(clazz("Base")) + many + listOf(clazz("X", "Base"), clazz("Y", "Base"), clazz("Z", "Base")))
        val widePlan = TypeChecks.analyze(wide, emptySet())
        assertTrue(widePlan.families.isEmpty())

        val cyclic = MirModule(
            "cycle.kt", listOf(checks(base, listOf(TObject("A", "A", false), TObject("B", "B", false), TObject("C", "C", false)))),
            listOf(clazz("Base"), clazz("A", "B"), clazz("B", "A"), clazz("C", "Base")),
        )
        val cyclePlan = TypeChecks.analyze(cyclic, emptySet())
        assertTrue(cyclePlan.families.isEmpty())
    }
}
