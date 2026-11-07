package mini.backend

/** Raised for MIR the emitter cannot translate (a producer bug, not a user error). */
class EmitError(message: String) : Exception(message)

/**
 * MIR to C17. The output includes only `minirt.h` plus the C standard headers.
 *
 * Arithmetic follows Kotlin semantics: Int/Long `+ - * neg` are computed on the
 * unsigned counterpart (wraps, no UB); `/` and `%` go through helpers that panic on
 * zero and handle MIN / -1; shifts mask the count; Double to integer conversions
 * saturate and map NaN to 0.
 *
 * Reference counting (Managed mode), see compiler/README.md:
 *  - every reference-typed local owns one reference; it is NULL-initialised at the top of
 *    its C scope and released at every exit of that scope (end, break, continue, return);
 *  - parameters are borrowed;
 *  - "owned" expressions ([isOwned]) transfer their +1 to the Declare/Assign/SetField/Return
 *    that consumes them; borrowed values are retained first;
 *  - fields: retain the new value, release the old one;
 *  - returns are owned (+1).
 */
object CEmitter {
    private const val LINE_RESET = "#mk-line-reset"

    /**
     * [rcOpt] runs the reference-count elision pass ([RcOpt]) first; the result is deterministic. [strict] turns on the
     * Strict code generation ([Tracking]: no reference counts for tracked values, allocators, `free`); without it
     * Strict programs run on reference counting alone (the Managed baseline). [conventions] enables the borrowed results
     * and owned parameters of the stdlib hot paths ([Conventions]; needs [rcOpt]).
     */
    fun emit(module: MirModule, rcOpt: Boolean = true, strict: Boolean = true, conventions: Boolean = true, lineFile: String? = null): String =
        emitDetailed(module, rcOpt, strict, conventions, lineFile).c

    /** The C text plus the decisions behind it (for `minic mem-report`, tests). */
    class Result(
        val c: String,
        val plan: RcPlan?,
        val tracking: Tracking,
        /** Per function (C name; `<dispose Class>` for the dispose glue): retain sites and release sites of the emitted C by reason. */
        val reasons: Map<String, Pair<Map<String, Int>, Map<String, Int>>>,
    )

    /**
     * [lineFile] (the name the C file is going to have) turns on `#line` directives: statements that know their source
     * position ([MirStmt.srcLine]) are emitted under `#line <n> "<file.kt>"`, so debuggers, sanitizers and crash reports
     * point to the Kotlin source, and every function ends with a `#line` back into [lineFile].
     */
    fun emitDetailed(module: MirModule, rcOpt: Boolean = true, strict: Boolean = true, conventions: Boolean = true, lineFile: String? = null): Result {
        val tr = Tracking(module, strict)
        val plan = if (rcOpt) RcOpt.analyze(module, tr, conventions) else null
        val st = State(module, plan, tr)
        st.lineFile = lineFile
        val c = st.run()
        return Result(c, plan, tr, st.reasons)
    }

    private class Scope(val locals: List<MirLocal>, val loop: MirLabel?)

    private class State(val module: MirModule, val rcPlan: RcPlan?, val tr: Tracking) {
        var curFn: MirFunction? = null
        /** Not null: write `#line` directives (see [emitDetailed]). */
        var lineFile: String? = null
        val newaUsed = LinkedHashSet<String>()

        fun rcT(t: MirType) = tr.rc(curFn, t)
        fun rcL(l: MirLocal) = tr.rcLocal(curFn, l)
        fun ownedE(e: MirExpr) = tr.owned(curFn, e)
        val literals = LinkedHashMap<String, String>() // kotlin value -> C name
        val sig = HashMap<String, MirFunction>()
        val classes = HashMap<String, MirClass>()
        /** C names of the classes some other class extends (the closed world: every class of the program is in the module) */
        var extendedSet: HashSet<String>? = null
        fun extended(): HashSet<String> {
            val ex = extendedSet
            if (ex != null) return ex
            val r = HashSet<String>()
            for (c in classes.values) { val s = c.superCName; if (s != null) r.add(s) }
            extendedSet = r
            return r
        }
        /** Object layout (Layout.kt); pointers are 8 bytes (the C backend targets 64-bit hosts, the asserts in the struct emission check it). */
        val layouts = ObjectLayouts(classes, 8)
        val cycles = CycleAnalysis(classes) // which classes can be part of a reference cycle
        var usedLabels = HashSet<String>()
        val scopes = ArrayList<Scope>()
        var currentReturn: MirType = TUnit
        /** RcOpt decisions for the function being emitted (null: baseline reference counting). */
        var fplan: RcFunctionPlan? = null
        /** Locals (by id) that gave their reference away on every path to the current point: nothing releases them any more. */
        val dead = HashSet<Int>()
        /** Reference locals whose declaration has not been emitted yet: still NULL, nothing to release (RcOpt mode). */
        val pending = HashSet<Int>()
        /** The statement whose expressions are being emitted (call arguments handed over to a callee refer to it). */
        var curStmt: MirStmt? = null
        lateinit var throwing: ThrowAnalysis

        /** Enclosing `try` bodies of the function being emitted: label to jump to and index of the body scope. */
        class Handler(val label: String, val scopeIndex: Int)
        val handlers = ArrayList<Handler>()
        val caught = ArrayList<String>()
        var tryCounter = 0
        var tmpCounter = 0

        /** C interop: a Load / Store was emitted (the prelude then defines the memory helpers). */
        var usesMemory = false
        /** C identifier of the prototype of every C function the program calls, by [MirExtern.prototypeKey]. */
        val externNames = LinkedHashMap<String, String>()
        val externProtos = LinkedHashMap<String, MirExtern>()

        fun run(): String {
            val bodies = StringBuilder()
            for (f in module.functions) {
                if (sig.put(f.cName, f) != null) throw EmitError("duplicate C symbol ${f.cName}")
            }
            for (c in module.classes) classes[c.cName] = c
            val symbolCount = HashMap<String, Int>()
            for (x in module.externs) {
                val key = x.prototypeKey
                if (externNames[key] != null) continue
                val base = "mkx_" + cIdent(x.symbol)
                val n = symbolCount[base] ?: 0
                symbolCount[base] = n + 1
                if (isLibmBuiltin(x)) {
                    // clang does not recognise a libm function behind an `__asm__` label: call the builtin (sqrtsd, roundsd, ...)
                    externNames[key] = "__builtin_" + x.symbol
                    continue
                }
                externNames[key] = if (n == 0) base else base + "_" + n
                externProtos[key] = x
            }
            throwing = ThrowAnalysis(module).run()
            for (f in module.functions) emitFunction(f, bodies)
            val userMain = module.functions.firstOrNull { it.cName == Mangling.MAIN }
            if (userMain != null) {
                bodies.append("/* program entry: an exception that escaped main is reported by the runtime */\n")
                if (userMain.params.isEmpty()) {
                    bodies.append("void mk_main(void) {\n    $USER_MAIN();\n    if (mk_pending_exception != NULL) mk_report_uncaught();\n}\n\n")
                } else {
                    // fun main(args: Array<String>): the runtime hands over the command line (B1 block of minirt.h)
                    bodies.append("void mk_main(void) {\n    MkObject *args = mk_b1_args();\n    $USER_MAIN(args);\n    mk_release(args);\n")
                    bodies.append("    if (mk_pending_exception != NULL) mk_report_uncaught();\n}\n\n")
                }
            }

            val out = StringBuilder()
            out.append("/* Generated by minic from ${comment(module.sourceName)}. Do not edit. */\n")
            // `@file:CLink("lib")`: the C file alone does not say what to link with (minic passes -l<lib> itself)
            for (l in module.links) out.append("/* mini:link ${comment(l)} */\n")
            out.append("#include <math.h>\n#include <stddef.h>\n#include <stdint.h>\n#include \"minirt.h\"\n")
            if (usesMemory) out.append("#include <string.h>\n")
            for (h in module.includes) out.append("#include \"${headerName(h)}\"\n")
            // Kotlin rounds each floating-point operator separately. The standard C pragma is ignored by GCC,
            // so select the supported compiler-specific spelling to keep generated C aligned with LLVM.
            out.append("\n#if defined(__clang__)\n#pragma clang fp contract(off)\n#elif defined(__GNUC__)\n#pragma GCC optimize (\"fp-contract=off\")\n#endif\n")
            out.append("\n")
            // a program that can start threads: every reference count operation goes through the runtime (atomic once a thread runs)
            if (module.functions.any { it.qualifiedName == "java.lang.Thread.start" }) out.append("#define MK_ATOMIC_RC 1\n")
            out.append(PRELUDE)
            if (usesMemory) out.append(MEMORY_PRELUDE)
            if (literals.isNotEmpty()) {
                out.append("\n/* string literals (one byte per character when every character is below 256, else UTF-16) */\n")
                for ((value, name) in literals) {
                    if (value.all { it.code < 256 }) out.append("MK_STRING_LITERAL8($name, ${value.length}, {${latin1Bytes(value)}});\n")
                    else out.append("MK_STRING_LITERAL($name, ${utf16Literal(value)});\n")
                }
            }
            out.append("\n/* prototypes */\n")
            for (f in module.functions) out.append(prototype(f)).append(";\n")
            if (externProtos.isNotEmpty()) {
                // C functions of the program (C interop): own C names bound to the real symbols with asm labels, so
                // the declarations never clash with a header and calls are the same in the C and the LLVM backend
                out.append("\n/* C functions (external fun) */\n")
                // the label of a C symbol carries the platform's user label prefix ("_" on Mach-O, empty on ELF)
                out.append("#define MKX_STR2(x) #x\n#define MKX_STR(x) MKX_STR2(x)\n#define MKX_SYM(s) MKX_STR(__USER_LABEL_PREFIX__) s\n")
                for ((key, x) in externProtos) {
                    val ps = x.prototypeParams.map { cType(it) } + (if (x.isVariadic) listOf("...") else emptyList())
                    out.append("extern ${cType(x.returnType)} ${externNames[key]}(${if (ps.isEmpty()) "void" else ps.joinToString(", ")}) __asm__(MKX_SYM(\"${asmLabel(x.symbol)}\"));\n")
                }
            }
            out.append("\n/* classes */\n")
            emitClasses(out)
            out.append("\n").append(bodies)
            return if (lineFile != null) resolveLineResets(out.toString(), lineFile!!) else out.toString()
        }

