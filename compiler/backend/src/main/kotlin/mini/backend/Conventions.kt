package mini.backend

/*
 * Calling conventions beyond "borrowed arguments, owned result" for the hot paths of the stdlib
 * (docs/benchmarks.md D2, compiler/README.md "Conventions").
 *
 *  - `@kotlin.internal.TakesOwnership` on a parameter: the callee *owns* the reference it receives (it stores it, hands it
 *    on or releases it at its exits); the caller passes a reference it owns (a moved local or a fresh retain). The
 *    constructors' inferred owned parameters (RcOpt rule 3) are the same convention.
 *  - `@kotlin.internal.ReturnsBorrowed` on a function: the result is *not* +1: it is a reference some other owner (a
 *    parameter, a field of a parameter) keeps alive; the caller treats it like a field read.
 *
 * A virtual or interface call reaches any implementation of its dispatch slot, so there is one convention per
 * *group*: the functions in the same vtable slot / itable entry. A function can sit in several groups (a slot of an
 * abstract class and an interface entry), so the groups are merged transitively (union-find over functions and
 * groups). The owned parameters of a group are the union of the annotations (an owned parameter is always safe to
 * accept: the callee releases what it does not consume). The borrowed result is a *request*: it holds only when every
 * function of the merged group honours it (all `return`s are borrowed expressions, see RcAnalyzer.conformsBorrowed);
 * one function that returns a fresh object drops the convention for the whole group, so the program stays correct
 * ("one ABI per slot").
 */
class Conventions private constructor(
    private val module: MirModule,
    private val parent: HashMap<String, String>,
    private val groups: DispatchGroups,
    private val enabled: Boolean,
) {
    /** DSU root -> members that are functions (cNames) with a body. */
    private val members = HashMap<String, ArrayList<MirFunction>>()
    private val rootMask = HashMap<String, BooleanArray>()
    private val requested = HashSet<String>()
    private val borrowedRoots = HashSet<String>()
    private val byName = HashMap<String, MirFunction>()

    /** Why a requested borrowed result was dropped (function qualified names), for `mem-report`. */
    val dropped = ArrayList<String>()

    private fun find(x: String): String {
        var r = x
        while (true) {
            val p = parent[r] ?: return r
            if (p == r) return r
            r = p
        }
    }

    private fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    private fun build() {
        for (f in module.functions) byName[f.cName] = f
        for ((key, fns) in groups.groupKeys()) for (n in fns) union(key, n)
        for (f in module.functions) {
            val r = find(f.cName)
            members.getOrPut(r) { ArrayList() } += f
            if (f.ownsParams.isNotEmpty()) {
                var m = rootMask[r]
                if (m == null || m.size < f.params.size) {
                    val n = BooleanArray(f.params.size)
                    if (m != null) for (i in m.indices) n[i] = m[i]
                    m = n
                    rootMask[r] = m
                }
                for (i in f.ownsParams) if (i < m.size) m[i] = true
            }
            if (f.returnsBorrowed && f.returnType.isRef) requested += r
        }
        borrowedRoots += requested
    }

    // ---- queries -------------------------------------------------------------------------------------------

    val any: Boolean get() = enabled && (rootMask.isNotEmpty() || borrowedRoots.isNotEmpty())

    /** Ownership mask (over the parameters of the callee including `this`) of function [cName], null = all borrowed. */
    fun maskOfFunction(cName: String): BooleanArray? = if (!enabled) null else rootMask[find(cName)]

    fun borrowedFunction(cName: String): Boolean = enabled && borrowedRoots.isNotEmpty() && find(cName) in borrowedRoots

    /** Owned-parameter mask of a virtual / interface call (the callee's parameters including the receiver), or null. */
    fun maskOfDynamic(e: MirExpr): BooleanArray? {
        if (!enabled || rootMask.isEmpty()) return null
        val key = dynKey(e) ?: return null
        return rootMask[find(key)]
    }

    /** True when the result of the call-like expression [e] is a borrowed reference (callee convention, or a borrowed native). */
    fun borrowedResult(e: MirExpr): Boolean {
        if (!enabled) return false
        return when (e) {
            is MirExpr.RuntimeCall -> NativeConventions.resultBorrowed(e.cName)
            is MirExpr.Call -> borrowedFunction(e.cName)
            is MirExpr.VCall, is MirExpr.ICall -> {
                if (borrowedRoots.isEmpty()) return false
                val key = dynKey(e) ?: return false
                find(key) in borrowedRoots
            }
            else -> false
        }
    }

    private fun dynKey(e: MirExpr): String? = groups.key(e)

    /** Human readable list of what the conventions ended up as (for `mem-report` and tests). */
    fun summary(): List<String> {
        val out = ArrayList<String>()
        for (r in borrowedRoots.sorted()) {
            val fns = members[r] ?: continue
            out += "borrowed result: " + fns.map { it.qualifiedName }.sorted().joinToString(", ")
        }
        for (r in requested.sorted()) if (r !in borrowedRoots) out += "borrowed result dropped for the group of " + (members[r]?.firstOrNull()?.qualifiedName ?: r)
        for (d in dropped) out += "  reason: $d"
        val seen = HashSet<String>()
        for ((r, m) in rootMask.entries.sortedBy { it.key }) {
            if (!seen.add(r)) continue
            val fns = members[r] ?: continue
            out += "owned parameters " + m.withIndex().filter { it.value }.joinToString(",") { it.index.toString() } + ": " + fns.map { it.qualifiedName }.sorted().joinToString(", ")
        }
        return out
    }

    // ---- verification of the borrowed requests ----------------------------------------------------------------

    /** The functions of the borrowed groups that must honour the convention, with their group root. */
    fun requestedGroups(): List<Pair<String, List<MirFunction>>> =
        borrowedRoots.sorted().map { it to (members[it] ?: ArrayList<MirFunction>()) }

    fun drop(root: String, because: String) {
        if (borrowedRoots.remove(root)) dropped += because
    }

    companion object {
        /** Conventions of [module]; with [enabled] false nothing is requested (baseline code generation). */
        fun build(module: MirModule, enabled: Boolean): Conventions {
            val c = Conventions(module, HashMap(), DispatchGroups(module), enabled)
            if (enabled) c.build()
            return c
        }
    }
}

