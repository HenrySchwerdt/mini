package mini.lower

import mini.backend.MirLink
import mini.backend.MirModule
import mini.backend.MirExpr
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.MirFunction
import mini.backend.TObject
import mini.backend.ANY_CNAME
import mini.backend.MirReader
import mini.backend.MirWriter
import mini.backend.Mangling
import mini.backend.MIR_FORMAT_VERSION
import mini.backend.RcOpt
import mini.backend.Tracking
import mini.backend.memory.MemoryReport
import mini.frontend.StubWriter

/*
 * Precompiled libraries (`.mlib`, docs/mlib.md): the API stubs and the target-independent MIR of a set of Kotlin sources, in one
 * text file. A program compiled against libraries parses only the stubs (declarations, no code) and links the library MIR into its
 * own at the MIR level, so a library is analysed and lowered once, not on every build.
 */

/** The version of the container format; a reader refuses every other one. */
const val MLIB_FORMAT_VERSION = 1

const val MLIB_MAGIC = "mini-mlib"

/** A library problem (unreadable, wrong version, hash mismatch, missing dependency); [message] is ready to print. */
class MlibError(message: String) : Exception(message)

class MlibDep(val name: String, val hash: String)

class MlibStub(val path: String, val text: String)

/** A function of the library that the backend cannot lower (a construct it does not support); a program that reaches it fails with [message]. */
class MlibFailure(val cName: String, val message: String)

class Mlib(
    val name: String,
    val version: String,
    /** Identity of the compiler that built it: the MIR it contains is only valid for that compiler (and runtime ABI). */
    val compilerId: String,
    /** Makes the generated names of this library unique among the linked modules. */
    val tag: String,
    val deps: List<MlibDep>,
    val stubs: List<MlibStub>,
    /** The unpruned MIR in the MirWriter text format. */
    val mir: String,
    val hash: String,
    val failures: List<MlibFailure> = emptyList(),
)

/** The MIR inputs needed to emit one separately compiled wasm library object. */
class MlibObjectInput(
    val module: MirModule,
    val externalClassNames: Set<String>,
    val exportedClassNames: Set<String>,
    val externalFunctions: List<mini.backend.MirFunction>,
    val externalFunctionOwnedMasks: Map<String, BooleanArray>,
    val externalBorrowedFunctions: Set<String>,
)

/** Effective RC calling conventions of the complete ordered library set. */
fun effectiveMlibAbi(libs: List<Mlib>): Pair<Map<String, BooleanArray>, Set<String>> {
    if (libs.isEmpty()) return Pair(emptyMap(), emptySet())
    val modules = libs.map { lib ->
        try { MirReader.read(lib.mir) }
        catch (e: Exception) { throw MlibError("library '${lib.name}': damaged MIR section (${e.message})") }
    }
    val combined = MirLink.link(modules, MirModule("<library-abi>", emptyList()))
    val plan = RcOpt.analyze(combined, Tracking(combined, true))
    val owned = LinkedHashMap<String, BooleanArray>()
    val borrowed = LinkedHashSet<String>()
    for (f in combined.functions) {
        plan.ownedMask(f.cName)?.let { mask -> owned[f.cName] = BooleanArray(mask.size) { mask[it] } }
        if (plan.returnsBorrowed(f.cName)) borrowed.add(f.cName)
    }
    return Pair(owned, borrowed)
}