        /** Replaces the [LINE_RESET] markers (end of a function) by `#line` directives that point back into the C file. */
        fun resolveLineResets(text: String, file: String): String {
            val sb = StringBuilder()
            var line = 1
            var i = 0
            while (i < text.length) {
                var e = text.indexOf('\n', i)
                if (e < 0) e = text.length
                if (e - i == LINE_RESET.length && text.substring(i, e) == LINE_RESET) {
                    sb.append("#line ").append(line + 1).append(" \"").append(file.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
                } else {
                    sb.append(text.substring(i, e))
                    if (e < text.length) sb.append('\n')
                }
                line++
                i = e + 1
            }
            return sb.toString()
        }

        /**
         * Release of a reference that may be NULL (locals not yet assigned or moved out, old field values, fields of a
         * dying object). With RcOpt the NULL test is inlined: no call into the runtime for the many NULL cases.
         */
        fun rel(x: String, why: String = "release"): String {
            note(releaseReasons, why)
            return if (rcPlan != null) "MKH_REL($x)" else "mk_release((MkObject *)$x)"
        }

        /** A retain of the C expression [x] (with RcOpt: the fast path is inline, see MKH_RETAIN). */
        fun ret(x: String, why: String = "retain"): String {
            note(retainReasons, why)
            return if (rcPlan != null) "MKH_RETAIN($x)" else "mk_retain((MkObject *)$x)"
        }

        /** The retain / release sites of the function being emitted by reason (for `minic mem-report`). */
        var retainReasons = HashMap<String, Int>()
        var releaseReasons = HashMap<String, Int>()
        val reasons = LinkedHashMap<String, Pair<HashMap<String, Int>, HashMap<String, Int>>>()

        fun note(m: HashMap<String, Int>, why: String) {
            m[why] = (m[why] ?: 0) + 1
        }

        fun comment(s: String) = s.replace("*/", "* /").replace('\n', ' ')

        fun cIdent(s: String): String {
            val sb = StringBuilder()
            for (c in s) sb.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_') c else '_')
            return sb.toString()
        }

        /** A libm function of the `Double -> Double` or `(Double, Double) -> Double` shape that the compiler knows as a builtin. */
        fun isLibmBuiltin(x: MirExtern): Boolean {
            if (x.isVariadic || x.returnType !== TDouble || x.params.isEmpty() || x.params.size > 2) return false
            for (p in x.params) if (p !== TDouble) return false
            return when (x.symbol) {
                "sqrt", "floor", "ceil", "trunc", "rint", "sin", "cos", "exp", "log", "fabs" -> x.params.size == 1
                "pow", "remainder", "nextafter" -> x.params.size == 2
                else -> false
            }
        }

        fun asmLabel(s: String): String {
            for (c in s) if (c < '!' || c > '~' || c == '"' || c == '\\') throw EmitError("bad C symbol name '$s'")
            return s
        }

        fun headerName(s: String): String {
            for (c in s) if (c < ' ' || c > '~' || c == '"' || c == '\\') throw EmitError("bad header name '$s'")
            return s
        }

        /** A whole program (it has `main`): its functions have internal linkage, so the C compiler can inline them and drop the unused ones. */
        val closedWorld: Boolean = module.functions.any { it.cName == Mangling.MAIN }

        fun prototype(f: MirFunction): String {
            val params = if (f.params.isEmpty()) "void" else f.params.joinToString(", ") { cType(it.type) + " " + Mangling.local(it.name, it.id) }
            val linkage = if (closedWorld && f.cName != Mangling.MAIN) "MKH_STATIC " else ""
            return "$linkage${cType(f.returnType)} ${if (f.cName == Mangling.MAIN) USER_MAIN else f.cName}($params)"
        }

        // ---- classes -----------------------------------------------------------------

        fun typeInfoOf(t: MirType): String = when (t) {
            TString, TNString -> "mk_type_String"
            is TObject -> t.typeInfo
            is TBoxed -> boxTypeInfo(t.prim)
            else -> throw EmitError("no type info for $t" + (if (curFn != null) " (in " + curFn!!.qualifiedName + ")" else ""))
        }

        fun boxTypeInfo(p: MirType): String = when (p) {
            is TInline -> p.cName + "_ti"
            TInt -> "mk_type_Int"
            TLong -> "mk_type_Long"
            TBoolean -> "mk_type_Boolean"
            TDouble -> "mk_type_Double"
        TFloat -> "mk_type_Float"
        TByte -> "mk_type_Byte"
        TShort -> "mk_type_Short"
            TChar -> "mk_type_Char"
            else -> throw EmitError("no box for $p")
        }

        fun boxName(p: MirType): String = when (p) {
            is TInline -> throw EmitError("a value class is boxed by a call to its box function, not by MirExpr.Box ($p)")
            TInt -> "int"
            TLong -> "long"
            TBoolean -> "boolean"
            TDouble -> "double"
        TFloat -> "float"
        TByte -> "byte"
        TShort -> "short"
            TChar -> "char"
            else -> throw EmitError("no box for $p")
        }

        fun fnRef(name: String?, abstractDefault: String): String = "(void *)" + (name ?: abstractDefault)

        /** Classes (C names) whose instances are cycle collection candidates: they have a trace function. */
        val cyclicClasses = HashSet<String>()

        /** The allocation entry of a class: the cycle collector decision (armed or not) is made here, at compile time (the runtime
         *  entry `mk_alloc` decides at run time from the type info; classes defined by the runtime use it). */
        fun allocFn(cName: String): String = when {
            classes[cName]?.external != false -> "mk_alloc"
            cyclicClasses.contains(cName) -> "mk_alloc_armed"
            else -> "mk_alloc_plain"
        }

        fun emitClasses(out: StringBuilder) {
            for (c in module.classes) {
                if (c.external) continue
                out.append("/* ${if (c.isInterface) "interface" else "class"} ${comment(c.qualifiedName)} */\n")
                val ifs = if (c.interfaces.isEmpty()) "NULL" else "${c.cName}_ifs"
                if (c.interfaces.isNotEmpty()) {
                    out.append("static const MkTypeInfo *const ${c.cName}_ifs[] = {")
                    out.append(c.interfaces.joinToString(", ") { "&${it}_ti" }).append("};\n")
                }
                if (c.isInterface) {
                    out.append("static const MkTypeInfo ${c.cName}_ti = {${cStringLiteral(c.qualifiedName)}, 0, NULL, NULL, NULL, 0, $ifs, ${c.interfaces.size}};\n\n")
                    continue
                }
                // layout
                val sup = c.superCName?.let { classes[it] ?: throw EmitError("unknown superclass $it") }
                // The shared layout (Layout.kt): fields in offset order, the inherited ones flattened in (a subclass fills its
                // superclass's tail padding, so the padded superclass struct cannot be embedded); a runtime-defined superclass
                // (the exception classes) is a fixed prefix and is embedded. The asserts keep the C compiler's own layout honest.
                val lay = layouts.of(c.cName)
                var root: MirClass? = sup
                while (root != null && !root.external && root.superCName != null) root = classes[root.superCName!!]
                val prefix = if (root != null && root.external) "struct ${root.cName} base;" else "MkObject header;"
                out.append("struct ${c.cName} {\n    $prefix\n")
                for (sl in lay.slots) {
                    val fname = if (sl.owner == c.cName) Mangling.field(sl.name) else "${sl.owner}_${Mangling.field(sl.name)}"
                    out.append("    ${cType(sl.type)} $fname;\n")
                }
                out.append("};\n")
                // the numbers are computed for 8-byte pointers (layouts = ObjectLayouts(classes, 8)): a target with other pointers
                // (a 32-bit C build) keeps the C compiler's own layout and skips the checks
                out.append("#if UINTPTR_MAX == 0xffffffffffffffffu\n")
                out.append("_Static_assert(sizeof(struct ${c.cName}) == ${lay.size}, \"object layout\");\n")
                for (f in c.fields) out.append("_Static_assert(offsetof(struct ${c.cName}, ${Mangling.field(f.name)}) == ${lay.offsets[f.name]}, \"object layout\");\n")
                out.append("#endif\n")
                // dispose
                val refFields = c.fields.filter { tr.rcField(c, it) }
                // owned fields of tracked type (Strict classes): freeing the object frees them (docs/memory-model.md 5.3)
                val ownedFields = c.fields.filter { tr.ownedTrackedField(c, it) }
                var dispose = "NULL"
                // cycle collection (docs/memory-model.md, "Cycles"): the fields that can lead to a cycle; strings and boxes cannot
                val traceFields = refFields.filter { cycles.fieldCapable(c, it) }
                val superTraced = sup != null && cyclicClasses.contains(sup.cName)
                var trace = "NULL"
                if (traceFields.isNotEmpty() || superTraced) {
                    cyclicClasses.add(c.cName)
                    trace = "${c.cName}_trace"
                    out.append("static void ${c.cName}_trace(MkObject *self, void (*fn)(MkObject *, void *), void *ctx) {\n    struct ${c.cName} *o = (struct ${c.cName} *)self;\n")
                    if (superTraced) out.append("    ${sup!!.cName}_trace(self, fn, ctx);\n")
                    for (f in traceFields) out.append("    fn((MkObject *)o->${Mangling.field(f.name)}, ctx);\n")
                    out.append("}\n")
                }
                if (refFields.isNotEmpty() || ownedFields.isNotEmpty()) {
                    dispose = "${c.cName}_dispose"
                    out.append("static void ${c.cName}_dispose(MkObject *self) {\n    struct ${c.cName} *o = (struct ${c.cName} *)self;\n")
                    for (f in ownedFields) out.append("    mk_free_owned((MkObject *)o->${Mangling.field(f.name)});\n")
                    retainReasons = HashMap()
                    releaseReasons = HashMap()
                    for (f in refFields) out.append("    ${rel("o->${Mangling.field(f.name)}", "field of a dying object")};\n")
                    if (releaseReasons.isNotEmpty()) reasons["<dispose ${c.qualifiedName}>"] = retainReasons to releaseReasons
                    out.append("}\n")
                }
                // itables and vtable
                val vt = ArrayList<String>()
                val anyDefaults = listOf("mk_any_to_string", "mk_any_equals", "mk_any_hash_code")
                for ((i, e) in c.vtable.withIndex()) {
                    vt += if (i < 3) fnRef(e, anyDefaults[i]) else fnRef(e, "mkh_abstract")
                }
                for ((k, it) in c.itables.withIndex()) {
                    val entries = if (it.entries.isEmpty()) "(void *)0" else it.entries.joinToString(", ") { e -> fnRef(e, "mkh_abstract") }
                    out.append("static void *const ${c.cName}_it$k[] = {$entries};\n")
                    vt += "(void *)${c.cName}_it$k"
                }
                out.append("static void *const ${c.cName}_vt[] = {${vt.joinToString(", ")}};\n")
                val superTi = if (sup == null) "&mk_type_Any" else "&${sup.cName}_ti"
                out.append(
                    "static const MkTypeInfo ${c.cName}_ti = {${cStringLiteral(c.qualifiedName)}, sizeof(struct ${c.cName}), $superTi, $dispose, " +
                        "${c.cName}_vt, ${vt.size}, $ifs, ${c.interfaces.size}, $trace};\n",
                )
                if (c.objectCtor != null) {
                    out.append("static MkObject *${c.cName}_obj;\n")
                    out.append(
                        "MKH_FN MkObject *${c.cName}_get(void) {\n    MkObject *o = __atomic_load_n(&${c.cName}_obj, __ATOMIC_ACQUIRE);\n" +
                            "    return o != NULL ? o : mk_object_init(&${c.cName}_obj, &${c.cName}_ti, ${c.objectCtor});\n}\n",
                    )
                }
                out.append("\n")
            }
            for (f in module.functions) {
                val cls = f.constructorOf ?: continue
                val ps = f.params.drop(1)
                val decl = if (ps.isEmpty()) "void" else ps.withIndex().joinToString(", ") { (i, p) -> "${cType(p.type)} a$i" }
                val call = (listOf("o") + ps.indices.map { "a$it" }).joinToString(", ")
                val check = if (f.cName in throwing.mayThrow) "    if (mk_pending_exception != NULL) {\n        mk_release(o);\n        return NULL;\n    }\n" else ""
                out.append("MKH_FN MkObject *${f.cName}_new($decl) {\n    MkObject *o = ${allocFn(cls)}(&${cls}_ti);\n    ${f.cName}($call);\n$check    return o;\n}\n")
                if (f.cName in newaUsed) {
                    // allocation through a chosen allocator (Strict code): the same, from `al` (0 = the default allocator)
                    val decla = if (ps.isEmpty()) "MkLong al" else "MkLong al, " + ps.withIndex().joinToString(", ") { (i, p) -> "${cType(p.type)} a$i" }
                    val checka = if (f.cName in throwing.mayThrow) "    if (mk_pending_exception != NULL) {\n        mk_discard(o);\n        return NULL;\n    }\n" else ""
                    out.append(
                        "MKH_FN MkObject *${f.cName}_newa($decla) {\n    MkObject *o = mk_alloc_with((MkAllocator *)(intptr_t)al, &${cls}_ti);\n" +
                            "    if (o == NULL) mk_panic(\"out of memory\");\n    ${f.cName}($call);\n$checka    return o;\n}\n",
                    )
                }
            }
        }

        // ---- functions ---------------------------------------------------------------

        fun emitFunction(f: MirFunction, out: StringBuilder) {
            out.append("/* ${comment(f.qualifiedName)} */\n")
            out.append(prototype(f)).append(" {\n")
            scopes.clear()
            handlers.clear()
            currentReturn = f.returnType
            fplan = rcPlan?.of(f)
            curFn = f
            retainReasons = HashMap()
            releaseReasons = HashMap()
            dead.clear()
            pending.clear()
            curStmt = null
            scope(f.body, 1, out, ownedParams = fplan?.ownedParams ?: emptyList())
            val last = f.body.lastOrNull()
            if (f.returnType != TUnit && last !is MirStmt.Return) {
                val safe = f.qualifiedName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
                out.append("    mk_panic(\"unreachable: control reached the end of $safe\");\n")
            }
            out.append("}\n\n")
            if (lineFile != null) out.append(LINE_RESET).append('\n')
            if (retainReasons.isNotEmpty() || releaseReasons.isNotEmpty()) reasons[f.cName] = retainReasons to releaseReasons
        }

        // ---- scopes / reference counting -------------------------------------------------

        fun ind(n: Int) = "    ".repeat(n)

        fun isTerminator(s: MirStmt?) = s is MirStmt.Return || s is MirStmt.Break || s is MirStmt.Continue || s is MirStmt.Panic || s is MirStmt.Throw

        fun refDecls(list: List<MirStmt>): List<MirLocal> =
            list.filterIsInstance<MirStmt.Declare>().map { it.local }.filter { rcL(it) }

        fun lname(l: MirLocal) = Mangling.local(l.name, l.id)

        /**
         * Emits [body] as the contents of one C scope. Reference locals declared directly in it
         * ([extra] adds declarations of statements emitted by [mid]) are NULL-initialised first,
         * [mid] runs after the body (loop continue label, do-while condition) and the locals
         * are released at the end unless [epilogue] is false.
         */
        fun scope(
            body: List<MirStmt>,
            d: Int,
            out: StringBuilder,
            loop: MirLabel? = null,
            extra: List<MirStmt> = emptyList(),
            epilogue: Boolean = true,
            mid: ((StringBuilder) -> Unit)? = null,
            ownedParams: List<MirLocal> = emptyList(),
        ) {
            val all = refDecls(body) + refDecls(extra)
            for (l in all) out.append("${ind(d)}${cType(l.type)} ${lname(l)} = NULL;\n")
            // borrowing locals (RcOpt) own nothing: they are never released
            val locals = if (fplan == null) all else ownedParams + all.filter { !isBorrowed(it) }
            scopes += Scope(locals, loop)
            if (rcPlan != null) for (l in all) pending += l.id
            for (s in body) stmt(s, d, out)
            mid?.invoke(out)
            if (epilogue && (mid != null || !isTerminator(body.lastOrNull()))) out.append(releases(scopes.size - 1, scopes.size - 1, d, null))
            scopes.removeAt(scopes.size - 1)
        }

        /** Release statements for scopes [from] down to [to] (indices, from >= to), skipping [keep]. */
        fun releases(from: Int, to: Int, d: Int, keep: MirLocal?, cold: Boolean = false): String {
            val sb = StringBuilder()
            for (i in from downTo to) {
                for (l in scopes[i].locals.asReversed()) {
                    if (l === keep || l.id in dead || l.id in pending) continue
                    // exception exits are cold and repeated at every call that can throw: plain calls keep the C small
                    sb.append(if (cold) "${ind(d)}mk_release((MkObject *)${lname(l)});\n" else "${ind(d)}${rel(lname(l), if (l.id in (fplan?.ownedParams?.map { it.id } ?: emptyList())) "owned parameter released at exit" else "owned local released at scope exit")};\n")
                }
            }
            return sb.toString()
        }

        fun isBorrowed(l: MirLocal) = fplan?.isBorrowed(l) == true

        fun isStaticValue(e: MirExpr) = e is MirExpr.StringConst || e is MirExpr.NullConst

        // ---- statements --------------------------------------------------------------

        fun stmt(s: MirStmt, d: Int, out: StringBuilder) {
            val srcFile = s.srcFile
            if (lineFile != null && s.srcLine > 0 && srcFile != null) {
                out.append("#line ").append(s.srcLine).append(" \"").append(srcFile.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
            }
            val saved = curStmt
            curStmt = s
            stmtInner(s, d, out)
            curStmt = saved
            if (s is MirStmt.Declare) pending -= s.local.id
            val gone = fplan?.dead?.get(s)
            if (gone != null) for (l in gone) dead += l.id
            // the last use of an owned local: release it here, not at the end of its scope (RcOpt: RcFunctionPlan.early)
            val early = fplan?.early?.get(s)
            if (early != null) for (l in early) {
                if (l.id in dead || l.id in pending) continue
                out.append(ind(d)).append(rel(lname(l), "last use")).append(";\n")
                out.append(ind(d)).append(lname(l)).append(" = NULL;\n")
            }
        }

        fun closedFor(s: MirStmt?, l: MirLocal): Boolean = s != null && fplan?.dead?.get(s)?.any { it === l } == true

        fun stmtInner(s: MirStmt, d: Int, out: StringBuilder) {
            val i = ind(d)
            when (s) {
                is MirStmt.Declare -> {
                    val t = s.local.type
                    if (t == TUnit || t == TNothing) throw EmitError("local of type $t" + (if (curFn != null) " (in " + curFn!!.qualifiedName + ")" else ""))
                    if (rcL(s.local)) {
                        // declared (NULL) at the top of the scope
                        val init = s.init ?: return
                        out.append("$i${lname(s.local)} = ${coerceTo(init, t, top = true)};\n")
                        val moved = fplan?.moves?.get(s)
                        if (!ownedE(init) && !isStaticValue(init) && moved == null && !isBorrowed(s.local)) out.append("$i${ret(lname(s.local), "borrowed value copied into an owned local")};\n")
                        if (moved != null && !closedFor(s, moved)) out.append("$i${lname(moved)} = NULL;\n")
                    } else {
                        val init = s.init?.let { " = " + expr(it, top = true) } ?: " = 0"
                        out.append("$i${cType(t)} ${lname(s.local)}$init;\n")
                    }
                }
                is MirStmt.Assign -> {
                    val l = s.local
                    if (rcL(l) && isBorrowed(l)) {
                        out.append("$i${lname(l)} = ${coerceTo(s.value, l.type, top = true)};\n")
                    } else if (rcL(l) && fplan?.selfTakes?.contains(s) == true) {
                        // the value expression gave the old reference to the callee (the local is NULL now)
                        out.append("$i${lname(l)} = ${coerceTo(s.value, l.type, top = true)};\n")
                        if (!ownedE(s.value) && !isStaticValue(s.value)) out.append("$i${ret(lname(l), "borrowed value copied into an owned local")};\n")
                    } else if (rcL(l)) {
                        val moved = fplan?.moves?.get(s)
                        out.append("$i{\n")
                        out.append("${ind(d + 1)}${cType(l.type)} n = ${coerceTo(s.value, l.type, top = true)};\n")
                        if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) out.append("${ind(d + 1)}${ret("n", "borrowed value copied into an owned local")};\n")
                        out.append("${ind(d + 1)}${cType(l.type)} old = ${lname(l)};\n")
                        out.append("${ind(d + 1)}${lname(l)} = n;\n")
                        out.append("${ind(d + 1)}${rel("old", "old value of an assigned local")};\n")
                        if (moved != null && !closedFor(s, moved)) out.append("${ind(d + 1)}${lname(moved)} = NULL;\n")
                        out.append("$i}\n")
                    } else {
                        out.append("$i${lname(l)} = ${expr(s.value, top = true)};\n")
                    }
                }
                is MirStmt.SetField -> {
                    val target = "((struct ${s.owner} *)(${expr(s.obj)}))->${Mangling.field(s.field)}"
                    if (tr.rcStore(curFn, s)) {
                        out.append("$i{\n")
                        val moved = fplan?.moves?.get(s)
                        out.append("${ind(d + 1)}${cType(s.fieldType)} n = ${coerceTo(s.value, s.fieldType, top = true)};\n")
                        if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) out.append("${ind(d + 1)}${ret("n", "reference stored into a field (the field owns a count)")};\n")
                        if (fplan?.fresh?.contains(s) == true) {
                            // first store into a field of the object under construction: the old value is NULL
                            out.append("${ind(d + 1)}$target = n;\n")
                        } else {
                            out.append("${ind(d + 1)}${cType(s.fieldType)} *p = &$target;\n")
                            out.append("${ind(d + 1)}${cType(s.fieldType)} old = *p;\n")
                            out.append("${ind(d + 1)}*p = n;\n")
                            out.append("${ind(d + 1)}${rel("old", "old value of a field")};\n")
                        }
                        if (moved != null && !closedFor(s, moved)) out.append("${ind(d + 1)}${lname(moved)} = NULL;\n")
                        out.append("$i}\n")
                    } else {
                        out.append("$i$target = ${expr(s.value, top = true)};\n")
                    }
                }
                is MirStmt.Store -> {
                    usesMemory = true
                    out.append("$i{ ${cType(s.value.type)} mkh_s = ${expr(s.value)}; memcpy(${expr(s.ptr)}, &mkh_s, sizeof mkh_s); }\n")
                }
                is MirStmt.Eval -> {
                    val e = s.expr
                    if (e === MirExpr.UnitValue) return
                    if (e.type == TUnit || e.type == TNothing) out.append("$i${expr(e, top = true)};\n")
                    else if (ownedE(e)) out.append("$i${rel(expr(e, top = true), "unused owned result")};\n")
                    else out.append("${i}(void)${expr(e)};\n")
                }
                is MirStmt.If -> {
                    out.append("${i}if (${condExpr(s.cond)}) {\n")
                    scope(s.thenBody, d + 1, out)
                    if (s.elseBody.isEmpty()) {
                        out.append("$i}\n")
                    } else {
                        out.append("$i} else {\n")
                        scope(s.elseBody, d + 1, out)
                        out.append("$i}\n")
                    }
                }
                is MirStmt.Block -> {
                    out.append("$i{\n")
                    scope(s.body, d + 1, out)
                    out.append("$i}\n")
                }
                is MirStmt.While -> loop(s.label, "while (${condExpr(s.cond)})", s.body, d, out)
                is MirStmt.Loop -> loop(s.label, "for (;;)", s.body, d, out)
                is MirStmt.DoWhile -> doWhile(s, d, out)
                is MirStmt.ForRange -> forRange(s, d, out)
                is MirStmt.Break -> {
                    usedLabels.add(brk(s.label))
                    out.append(releases(scopes.size - 1, loopIndex(s.label), d, null))
                    out.append("${i}goto ${brk(s.label)};\n")
                }
                is MirStmt.Continue -> {
                    usedLabels.add(cont(s.label))
                    out.append(releases(scopes.size - 1, loopIndex(s.label) + 1, d, null))
                    out.append("${i}goto ${cont(s.label)};\n")
                }
                is MirStmt.Panic -> {
                    if (s.message.any { it < ' ' || it > '~' || it == '"' || it == '\\' }) throw EmitError("unsafe panic message")
                    out.append("${i}mk_panic(\"${s.message}\");\n")
                }
                is MirStmt.Return -> returnStmt(s, d, out)
                is MirStmt.Throw -> {
                    out.append("${i}mk_throw((MkObject *)${expr(s.value)});\n")
                    out.append(exitStmt(d))
                }
                is MirStmt.Try -> tryStmt(s, d, out)
            }
        }

