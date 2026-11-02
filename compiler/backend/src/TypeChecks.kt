package mini.backend

/** One fast path for a repeated `is` target on a single local of a concrete static base type. */
internal class TypeCheckUse(val helperName: String, val targetMask: Int)

/** The exact local type-info globals the classifier is allowed to recognize. */
internal class TypeCheckFamily(val baseCName: String, val helperName: String, val tags: List<MirClass>)

internal class TypeCheckPlan(
    val families: List<TypeCheckFamily>,
    val uses: Map<MirExpr.IsInstance, TypeCheckUse>,
) {
    companion object {
        val EMPTY = TypeCheckPlan(emptyList(), emptyMap())
    }
}

/** Plans bounded classifiers from the complete class graph available to this compilation. */
internal object TypeChecks {
    private const val MAX_TAGS = 8
    private const val MIN_TARGETS = 3

    private data class GroupKey(val localId: Int, val baseCName: String)
    private class Group(val local: MirLocal, val base: TObject) {
        val uses = ArrayList<MirExpr.IsInstance>()
    }

    fun analyze(module: MirModule, externalClassNames: Set<String>): TypeCheckPlan {
        val classes = HashMap<String, MirClass>()
        val duplicateNames = HashSet<String>()
        for (c in module.classes) {
            if (classes.put(c.cName, c) != null) duplicateNames.add(c.cName)
        }
        val graph = ClassGraph(classes, duplicateNames, externalClassNames)
        val groupsByFunction = ArrayList<Pair<MirFunction, Map<GroupKey, Group>>>()
        val usedBases = HashSet<String>()

        for (function in module.functions) {
            val groups = HashMap<GroupKey, Group>()
            val tests = ArrayList<MirExpr.IsInstance>()
            scanStatements(function.body, tests)
            for (test in tests) {
                val localExpr = test.operand as? MirExpr.Local
                if (localExpr != null) {
                    val local = localExpr.local
                    val base = local.type as? TObject
                    if (base != null && !base.isInterface) {
                        val key = GroupKey(local.id, base.cName)
                        var group = groups[key]
                        if (group == null) {
                            group = Group(local, base)
                            groups.put(key, group)
                        }
                        group.uses.add(test)
                    }
                }
            }
            for ((_, group) in groups) {
                val targets = HashSet<String>()
                for (use in group.uses) {
                    val target = use.target as? TObject
                    if (target != null) targets.add(target.cName)
                }
                if (targets.size >= MIN_TARGETS) {
                    usedBases.add(group.base.cName)
                }
            }
            groupsByFunction.add(Pair(function, groups))
        }

        val families = HashMap<String, TypeCheckFamily>()
        for (baseCName in usedBases.sorted()) {
            val base = graph.localClass(baseCName) ?: continue
            val descendants = graph.knownDescendants(baseCName)
            if (descendants.size > MAX_TAGS) continue
            val tags = descendants.filter { graph.closedTypeGraph(it.cName) }.sortedBy { it.cName }
            if (tags.isEmpty() || tags.size > MAX_TAGS) continue
            val helper = "mkh_type_tag_$baseCName"
            families[baseCName] = TypeCheckFamily(baseCName, helper, tags)
        }

        val uses = HashMap<MirExpr.IsInstance, TypeCheckUse>()
        for ((_, groups) in groupsByFunction) {
            for ((_, group) in groups) {
                val family = families[group.base.cName] ?: continue
                val targets = group.uses.mapNotNull { it.target as? TObject }.distinctBy { it.cName }
                    .filter {
                        graph.localType(it.cName) != null && graph.closedTypeGraph(it.cName) &&
                            it.isInterface == classes[it.cName]?.isInterface
                    }
                val masks = HashMap<String, Int>()
                for (target in targets) {
                    var mask = 0
                    for ((index, candidate) in family.tags.withIndex()) {
                        if (graph.knownSubtype(candidate, target.cName)) mask = mask or (1 shl index)
                    }
                    masks[target.cName] = mask
                }
                if (masks.size < MIN_TARGETS) continue
                for (test in group.uses) {
                    val target = test.target as? TObject ?: continue
                    val mask = masks[target.cName] ?: continue
                    uses[test] = TypeCheckUse(family.helperName, mask)
                }
            }
        }
        if (uses.isEmpty()) return TypeCheckPlan.EMPTY
        val activeHelpers = HashSet<String>()
        for (use in uses.values) activeHelpers.add(use.helperName)
        return TypeCheckPlan(families.values.filter { it.helperName in activeHelpers }.sortedBy { it.baseCName }, uses)
    }