/** Split one library into owned definitions and dependency declarations while preserving all class layouts. */
fun mlibObjectInput(library: Mlib, dependencies: List<Mlib>): MlibObjectInput {
    fun read(lib: Mlib): MirModule = try { MirReader.read(lib.mir) }
        catch (e: Exception) { throw MlibError("library '${lib.name}': damaged MIR section (${e.message})") }
    val dependencyModules = dependencies.map { read(it) }
    val libraryModule = read(library)
    val full = MirLink.link(dependencyModules, libraryModule)
    // The library's MIR is the definition boundary. Package names are not provenance:
    // a dependent library may extend a class from the same package, and its dispatch tables
    // then contain function definitions from an earlier library.
    val libraryClassNames = libraryModule.classes.map { it.cName }.toSet()
    val libraryFunctionNames = libraryModule.functions.map { it.cName }.toSet()
    // Lowering creates helper classes (for example mk.Ref for a mutable captured local) whose
    // qualified names do not belong to the source package. Those classes still belong in the
    // object that contains the function which constructs them. Track class references through
    // owned MIR before classifying the remaining classes as imports.
    val classesByName = full.classes.associateBy { it.cName }
    val dependencyFunctions = dependencyModules.flatMap { it.functions }
    val dependencyClassNames = dependencyModules.flatMap { it.classes }.map { it.cName }.toHashSet()
    dependencyClassNames.addAll(referencedClasses(dependencyFunctions))
    // The frontend also inserts builtin classes (notably kotlin.Function$arity) into every
    // module. They are definitions in this MIR but belong to stdlib when it is a dependency.
    val ownClasses = full.classes.filter {
        it.cName in libraryClassNames && !it.external && it.cName !in dependencyClassNames
    }.map { it.cName }.toHashSet()
    val ownFunctions = full.functions.filter { it.cName in libraryFunctionNames }.map { it.cName }.toHashSet()
    val constructors = full.functions.filter { it.constructorOf != null }.associateBy { it.constructorOf!! }
    val ownedBodyFunctions = full.functions.filter { it.cName in ownFunctions }
    ownClasses.addAll(referencedClasses(ownedBodyFunctions).filter {
        classesByName.containsKey(it) && !classesByName[it]!!.external && it !in dependencyClassNames
    })
    // Bridge methods can have synthetic names, so include table entries only when the
    // library's own MIR actually defines them. Inherited dependency implementations remain imports.
    var added = true
    while (added) {
        val before = ownClasses.size + ownFunctions.size
        for (c in full.classes) if (c.cName in ownClasses) {
            c.objectCtor?.takeIf { it in libraryFunctionNames }?.let { ownFunctions.add(it) }
            c.vtable.filterNotNull().filter { it in libraryFunctionNames }.forEach { ownFunctions.add(it) }
            for (itable in c.itables) {
                itable.entries.filterNotNull().filter { it in libraryFunctionNames }.forEach { ownFunctions.add(it) }
            }
        }
        for (className in ownClasses) constructors[className]?.takeIf { it.cName in libraryFunctionNames }?.let { ownFunctions.add(it.cName) }
        val newlyOwnedFunctions = full.functions.filter { it.cName in ownFunctions }
        val newlyReferenced = referencedClasses(newlyOwnedFunctions).filter {
            val c = classesByName[it]
            c != null && !c.external && it !in dependencyClassNames && it !in ownClasses
        }
        ownClasses.addAll(newlyReferenced)
        added = before != ownClasses.size + ownFunctions.size
    }
    val functions = full.functions.filter { it.cName in ownFunctions }
    if (functions.isEmpty()) throw MlibError("library '${library.name}' has no function definitions in its MIR")
    // Every non-owned class in the linked MIR comes from a dependency or the runtime contract.
    // Mark it imported even when the serialized dependency class itself was not tagged external;
    // otherwise both the dependency object and this object define its typeinfo/constructor.
    val externalClasses = full.classes.filter { it.cName !in ownClasses }.map { it.cName }.toHashSet()
    val module = MirModule(full.sourceName, functions, full.classes, full.externs, full.includes, full.links)
    val abi = effectiveMlibAbi(dependencies)
    return MlibObjectInput(module, externalClasses, ownClasses, full.functions.filter { it.cName !in ownFunctions }, abi.first, abi.second)
}