        // ---- exceptions ---------------------------------------------------------------------

        fun defaultReturn(): String = when (currentReturn.repr) {
            TUnit, TNothing -> "return;"
            TDouble -> "return 0.0;"
            TBoolean -> "return false;"
            TInt, TLong, TChar, TByte, TShort -> "return 0;"
            TFloat -> "return 0.0f;"
            else -> "return NULL;"
        }

        /** Leaves the current position with a pending exception: to the innermost handler or out of the function. */
        fun exitStmt(d: Int): String {
            val h = handlers.lastOrNull()
            return if (h != null) releases(scopes.size - 1, h.scopeIndex, d, null, true) + "${ind(d)}goto ${h.label};\n"
            else releases(scopes.size - 1, 0, d, null, true) + "${ind(d)}${defaultReturn()}\n"
        }

        /** [call] (a C expression of MIR type [type]) followed by the pending-exception check, as a GNU statement expression. */
        fun checked(call: String, type: MirType): String {
            val exit = exitStmt(0).replace("\n", " ").trim()
            if (type == TUnit || type == TNothing) return "({ $call; if (mk_pending_exception != NULL) { $exit } (void)0; })"
            val t = "mkt${tmpCounter++}"
            return "({ ${cType(type)} $t = $call; if (mk_pending_exception != NULL) { $exit } $t; })"
        }