/**
 * The dispatch groups of a module: which functions can a virtual call (class slot, identified by the class that
 * introduces the slot) or an interface call (interface + method index) reach? Shared by [Conventions] and [RcEffects].
 */
class DispatchGroups(module: MirModule) {
    val classes = HashMap<String, MirClass>()
    private val vMembers = HashMap<String, HashSet<String>>()
    private val iMembers = HashMap<String, HashSet<String>>()
    /** The overrides of `toString` / `equals` / `hashCode` (slots 0..2) of every class: what `mk_to_string` & co. can reach. */
    val anySlot = Array(3) { HashSet<String>() }

    init {
        for (c in module.classes) classes[c.cName] = c
        for (c in module.classes) {
            if (c.isInterface) continue
            for ((k, n) in c.vtable.withIndex()) {
                if (n == null) continue
                if (k < 3) anySlot[k].add(n) else vMembers.getOrPut(vKeyOf(c, k)) { HashSet() }.add(n)
            }
            for (t in c.itables) for ((i, n) in t.entries.withIndex()) if (n != null) iMembers.getOrPut(iKey(t.interfaceCName, i)) { HashSet() }.add(n)
        }
    }

    private fun nslots(c: MirClass): Int = if (c.isInterface) 0 else c.vtable.size

    private fun introducer(c: MirClass, slot: Int): MirClass {
        var cur = c
        while (true) {
            val sn = cur.superCName ?: return cur
            val s = classes[sn] ?: return cur
            if (nslots(s) <= slot) return cur
            cur = s
        }
    }

    private fun vKeyOf(c: MirClass, slot: Int) = "V:" + introducer(c, slot).cName + "#" + slot

    fun vKey(recv: MirType, slot: Int): String? {
        val c = (recv as? TObject)?.let { classes[it.cName] } ?: return null
        if (c.isInterface || slot >= nslots(c)) return null
        return vKeyOf(c, slot)
    }

    fun iKey(iface: String, index: Int) = "I:$iface#$index"

    /** Key of the dispatch group of a virtual / interface call, or null when unknown. */
    fun key(e: MirExpr): String? = when (e) {
        is MirExpr.VCall -> if (e.slot < 3) null else vKey(e.receiver.type, e.slot)
        is MirExpr.ICall -> iKey(e.interfaceCName, e.index)
        else -> null
    }

    /** The functions the call [e] can reach (empty set when the call has no known targets). */
    fun targets(e: MirExpr): Set<String> {
        val k = key(e)
        if (k == null) {
            if (e is MirExpr.VCall && e.slot < 3) return anySlot[e.slot]
            return emptySet()
        }
        return (if (e is MirExpr.VCall) vMembers[k] else iMembers[k]) ?: emptySet()
    }

    fun groupKeys(): List<Pair<String, Set<String>>> = vMembers.entries.map { it.key to it.value as Set<String> } + iMembers.entries.map { it.key to it.value as Set<String> }

    fun allTargets(): Sequence<Set<String>> = vMembers.values.asSequence() + iMembers.values.asSequence() + anySlot.asSequence()
}