/** Class symbols mentioned in function signatures and bodies. Keeps compiler-generated classes with their producer. */
private fun referencedClasses(functions: List<MirFunction>): Set<String> {
    val out = LinkedHashSet<String>()
    fun type(t: MirType?) {
        if (t is TObject && t.cName != ANY_CNAME) out.add(t.cName)
    }
    lateinit var expr: (MirExpr) -> Unit
    lateinit var stmt: (MirStmt) -> Unit
    val exprs: (List<MirExpr>) -> Unit = { xs -> xs.forEach(expr) }
    val stmts: (List<MirStmt>) -> Unit = { xs -> xs.forEach(stmt) }
    expr = { e ->
        type(e.type)
        when (e) {
            is MirExpr.Binary -> { expr(e.left); expr(e.right) }
            is MirExpr.Unary -> expr(e.operand)
            is MirExpr.Convert -> expr(e.operand)
            is MirExpr.Call -> e.args.forEach(expr)
            is MirExpr.RuntimeCall -> e.args.forEach(expr)
            is MirExpr.Conditional -> { expr(e.cond); expr(e.thenValue); expr(e.elseValue) }
            is MirExpr.Box -> expr(e.operand)
            is MirExpr.Unbox -> expr(e.operand)
            is MirExpr.Cast -> { type(e.to); expr(e.operand) }
            is MirExpr.IsInstance -> { type(e.target); expr(e.operand) }
            is MirExpr.NotNull -> expr(e.operand)
            is MirExpr.FieldGet -> { out.add(e.owner); expr(e.obj) }
            is MirExpr.VCall -> { e.paramTypes.forEach(::type); expr(e.receiver); e.args.forEach(expr) }
            is MirExpr.ICall -> { e.paramTypes.forEach(::type); expr(e.receiver); e.args.forEach(expr) }
            is MirExpr.New -> { exprs(e.args); e.allocator?.let(expr) }
            is MirExpr.ObjectGet -> out.add(e.classCName)
            is MirExpr.StringLength -> expr(e.operand)
            is MirExpr.Load -> expr(e.ptr)
            is MirExpr.PtrAdd -> { expr(e.ptr); expr(e.bytes) }
            is MirExpr.CCall -> { e.extern.params.forEach(::type); exprs(e.args) }
            is MirExpr.CCallIndirect -> { e.paramTypes.forEach(::type); expr(e.callee); exprs(e.args) }
            is MirExpr.TypeInfoOf -> type(e.target)
            is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst,
            is MirExpr.CharConst, is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local,
            is MirExpr.NullConst, is MirExpr.Unreachable, is MirExpr.CaughtException, is MirExpr.FuncAddr -> Unit
        }
    }
    stmt = { s ->
        when (s) {
            is MirStmt.Declare -> { type(s.local.type); s.init?.let(expr) }
            is MirStmt.Assign -> { type(s.local.type); expr(s.value) }
            is MirStmt.SetField -> { out.add(s.owner); type(s.fieldType); expr(s.obj); expr(s.value) }
            is MirStmt.Store -> { expr(s.ptr); expr(s.value) }
            is MirStmt.Eval -> expr(s.expr)
            is MirStmt.If -> { expr(s.cond); stmts(s.thenBody); stmts(s.elseBody) }
            is MirStmt.Block -> stmts(s.body)
            is MirStmt.While -> { expr(s.cond); stmts(s.body) }
            is MirStmt.Loop -> stmts(s.body)
            is MirStmt.DoWhile -> { stmts(s.body); stmts(s.condStmts); expr(s.cond) }
            is MirStmt.ForRange -> { type(s.variable.type); expr(s.start); expr(s.end); s.step?.let(expr); stmts(s.body) }
            is MirStmt.Return -> s.value?.let(expr)
            is MirStmt.Throw -> expr(s.value)
            is MirStmt.Try -> { stmts(s.body); for (c in s.catches) { type(c.type); stmts(c.body) } }
            is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> Unit
        }
    }
    for (f in functions) {
        type(f.returnType)
        f.params.forEach { type(it.type) }
        stmts(f.body)
    }
    return out
}


/** FNV-1a (64 bit) of the pieces, as 16 hex digits. Not cryptographic: it detects damage and keys caches. */
class MlibHasher {
    private var h: Long = -3750763034362895579L   // 0xcbf29ce484222325
    fun add(s: String): MlibHasher {
        for (i in 0 until s.length) {
            h = (h xor s[i].code.toLong()) * 1099511628211L
        }
        h = (h xor 0x1fL) * 1099511628211L   // separator, so ("ab","c") differs from ("a","bc")
        return this
    }
    fun hex(): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder()
        var i = 60
        while (i >= 0) {
            sb.append(digits[((h ushr i) and 15L).toInt()])
            i -= 4
        }
        return sb.toString()
    }
}