        fun tryStmt(s: MirStmt.Try, d: Int, out: StringBuilder) {
            val i = ind(d)
            val id = tryCounter++
            val label = "mkcatch_$id"
            val end = "mkend_$id"
            out.append("$i{\n")
            handlers += Handler(label, scopes.size)
            scope(s.body, d + 1, out)
            handlers.removeAt(handlers.size - 1)
            out.append("${ind(d + 1)}goto $end;\n")
            out.append("$label:;\n")
            out.append("${ind(d + 1)}{\n")
            val ex = "mkex_$id"
            out.append("${ind(d + 2)}MkObject *$ex = mk_catch();\n")
            caught += ex
            for (c in s.catches) {
                out.append("${ind(d + 2)}if (mk_is_instance($ex, &${typeInfoOf(c.type)})) {\n")
                scope(c.body, d + 3, out)
                out.append("${ind(d + 2)}} else ")
            }
            out.append("{\n${ind(d + 3)}mk_rethrow($ex);\n")
            out.append(exitStmt(d + 3))
            out.append("${ind(d + 2)}}\n")
            caught.removeAt(caught.size - 1)
            out.append("${ind(d + 1)}}\n")
            out.append("$end:;\n")
            out.append("$i}\n")
        }

        fun loopIndex(l: MirLabel): Int {
            for (k in scopes.indices.reversed()) if (scopes[k].loop === l) return k
            throw EmitError("jump to unknown loop ${l.id}")
        }

        fun returnStmt(s: MirStmt.Return, d: Int, out: StringBuilder) {
            val i = ind(d)
            val v = s.value
            if (v == null || v === MirExpr.UnitValue) {
                out.append(releases(scopes.size - 1, 0, d, null)).append("${i}return;\n")
                return
            }
            if (v.type == TUnit) {
                out.append("$i${expr(v, top = true)};\n").append(releases(scopes.size - 1, 0, d, null)).append("${i}return;\n")
                return
            }
            if (!rcT(currentReturn)) {
                // the value first: a call in it may hand a local over to the callee (RcOpt takes), which then must not be released
                val value = expr(v, top = true)
                val rels = releases(scopes.size - 1, 0, d + 1, null)
                if (rels.isEmpty()) {
                    out.append("${i}return $value;\n")
                } else {
                    out.append("$i{\n${ind(d + 1)}${cType(currentReturn)} r = $value;\n").append(rels).append("${ind(d + 1)}return r;\n$i}\n")
                }
                return
            }
            if (rcPlan?.returnsBorrowed(curFn?.cName ?: "") == true) {
                // borrowed result (Conventions): a reference some parameter keeps alive, no count for the caller; the
                // conformance check guarantees the value does not depend on anything released below
                out.append("$i{\n")
                out.append("${ind(d + 1)}${cType(currentReturn)} r = ${coerceTo(v, currentReturn, top = true)};\n")
                out.append(releases(scopes.size - 1, 0, d + 1, null))
                out.append("${ind(d + 1)}return r;\n$i}\n")
                return
            }
            // reference result: +1 for the caller. An owned local is moved out instead of retained.
            val moved = fplan?.moves?.get(s)
                ?: (v as? MirExpr.Local)?.local?.takeIf { l -> scopes.any { sc -> sc.locals.any { it === l } } }
            out.append("$i{\n")
            out.append("${ind(d + 1)}${cType(currentReturn)} r = ${coerceTo(v, currentReturn, top = true)};\n")
            if (moved == null && !ownedE(v) && !isStaticValue(v)) out.append("${ind(d + 1)}${ret("r", "returned reference is not owned here (+1 for the caller)")};\n")
            out.append(releases(scopes.size - 1, 0, d + 1, moved))
            out.append("${ind(d + 1)}return r;\n$i}\n")
        }

        /** Expression for use as a condition: drops one redundant pair of outer parentheses. */
        fun condExpr(e: MirExpr): String {
            val s = expr(e)
            if (s.length < 2 || s[0] != '(' || s[s.length - 1] != ')' || s.startsWith("({")) return s
            var depth = 0
            for ((i, ch) in s.withIndex()) {
                if (ch == '(') depth++
                if (ch == ')') {
                    depth--
                    if (depth == 0 && i != s.length - 1) return s
                }
            }
            return s.substring(1, s.length - 1)
        }

        fun brk(l: MirLabel) = "brk_${l.id}"
        fun cont(l: MirLabel) = "cont_${l.id}"

        fun loop(label: MirLabel, header: String, body: List<MirStmt>, d: Int, out: StringBuilder) {
            val i = ind(d)
            out.append("$i$header {\n")
            scope(body, d + 1, out, loop = label, mid = { sb ->
                if (cont(label) in usedLabels) sb.append("${ind(d + 1)}${cont(label)}:;\n")
            })
            out.append("$i}\n")
            if (brk(label) in usedLabels) out.append("$i${brk(label)}:;\n")
        }

