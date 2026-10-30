package mini.backend

/*
 * Linking at the MIR level (docs/mlib.md): the MIR of the precompiled libraries and of the program are merged into one module and
 * pruned from `main`, so that only the reachable part reaches the emitters, exactly what the source-level pruning of the
 * standard library did before.
 *
 * Merging: classes and functions are identified by their C name; the first definition wins (the libraries come first, in dependency
 * order, then the program). The same generated helper (a function interface, a bridge, a reference cell class) is produced by every
 * module that needs it, so duplicates are expected and identical.
 *
 * Pruning: a function is reachable when a reachable function calls it, takes its address or constructs one of its classes. A class is
 * reachable when a reachable function mentions its type, or it is the superclass or an interface of a reachable class; it keeps its
 * vtable and itable entries, its object initialiser, and the field types it has.
 */
object MirLink {
    /** Thrown when the modules cannot be merged (the message says why). */
    class LinkError(message: String) : Exception(message)

    fun link(libs: List<MirModule>, program: MirModule): MirModule {
        val functions = ArrayList<MirFunction>()
        val classes = ArrayList<MirClass>()
        val externs = ArrayList<MirExtern>()
        val includes = ArrayList<String>()
        val links = ArrayList<String>()
        val seenF = HashSet<String>()
        val seenC = HashSet<String>()
        val seenX = HashSet<String>()
        val all = ArrayList<MirModule>(libs)
        all.add(program)
        for (m in all) {
            for (c in m.classes) if (seenC.add(c.cName)) classes.add(c)
            for (f in m.functions) if (seenF.add(f.cName)) functions.add(f)
            for (x in m.externs) if (seenX.add(x.prototypeKey)) externs.add(x)
            for (h in m.includes) if (!includes.contains(h)) includes.add(h)
            for (l in m.links) if (!links.contains(l)) links.add(l)
        }
        return MirModule(program.sourceName, functions, classes, externs, includes, links)
    }

    /** [module] without what the functions named in [roots] cannot reach. */
    fun prune(module: MirModule, roots: List<String>): MirModule = Pruner(module).run(roots)

    private class Pruner(val m: MirModule) {
        val fnByName = HashMap<String, MirFunction>()
        val clsByName = HashMap<String, MirClass>()
        val liveF = HashSet<String>()
        val liveC = HashSet<String>()
        val workF = ArrayList<MirFunction>()
        val workC = ArrayList<MirClass>()

        fun run(roots: List<String>): MirModule {
            for (f in m.functions) fnByName[f.cName] = f
            for (c in m.classes) clsByName[c.cName] = c
            for (c in m.classes) if (c.external) cls(c.cName)   // provided by the runtime: always declared
            for (r in roots) fn(r)
            while (workF.isNotEmpty() || workC.isNotEmpty()) {
                while (workF.isNotEmpty()) visitFunction(workF.removeAt(workF.size - 1))
                while (workC.isNotEmpty()) visitClass(workC.removeAt(workC.size - 1))
            }
            val fs = ArrayList<MirFunction>()
            for (f in m.functions) if (liveF.contains(f.cName)) fs.add(f)
            val cs = ArrayList<MirClass>()
            for (c in m.classes) if (liveC.contains(c.cName)) cs.add(c)
            return MirModule(m.sourceName, fs, cs, m.externs, m.includes, m.links)
        }

        fun fn(name: String?) {
            if (name == null || !liveF.add(name)) return
            val f = fnByName[name] ?: return
            workF.add(f)
        }

        fun cls(name: String?) {
            if (name == null || !liveC.add(name)) return
            val c = clsByName[name] ?: return
            workC.add(c)
        }

        fun type(t: MirType?) {
            if (t is TObject) cls(t.cName)
        }

        fun visitFunction(f: MirFunction) {
            cls(f.constructorOf)
            for (p in f.params) type(p.type)
            type(f.returnType)
            stmts(f.body)
        }

        fun visitClass(c: MirClass) {
            cls(c.superCName)
            for (i in c.interfaces) cls(i)
            for (fl in c.fields) type(fl.type)
            for (v in c.vtable) fn(v)
            for (t in c.itables) for (e in t.entries) fn(e)
            fn(c.objectCtor)
        }

        fun stmts(l: List<MirStmt>) {
            for (s in l) stmt(s)
        }

        fun stmt(s: MirStmt) {
            when (s) {
                is MirStmt.Declare -> { type(s.local.type); if (s.init != null) expr(s.init) }
                is MirStmt.Assign -> expr(s.value)
                is MirStmt.SetField -> { cls(s.owner); type(s.fieldType); expr(s.obj); expr(s.value) }
                is MirStmt.Store -> { expr(s.ptr); expr(s.value) }
                is MirStmt.Eval -> expr(s.expr)
                is MirStmt.If -> { expr(s.cond); stmts(s.thenBody); stmts(s.elseBody) }
                is MirStmt.Block -> stmts(s.body)
                is MirStmt.While -> { expr(s.cond); stmts(s.body) }
                is MirStmt.Loop -> stmts(s.body)
                is MirStmt.DoWhile -> { stmts(s.body); stmts(s.condStmts); expr(s.cond) }
                is MirStmt.ForRange -> {
                    expr(s.start); expr(s.end)
                    if (s.step != null) expr(s.step)
                    stmts(s.body)
                }
                is MirStmt.Break -> {}
                is MirStmt.Continue -> {}
                is MirStmt.Return -> if (s.value != null) expr(s.value)
                is MirStmt.Panic -> {}
                is MirStmt.Throw -> expr(s.value)
                is MirStmt.Try -> {
                    stmts(s.body)
                    for (c in s.catches) { type(c.type); stmts(c.body) }
                }
            }
        }

        fun expr(e: MirExpr) {
            type(e.type)
            when (e) {
                is MirExpr.Call -> fn(e.cName)
                is MirExpr.New -> { fn(e.ctorCName); if (e.allocator != null) expr(e.allocator) }
                is MirExpr.ObjectGet -> cls(e.classCName)
                is MirExpr.FuncAddr -> fn(e.cName)
                is MirExpr.FieldGet -> cls(e.owner)
                is MirExpr.ICall -> { cls(e.interfaceCName); for (t in e.paramTypes) type(t) }
                is MirExpr.VCall -> for (t in e.paramTypes) type(t)
                is MirExpr.Cast -> type(e.to)
                is MirExpr.IsInstance -> type(e.target)
                is MirExpr.TypeInfoOf -> type(e.target)
                is MirExpr.Box -> type(e.to)
                is MirExpr.Unbox -> type(e.to)
                is MirExpr.CCallIndirect -> for (t in e.paramTypes) type(t)
                else -> {}
            }
            for (c in mirChildren(e)) expr(c)
        }
    }
}