    private class ClassGraph(
        private val classes: Map<String, MirClass>,
        private val duplicates: Set<String>,
        private val externalClassNames: Set<String>,
    ) {
        private val closedMemo = HashMap<String, Boolean>()
        private val childrenByParent = HashMap<String, MutableList<MirClass>>()

        init {
            for (c in classes.values) {
                if (localClass(c.cName) == null) continue
                val parent = c.superCName ?: continue
                var children = childrenByParent[parent]
                if (children == null) {
                    children = ArrayList()
                    childrenByParent.put(parent, children)
                }
                children.add(c)
            }
        }

        fun localType(cName: String): MirClass? {
            val c = classes[cName] ?: return null
            if (cName in duplicates || c.external || cName in externalClassNames) return null
            return c
        }

        fun localClass(cName: String): MirClass? = localType(cName)?.takeUnless { it.isInterface }

        fun knownDescendants(baseCName: String): List<MirClass> {
            if (localClass(baseCName) == null || !closedTypeGraph(baseCName)) return emptyList()
            val result = ArrayList<MirClass>()
            val pending = ArrayList<MirClass>()
            val seen = HashSet<String>()
            pending.add(classes[baseCName]!!)
            var index = 0
            while (index < pending.size) {
                if (pending.size > MAX_TAGS) return pending
                val c = pending[index]
                index++
                if (!seen.add(c.cName)) continue
                if (closedTypeGraph(c.cName)) result.add(c)
                val children = childrenByParent[c.cName]
                if (children != null) {
                    for (child in children) {
                        if (pending.size > MAX_TAGS) return pending
                        pending.add(child)
                    }
                }
            }
            return result
        }

        fun closedTypeGraph(start: String): Boolean {
            val cached = closedMemo[start]
            if (cached != null) return cached
            return closedTypeGraph(start, HashSet(), HashSet())
        }

        private fun closedTypeGraph(name: String, visiting: MutableSet<String>, done: MutableSet<String>): Boolean {
            if (name in done) return closedMemo[name] == true
            val cached = closedMemo[name]
            if (cached != null) return cached
            if (!visiting.add(name)) return false
            val c = classes[name]
            if (c == null || name in duplicates || c.external || name in externalClassNames) {
                visiting.remove(name)
                done.add(name)
                closedMemo[name] = false
                return false
            }
            var ok = true
            val parent = c.superCName
            if (parent != null) {
                val parentClass = classes[parent]
                if (parentClass == null || parentClass.isInterface || !closedTypeGraph(parent, visiting, done)) ok = false
            }
            for (ifaceName in c.interfaces) {
                val iface = classes[ifaceName]
                if (iface == null || !iface.isInterface || !closedTypeGraph(ifaceName, visiting, done)) ok = false
            }
            visiting.remove(name)
            done.add(name)
            closedMemo[name] = ok
            return ok
        }

        fun knownSubtype(candidate: MirClass, targetCName: String): Boolean {
            if (!closedTypeGraph(candidate.cName) || !closedTypeGraph(targetCName)) return false
            val target = classes[targetCName] ?: return false
            return hasType(candidate, target, HashSet())
        }

        /** Follow superclass and interface edges explicitly, including interface inheritance. */
        private fun hasType(candidate: MirClass, target: MirClass, seen: MutableSet<String>): Boolean {
            if (candidate.cName == target.cName) return true
            if (!seen.add(candidate.cName)) return false
            for (ifaceName in candidate.interfaces) {
                val iface = classes[ifaceName] ?: continue
                if (hasType(iface, target, seen)) return true
            }
            val parent = candidate.superCName?.let { classes[it] }
            return parent != null && hasType(parent, target, seen)
        }
    }

    private fun scanStatements(statements: List<MirStmt>, tests: MutableList<MirExpr.IsInstance>) {
        for (stmt in statements) {
            when (stmt) {
                is MirStmt.Declare -> stmt.init?.let { scanExpr(it, tests) }
                is MirStmt.Assign -> scanExpr(stmt.value, tests)
                is MirStmt.SetField -> { scanExpr(stmt.obj, tests); scanExpr(stmt.value, tests) }
                is MirStmt.Store -> { scanExpr(stmt.ptr, tests); scanExpr(stmt.value, tests) }
                is MirStmt.Eval -> scanExpr(stmt.expr, tests)
                is MirStmt.If -> { scanExpr(stmt.cond, tests); scanStatements(stmt.thenBody, tests); scanStatements(stmt.elseBody, tests) }
                is MirStmt.Block -> scanStatements(stmt.body, tests)
                is MirStmt.While -> { scanExpr(stmt.cond, tests); scanStatements(stmt.body, tests) }
                is MirStmt.Loop -> scanStatements(stmt.body, tests)
                is MirStmt.DoWhile -> { scanStatements(stmt.body, tests); scanStatements(stmt.condStmts, tests); scanExpr(stmt.cond, tests) }
                is MirStmt.ForRange -> {
                    scanExpr(stmt.start, tests); scanExpr(stmt.end, tests); stmt.step?.let { scanExpr(it, tests) }; scanStatements(stmt.body, tests)
                }
                is MirStmt.Return -> stmt.value?.let { scanExpr(it, tests) }
                is MirStmt.Throw -> scanExpr(stmt.value, tests)
                is MirStmt.Try -> {
                    scanStatements(stmt.body, tests)
                    for (clause in stmt.catches) scanStatements(clause.body, tests)
                }
                is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> Unit
            }
        }
    }

    private fun scanExpr(expr: MirExpr, tests: MutableList<MirExpr.IsInstance>) {
        if (expr is MirExpr.IsInstance) tests.add(expr)
        for (child in mirChildren(expr)) scanExpr(child, tests)
    }
}