        fun doWhile(s: MirStmt.DoWhile, d: Int, out: StringBuilder) {
            val i = ind(d)
            out.append("${i}for (;;) {\n")
            scope(s.body, d + 1, out, loop = s.label, extra = s.condStmts, epilogue = false, mid = { sb ->
                if (cont(s.label) in usedLabels) sb.append("${ind(d + 1)}${cont(s.label)}:;\n")
                for (c in s.condStmts) stmt(c, d + 1, sb)
                sb.append("${ind(d + 1)}{\n${ind(d + 2)}MkBoolean c = ${expr(s.cond)};\n")
                sb.append(releases(scopes.size - 1, scopes.size - 1, d + 2, null))
                sb.append("${ind(d + 2)}if (!c) break;\n${ind(d + 1)}}\n")
            })
            out.append("$i}\n")
            if (brk(s.label) in usedLabels) out.append("$i${brk(s.label)}:;\n")
        }

        fun forRange(s: MirStmt.ForRange, d: Int, out: StringBuilder) {
            val i = ind(d)
            val t = cType(s.variable.type)
            val v = Mangling.local(s.variable.name, s.variable.id)
            val n = s.label.id
            val cur = "cur_$n"
            val last = "last_$n"
            val st = "step_$n"
            out.append("$i{\n")
            out.append("${ind(d + 1)}$t $cur = ${expr(s.start)};\n")
            out.append("${ind(d + 1)}$t $last = ${expr(s.end)};\n")
            if (s.step != null) {
                out.append("${ind(d + 1)}int64_t $st = (int64_t)(${expr(s.step)});\n")
                out.append("${ind(d + 1)}if ($st <= 0) {\n${ind(d + 2)}mk_raise_illegal_argument(\"Step must be positive.\");\n${exitStmt(d + 2)}${ind(d + 1)}}\n")
            } else {
                out.append("${ind(d + 1)}const int64_t $st = 1;\n")
            }
            val enter = when {
                s.ascending && s.inclusive -> "$cur <= $last"
                s.ascending -> "$cur < $last"
                s.inclusive -> "$cur >= $last"
                else -> "$cur > $last"
            }
            // distance from the current element to the end, exact in uint64
            val dist = if (s.ascending) "(uint64_t)(int64_t)$last - (uint64_t)(int64_t)$cur" else "(uint64_t)(int64_t)$cur - (uint64_t)(int64_t)$last"
            val stop = if (s.inclusive) "$dist < (uint64_t)$st" else "$dist <= (uint64_t)$st"
            val adv = if (s.ascending) "(int64_t)$cur + $st" else "(int64_t)$cur - $st"
            out.append("${ind(d + 1)}if ($enter) {\n")
            out.append("${ind(d + 2)}for (;;) {\n")
            out.append("${ind(d + 3)}const $t $v = $cur;\n")
            scope(s.body, d + 3, out, loop = s.label, mid = { sb ->
                if (cont(s.label) in usedLabels) sb.append("${ind(d + 3)}${cont(s.label)}:;\n")
            })
            out.append("${ind(d + 3)}if ($stop) break;\n")
            out.append("${ind(d + 3)}$cur = ($t)($adv);\n")
            out.append("${ind(d + 2)}}\n")
            out.append("${ind(d + 1)}}\n")
            if (brk(s.label) in usedLabels) out.append("${ind(d + 1)}${brk(s.label)}:;\n")
            out.append("$i}\n")
        }

        // ---- expressions -------------------------------------------------------------

        fun cType(t: MirType): String = when (t) {
            TInt -> "MkInt"
            TLong -> "MkLong"
            TBoolean -> "MkBoolean"
            TDouble -> "MkDouble"
            TChar -> "MkChar"
            TByte -> "MkByte"
            TShort -> "MkShort"
            TFloat -> "MkFloat"
            is TPtr -> "void *"
            TString, TNString -> "const MkString *"
            TUnit, TNothing -> "void"
            TNull, is TObject, is TBoxed -> "MkObject *"
            is TInline -> cType(t.rep)
        }

        /** [e] as a C expression of C type `cType(to)` (adds a cast between reference types). */
        fun coerceTo(e: MirExpr, to: MirType, top: Boolean = false): String {
            val s = expr(e, top)
            if (!to.isRef || !e.type.isRef) return s
            return if (cType(e.type) == cType(to)) s else "((${cType(to)})$s)"
        }

        fun obj(e: MirExpr): String = "((MkObject *)${expr(e)})"

        fun needsCheck(e: MirExpr): Boolean = when (e) {
            is MirExpr.Call -> e.cName in throwing.mayThrow
            is MirExpr.RuntimeCall -> ThrowAnalysis.runtimeCallThrows(e.cName)
            is MirExpr.VCall, is MirExpr.ICall -> throwing.dynamicMayThrow
            is MirExpr.New -> e.ctorCName in throwing.mayThrow
            is MirExpr.Unbox, is MirExpr.NotNull -> true
            is MirExpr.Cast -> e.mode == MirExpr.CastMode.Checked
            is MirExpr.Binary -> (e.op == BinaryOp.Div || e.op == BinaryOp.Rem) && (e.left.type == TInt || e.left.type == TLong) &&
                !((e.right is MirExpr.IntConst && e.right.value != 0) || (e.right is MirExpr.LongConst && e.right.value != 0L))
            else -> false
        }

        fun expr(e: MirExpr, top: Boolean = false): String {
            if (!top && ownedE(e)) throw EmitError("owned temporary in operand position: ${e.type}")
            inlineChecked.remove(e)
            val s = exprInner(e)
            return if (needsCheck(e) && !inlineChecked.contains(e)) checked(s, e.type) else s
        }

        /** Array accesses whose failing index leaves the function inline (see [arrayAccess]): they need no test of the pending exception. */
        val inlineChecked = HashSet<MirExpr>()

        /**
         * `mk_array_get_*` / `mk_array_set_*` as one statement expression: the bounds test is the only branch of the fast path and the
         * failing index raises and leaves the function right there (like the LLVM lowering), instead of testing the pending
         * exception after every access. Only when the exit path is short (few locals to release): else the call plus check is
         * smaller.
         */
        fun arrayAccess(e: MirExpr.RuntimeCall): String? {
            val n = e.cName
            val isGet = n.startsWith("mk_array_get_")
            if (!isGet && !n.startsWith("mk_array_set_")) return null
            val kind = n.substring(13)
            val ct = when (kind) {
                "int" -> "MkInt"; "long" -> "MkLong"; "double" -> "MkDouble"; "float" -> "MkFloat"; "byte" -> "MkByte"
                "short" -> "MkShort"; "boolean" -> "MkBoolean"; "char" -> "MkChar"; "obj" -> "MkObject *"
                else -> return null
            }
            if (e.args.size != (if (isGet) 2 else 3) || e.args[1].type !== TInt) return null
            val exit = exitStmt(0).replace("\n", " ").trim()
            if (exit.length > 260) return null
            val id = tmpCounter++
            val a = "mka$id"; val i = "mki$id"; val v = "mkv$id"
            val borrowed = fplan?.borrowedReads?.contains(e) == true
            val sb = StringBuilder("({ MkObject *$a = (MkObject *)${expr(e.args[0])}; MkInt $i = ${expr(e.args[1])}; ")
            if (!isGet) sb.append("$ct $v = ${coerceTo(e.args[2], if (kind == "obj") e.args[2].type else e.args[2].type)}; ")
            sb.append("if (__builtin_expect((uint32_t)$i >= (uint32_t)((MkArray *)$a)->length, 0)) { mk_raise_index($i, ((MkArray *)$a)->length); $exit } ")
            if (isGet) {
                sb.append("$ct $v = MK_ARRAY_ELEMS($ct, $a)[$i]; ")
                if (kind == "obj" && !borrowed) sb.append("${ret(v, "array read")}; ")
                sb.append("$v; })")
            } else if (kind == "obj") {
                sb.append("$ct *mkp$id = &MK_ARRAY_ELEMS($ct, $a)[$i]; ${ret(v, "array store")}; MkObject *mko$id = *mkp$id; *mkp$id = $v; ${rel("mko$id", "array store")}; (void)0; })")
            } else {
                sb.append("MK_ARRAY_ELEMS($ct, $a)[$i] = $v; (void)0; })")
            }
            inlineChecked.add(e)
            return sb.toString()
        }

        /**
         * The checks of erased values (`as T` of a generic read, a field that may be unset, the unbox of a box) as one statement expression:
         * the test is the only code of the fast path, the failing case raises and leaves the function right there (no load of the pending
         * exception after every check). Only when the exit path is short, like [arrayAccess].
         */
        fun inlineGuard(e: MirExpr, operand: MirExpr, cond: (String) -> String, slow: (String) -> String, value: (String) -> String, post: (String) -> String = { "" }, decl: String = ""): String? {
            val exit = exitStmt(0).replace("\n", " ").trim()
            if (exit.length > 260) return null
            val c = "mkc${tmpCounter++}"
            val s = "({ $decl MkObject *$c = ${obj(operand)}; if (__builtin_expect(${cond(c)}, 0)) { ${slow(c)} if (mk_pending_exception != NULL) { $exit } ${post(c)} } ${value(c)}; })"
            inlineChecked.add(e)
            return s
        }

        fun notNullInline(e: MirExpr.NotNull): String? =
            inlineGuard(e, e.operand, { "$it == NULL" }, { "(void)mk_checked_nn($it);" }, { "(${cType(e.type)})$it" })

        fun castInline(e: MirExpr.Cast): String? {
            val to = e.to
            if (to is TObject && to.elem != null) return null // an array with a known element type: mk_array_cast
            if (to is TObject && to.cName == ANY_CNAME) {
                if (to.nullable) return null
                return inlineGuard(e, e.operand, { "$it == NULL" }, { "(void)mk_checked_nn($it);" }, { "(MkObject *)$it" })
            }
            val ti = "&${typeInfoOf(to)}"
            val nullable = to.isNullableRef()
            val value = { c: String -> "(${cType(to)})$c" }
            val slow = { c: String -> if (nullable) "(void)mk_checked_cast($c, $ti);" else "(void)mk_checked_cast_nn($c, $ti);" }
            if (to is TObject && classes[to.cName]?.isInterface == true) {
                // an interface is never the exact type of an object: the type that last passed this cast is remembered (a monomorphic site then costs one compare)
                val k = "mkk${tmpCounter++}"
                val cond = { c: String -> if (nullable) "$c != NULL && $c->type != $k" else "$c == NULL || $c->type != $k" }
                return inlineGuard(e, e.operand, cond, slow, value, { c -> "$k = $c->type;" }, "static const MkTypeInfo *$k;")
            }
            // a class: the object's own type, or its direct superclass (the common hierarchy of one abstract base)
            val leaf = to !is TObject || (classes[to.cName]?.let { !it.external && it.cName !in extended() } == true)
            val ok = { c: String -> if (!leaf) "$c->type != $ti && $c->type->super != $ti" else "$c->type != $ti" }
            val cond = { c: String -> if (nullable) "$c != NULL && ${ok(c)}" else "$c == NULL || (${ok(c)})" }
            return inlineGuard(e, e.operand, cond, slow, value)
        }

