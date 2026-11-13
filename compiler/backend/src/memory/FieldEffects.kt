package mini.backend.memory

/**
 * Can a call of [fn] change a collection that is held in a field of some object (strict-fieldcoll)? The elements that were read from such a collection
 * before the call are loans that the call may end. Computed from the bodies, transitively over the calls of the program (and the known overriders of a
 * virtual call); a function without a body that is not a `@Mutates` function changes nothing. Over-approximate: a `@Mutates` call on a parameter, a field,
 * or a local that does not hold a fresh collection counts, the assignment of a field that holds a collection counts.
 */
internal object FieldEffects {
    private val memo = HashMap<MFun, Boolean>()
    private val directMemo = HashMap<MFun, Boolean>()

    private fun bodyOf(fn: MFun): List<MStmt>? = fn.body ?: fn.bodyProvider?.invoke()

    fun mayChange(fn: MFun): Boolean {
        memo[fn]?.let { return it }
        var result = false
        val seen = HashSet<MFun>()
        val todo = ArrayDeque<MFun>()
        todo.add(fn)
        while (todo.isNotEmpty() && !result) {
            val g = todo.removeFirst()
            if (!seen.add(g)) continue
            if (direct(g)) { result = true; break }
            val body = bodyOf(g)
            if (body != null) MemoryChecker.walk(body, enterLambdas = true, onStmt = {}, onExpr = { e ->
                if (e is MExpr.Call) todo.add(e.fn)
                if (e is MExpr.New) todo.add(e.ctor)
            })
            g.virtualTargets?.invoke()?.let { todo.addAll(it) }
        }
        memo[fn] = result
        return result
    }

    private fun fresh(e: MExpr): Boolean = when (e) {
        is MExpr.New -> true
        is MExpr.Call -> e.receiver == null && !e.fn.userCode && e.type.holds
        else -> false
    }

    private fun direct(g: MFun): Boolean {
        directMemo[g]?.let { return it }
        val body = bodyOf(g)
        var hit = false
        if (body != null) {
            val unsafe = HashSet<Int>()     // locals that may stand for a collection of some object
            val declared = HashSet<Int>()
            MemoryChecker.walk(body, enterLambdas = true, onExpr = {}, onStmt = { s ->
                when (s) {
                    is MStmt.Decl -> { declared += s.local.id; if (s.init == null || !fresh(s.init)) unsafe += s.local.id }
                    is MStmt.Assign -> if (!fresh(s.value)) unsafe += s.local.id
                    is MStmt.SetField -> if (s.field.type.holds && !s.field.type.tracked) hit = true
                    else -> {}
                }
            })
            if (!hit) MemoryChecker.walk(body, enterLambdas = true, onStmt = {}, onExpr = { e ->
                if (e is MExpr.Call && e.fn.mutates) {
                    val r = e.receiver
                    val safe = r is MExpr.Local && r.local.id in declared && r.local.id !in unsafe
                    if (!safe) hit = true
                }
            })
        }
        directMemo[g] = hit
        return hit
    }
}