object MlibFormat {
    fun tagOf(name: String): String = "L" + MlibHasher().add(name).hex().substring(0, 8) + "_"

    fun hashOf(name: String, version: String, compilerId: String, deps: List<MlibDep>, stubs: List<MlibStub>, mir: String, failures: List<MlibFailure>): String {
        val h = MlibHasher().add(name).add(version).add(compilerId).add(MLIB_FORMAT_VERSION.toString())
        for (d in deps) h.add(d.name).add(d.hash)
        for (s in stubs) h.add(s.path).add(s.text)
        for (f in failures) h.add(f.cName).add(f.message)
        h.add(mir)
        return h.hex()
    }

    fun write(m: Mlib): String {
        val sb = StringBuilder()
        sb.append(MLIB_MAGIC).append(' ').append(MLIB_FORMAT_VERSION).append('\n')
        sb.append("mir-format ").append(MIR_FORMAT_VERSION).append('\n')
        sb.append("compiler ").append(m.compilerId).append('\n')
        sb.append("name ").append(m.name).append('\n')
        sb.append("version ").append(m.version).append('\n')
        sb.append("tag ").append(m.tag).append('\n')
        sb.append("hash ").append(m.hash).append('\n')
        for (d in m.deps) sb.append("dep ").append(d.name).append(' ').append(d.hash).append('\n')
        for (f in m.failures) sb.append("fail ").append(f.cName).append(' ').append(f.message).append('\n')
        for (s in m.stubs) {
            sb.append("stub ").append(s.text.length).append(' ').append(s.path).append('\n')
            sb.append(s.text).append('\n')
        }
        sb.append("mir ").append(m.mir.length).append('\n')
        sb.append(m.mir).append('\n')
        sb.append("end\n")
        return sb.toString()
    }

    /** Parses [text] (the content of [origin], used in messages); verifies the format version and the content hash. */
    fun read(text: String, origin: String, verify: Boolean = true): Mlib {
        var pos = 0
        fun line(): String {
            if (pos >= text.length) throw MlibError("$origin: truncated library (unexpected end of file)")
            var e = text.indexOf('\n', pos)
            if (e < 0) e = text.length
            val l = text.substring(pos, e)
            pos = e + 1
            return l
        }
        fun field(l: String, key: String): String {
            if (!l.startsWith("$key ")) throw MlibError("$origin: damaged library header (expected '$key', found '${l.take(40)}')")
            return l.substring(key.length + 1)
        }
        if (!text.startsWith(MLIB_MAGIC + " ")) {
            throw MlibError("$origin: not a Mini library (no '$MLIB_MAGIC' header); libraries are built with 'minic-native --emit-mlib' or 'mini package'")
        }
        val fmt = line().substring(MLIB_MAGIC.length + 1).trim().toIntOrNull() ?: throw MlibError("$origin: damaged library header (format version)")
        if (fmt != MLIB_FORMAT_VERSION) {
            throw MlibError("$origin: library format version $fmt, but this compiler reads version $MLIB_FORMAT_VERSION; rebuild the library with this toolchain")
        }
        val mirFmt = field(line(), "mir-format").trim().toIntOrNull() ?: -1
        if (mirFmt != MIR_FORMAT_VERSION) {
            throw MlibError("$origin: library MIR format $mirFmt, but this compiler reads $MIR_FORMAT_VERSION; rebuild the library with this toolchain")
        }
        val compilerId = field(line(), "compiler")
        val name = field(line(), "name")
        val version = field(line(), "version")
        val tag = field(line(), "tag")
        val hash = field(line(), "hash")
        val deps = ArrayList<MlibDep>()
        val stubs = ArrayList<MlibStub>()
        val failures = ArrayList<MlibFailure>()
        var mir: String? = null
        while (true) {
            val l = line()
            if (l == "end") break
            if (l.startsWith("dep ")) {
                val parts = l.substring(4).split(' ')
                if (parts.size != 2) throw MlibError("$origin: damaged dependency line '$l'")
                deps.add(MlibDep(parts[0], parts[1]))
            } else if (l.startsWith("fail ")) {
                val rest = l.substring(5)
                val sp = rest.indexOf(' ')
                if (sp < 0) throw MlibError("$origin: damaged line '${l.take(40)}'")
                failures.add(MlibFailure(rest.substring(0, sp), rest.substring(sp + 1)))
            } else if (l.startsWith("stub ")) {
                val rest = l.substring(5)
                val sp = rest.indexOf(' ')
                val n = (if (sp < 0) null else rest.substring(0, sp).toIntOrNull()) ?: throw MlibError("$origin: damaged stub line '$l'")
                if (pos + n > text.length) throw MlibError("$origin: truncated library (stub ${rest.substring(sp + 1)})")
                stubs.add(MlibStub(rest.substring(sp + 1), text.substring(pos, pos + n)))
                pos += n + 1
            } else if (l.startsWith("mir ")) {
                val n = l.substring(4).toIntOrNull() ?: throw MlibError("$origin: damaged MIR section line '$l'")
                if (pos + n > text.length) throw MlibError("$origin: truncated library (MIR section)")
                mir = text.substring(pos, pos + n)
                pos += n + 1
            } else {
                throw MlibError("$origin: damaged library (unknown section '${l.take(40)}')")
            }
        }
        if (mir == null) throw MlibError("$origin: damaged library (no MIR section)")
        if (verify) {
            val actual = hashOf(name, version, compilerId, deps, stubs, mir, failures)
            if (actual != hash) {
                throw MlibError("$origin: content hash mismatch (header says $hash, content is $actual); the file is damaged or was edited, rebuild it")
            }
        }
        return Mlib(name, version, compilerId, tag, deps, stubs, mir, hash, failures)
    }