        fun unboxInline(e: MirExpr.Unbox): String? {
            val k = boxName(e.to)
            val ct = cType(e.to)
            val exit = exitStmt(0).replace("\n", " ").trim()
            if (exit.length > 260) return null
            val id = tmpCounter++
            val c = "mkc$id"; val r = "mkr$id"
            val tn = when (k) { "int" -> "Int"; "long" -> "Long"; "boolean" -> "Boolean"; "double" -> "Double"; "float" -> "Float"; "byte" -> "Byte"; "short" -> "Short"; else -> "Char" }
            val s = "({ MkObject *$c = ${obj(e.operand)}; $ct $r; " +
                "if (__builtin_expect($c != NULL && $c->type == &mk_type_$tn, 1)) $r = ((const struct { MkObject h; $ct v; } *)$c)->v; " +
                "else { $r = mk_checked_unbox_$k($c); if (mk_pending_exception != NULL) { $exit } } $r; })"
            inlineChecked.add(e)
            return s
        }

        fun exprInner(e: MirExpr): String {
            return when (e) {
                is MirExpr.IntConst -> if (e.value == Int.MIN_VALUE) "(-2147483647-1)" else if (e.value < 0) "(${e.value})" else e.value.toString()
                is MirExpr.LongConst ->
                    if (e.value == Long.MIN_VALUE) "(-INT64_C(9223372036854775807)-1)"
                    else if (e.value < 0) "(-INT64_C(${-e.value}))" else "INT64_C(${e.value})"
                is MirExpr.DoubleConst -> doubleLiteral(e.value)
                is MirExpr.BooleanConst -> if (e.value) "true" else "false"
                is MirExpr.CharConst -> "((MkChar)${e.code})"
                is MirExpr.StringConst -> {
                    val name = literals.getOrPut(e.value) { "kstr_${literals.size}" }
                    "((const MkString *)&$name)"
                }
                MirExpr.UnitValue -> "((void)0)"
                is MirExpr.Local -> lname(e.local)
                is MirExpr.Binary -> binary(e)
                is MirExpr.Unary -> unary(e)
                is MirExpr.Convert -> convert(e)
                is MirExpr.Call -> {
                    val f = sig[e.cName] ?: throw EmitError("call of unknown function ${e.cName}")
                    if (f.params.size != e.args.size) throw EmitError("wrong argument count for ${e.cName}")
                    val mask = rcPlan?.ownedMask(e.cName)
                    if (mask == null) "${e.cName}(${e.args.indices.joinToString(", ") { coerceTo(e.args[it], f.params[it].type) }})"
                    else ownedCall(e.cName, f.params.map { it.type }, e.args, mask, 0)
                }
                is MirExpr.RuntimeCall -> {
                    val owns = NativeConventions.ownedArgs(e.cName)
                    if (e.cName == "mk_free_dealloc" && !tr.enabled) "((void)0)"
                    else if (owns != null) ownedCall(e.cName, e.args.map { it.type }, e.args, BooleanArray(e.args.size) { k -> ownsIndex(owns, k) }, 0)
                    else arrayAccess(e) ?: "${if (fplan?.borrowedReads?.contains(e) == true) e.cName + "_b" else e.cName}(${e.args.joinToString(", ") { expr(it) }})"
                }
                is MirExpr.StringLength -> "((MkInt)${expr(e.operand)}->length)"
                is MirExpr.Load -> { usesMemory = true; "MKH_LOAD(${cType(e.type)}, ${expr(e.ptr)})" }
                is MirExpr.PtrAdd -> { usesMemory = true; "mkh_padd(${expr(e.ptr)}, ${expr(e.bytes)})" }
                is MirExpr.CCall -> {
                    val name = externNames[e.extern.prototypeKey] ?: throw EmitError("call of undeclared C function ${e.extern.symbol}")
                    "$name(${e.args.joinToString(", ") { expr(it) }})"
                }
                is MirExpr.CCallIndirect ->
                    "((${cType(e.type)} (*)(${if (e.paramTypes.isEmpty()) "void" else e.paramTypes.joinToString(", ") { cType(it) }}))${expr(e.callee)})(${e.args.joinToString(", ") { expr(it) }})"
                is MirExpr.FuncAddr -> "((void *)${e.cName})"
                is MirExpr.Conditional -> "(${expr(e.cond)} ? ${coerceTo(e.thenValue, e.type)} : ${coerceTo(e.elseValue, e.type)})"
                is MirExpr.Unreachable -> when (e.type.repr) {
                    TDouble -> "0.0"
                    TFloat -> "0.0f"
                    TUnit, TNothing -> "((void)0)"
                    else -> "(${cType(e.type)})0"
                }
                is MirExpr.NullConst -> "NULL"
                is MirExpr.Box -> "mk_box_${boxName(e.operand.type)}(${expr(e.operand)})"
                is MirExpr.Unbox -> unboxInline(e) ?: "mk_checked_unbox_${boxName(e.to)}(${obj(e.operand)})"
                is MirExpr.Cast -> if (e.mode == MirExpr.CastMode.Checked && prunedClass(e.to)) cast(MirExpr.Cast(e.operand, e.to, MirExpr.CastMode.Unchecked))
                    else (if (e.mode == MirExpr.CastMode.Checked) castInline(e) else null) ?: cast(e)
                is MirExpr.IsInstance -> arrayIs(e) ?: "${if (isClassTarget(e.target)) "mkh_is_class" else "mk_is_instance"}((const MkObject *)${expr(e.operand)}, &${typeInfoOf(e.target)})"
                is MirExpr.NotNull -> notNullInline(e) ?: "((${cType(e.type)})mkh_nn((MkObject *)${expr(e.operand)}))"
                is MirExpr.FieldGet -> "(((struct ${e.owner} *)${expr(e.obj)})->${Mangling.field(e.field)})"
                is MirExpr.VCall -> {
                    val fp = fnPtrType(e.type, e.paramTypes)
                    val mask = rcPlan?.ownedMaskOf(e)
                    if (mask != null) {
                        ownedDynamic(e.receiver, e.args, e.paramTypes, mask) { r, args ->
                            "((($fp)$r->type->vtable[${e.slot}]))(${listOf(r).plus(args).joinToString(", ")})"
                        }
                    } else {
                        val r = obj(e.receiver)
                        "((($fp)$r->type->vtable[${e.slot}]))(${listOf(r).plus(argList(e.args, e.paramTypes)).joinToString(", ")})"
                    }
                }
                is MirExpr.ICall -> {
                    val fp = fnPtrType(e.type, e.paramTypes)
                    val mask = rcPlan?.ownedMaskOf(e)
                    if (mask != null) {
                        ownedDynamic(e.receiver, e.args, e.paramTypes, mask) { r, args ->
                            "((($fp)mk_itable($r, &${e.interfaceCName}_ti)[${e.index}]))(${listOf(r).plus(args).joinToString(", ")})"
                        }
                    } else {
                        val r = obj(e.receiver)
                        "((($fp)mk_itable($r, &${e.interfaceCName}_ti)[${e.index}]))(${listOf(r).plus(argList(e.args, e.paramTypes)).joinToString(", ")})"
                    }
                }
                is MirExpr.New -> {
                    val f = sig[e.ctorCName] ?: throw EmitError("unknown constructor ${e.ctorCName}")
                    val ps = f.params.drop(1)
                    val mask = rcPlan?.ownedMask(e.ctorCName)
                    val al = e.allocator
                    if (al != null && tr.enabled && curFn?.strict == true) {
                        newaUsed += e.ctorCName
                        val lead = expr(al)
                        if (mask == null) "${e.ctorCName}_newa($lead${e.args.indices.joinToString("") { ", " + coerceTo(e.args[it], ps[it].type) }})"
                        else ownedCall("${e.ctorCName}_newa", ps.map { it.type }, e.args, mask, 1, lead)
                    } else if (mask == null) "${e.ctorCName}_new(${e.args.indices.joinToString(", ") { coerceTo(e.args[it], ps[it].type) }})"
                    else ownedCall("${e.ctorCName}_new", ps.map { it.type }, e.args, mask, 1)
                }
                is MirExpr.ObjectGet -> "${e.classCName}_get()"
                is MirExpr.CaughtException -> caught.lastOrNull() ?: throw EmitError("CaughtException outside of a catch clause")
                is MirExpr.TypeInfoOf -> if (e.target == null) "NULL" else "(&${typeInfoOf(e.target)})"
            }
        }

        /**
         * A call whose callee owns some parameters (RcOpt convention): all arguments are evaluated first (an exit
         * while doing so still finds every local intact), then the callee's references are handed over: a moved
         * local gives its own away, anything else is retained.
         */
        fun ownedCall(callee: String, ptypes: List<MirType>, args: List<MirExpr>, mask: BooleanArray, shift: Int, lead: String? = null): String {
            val temps = args.indices.map { "mkp${tmpCounter++}" }
            val sb = StringBuilder("({ ")
            val leadTemp = if (lead != null) "mkp${tmpCounter++}" else null
            if (lead != null) sb.append("MkLong $leadTemp = $lead; ")
            for (k in args.indices) sb.append("${cType(ptypes[k])} ${temps[k]} = ${coerceTo(args[k], ptypes[k])}; ")
            for (k in args.indices) {
                if (k + shift >= mask.size || !mask[k + shift] || !ptypes[k].isRef || isStaticValue(args[k])) continue
                val x = fplan?.takes?.get(args[k])
                if (x == null) {
                    // a compiler temporary that holds a fresh Strict object: its count is the one the callee takes
                    if (!tr.movedTemp(curFn, args[k])) sb.append("${ret(temps[k], "reference handed to an owned parameter (the caller still needs its own)")}; ")
                }
                else if (closedFor(curStmt, x)) dead += x.id
                else sb.append("${lname(x)} = NULL; ")
            }
            sb.append("$callee(${(listOfNotNull(leadTemp) + temps).joinToString(", ")}); })")
            return sb.toString()
        }

        /**
         * A virtual / interface call whose callee group owns some parameters ([Conventions]): receiver and arguments are
         * evaluated first, then the references the callee owns are handed over (a moved local gives its own, anything
         * else is retained), then [call] builds the dispatch with the temporaries. [mask] is over `this` + parameters.
         */
        fun ownedDynamic(receiver: MirExpr, args: List<MirExpr>, ptypes: List<MirType>, mask: BooleanArray, call: (String, List<String>) -> String): String {
            val r = "mkr${tmpCounter++}"
            val temps = args.indices.map { "mkp${tmpCounter++}" }
            val sb = StringBuilder("({ MkObject *$r = ${obj(receiver)}; ")
            for (k in args.indices) sb.append("${cType(ptypes[k])} ${temps[k]} = ${coerceTo(args[k], ptypes[k])}; ")
            for (k in args.indices) {
                if (k + 1 >= mask.size || !mask[k + 1] || !ptypes[k].isRef || isStaticValue(args[k])) continue
                val x = fplan?.takes?.get(args[k])
                if (x == null) sb.append("${ret(temps[k], "reference handed to an owned parameter (the caller still needs its own)")}; ")
                else if (closedFor(curStmt, x)) dead += x.id
                else sb.append("${lname(x)} = NULL; ")
            }
            sb.append(call(r, temps)).append("; })")
            return sb.toString()
        }

        fun ownsIndex(owns: IntArray, k: Int): Boolean {
            for (x in owns) if (x == k) return true
            return false
        }

        fun argList(args: List<MirExpr>, types: List<MirType>): List<String> = args.indices.map { coerceTo(args[it], types[it]) }

        fun fnPtrType(ret: MirType, params: List<MirType>): String =
            "${cType(ret)} (*)(${(listOf("MkObject *") + params.map { cType(it) }).joinToString(", ")})"

        /** A class of the program that is not in the module: it was pruned because nothing creates it (a value class that is never boxed), no object has its type info. */
        fun prunedClass(t: MirType): Boolean = t is TObject && t.cName.startsWith("kc_") && (classes[t.cName]?.external ?: true)

        fun cast(e: MirExpr.Cast): String {
            val to = e.to
            return when (e.mode) {
                MirExpr.CastMode.Unchecked -> {
                    val s = expr(e.operand)
                    if (cType(e.operand.type) == cType(to)) s else "((${cType(to)})$s)"
                }
                MirExpr.CastMode.Checked -> {
                    if (to is TObject && to.elem != null) return "((${cType(to)})mk_array_cast(${obj(e.operand)}, &${typeInfoOf(arrayElemTypeInfoType(to.elem!!))}, ${arrayCastFlags(to)}))"
                    // everything is an Any: only the null check is left (the erased `as E` of every generic getter)
                    if (to is TObject && to.cName == ANY_CNAME) return if (to.nullable) "((MkObject *)${obj(e.operand)})" else "((MkObject *)mkh_nn(${obj(e.operand)}))"
                    val fn = if (to.isNullableRef()) "mkh_cc" else "mkh_cast_nn"
                    "((${cType(to)})$fn(${obj(e.operand)}, &${typeInfoOf(to)}))"
                }
                MirExpr.CastMode.Safe -> if (to is TObject && to.elem != null) {
                    val t = "mkc${tmpCounter++}"
                    "({ MkObject *$t = ${obj(e.operand)}; (${cType(to)})(mk_array_is($t, &${typeInfoOf(arrayElemTypeInfoType(to.elem!!))}, ${arrayCastFlags(to)}) ? $t : NULL); })"
                } else "((${cType(to)})mkh_safe_cast(${obj(e.operand)}, &${typeInfoOf(to)}))"
            }
        }

        /** `x is Array<E>` for an array type with a known element type. */
        fun arrayIs(e: MirExpr.IsInstance): String? {
            val t = e.target
            if (t !is TObject) return null
            val el = t.elem ?: return null
            return "mk_array_is((const MkObject *)${expr(e.operand)}, &${typeInfoOf(arrayElemTypeInfoType(el))}, ${arrayCastFlags(t)})"
        }

        /** A class (not an interface) as the target of an `is` test: only the superclass chain of the object's type can contain it. */
        fun isClassTarget(t: MirType): Boolean = t === TString || t === TNString || t is TBoxed || (t is TObject && classes[t.cName]?.isInterface == false)

        fun MirType.isNullableRef(): Boolean = this === TNString || (this is TObject && nullable) || this is TBoxed

        fun doubleLiteral(v: Double): String = when {
            v.isNaN() -> "NAN"
            v == Double.POSITIVE_INFINITY -> "INFINITY"
            v == Double.NEGATIVE_INFINITY -> "(-INFINITY)"
            else -> {
                val s = v.toString()
                if (s.startsWith("-")) "($s)" else s
            }
        }

        fun unsignedOf(t: MirType): String = when (t) {
            TInt -> "uint32_t"
            TLong -> "uint64_t"
            else -> throw EmitError("no unsigned type for $t")
        }

        fun binary(e: MirExpr.Binary): String {
            val op = e.op
            val t = e.left.type
            if (op == BinaryOp.IdEq || op == BinaryOp.IdNe) {
                val c = if (op == BinaryOp.IdEq) "==" else "!="
                return "((const void *)${expr(e.left)} $c (const void *)${expr(e.right)})"
            }
            val a = expr(e.left)
            val b = expr(e.right)
            if (op == BinaryOp.AndAnd) return "($a && $b)"
            if (op == BinaryOp.OrOr) return "($a || $b)"
            if (t == TString) {
                return when (op) {
                    BinaryOp.Eq -> "mk_string_equals($a, $b)"
                    BinaryOp.Ne -> "(!mk_string_equals($a, $b))"
                    else -> throw EmitError("operator $op on String")
                }
            }
            if (op.isComparison) {
                val c = when (op) {
                    BinaryOp.Eq -> "=="; BinaryOp.Ne -> "!="; BinaryOp.Lt -> "<"
                    BinaryOp.Le -> "<="; BinaryOp.Gt -> ">"; else -> ">="
                }
                if (t.isRef) throw EmitError("operator $op on references")
                return "($a $c $b)"
            }
            if (t == TBoolean) {
                return when (op) {
                    BinaryOp.And -> "($a & $b)"; BinaryOp.Or -> "($a | $b)"; BinaryOp.Xor -> "($a ^ $b)"
                    else -> throw EmitError("operator $op on Boolean")
                }
            }
            if (t == TDouble) {
                return when (op) {
                    BinaryOp.Add -> "($a + $b)"; BinaryOp.Sub -> "($a - $b)"; BinaryOp.Mul -> "($a * $b)"
                    BinaryOp.Div -> "($a / $b)"; BinaryOp.Rem -> "fmod($a, $b)"
                    else -> throw EmitError("operator $op on Double")
                }
            }
            if (t == TFloat) {
                return when (op) {
                    BinaryOp.Add -> "($a + $b)"; BinaryOp.Sub -> "($a - $b)"; BinaryOp.Mul -> "($a * $b)"
                    BinaryOp.Div -> "($a / $b)"; BinaryOp.Rem -> "fmodf($a, $b)"
                    else -> throw EmitError("operator $op on Float")
                }
            }
            if (t != TInt && t != TLong) throw EmitError("operator $op on $t")
            val c = cType(t)
            val u = unsignedOf(t)
            val mask = if (t == TInt) "31" else "63"
            val suffix = if (t == TInt) "int" else "long"
            return when (op) {
                BinaryOp.Add -> "($c)(($u)$a + ($u)$b)"
                BinaryOp.Sub -> "($c)(($u)$a - ($u)$b)"
                BinaryOp.Mul -> "($c)(($u)$a * ($u)$b)"
                BinaryOp.Div -> "mkh_div_$suffix($a, $b)"
                BinaryOp.Rem -> "mkh_rem_$suffix($a, $b)"
                BinaryOp.Shl -> "($c)(($u)$a << (($b) & $mask))"
                BinaryOp.Shr -> "($c)($a >> (($b) & $mask))"
                BinaryOp.Ushr -> "($c)(($u)$a >> (($b) & $mask))"
                BinaryOp.And -> "($a & $b)"
                BinaryOp.Or -> "($a | $b)"
                BinaryOp.Xor -> "($a ^ $b)"
                else -> throw EmitError("operator $op on $t")
            }
        }

        fun unary(e: MirExpr.Unary): String {
            val a = expr(e.operand)
            val t = e.operand.type
            return when (e.op) {
                UnaryOp.Not -> "(!$a)"
                UnaryOp.Neg -> when (t) {
                    TDouble, TFloat -> "(-$a)"
                    TInt, TLong -> {
                        val u = unsignedOf(t)
                        "(${cType(t)})(($u)0 - ($u)$a)"
                    }
                    else -> throw EmitError("negation of $t")
                }
                UnaryOp.Inv -> when (t) {
                    TInt, TLong -> "(${cType(t)})~(${unsignedOf(t)})$a"
                    else -> throw EmitError("inversion of $t")
                }
            }
        }

        fun convert(e: MirExpr.Convert): String {
            val a = expr(e.operand)
            val from = e.operand.type.repr
            val to = e.to.repr
            if (from == to) return a
            val smallInt = from == TByte || from == TShort
            return when {
                // C interop scalars: Byte, Short, Float and pointers
                to is TPtr && from is TPtr -> a
                to is TPtr && from == TLong -> "((void *)(intptr_t)$a)"
                to == TLong && from is TPtr -> "((MkLong)(intptr_t)$a)"
                to == TByte && (from == TInt || from == TShort || from == TChar) -> "((MkByte)(uint8_t)$a)"
                to == TByte && from == TLong -> "((MkByte)(uint8_t)(uint64_t)$a)"
                to == TByte && (from == TDouble || from == TFloat) -> "((MkByte)(uint8_t)(uint32_t)mkh_d2i((MkDouble)$a))"
                to == TShort && (from == TInt || from == TByte || from == TChar) -> "((MkShort)(uint16_t)$a)"
                to == TShort && from == TLong -> "((MkShort)(uint16_t)(uint64_t)$a)"
                to == TShort && (from == TDouble || from == TFloat) -> "((MkShort)(uint16_t)(uint32_t)mkh_d2i((MkDouble)$a))"
                to == TInt && smallInt -> "((MkInt)$a)"
                to == TInt && from == TFloat -> "mkh_d2i((MkDouble)$a)"
                to == TLong && smallInt -> "((MkLong)$a)"
                to == TLong && from == TFloat -> "mkh_d2l((MkDouble)$a)"
                to == TChar && smallInt -> "((MkChar)(uint16_t)$a)"
                to == TChar && from == TFloat -> "((MkChar)(uint16_t)(uint32_t)mkh_d2i((MkDouble)$a))"
                to == TFloat && (from == TInt || from == TLong || from == TDouble || smallInt || from == TChar) -> "((MkFloat)$a)"
                to == TDouble && (from == TFloat || smallInt) -> "((MkDouble)$a)"
                to == TLong && (from == TInt || from == TChar) -> "((MkLong)$a)"
                to == TInt && from == TChar -> "((MkInt)$a)"
                to == TInt && from == TLong -> "((MkInt)(uint32_t)(uint64_t)$a)"
                to == TChar && (from == TInt || from == TLong) -> "((MkChar)(uint16_t)(${unsignedOf(from)})$a)"
                to == TDouble && (from == TInt || from == TLong || from == TChar) -> "((MkDouble)$a)"
                to == TInt && from == TDouble -> "mkh_d2i($a)"
                to == TLong && from == TDouble -> "mkh_d2l($a)"
                to == TChar && from == TDouble -> "((MkChar)(uint16_t)(uint32_t)mkh_d2i($a))"
                else -> throw EmitError("conversion $from -> $to")
            }
        }