    /**
     * The libraries in dependency order (dependencies first). Every dependency must be among [libs] with the hash the dependent was built
     * against, and all libraries must come from the same compiler ([compilerId], unless it is empty).
     */
    fun order(libs: List<Mlib>, compilerId: String): List<Mlib> {
        val byName = HashMap<String, Mlib>()
        for (l in libs) {
            if (compilerId.isNotEmpty() && l.compilerId != compilerId) {
                throw MlibError("library '${l.name}' was built by compiler '${l.compilerId}', this one is '$compilerId'; rebuild the library")
            }
            val other = byName[l.name]
            if (other != null && other.hash != l.hash) {
                throw MlibError("two different libraries named '${l.name}' were given (hash ${other.hash} and ${l.hash})")
            }
            byName[l.name] = l
        }
        val out = ArrayList<Mlib>()
        val state = HashMap<String, Int>()
        fun visit(l: Mlib, chain: String) {
            val s = state[l.name] ?: 0
            if (s == 2) return
            if (s == 1) throw MlibError("library dependency cycle: $chain")
            state[l.name] = 1
            for (d in l.deps) {
                val dep = byName[d.name] ?: throw MlibError("library '${l.name}' needs library '${d.name}' (hash ${d.hash}), which was not given (--lib <path to ${d.name}.mlib>)")
                if (dep.hash != d.hash) {
                    throw MlibError("library '${l.name}' was built against '${d.name}' with hash ${d.hash}, but the given '${d.name}' has hash ${dep.hash}; rebuild '${l.name}' or use the matching '${d.name}'")
                }
                visit(dep, chain + " -> " + d.name)
            }
            state[l.name] = 2
            out.add(l)
        }
        for (l in libs) visit(byName[l.name]!!, l.name)
        return out
    }
}

/** The stubs of [libs] as declaration-only inputs of the analysis. */
fun libInputs(libs: List<Mlib>): List<LowerInput> {
    val out = ArrayList<LowerInput>()
    for (l in libs) for (s in l.stubs) out.add(LowerInput(s.path, s.text, true, true))
    return out
}

/**
 * Lower one new module against declarations from already materialized libraries, without linking their MIR bodies again.
 * Used by incremental execution backends: the prior library code is already resident, so this module must emit references
 * to its stable C names rather than duplicate their definitions.
 */