        /** A narrow UTF-8 C literal independent of source encoding and greedy hex escapes. */
        fun cStringLiteral(value: String): String {
            val sb = StringBuilder("\"")
            for (b in mini.backend.llvm.IrStrings.utf8(value)) {
                if (b in 0x20..0x7E && b != 34 && b != 92 && b != 63) sb.append(b.toChar())
                else {
                    // Exactly three octal digits: the following character cannot extend the escape.
                    sb.append('\\').append(('0'.code + (b shr 6)).toChar())
                        .append(('0'.code + ((b shr 3) and 7)).toChar())
                        .append(('0'.code + (b and 7)).toChar())
                }
            }
            return sb.append('"').toString()
        }

        /** The characters of a Latin-1 literal as a C initializer list (with the terminating 0). */
        fun latin1Bytes(value: String): String {
            val sb = StringBuilder()
            for (ch in value) sb.append(ch.code).append(',')
            sb.append('0')
            return sb.toString()
        }

        fun utf16Literal(value: String): String {
            // Printable ASCII is written directly; every other UTF-16 unit as a hex escape,
            // after which the literal is closed and reopened (hex escapes are greedy).
            val sb = StringBuilder("u\"")
            for (ch in value) {
                val c = ch.code
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch == '?' -> sb.append("\\?")
                    c in 0x20..0x7E -> sb.append(ch)
                    else -> {
                        val h = c.toString(16).uppercase()
                        sb.append("\\x").append("0".repeat(4 - h.length)).append(h).append("\" u\"")
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }

    private const val USER_MAIN = "mkh_user_main"

    /** Memory access of C interop: loads and stores of any alignment, outside the aliasing rules (memcpy compiles to a plain access). */
    private const val MEMORY_PRELUDE = """
#define MKH_LOAD(T, p) ({ T mkh_v; memcpy(&mkh_v, (p), sizeof mkh_v); mkh_v; })
MKH_FN void *mkh_padd(void *p, MkLong n) { return (void *)((char *)p + n); }
"""

    private const val PRELUDE = """
#if defined(__GNUC__) || defined(__clang__)
#define MKH_FN static inline __attribute__((unused))
#define MKH_STATIC static __attribute__((unused))
#else
#define MKH_FN static inline
#define MKH_STATIC static
#endif

/* Reference counting with inline fast paths (RcOpt code generation). Anything unusual goes to the runtime:
 * NULL, static/stack/shared objects (atomic), a count that would overflow or reach zero, resurrection. With
 * MK_STATS every operation calls the runtime so that the counters see all of them. */
#if defined(MK_STATS) || defined(MK_ATOMIC_RC)
#define MKH_RETAIN(x) mk_retain((MkObject *)(x))
#define MKH_REL(x) mk_release((MkObject *)(x))
#else
#define MKH_RETAIN(x) do { MkObject *mkr_ = (MkObject *)(x); \
    if (mkr_ != NULL && (mkr_->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) == 0) { \
        if ((mkr_->flags & MK_FLAG_SHARED) == 0 && mkr_->refcount - 1u < UINT32_MAX - 2u) mkr_->refcount++; \
        else mk_retain(mkr_); } } while (0)
#define MKH_REL(x) do { MkObject *mkr_ = (MkObject *)(x); \
    if (mkr_ != NULL && (mkr_->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) == 0) { \
        if ((mkr_->flags & MK_FLAG_SHARED) == 0 && mkr_->refcount > 1u) { mkr_->refcount--; if (mkr_->flags & MK_FLAG_ARMED) mk_cycles_candidate(mkr_); } \
        else mk_release(mkr_); } } while (0)
#endif

/* The reference arrays of the stdlib collections (v1s.c): the slots are read and written inline. The layout is MkArray's (the
 * elements right behind the length); a failing index or a NULL array takes the runtime's panic. The parentheses keep the macros
 * of this block from expanding the names of the real functions. */
#define MKH_RA_ITEMS(a) ((MkObject **)(((MkArray *)(a)) + 1))
MKH_FN MkObject *mkh_ra_get_b(MkObject *a, MkInt i) {
    if (__builtin_expect(a == NULL || (uint32_t)i >= (uint32_t)((MkArray *)a)->length, 0)) return (mk_ref_array_get_b)(a, i);
    return MKH_RA_ITEMS(a)[i];
}
MKH_FN MkObject *mkh_ra_get(MkObject *a, MkInt i) {
    MkObject *v = mkh_ra_get_b(a, i);
    MKH_RETAIN(v);
    return v;
}
MKH_FN void mkh_ra_set_o(MkObject *a, MkInt i, MkObject *v) {
    if (__builtin_expect(a == NULL || (uint32_t)i >= (uint32_t)((MkArray *)a)->length, 0)) { (mk_ref_array_set_o)(a, i, v); return; }
    MkObject **p = &MKH_RA_ITEMS(a)[i];
    MkObject *old = *p;
    *p = v;
    MKH_REL(old);
}
MKH_FN void mkh_ra_set(MkObject *a, MkInt i, MkObject *v) {
    if (__builtin_expect(a == NULL || (uint32_t)i >= (uint32_t)((MkArray *)a)->length, 0)) { (mk_ref_array_set)(a, i, v); return; }
    MKH_RETAIN(v);
    mkh_ra_set_o(a, i, v);
}
MKH_FN MkInt mkh_ra_length(MkObject *a) {
    if (__builtin_expect(a == NULL, 0)) return (mk_ref_array_length)(a);
    return ((MkArray *)a)->length;
}
#define mk_ref_array_get_b(a, i) mkh_ra_get_b((a), (i))
#define mk_ref_array_get(a, i) mkh_ra_get((a), (i))
#define mk_ref_array_set_o(a, i, v) mkh_ra_set_o((a), (i), (v))
#define mk_ref_array_set(a, i, v) mkh_ra_set((a), (i), (v))
#define mk_ref_array_length(a) mkh_ra_length((a))

/* Strings (minirt.h MkString): the length and the indexing of the common case are inline; NULL and a bad index take the runtime. */
MKH_FN MkInt mkh_str_length(const MkString *s) { return s != NULL ? s->length : 0; }
MKH_FN MkChar mkh_str_char_at(const MkString *s, MkInt i) {
    if (__builtin_expect(s == NULL || (uint32_t)i >= (uint32_t)s->length, 0)) return (mk_string_char_at)(s, i);
    return mk_string_at(s, i);
}
#define mk_string_length(s) mkh_str_length((s))
#define mk_string_char_at(s, i) mkh_str_char_at((s), (i))

MKH_FN void mkh_abstract(void) { mk_panic("AbstractMethodError"); }
MKH_FN MkObject *mkh_cast_nn(MkObject *o, const MkTypeInfo *t) {
    if (o == NULL) {
        mk_raise(&MkNullPointerException_ti, "null cannot be cast to non-null type");
        return NULL;
    }
    return o->type == t ? o : mk_checked_cast(o, t);
}
MKH_FN MkObject *mkh_cc(MkObject *o, const MkTypeInfo *t) { return (o == NULL || o->type == t) ? o : mk_checked_cast(o, t); }
MKH_FN MkObject *mkh_nn(MkObject *o) {
    if (o == NULL) mk_raise(&MkNullPointerException_ti, "null cannot be cast to non-null type");
    return o;
}
MKH_FN MkObject *mkh_safe_cast(MkObject *o, const MkTypeInfo *t) { return mk_is_instance(o, t) ? o : NULL; }
/* `is` with a class: the superclass chain of the object's type (an interface needs the runtime's search of the interface tables) */
MKH_FN bool mkh_is_class(const MkObject *o, const MkTypeInfo *t) {
    if (o == NULL) return false;
    for (const MkTypeInfo *k = o->type; k != NULL; k = k->super) if (k == t) return true;
    return false;
}

/* Kotlin-exact integer helpers. */
MKH_FN MkInt mkh_div_int(MkInt a, MkInt b) {
    if (b == 0) {
        mk_raise_arith("/ by zero");
        return 0;
    }
    if (b == -1) return (MkInt)(0u - (uint32_t)a);
    return a / b;
}
MKH_FN MkInt mkh_rem_int(MkInt a, MkInt b) {
    if (b == 0) {
        mk_raise_arith("/ by zero");
        return 0;
    }
    if (b == -1) return 0;
    return a % b;
}
MKH_FN MkLong mkh_div_long(MkLong a, MkLong b) {
    if (b == 0) {
        mk_raise_arith("/ by zero");
        return 0;
    }
    if (b == -1) return (MkLong)(0ull - (uint64_t)a);
    return a / b;
}
MKH_FN MkLong mkh_rem_long(MkLong a, MkLong b) {
    if (b == 0) {
        mk_raise_arith("/ by zero");
        return 0;
    }
    if (b == -1) return 0;
    return a % b;
}
MKH_FN MkInt mkh_d2i(MkDouble d) {
    if (d != d) return 0;
    if (d >= 2147483647.0) return INT32_MAX;
    if (d <= -2147483648.0) return INT32_MIN;
    return (MkInt)d;
}
MKH_FN MkLong mkh_d2l(MkDouble d) {
    if (d != d) return 0;
    if (d >= 9223372036854775807.0) return INT64_MAX;
    if (d <= -9223372036854775808.0) return INT64_MIN;
    return (MkLong)d;
}
"""
}