fun lowerToMirAgainstLibs(
    inputs: List<LowerInput>,
    libs: List<Mlib>,
    sourceName: String,
    lineDirectives: Boolean = false,
    memoryGate: ((MemoryReport) -> Unit)? = null,
    nameTag: String = "",
): MirModule {
    val all = ArrayList<LowerInput>(inputs)
    all.addAll(libInputs(libs))
    return lowerToMir(all, sourceName, lineDirectives, memoryGate, nameTag)
}

/** Reject a recorded unsupported library body if it is reachable from a separately emitted user module. */
fun validateMlibFailures(program: MirModule, libs: List<Mlib>) {
    if (libs.isEmpty()) return
    val modules = libs.map { lib ->
        try { MirReader.read(lib.mir) }
        catch (e: Exception) { throw MlibError("library '${lib.name}': damaged MIR section (${e.message})") }
    }
    val pruned = MirLink.prune(MirLink.link(modules, program), programRoots(program))
    val failed = HashMap<String, String>()
    for (lib in libs) for (f in lib.failures) failed[f.cName] = f.message
    for (f in pruned.functions) failed[f.cName]?.let { throw UnsupportedConstruct(it) }
}

/**
 * Compiles [sources] (user files, all of them are library code) against [libs] (already ordered) into a library: stubs of the sources
 * plus their complete, unpruned MIR.
 */
fun buildMlib(
    sources: List<LowerInput>,
    libs: List<Mlib>,
    name: String,
    version: String,
    compilerId: String,
    memoryGate: ((MemoryReport) -> Unit)? = null,
): Mlib {
    val tag = MlibFormat.tagOf(name)
    val inputs = ArrayList<LowerInput>()
    inputs.addAll(sources)
    inputs.addAll(libInputs(libs))
    val failMap = LinkedHashMap<String, String>()
    val module = lowerToMir(inputs, name, false, memoryGate, tag, true, failMap)
    val mir = MirWriter.write(module)
    val stubs = ArrayList<MlibStub>()
    for (s in sources) stubs.add(MlibStub(s.path, StubWriter.stub(s.text)))
    val deps = ArrayList<MlibDep>()
    for (l in libs) deps.add(MlibDep(l.name, l.hash))
    val failures = ArrayList<MlibFailure>()
    for ((k, v) in failMap) failures.add(MlibFailure(k, v))
    val hash = MlibFormat.hashOf(name, version, compilerId, deps, stubs, mir, failures)
    return Mlib(name, version, compilerId, tag, deps, stubs, mir, hash, failures)
}

/** The roots of the program for the link: `main`, or every function of the program when it has none (a library-like program). */
private fun programRoots(program: MirModule): List<String> {
    val roots = ArrayList<String>()
    for (f in program.functions) if (f.cName == Mangling.MAIN) roots.add(f.cName)
    if (roots.isNotEmpty()) return roots
    for (f in program.functions) roots.add(f.cName)
    return roots
}

/** Like [lowerToMir], with the declarations of [libs] taken from their stubs and their code from their MIR (pruned from `main`). */
fun lowerToMirWithLibs(
    inputs: List<LowerInput>,
    libs: List<Mlib>,
    sourceName: String,
    lineDirectives: Boolean = false,
    memoryGate: ((MemoryReport) -> Unit)? = null,
): MirModule {
    if (libs.isEmpty()) return lowerToMir(inputs, sourceName, lineDirectives, memoryGate)
    val all = ArrayList<LowerInput>(inputs)
    all.addAll(libInputs(libs))
    val program = lowerToMir(all, sourceName, lineDirectives, memoryGate)
    val mirs = ArrayList<MirModule>()
    for (l in libs) {
        mirs.add(try {
            MirReader.read(l.mir)
        } catch (e: Exception) {
            throw MlibError("library '${l.name}': damaged MIR section (${e.message})")
        })
    }
    val linked = MirLink.link(mirs, program)
    val pruned = MirLink.prune(linked, programRoots(program))
    // a function of a library that the backend could not lower is an error only when the program needs it
    val failed = HashMap<String, String>()
    for (l in libs) for (f in l.failures) failed[f.cName] = f.message
    if (failed.isNotEmpty()) {
        for (f in pruned.functions) {
            val msg = failed[f.cName]
            if (msg != null) throw UnsupportedConstruct(msg)
        }
    }
    return pruned
}
