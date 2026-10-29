package mini.backend.llvm

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.EmitError
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.NativeConventions
import mini.backend.Tracking
import mini.backend.RcFunctionPlan
import mini.backend.RcOpt
import mini.backend.RcPlan
import mini.backend.TBoolean
import mini.backend.TBoxed
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TNull
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.ThrowAnalysis
import mini.backend.UnaryOp
import mini.backend.isOwned
import mini.backend.isRef

/**
 * MIR to textual LLVM IR (`.ll`), the alternative to [mini.backend.CEmitter] (docs/plan-kotlin.md section 10g).
 *
 * The lowering follows CEmitter statement by statement and uses the same RcOpt decisions ([RcPlan]), so both
 * backends make the same reference-counting and exception-path choices:
 *
 *  - every MIR local is an `alloca` in the entry block (LLVM's mem2reg/SROA turns them into SSA values);
 *  - reference locals are NULL-initialised at the top of their scope and released at every exit of it;
 *  - the exception ABI is the error-return one of the C backend: after a call that can throw, the thread-local
 *    `mk_pending_exception` is tested and the exit path releases the locals it leaves (cold `mk_release`);
 *  - objects have the C layout, computed here ([Layouts]); fields are byte-offset GEPs, so the IR does not
 *    depend on a data layout; type infos, vtables and itables are constant globals with the same contents;
 *  - the runtime is libminirt: the IR calls the functions of minirt.h by name (declared from the MIR types);
 *  - `inlineRc` (RcOpt on, no MK_STATS) uses alwaysinline helpers with the fast paths of MKH_RETAIN/MKH_REL.
 *
 * Pure Kotlin like the rest of the backend (self-hosting rule, section 10a).
 */
object LlvmEmitter {
    /**
     * [rcOpt], [strict] and [conventions] mean what they mean for [mini.backend.CEmitter.emit] (RcOpt, Strict code generation
     * with tracked values, borrowed results / owned parameters of the stdlib hot paths); [stats] is the MK_STATS build.
     */
    fun emit(
        module: MirModule,
        rcOpt: Boolean = true,
        strict: Boolean = true,
        conventions: Boolean = true,
        stats: Boolean = false,
        target: LlvmTarget = LlvmTarget.HOST,
    ): String {
        val tr = Tracking(module, strict)
        val plan = if (rcOpt) RcOpt.analyze(module, tr, conventions) else null
        return State(module, plan, tr, stats, target).run()
    }

    private const val USER_MAIN = "mkh_user_main"
    private const val ATTR = "#0"
    private const val COLD = ", !prof !0"

    private class Scope(val locals: List<MirLocal>, val loop: MirLabel?)
    private class Handler(val label: String, val scopeIndex: Int)

    private class State(val module: MirModule, val rcPlan: RcPlan?, val tr: Tracking, val stats: Boolean, val target: LlvmTarget) {
        var curFn: MirFunction? = null
        val newaUsed = LinkedHashSet<String>()

        fun rcT(t: MirType): Boolean = tr.rc(curFn, t)
        fun rcL(l: MirLocal): Boolean = tr.rcLocal(curFn, l)
        fun ownedE(e: MirExpr): Boolean = tr.owned(curFn, e)

        val sig = HashMap<String, MirFunction>()
        val classes = HashMap<String, MirClass>()
        lateinit var layouts: Layouts
        lateinit var throwing: ThrowAnalysis

        val inlineRc: Boolean = rcPlan != null && !stats
        val sz: String = target.sizeT
        val pb: Int = target.ptrBytes

        // module level
        val literals = LinkedHashMap<String, String>() // kotlin value -> global name
        val cstrings = LinkedHashMap<String, String>() // text -> global name
        val externs = LinkedHashMap<String, String>() // function name -> declare line
        val externGlobals = LinkedHashMap<String, String>() // symbol -> declaration line

        // per function
        val body = StringBuilder()
        val allocas = StringBuilder()
        val allocated = HashSet<Int>()
        var terminated = false
        var curLabel = "entry"
        var tmpCounter = 0
        var lblCounter = 0
        val scopes = ArrayList<Scope>()
        var currentReturn: MirType = TUnit
        var fplan: RcFunctionPlan? = null
        val dead = HashSet<Int>()
        val pending = HashSet<Int>()
        var curStmt: MirStmt? = null
        val handlers = ArrayList<Handler>()
        val caught = ArrayList<String>()
        var tryCounter = 0
        val usedLabels = HashSet<String>()

        // ---- module ---------------------------------------------------------------------

        fun run(): String {
            for (f in module.functions) {
                if (sig.put(f.cName, f) != null) throw EmitError("duplicate C symbol ${f.cName}")
            }
            for (c in module.classes) classes[c.cName] = c
            layouts = Layouts(classes, target)
            throwing = ThrowAnalysis(module).run()

            val fns = StringBuilder()
            for (f in module.functions) emitFunction(f, fns)
            val userMain = module.functions.firstOrNull { it.cName == Mangling.MAIN }
            if (userMain != null) emitMainWrapper(userMain, fns)
            val clsText = StringBuilder()
            emitClasses(clsText)
            val prelude = StringBuilder()
            emitPrelude(prelude)

            val out = StringBuilder()
            out.append("; Generated by minic (LLVM backend) from ${comment(module.sourceName)}. Do not edit.\n")
            out.append("source_filename = \"${cquote(module.sourceName)}\"\n")
            if (target.dataLayout != null) out.append("target datalayout = \"${target.dataLayout}\"\n")
            if (target.triple != null) out.append("target triple = \"${target.triple}\"\n")
            out.append("\n%MkTypeInfo = type <{ ptr, $sz, ptr, ptr, ptr, $sz, ptr, $sz }>\n\n")
            out.append("@mk_pending_exception = external thread_local global ptr\n")
            for ((_, line) in externGlobals) out.append(line).append('\n')
            out.append('\n')
            for ((_, line) in externs) out.append(line).append('\n')
            out.append("\n")
            for ((text, name) in cstrings) out.append("$name = private unnamed_addr constant [${utf8(text).size + 1} x i8] c\"${cbytes(text)}\\00\"\n")
            if (literals.isNotEmpty()) {
                out.append("\n; string literals (UTF-16)\n")
                for ((value, name) in literals) out.append(stringLiteral(name, value)).append('\n')
            }
            out.append("\n").append(clsText)
            out.append("\n").append(prelude)
            out.append("\n").append(fns)
            // sanitize_address: with -fsanitize=address clang instruments only functions that carry the attribute
            out.append("attributes #0 = { nounwind sanitize_address }\n")
            out.append("attributes #1 = { nounwind alwaysinline sanitize_address }\n")
            out.append("attributes #2 = { noreturn nounwind }\n")
            out.append("\n!0 = !{!\"branch_weights\", i32 1, i32 2000}\n")
            return out.toString()
        }

        fun comment(s: String): String = s.replace('\n', ' ').replace('\r', ' ')

        /** A pooled NUL-terminated C string constant; the result is its global (a `ptr` operand). */
        fun cstr(text: String): String = cstrings.getOrPut(text) { "@.cs.${cstrings.size}" }

        fun utf8(s: String): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i < s.length) {
                var c = s[i].code
                i++
                if (c in 0xD800..0xDBFF && i < s.length && s[i].code in 0xDC00..0xDFFF) {
                    c = 0x10000 + ((c - 0xD800) shl 10) + (s[i].code - 0xDC00)
                    i++
                } else if (c in 0xD800..0xDFFF) {
                    c = 0xFFFD
                }
                if (c < 0x80) out.add(c)
                else if (c < 0x800) { out.add(0xC0 or (c shr 6)); out.add(0x80 or (c and 63)) }
                else if (c < 0x10000) { out.add(0xE0 or (c shr 12)); out.add(0x80 or ((c shr 6) and 63)); out.add(0x80 or (c and 63)) }
                else { out.add(0xF0 or (c shr 18)); out.add(0x80 or ((c shr 12) and 63)); out.add(0x80 or ((c shr 6) and 63)); out.add(0x80 or (c and 63)) }
            }
            return out
        }

        fun hex2(v: Int): String {
            val h = "0123456789ABCDEF"
            return "" + h[(v shr 4) and 15] + h[v and 15]
        }

        /** The body of an LLVM `c"..."` string: printable ASCII as is, everything else (UTF-8 bytes) as `\HH`. */
        fun cbytes(s: String): String {
            val sb = StringBuilder()
            for (v in utf8(s)) {
                if (v in 0x20..0x7E && v != '"'.code && v != '\\'.code) sb.append(v.toChar()) else sb.append('\\').append(hex2(v))
            }
            return sb.toString()
        }

        fun cquote(s: String): String = cbytes(s)

        fun stringLiteral(name: String, value: String): String {
            val n = value.length + 1
            val chars = StringBuilder()
            for (ch in value) chars.append("i16 ").append(ch.code).append(", ")
            chars.append("i16 0")
            return "$name = internal constant <{ ptr, i32, i32, i32, [$n x i16] }> <{ ptr @mk_type_String, i32 0, i32 1, i32 ${value.length}, [$n x i16] [$chars] }>, align 8"
        }

        // ---- types ----------------------------------------------------------------------

        fun lt(t: MirType): String = when (t) {
            TInt -> "i32"
            TLong -> "i64"
            TBoolean -> "i1"
            TDouble -> "double"
            TChar -> "i16"
            TString, TNString -> "ptr"
            TUnit, TNothing -> "void"
            TNull, is TObject, is TBoxed -> "ptr"
        }

        /** The ABI attribute of an argument / result of type [t] (C `bool` and `uint16_t` are zero-extended). */
        fun ext(t: MirType): String = if (t === TBoolean || t === TChar) " zeroext" else ""

        /** The same attribute on a result: it goes before the type (`call zeroext i1 @f()`). */
        fun rext(t: MirType): String = if (t === TBoolean || t === TChar) "zeroext " else ""

        fun zero(t: MirType): String = when (t) {
            TDouble -> "0.0"
            TBoolean -> "false"
            TInt, TLong, TChar -> "0"
            else -> "null"
        }

        fun typeInfoOf(t: MirType): String = when (t) {
            TString, TNString -> "@mk_type_String"
            is TObject -> if (t.cName == ANY_CNAME) "@mk_type_Any" else "@${t.cName}_ti"
            is TBoxed -> "@" + boxTypeInfo(t.prim)
            else -> throw EmitError("no type info for $t")
        }

        fun boxTypeInfo(p: MirType): String = when (p) {
            TInt -> "mk_type_Int"
            TLong -> "mk_type_Long"
            TBoolean -> "mk_type_Boolean"
            TDouble -> "mk_type_Double"
            TChar -> "mk_type_Char"
            else -> throw EmitError("no box for $p")
        }

        fun boxName(p: MirType): String = when (p) {
            TInt -> "int"
            TLong -> "long"
            TBoolean -> "boolean"
            TDouble -> "double"
            TChar -> "char"
            else -> throw EmitError("no box for $p")
        }

        /** Declares the runtime function [name] from the MIR types (first use wins) and returns its operand. */
        fun declare(name: String, ret: MirType, params: List<MirType>, attrs: String = ATTR): String {
            if (!externs.containsKey(name)) {
                val ps = params.joinToString(", ") { lt(it) + ext(it) }
                externs[name] = "declare ${rext(ret)}${lt(ret)} @$name($ps) $attrs"
            }
            return "@$name"
        }

        /** A vtable / itable entry that is not a function of this module is a runtime function (only its address is taken). */
        fun declareIfExternal(name: String) {
            if (!sig.containsKey(name) && !externs.containsKey(name)) externs[name] = "declare ptr @$name(ptr) $ATTR"
        }

        fun externType(sym: String) {
            if (!externGlobals.containsKey(sym)) externGlobals[sym] = "@$sym = external global %MkTypeInfo"
        }

        // ---- emission primitives -------------------------------------------------------------

        fun tmp(): String = "%t${tmpCounter++}"

        /** `mk_pending_exception` (an `MkObject *`) loaded into a fresh temporary. */
        fun loadPending(): String {
            val p = tmp()
            ins("$p = load ptr, ptr @mk_pending_exception")
            return p
        }
        fun newLabel(prefix: String): String = "$prefix${lblCounter++}"

        fun openBlock() {
            if (terminated) startBlock(newLabel("dead"))
        }

        fun ins(s: String) {
            openBlock()
            body.append("  ").append(s).append('\n')
        }

        fun term(s: String) {
            openBlock()
            body.append("  ").append(s).append('\n')
            terminated = true
        }

        fun br(label: String) {
            if (!terminated) term("br label %$label")
        }

        /** Starts block [label]; the previous one falls into it with a `br` unless it ended already. */
        fun startBlock(label: String) {
            if (!terminated) {
                body.append("  br label %").append(label).append('\n')
            }
            body.append(label).append(":\n")
            terminated = false
            curLabel = label
        }

        fun condBr(c: String, a: String, b: String, weights: Boolean = false) {
            term("br i1 $c, label %$a, label %$b" + (if (weights) COLD else ""))
        }

        fun call(ret: MirType, callee: String, types: List<MirType>, args: List<String>): String {
            val sb = StringBuilder()
            for (k in args.indices) {
                if (k > 0) sb.append(", ")
                sb.append(lt(types[k])).append(ext(types[k])).append(' ').append(args[k])
            }
            if (ret === TUnit || ret === TNothing) {
                ins("call void $callee($sb)")
                return ""
            }
            val r = tmp()
            ins("$r = call ${rext(ret)}${lt(ret)} $callee($sb)")
            return r
        }

        /** Call of a libminirt function with explicit MIR types. */
        fun rt(name: String, ret: MirType, types: List<MirType>, args: List<String>): String {
            declare(name, ret, types)
            return call(ret, "@$name", types, args)
        }

        fun voidCall(name: String, a: String) {
            rt(name, TUnit, listOf(TString), listOf(a))
        }

        fun gep(base: String, off: Int): String {
            if (off == 0) return base
            val r = tmp()
            ins("$r = getelementptr inbounds i8, ptr $base, i64 $off")
            return r
        }

        fun load(t: MirType, p: String): String {
            val r = tmp()
            ins("$r = load ${lt(t)}, ptr $p")
            return r
        }

        fun store(t: MirType, v: String, p: String) {
            ins("store ${lt(t)} $v, ptr $p")
        }

        fun binop(op: String, t: String, a: String, b: String): String {
            val r = tmp()
            ins("$r = $op $t $a, $b")
            return r
        }

        // ---- reference counting -------------------------------------------------------------

        /** Release of a reference that may be NULL; with RcOpt the NULL test and the fast path are inline. */
        fun rel(x: String) {
            if (inlineRc) ins("call void @mkh_rel(ptr $x)") else relCall(x)
        }

        fun relCall(x: String) {
            declare("mk_release", TUnit, listOf(TString))
            ins("call void @mk_release(ptr $x)")
        }

        fun ret(x: String) {
            if (inlineRc) ins("call void @mkh_retain(ptr $x)")
            else {
                declare("mk_retain", TUnit, listOf(TString))
                ins("call void @mk_retain(ptr $x)")
            }
        }

        // ---- the prelude: helpers that CEmitter's PRELUDE defines in C ------------------------------

        fun emitPrelude(o: StringBuilder) {
            val flagsOff = layouts.refcountOffset + 4
            val rcOff = layouts.refcountOffset
            if (inlineRc) {
                declare("mk_retain", TUnit, listOf(TString))
                declare("mk_release", TUnit, listOf(TString))
                o.append(
                    "define internal void @mkh_retain(ptr %o) #1 {\n" +
                        "entry:\n  %isnull = icmp eq ptr %o, null\n  br i1 %isnull, label %done, label %nn\n" +
                        "nn:\n  %fp = getelementptr inbounds i8, ptr %o, i64 $flagsOff\n  %fl = load i32, ptr %fp\n" +
                        "  %sm = and i32 %fl, 3\n  %imm = icmp eq i32 %sm, 0\n  br i1 %imm, label %chk, label %done\n" +
                        "chk:\n  %sh = and i32 %fl, 4\n  %nsh = icmp eq i32 %sh, 0\n  %rp = getelementptr inbounds i8, ptr %o, i64 $rcOff\n" +
                        "  %rc = load i32, ptr %rp\n  %rm1 = sub i32 %rc, 1\n  %ok = icmp ult i32 %rm1, -3\n  %both = and i1 %nsh, %ok\n" +
                        "  br i1 %both, label %inc, label %slow\n" +
                        "inc:\n  %rc1 = add i32 %rc, 1\n  store i32 %rc1, ptr %rp\n  br label %done\n" +
                        "slow:\n  call void @mk_retain(ptr %o)\n  br label %done\n" +
                        "done:\n  ret void\n}\n\n",
                )
                o.append(
                    "define internal void @mkh_rel(ptr %o) #1 {\n" +
                        "entry:\n  %isnull = icmp eq ptr %o, null\n  br i1 %isnull, label %done, label %nn\n" +
                        "nn:\n  %fp = getelementptr inbounds i8, ptr %o, i64 $flagsOff\n  %fl = load i32, ptr %fp\n" +
                        "  %sm = and i32 %fl, 3\n  %imm = icmp eq i32 %sm, 0\n  br i1 %imm, label %chk, label %done\n" +
                        "chk:\n  %sh = and i32 %fl, 4\n  %nsh = icmp eq i32 %sh, 0\n  %rp = getelementptr inbounds i8, ptr %o, i64 $rcOff\n" +
                        "  %rc = load i32, ptr %rp\n  %gt = icmp ugt i32 %rc, 1\n  %both = and i1 %nsh, %gt\n" +
                        "  br i1 %both, label %dec, label %slow\n" +
                        "dec:\n  %rc1 = sub i32 %rc, 1\n  store i32 %rc1, ptr %rp\n  br label %done\n" +
                        "slow:\n  call void @mk_release(ptr %o)\n  br label %done\n" +
                        "done:\n  ret void\n}\n\n",
                )
            }
            val abstractMsg = cstr("AbstractMethodError")
            declare("mk_panic", TNothing, listOf(TString), "#2")
            o.append("define internal void @mkh_abstract() #0 {\n  call void @mk_panic(ptr $abstractMsg)\n  unreachable\n}\n\n")
            // checked / safe casts
            val npeMsg = cstr("null cannot be cast to non-null type")
            declare("mk_raise", TUnit, listOf(TString, TString))
            declare("mk_checked_cast", TString, listOf(TString, TString))
            externType("MkNullPointerException_ti")
            o.append(
                "define internal ptr @mkh_cast_nn(ptr %o, ptr %t) #0 {\n  %n = icmp eq ptr %o, null\n  br i1 %n, label %isnull, label %ok\n" +
                    "isnull:\n  call void @mk_raise(ptr @MkNullPointerException_ti, ptr $npeMsg)\n  ret ptr null\n" +
                    "ok:\n  %r = call ptr @mk_checked_cast(ptr %o, ptr %t)\n  ret ptr %r\n}\n\n",
            )
            o.append(
                "define internal ptr @mkh_nn(ptr %o) #0 {\n  %n = icmp eq ptr %o, null\n  br i1 %n, label %isnull, label %ok\n" +
                    "isnull:\n  call void @mk_raise(ptr @MkNullPointerException_ti, ptr $npeMsg)\n  ret ptr %o\nok:\n  ret ptr %o\n}\n\n",
            )
            // Kotlin-exact integer division
            val dz = cstr("/ by zero")
            declare("mk_raise_arith", TUnit, listOf(TString))
            for (w in listOf("int" to "i32", "long" to "i64")) {
                val n = w.first
                val t = w.second
                o.append(
                    "define internal $t @mkh_div_$n($t %a, $t %b) #0 {\n  %z = icmp eq $t %b, 0\n  br i1 %z, label %zero, label %nz\n" +
                        "zero:\n  call void @mk_raise_arith(ptr $dz)\n  ret $t 0\n" +
                        "nz:\n  %m = icmp eq $t %b, -1\n  br i1 %m, label %neg, label %div\n" +
                        "neg:\n  %ng = sub $t 0, %a\n  ret $t %ng\n" +
                        "div:\n  %q = sdiv $t %a, %b\n  ret $t %q\n}\n\n",
                )
                o.append(
                    "define internal $t @mkh_rem_$n($t %a, $t %b) #0 {\n  %z = icmp eq $t %b, 0\n  br i1 %z, label %zero, label %nz\n" +
                        "zero:\n  call void @mk_raise_arith(ptr $dz)\n  ret $t 0\n" +
                        "nz:\n  %m = icmp eq $t %b, -1\n  br i1 %m, label %neg, label %div\n" +
                        "neg:\n  ret $t 0\n" +
                        "div:\n  %q = srem $t %a, %b\n  ret $t %q\n}\n\n",
                )
            }
        }

        // ---- classes ---------------------------------------------------------------------

        fun emitClasses(o: StringBuilder) {
            for (c in module.classes) {
                if (c.external) {
                    externType(c.cName + "_ti")
                    continue
                }
                o.append("; ${if (c.isInterface) "interface" else "class"} ${comment(c.qualifiedName)}\n")
                val nameSym = "@${c.cName}_name"
                o.append("$nameSym = private unnamed_addr constant [${utf8(c.qualifiedName).size + 1} x i8] c\"${cbytes(c.qualifiedName)}\\00\"\n")
                var ifs = "null"
                if (c.interfaces.isNotEmpty()) {
                    ifs = "@${c.cName}_ifs"
                    o.append("$ifs = internal constant [${c.interfaces.size} x ptr] [${c.interfaces.joinToString(", ") { "ptr @${it}_ti" }}]\n")
                }
                if (c.isInterface) {
                    o.append("@${c.cName}_ti = internal constant %MkTypeInfo <{ ptr $nameSym, $sz 0, ptr null, ptr null, ptr null, $sz 0, ptr $ifs, $sz ${c.interfaces.size} }>, align 8\n\n")
                    continue
                }
                val sup = c.superCName?.let { classes[it] ?: throw EmitError("unknown superclass $it") }
                val lay = layouts.of(c.cName)
                // dispose
                val refFields = c.fields.filter { tr.rcField(c, it) }
                // owned fields of tracked type (Strict classes): freeing the object frees them
                val ownedFields = c.fields.filter { tr.ownedTrackedField(c, it) }
                var dispose = "null"
                if (refFields.isNotEmpty() || ownedFields.isNotEmpty()) {
                    dispose = "@${c.cName}_dispose"
                    startFunction()
                    for (f in ownedFields) {
                        val v = load(TString, gep("%self", layouts.fieldOffset(c.cName, f.name)))
                        voidCall("mk_free_owned", v)
                    }
                    for (f in refFields) {
                        val v = load(TString, gep("%self", layouts.fieldOffset(c.cName, f.name)))
                        rel(v)
                    }
                    term("ret void")
                    o.append("define internal void @${c.cName}_dispose(ptr %self) #0 {\nentry:\n").append(allocas).append(body).append("}\n")
                }
                // vtable (three Any slots, user slots, one pointer per itable) and itables
                val vt = ArrayList<String>()
                val anyDefaults = listOf("mk_any_to_string", "mk_any_equals", "mk_any_hash_code")
                for ((i, e) in c.vtable.withIndex()) {
                    if (i < 3 && e == null) {
                        when (i) {
                            0 -> declare("mk_any_to_string", TString, listOf(TString))
                            1 -> declare("mk_any_equals", TBoolean, listOf(TString, TString))
                            else -> declare("mk_any_hash_code", TInt, listOf(TString))
                        }
                    }
                    if (e != null) declareIfExternal(e)
                    vt.add("ptr @" + (e ?: if (i < 3) anyDefaults[i] else "mkh_abstract"))
                }
                for ((k, it) in c.itables.withIndex()) {
                    for (e in it.entries) if (e != null) declareIfExternal(e)
                    val entries = if (it.entries.isEmpty()) listOf("ptr null") else it.entries.map { e -> "ptr @" + (e ?: "mkh_abstract") }
                    o.append("@${c.cName}_it$k = internal constant [${entries.size} x ptr] [${entries.joinToString(", ")}]\n")
                    vt.add("ptr @${c.cName}_it$k")
                }
                o.append("@${c.cName}_vt = internal constant [${vt.size} x ptr] [${vt.joinToString(", ")}]\n")
                val superTi = if (sup == null) "@mk_type_Any" else "@${sup.cName}_ti"
                if (sup == null) externType("mk_type_Any")
                o.append(
                    "@${c.cName}_ti = internal constant %MkTypeInfo <{ ptr $nameSym, $sz ${lay.size}, ptr $superTi, ptr $dispose, " +
                        "ptr @${c.cName}_vt, $sz ${vt.size}, ptr $ifs, $sz ${c.interfaces.size} }>, align 8\n",
                )
                if (c.objectCtor != null) {
                    o.append("@${c.cName}_obj = internal global ptr null\n")
                    o.append(
                        "define internal ptr @${c.cName}_get() #0 {\nentry:\n  %o = load ptr, ptr @${c.cName}_obj\n  %n = icmp eq ptr %o, null\n" +
                            "  br i1 %n, label %init, label %done\ninit:\n  %a = call ptr @mk_alloc(ptr @${c.cName}_ti)\n" +
                            "  store ptr %a, ptr @${c.cName}_obj\n  call void @${c.objectCtor}(ptr %a)\n  br label %done\n" +
                            "done:\n  %r = load ptr, ptr @${c.cName}_obj\n  ret ptr %r\n}\n",
                    )
                    declare("mk_alloc", TString, listOf(TString))
                }
                o.append("\n")
            }
            // allocating constructor wrappers
            for (f in module.functions) {
                val cls = f.constructorOf ?: continue
                val ps = f.params.drop(1)
                val decl = ps.withIndex().joinToString(", ") { (i, p) -> "${lt(p.type)}${ext(p.type)} %a$i" }
                declare("mk_alloc", TString, listOf(TString))
                startFunction()
                val a = tmp()
                ins("$a = call ptr @mk_alloc(ptr @${cls}_ti)")
                val argv = ArrayList<String>()
                val argt = ArrayList<MirType>()
                argv.add(a); argt.add(TString)
                for ((i, p) in ps.withIndex()) { argv.add("%a$i"); argt.add(p.type) }
                call(TUnit, "@${f.cName}", argt, argv)
                if (f.cName in throwing.mayThrow) {
                    val p = loadPending()
                    val c = tmp()
                    ins("$c = icmp ne ptr $p, null")
                    condBr(c, "fail", "ok", true)
                    startBlock("fail")
                    relCall(a)
                    term("ret ptr null")
                    startBlock("ok")
                }
                term("ret ptr $a")
                o.append("define ptr @${f.cName}_new($decl) #0 {\nentry:\n").append(allocas).append(body).append("}\n\n")
                if (f.cName in newaUsed) {
                    // the same through a chosen allocator (Strict code); `al` is the address of the MkAllocator (0: the default one)
                    startFunction()
                    var alp = "%al"
                    if (pb == 4) {
                        alp = tmp()
                        ins("$alp = trunc i64 %al to i32")
                    }
                    val ap = tmp()
                    ins("$ap = inttoptr ${if (pb == 4) "i32" else "i64"} $alp to ptr")
                    declare("mk_alloc_with", TString, listOf(TString, TString))
                    val a2 = rt("mk_alloc_with", TString, listOf(TString, TString), listOf(ap, "@${cls}_ti"))
                    val isNull = tmp()
                    ins("$isNull = icmp eq ptr $a2, null")
                    condBr(isNull, "oom", "alloc", true)
                    startBlock("oom")
                    declare("mk_panic", TNothing, listOf(TString), "#2")
                    ins("call void @mk_panic(ptr ${cstr("out of memory")})")
                    term("unreachable")
                    startBlock("alloc")
                    val argv2 = ArrayList<String>()
                    val argt2 = ArrayList<MirType>()
                    argv2.add(a2); argt2.add(TString)
                    for ((i, p) in ps.withIndex()) { argv2.add("%a$i"); argt2.add(p.type) }
                    call(TUnit, "@${f.cName}", argt2, argv2)
                    if (f.cName in throwing.mayThrow) {
                        val pe = loadPending()
                        val c2 = tmp()
                        ins("$c2 = icmp ne ptr $pe, null")
                        condBr(c2, "fail", "ok", true)
                        startBlock("fail")
                        voidCall("mk_discard", a2)
                        term("ret ptr null")
                        startBlock("ok")
                    }
                    term("ret ptr $a2")
                    val decla = if (ps.isEmpty()) "i64 %al" else "i64 %al, $decl"
                    o.append("define ptr @${f.cName}_newa($decla) #0 {\nentry:\n").append(allocas).append(body).append("}\n\n")
                }
            }
        }

        /** Resets the per-function emission state (also used for the generated helpers). */
        fun startFunction() {
            body.setLength(0)
            allocas.setLength(0)
            allocated.clear()
            terminated = false
            curLabel = "entry"
            tmpCounter = 0
            lblCounter = 0
            scopes.clear()
            handlers.clear()
            caught.clear()
            dead.clear()
            pending.clear()
            usedLabels.clear()
            curStmt = null
            fplan = null
            curFn = null
        }

        // ---- functions ---------------------------------------------------------------------

        fun lname(l: MirLocal): String = "%" + Mangling.local(l.name, l.id)

        /** The stack slot of [l]; created on first use (all allocas live in the entry block). */
        fun slot(l: MirLocal): String {
            if (allocated.add(l.id)) allocas.append("  ${lname(l)} = alloca ${lt(l.type)}\n")
            return lname(l)
        }

        fun prototype(f: MirFunction, define: Boolean): String {
            val name = if (f.cName == Mangling.MAIN) USER_MAIN else f.cName
            val ps = f.params.joinToString(", ") { p ->
                lt(p.type) + ext(p.type) + (if (define) " %in_" + Mangling.local(p.name, p.id) else "")
            }
            return "${rext(f.returnType)}${lt(f.returnType)} @$name($ps)"
        }

        fun emitFunction(f: MirFunction, out: StringBuilder) {
            startFunction()
            currentReturn = f.returnType
            fplan = rcPlan?.of(f)
            curFn = f
            for (p in f.params) {
                store(p.type, "%in_" + Mangling.local(p.name, p.id), slot(p))
            }
            scope(f.body, ownedParams = fplan?.ownedParams ?: emptyList())
            if (!terminated) {
                if (f.returnType === TUnit || f.returnType === TNothing) {
                    term("ret void")
                } else {
                    val safe = f.qualifiedName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
                    ins("call void @mk_panic(ptr ${cstr("unreachable: control reached the end of $safe")})")
                    declare("mk_panic", TNothing, listOf(TString), "#2")
                    term("unreachable")
                }
            }
            out.append("; ${comment(f.qualifiedName)}\n")
            out.append("define ${prototype(f, true)} #0 {\nentry:\n").append(allocas).append(body).append("}\n\n")
        }

        fun emitMainWrapper(userMain: MirFunction, out: StringBuilder) {
            startFunction()
            declare("mk_report_uncaught", TNothing, listOf(), "#2")
            if (userMain.params.isEmpty()) {
                ins("call void @$USER_MAIN()")
            } else {
                declare("mk_b1_args", TString, listOf())
                val a = tmp()
                ins("$a = call ptr @mk_b1_args()")
                ins("call void @$USER_MAIN(ptr $a)")
                relCall(a)
            }
            val p = loadPending()
            val c = tmp()
            ins("$c = icmp ne ptr $p, null")
            condBr(c, "unc", "fin", true)
            startBlock("unc")
            ins("call void @mk_report_uncaught()")
            term("unreachable")
            startBlock("fin")
            term("ret void")
            out.append("; program entry: an exception that escaped main is reported by the runtime\n")
            out.append("define void @mk_main() #0 {\nentry:\n").append(allocas).append(body).append("}\n\n")
        }

        // ---- scopes / reference counting ------------------------------------------------------------

        fun isTerminator(s: MirStmt?): Boolean =
            s is MirStmt.Return || s is MirStmt.Break || s is MirStmt.Continue || s is MirStmt.Panic || s is MirStmt.Throw

        fun refDecls(list: List<MirStmt>): List<MirLocal> =
            list.filterIsInstance<MirStmt.Declare>().map { it.local }.filter { rcL(it) }

        fun isBorrowed(l: MirLocal): Boolean = fplan?.isBorrowed(l) == true

        fun isStaticValue(e: MirExpr): Boolean = e is MirExpr.StringConst || e is MirExpr.NullConst

        fun scope(
            body: List<MirStmt>,
            loop: MirLabel? = null,
            extra: List<MirStmt> = emptyList(),
            epilogue: Boolean = true,
            mid: (() -> Unit)? = null,
            ownedParams: List<MirLocal> = emptyList(),
        ) {
            val all = refDecls(body) + refDecls(extra)
            for (l in all) store(TString, "null", slot(l))
            val locals = if (fplan == null) all else ownedParams + all.filter { !isBorrowed(it) }
            scopes.add(Scope(locals, loop))
            if (rcPlan != null) for (l in all) pending.add(l.id)
            for (s in body) stmt(s)
            if (mid != null) mid()
            if (epilogue && (mid != null || !isTerminator(body.lastOrNull()))) releases(scopes.size - 1, scopes.size - 1, null, false)
            scopes.removeAt(scopes.size - 1)
        }

        /** The locals released when leaving scopes [from] down to [to] (skipping [keep], dead and not yet declared ones). */
        fun releaseList(from: Int, to: Int, keep: MirLocal?): List<MirLocal> {
            val r = ArrayList<MirLocal>()
            for (i in from downTo to) {
                for (l in scopes[i].locals.asReversed()) {
                    if (l === keep || l.id in dead || l.id in pending) continue
                    r.add(l)
                }
            }
            return r
        }

        fun releaseAll(list: List<MirLocal>, cold: Boolean) {
            for (l in list) {
                val v = load(TString, slot(l))
                if (cold) relCall(v) else rel(v)
            }
        }

        fun releases(from: Int, to: Int, keep: MirLocal?, cold: Boolean) {
            releaseAll(releaseList(from, to, keep), cold)
        }

        // ---- statements ----------------------------------------------------------------------

        fun stmt(s: MirStmt) {
            val saved = curStmt
            curStmt = s
            stmtInner(s)
            curStmt = saved
            if (s is MirStmt.Declare) pending.remove(s.local.id)
            val gone = fplan?.dead?.get(s)
            if (gone != null) for (l in gone) dead.add(l.id)
        }

        fun closedFor(s: MirStmt?, l: MirLocal): Boolean = s != null && fplan?.dead?.get(s)?.any { it === l } == true

        fun stmtInner(s: MirStmt) {
            when (s) {
                is MirStmt.Declare -> {
                    val t = s.local.type
                    if (t === TUnit || t === TNothing) throw EmitError("local of type $t")
                    if (rcL(s.local)) {
                        val init = s.init ?: return
                        val v = expr(init, true)
                        store(TString, v, slot(s.local))
                        val moved = fplan?.moves?.get(s)
                        if (!ownedE(init) && !isStaticValue(init) && moved == null && !isBorrowed(s.local)) ret(v)
                        if (moved != null && !closedFor(s, moved)) store(TString, "null", slot(moved))
                    } else {
                        val v = if (s.init != null) expr(s.init, true) else zero(t)
                        store(t, v, slot(s.local))
                    }
                }
                is MirStmt.Assign -> {
                    val l = s.local
                    if (rcL(l) && isBorrowed(l)) {
                        store(TString, expr(s.value, true), slot(l))
                    } else if (rcL(l) && fplan?.selfTakes?.contains(s) == true) {
                        val v = expr(s.value, true)
                        store(TString, v, slot(l))
                        if (!ownedE(s.value) && !isStaticValue(s.value)) ret(v)
                    } else if (rcL(l)) {
                        val moved = fplan?.moves?.get(s)
                        val n = expr(s.value, true)
                        if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) ret(n)
                        val old = load(TString, slot(l))
                        store(TString, n, slot(l))
                        rel(old)
                        if (moved != null && !closedFor(s, moved)) store(TString, "null", slot(moved))
                    } else {
                        store(l.type, expr(s.value, true), slot(l))
                    }
                }
                is MirStmt.SetField -> {
                    val off = layouts.fieldOffset(s.owner, s.field)
                    if (tr.rcStore(curFn, s)) {
                        val moved = fplan?.moves?.get(s)
                        val n = expr(s.value, true)
                        if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) ret(n)
                        val p = gep(expr(s.obj), off)
                        if (fplan?.fresh?.contains(s) == true) {
                            store(TString, n, p)
                        } else {
                            val old = load(TString, p)
                            store(TString, n, p)
                            rel(old)
                        }
                        if (moved != null && !closedFor(s, moved)) store(TString, "null", slot(moved))
                    } else {
                        val v = expr(s.value, true)
                        store(s.fieldType, v, gep(expr(s.obj), off))
                    }
                }
                is MirStmt.Eval -> {
                    val e = s.expr
                    if (e === MirExpr.UnitValue) return
                    if (e.type === TUnit || e.type === TNothing) expr(e, true)
                    else if (ownedE(e)) rel(expr(e, true))
                    else expr(e)
                }
                is MirStmt.If -> {
                    val thenL = newLabel("then")
                    val elseL = newLabel("else")
                    val endL = newLabel("endif")
                    val c = expr(s.cond)
                    condBr(c, thenL, if (s.elseBody.isEmpty()) endL else elseL)
                    startBlock(thenL)
                    scope(s.thenBody)
                    br(endL)
                    if (s.elseBody.isNotEmpty()) {
                        startBlock(elseL)
                        scope(s.elseBody)
                        br(endL)
                    }
                    startBlock(endL)
                }
                is MirStmt.Block -> scope(s.body)
                is MirStmt.While -> whileLoop(s)
                is MirStmt.Loop -> infiniteLoop(s)
                is MirStmt.DoWhile -> doWhile(s)
                is MirStmt.ForRange -> forRange(s)
                is MirStmt.Break -> {
                    usedLabels.add(brk(s.label))
                    releases(scopes.size - 1, loopIndex(s.label), null, false)
                    br(brk(s.label))
                }
                is MirStmt.Continue -> {
                    usedLabels.add(cont(s.label))
                    releases(scopes.size - 1, loopIndex(s.label) + 1, null, false)
                    br(cont(s.label))
                }
                is MirStmt.Panic -> {
                    if (s.message.any { it < ' ' || it > '~' || it == '"' || it == '\\' }) throw EmitError("unsafe panic message")
                    declare("mk_panic", TNothing, listOf(TString), "#2")
                    ins("call void @mk_panic(ptr ${cstr(s.message)})")
                    term("unreachable")
                }
                is MirStmt.Return -> returnStmt(s)
                is MirStmt.Throw -> {
                    val v = expr(s.value)
                    voidCall("mk_throw", v)
                    exitStmt()
                }
                is MirStmt.Try -> tryStmt(s)
            }
        }

        // ---- exceptions ----------------------------------------------------------------------

        fun returnDefault() {
            when (currentReturn) {
                TUnit, TNothing -> term("ret void")
                TDouble -> term("ret double 0.0")
                TBoolean -> term("ret i1 false")
                TInt -> term("ret i32 0")
                TLong -> term("ret i64 0")
                TChar -> term("ret i16 0")
                else -> term("ret ptr null")
            }
        }

        /** Leaves the current position with a pending exception: to the innermost handler or out of the function. */
        fun exitStmt() {
            val h = if (handlers.isEmpty()) null else handlers[handlers.size - 1]
            if (h != null) {
                releases(scopes.size - 1, h.scopeIndex, null, true)
                br(h.label)
            } else {
                releases(scopes.size - 1, 0, null, true)
                returnDefault()
            }
        }

        /** Tests `mk_pending_exception` after a call that can throw; the exit path is a cold block. */
        fun checkPending() {
            val p = loadPending()
            val c = tmp()
            ins("$c = icmp ne ptr $p, null")
            val exitL = newLabel("exc")
            val contL = newLabel("cont")
            condBr(c, exitL, contL, true)
            startBlock(exitL)
            exitStmt()
            startBlock(contL)
        }

        fun tryStmt(s: MirStmt.Try) {
            val id = tryCounter++
            val label = "mkcatch_$id"
            val end = "mkend_$id"
            handlers.add(Handler(label, scopes.size))
            scope(s.body)
            handlers.removeAt(handlers.size - 1)
            br(end)
            startBlock(label)
            val ex = rt("mk_catch", TString, listOf(), listOf())
            caught.add(ex)
            for (c in s.catches) {
                val thenL = newLabel("catch")
                val nextL = newLabel("nomatch")
                val isInst = rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(ex, typeInfoOf(c.type)))
                condBr(isInst, thenL, nextL)
                startBlock(thenL)
                scope(c.body)
                br(end)
                startBlock(nextL)
            }
            voidCall("mk_rethrow", ex)
            exitStmt()
            caught.removeAt(caught.size - 1)
            startBlock(end)
        }

        fun loopIndex(l: MirLabel): Int {
            for (k in scopes.indices.reversed()) if (scopes[k].loop === l) return k
            throw EmitError("jump to unknown loop ${l.id}")
        }

        fun returnStmt(s: MirStmt.Return) {
            val v = s.value
            if (v == null || v === MirExpr.UnitValue) {
                releases(scopes.size - 1, 0, null, false)
                term("ret void")
                return
            }
            if (v.type === TUnit) {
                expr(v, true)
                releases(scopes.size - 1, 0, null, false)
                term("ret void")
                return
            }
            if (!rcT(currentReturn)) {
                // the value first: a call in it may hand a local over to the callee (RcOpt takes), which then must not be released
                val r = expr(v, true)
                releaseAll(releaseList(scopes.size - 1, 0, null), false)
                term("ret ${lt(currentReturn)} $r")
                return
            }
            if (rcPlan?.returnsBorrowed(curFn?.cName ?: "") == true) {
                // borrowed result (Conventions): no count for the caller
                val r = expr(v, true)
                releases(scopes.size - 1, 0, null, false)
                term("ret ptr $r")
                return
            }
            val moved = fplan?.moves?.get(s)
                ?: (v as? MirExpr.Local)?.local?.takeIf { l -> scopes.any { sc -> sc.locals.any { it === l } } }
            val r = expr(v, true)
            if (moved == null && !ownedE(v) && !isStaticValue(v)) ret(r)
            releases(scopes.size - 1, 0, moved, false)
            term("ret ptr $r")
        }

        fun brk(l: MirLabel): String = "brk_${l.id}"
        fun cont(l: MirLabel): String = "cont_${l.id}"

        fun whileLoop(s: MirStmt.While) {
            val hdr = newLabel("while")
            val bodyL = newLabel("wbody")
            startBlock(hdr)
            val c = expr(s.cond)
            condBr(c, bodyL, brk(s.label))
            startBlock(bodyL)
            scope(s.body, loop = s.label, mid = {
                if (cont(s.label) in usedLabels) startBlock(cont(s.label))
            })
            br(hdr)
            startBlock(brk(s.label))
        }

        fun infiniteLoop(s: MirStmt.Loop) {
            val hdr = newLabel("loop")
            startBlock(hdr)
            scope(s.body, loop = s.label, mid = {
                if (cont(s.label) in usedLabels) startBlock(cont(s.label))
            })
            br(hdr)
            startBlock(brk(s.label))
        }

        fun doWhile(s: MirStmt.DoWhile) {
            val hdr = newLabel("dowhile")
            startBlock(hdr)
            scope(s.body, loop = s.label, extra = s.condStmts, epilogue = false, mid = {
                if (cont(s.label) in usedLabels) startBlock(cont(s.label))
                for (c in s.condStmts) stmt(c)
                val c = expr(s.cond)
                releases(scopes.size - 1, scopes.size - 1, null, false)
                condBr(c, hdr, brk(s.label))
            })
            startBlock(brk(s.label))
        }

        fun forRange(s: MirStmt.ForRange) {
            val t = s.variable.type
            val lty = lt(t)
            val curSlot = "%cur" + lblCounter++
            allocas.append("  $curSlot = alloca $lty\n")
            store(t, expr(s.start), curSlot)
            val last = expr(s.end)
            var st = "1"
            if (s.step != null) {
                val sv = expr(s.step)
                st = if (s.step.type === TLong) sv else {
                    val r = tmp()
                    ins("$r = sext i32 $sv to i64")
                    r
                }
                val bad = tmp()
                ins("$bad = icmp sle i64 $st, 0")
                val badL = newLabel("badstep")
                val okL = newLabel("stepok")
                condBr(bad, badL, okL, true)
                startBlock(badL)
                declare("mk_raise_illegal_argument", TUnit, listOf(TString))
                ins("call void @mk_raise_illegal_argument(ptr ${cstr("Step must be positive.")})")
                exitStmt()
                startBlock(okL)
            }
            // widening to 64 bits keeps the sign of Int / Long and zero-extends Char (as the C conversion does)
            fun wide(v: String): String {
                if (t === TLong) return v
                val r = tmp()
                ins("$r = ${if (t === TChar) "zext" else "sext"} $lty $v to i64")
                return r
            }
            val c0 = load(t, curSlot)
            val pred = if (t === TChar) {
                if (s.ascending) (if (s.inclusive) "ule" else "ult") else (if (s.inclusive) "uge" else "ugt")
            } else {
                if (s.ascending) (if (s.inclusive) "sle" else "slt") else (if (s.inclusive) "sge" else "sgt")
            }
            val enter = tmp()
            ins("$enter = icmp $pred $lty $c0, $last")
            val bodyL = newLabel("frbody")
            val advL = newLabel("fradv")
            condBr(enter, bodyL, brk(s.label))
            startBlock(bodyL)
            val cv = load(t, curSlot)
            store(t, cv, slot(s.variable))
            scope(s.body, loop = s.label, mid = {
                if (cont(s.label) in usedLabels) startBlock(cont(s.label))
            })
            val cur = load(t, curSlot)
            val wc = wide(cur)
            val wl = wide(last)
            val dist = if (s.ascending) binop("sub", "i64", wl, wc) else binop("sub", "i64", wc, wl)
            val stop = tmp()
            ins("$stop = icmp ${if (s.inclusive) "ult" else "ule"} i64 $dist, $st")
            condBr(stop, brk(s.label), advL)
            startBlock(advL)
            val adv = if (s.ascending) binop("add", "i64", wc, st) else binop("sub", "i64", wc, st)
            val nv = if (t === TLong) adv else {
                val r = tmp()
                ins("$r = trunc i64 $adv to $lty")
                r
            }
            store(t, nv, curSlot)
            br(bodyL)
            startBlock(brk(s.label))
        }

        // ---- expressions ------------------------------------------------------------------

        fun isNullableRef(t: MirType): Boolean = t === TNString || (t is TObject && t.nullable) || t is TBoxed

        fun needsCheck(e: MirExpr): Boolean = when (e) {
            is MirExpr.Call -> e.cName in throwing.mayThrow
            is MirExpr.RuntimeCall -> ThrowAnalysis.runtimeCallThrows(e.cName)
            is MirExpr.VCall, is MirExpr.ICall -> throwing.dynamicMayThrow
            is MirExpr.New -> e.ctorCName in throwing.mayThrow
            is MirExpr.Unbox, is MirExpr.NotNull -> true
            is MirExpr.Cast -> e.mode == MirExpr.CastMode.Checked
            is MirExpr.Binary -> (e.op == BinaryOp.Div || e.op == BinaryOp.Rem) && (e.left.type === TInt || e.left.type === TLong) &&
                !((e.right is MirExpr.IntConst && e.right.value != 0) || (e.right is MirExpr.LongConst && e.right.value != 0L))
            else -> false
        }

        /** Emits [e] and returns its operand ("" for Unit/Nothing). */
        fun expr(e: MirExpr, top: Boolean = false): String {
            if (!top && ownedE(e)) throw EmitError("owned temporary in operand position: ${e.type}")
            val v = exprInner(e)
            if (needsCheck(e)) checkPending()
            return v
        }

        fun doubleLiteral(v: Double): String = when {
            v.isNaN() -> "0x7FF8000000000000"
            v == Double.POSITIVE_INFINITY -> "0x7FF0000000000000"
            v == Double.NEGATIVE_INFINITY -> "0xFFF0000000000000"
            else -> {
                // Kotlin's shortest round-trip text: LLVM rounds a decimal literal to the nearest double
                val s = v.toString()
                val e = s.indexOf('E')
                if (e < 0) s else s.substring(0, e) + "e" + s.substring(e + 1)
            }
        }

        fun exprInner(e: MirExpr): String {
            return when (e) {
                is MirExpr.IntConst -> e.value.toString()
                is MirExpr.LongConst -> e.value.toString()
                is MirExpr.DoubleConst -> doubleLiteral(e.value)
                is MirExpr.BooleanConst -> if (e.value) "true" else "false"
                is MirExpr.CharConst -> e.code.toString()
                is MirExpr.StringConst -> {
                    val name = literals.getOrPut(e.value) { "@kstr_${literals.size}" }
                    externType("mk_type_String")
                    name
                }
                MirExpr.UnitValue -> ""
                is MirExpr.Local -> load(e.local.type, slot(e.local))
                is MirExpr.Binary -> binary(e)
                is MirExpr.Unary -> unary(e)
                is MirExpr.Convert -> convert(e)
                is MirExpr.Call -> {
                    val f = sig[e.cName] ?: throw EmitError("call of unknown function ${e.cName}")
                    if (f.params.size != e.args.size) throw EmitError("wrong argument count for ${e.cName}")
                    val mask = rcPlan?.ownedMask(e.cName)
                    if (mask == null) {
                        val vs = e.args.map { expr(it) }
                        call(f.returnType, "@${e.cName}", f.params.map { it.type }, vs)
                    } else {
                        ownedCall(e.cName, f.returnType, f.params.map { it.type }, e.args, mask, 0, null)
                    }
                }
                is MirExpr.RuntimeCall -> {
                    val owns = NativeConventions.ownedArgs(e.cName)
                    if (e.cName == "mk_free_dealloc" && !tr.enabled) ""
                    else if (owns != null) {
                        declare(e.cName, e.type, e.args.map { it.type })
                        ownedCall(e.cName, e.type, e.args.map { it.type }, e.args, BooleanArray(e.args.size) { k -> ownsIndex(owns, k) }, 0, null)
                    } else {
                        val vs = e.args.map { expr(it) }
                        rt(e.cName, e.type, e.args.map { it.type }, vs)
                    }
                }
                is MirExpr.StringLength -> {
                    val o = expr(e.operand)
                    load(TInt, gep(o, layouts.headerSize))
                }
                is MirExpr.Conditional -> conditional(e)
                is MirExpr.Unreachable -> if (e.type === TUnit || e.type === TNothing) "" else zero(e.type)
                is MirExpr.NullConst -> "null"
                is MirExpr.Box -> {
                    val v = expr(e.operand)
                    val p = e.operand.type
                    rt("mk_box_${boxName(p)}", TString, listOf(p), listOf(v))
                }
                is MirExpr.Unbox -> {
                    val o = expr(e.operand)
                    rt("mk_checked_unbox_${boxName(e.to)}", e.to, listOf(TString), listOf(o))
                }
                is MirExpr.Cast -> cast(e)
                is MirExpr.IsInstance -> {
                    val o = expr(e.operand)
                    val ti = typeInfoOf(e.target)
                    externTypeInfo(e.target)
                    rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(o, ti))
                }
                is MirExpr.NotNull -> {
                    val o = expr(e.operand)
                    rt("mk_checked_not_null", TString, listOf(TString), listOf(o))
                }
                is MirExpr.FieldGet -> {
                    val o = expr(e.obj)
                    load(e.type, gep(o, layouts.fieldOffset(e.owner, e.field)))
                }
                is MirExpr.VCall -> {
                    val r = expr(e.receiver)
                    val vs = e.args.indices.map { expr(e.args[it]) }
                    handOver(e.args, e.paramTypes, vs, rcPlan?.ownedMaskOf(e))
                    val ti = load(TString, r)
                    val vtp = load(TString, gep(ti, 4 * pb))
                    val fp = load(TString, gep(vtp, e.slot * pb))
                    call(e.type, fp, listOf<MirType>(TString) + e.paramTypes, listOf(r) + vs)
                }
                is MirExpr.ICall -> {
                    val r = expr(e.receiver)
                    val vs = e.args.indices.map { expr(e.args[it]) }
                    handOver(e.args, e.paramTypes, vs, rcPlan?.ownedMaskOf(e))
                    val tbl = rt("mk_itable", TString, listOf(TString, TString), listOf(r, "@${e.interfaceCName}_ti"))
                    val fp = load(TString, gep(tbl, e.index * pb))
                    call(e.type, fp, listOf<MirType>(TString) + e.paramTypes, listOf(r) + vs)
                }
                is MirExpr.New -> {
                    val f = sig[e.ctorCName] ?: throw EmitError("unknown constructor ${e.ctorCName}")
                    val ps = f.params.drop(1)
                    val mask = rcPlan?.ownedMask(e.ctorCName)
                    val al = e.allocator
                    if (al != null && tr.enabled && curFn?.strict == true) {
                        // allocation through a chosen allocator (Strict code): `<ctor>_newa(allocator address, args...)`
                        newaUsed.add(e.ctorCName)
                        val lead = expr(al)
                        if (mask == null) {
                            val vs = e.args.map { expr(it) }
                            call(TString, "@${e.ctorCName}_newa", listOf<MirType>(TLong) + ps.map { it.type }, listOf(lead) + vs)
                        } else {
                            ownedCall("${e.ctorCName}_newa", TString, ps.map { it.type }, e.args, mask, 1, lead)
                        }
                    } else if (mask == null) {
                        val vs = e.args.map { expr(it) }
                        call(TString, "@${e.ctorCName}_new", ps.map { it.type }, vs)
                    } else {
                        ownedCall("${e.ctorCName}_new", TString, ps.map { it.type }, e.args, mask, 1, null)
                    }
                }
                is MirExpr.ObjectGet -> call(TString, "@${e.classCName}_get", listOf(), listOf())
                is MirExpr.CaughtException -> if (caught.isEmpty()) throw EmitError("CaughtException outside of a catch clause") else caught[caught.size - 1]
                is MirExpr.TypeInfoOf -> if (e.target == null) "null" else typeInfoOf(e.target).also { externTypeInfo(e.target) }
            }
        }

        fun externTypeInfo(t: MirType) {
            when (t) {
                TString, TNString -> externType("mk_type_String")
                is TObject -> if (t.cName == ANY_CNAME) externType("mk_type_Any") else if (classes[t.cName]?.external != false) externType(t.cName + "_ti")
                is TBoxed -> externType(boxTypeInfo(t.prim))
                else -> {}
            }
        }

        /**
         * A call whose callee owns some parameters (RcOpt convention): all arguments are evaluated first, then the
         * references are handed over: a moved local gives its own away, anything else is retained.
         */
        fun ownedCall(callee: String, retType: MirType, ptypes: List<MirType>, args: List<MirExpr>, mask: BooleanArray, shift: Int, lead: String?): String {
            val vs = args.map { expr(it) }
            handOverShifted(args, ptypes, vs, mask, shift)
            if (lead != null) return call(retType, "@$callee", listOf<MirType>(TLong) + ptypes, listOf(lead) + vs)
            return call(retType, "@$callee", ptypes, vs)
        }

        /** The references an owned parameter takes over: a moved local gives its own away, anything else is retained. */
        fun handOverShifted(args: List<MirExpr>, ptypes: List<MirType>, vs: List<String>, mask: BooleanArray, shift: Int) {
            for (k in args.indices) {
                if (k + shift >= mask.size || !mask[k + shift] || !ptypes[k].isRef || isStaticValue(args[k])) continue
                val x = fplan?.takes?.get(args[k])
                if (x == null) ret(vs[k])
                else if (closedFor(curStmt, x)) dead.add(x.id)
                else store(TString, "null", slot(x))
            }
        }

        /** Virtual / interface calls: [mask] is over `this` + parameters (Conventions). */
        fun handOver(args: List<MirExpr>, ptypes: List<MirType>, vs: List<String>, mask: BooleanArray?) {
            if (mask != null) handOverShifted(args, ptypes, vs, mask, 1)
        }

        fun ownsIndex(owns: IntArray, k: Int): Boolean {
            for (x in owns) if (x == k) return true
            return false
        }

        fun conditional(e: MirExpr.Conditional): String {
            val thenL = newLabel("ct")
            val elseL = newLabel("cf")
            val endL = newLabel("cend")
            val c = expr(e.cond)
            condBr(c, thenL, elseL)
            startBlock(thenL)
            val tv = expr(e.thenValue)
            val tEnd = curLabel
            br(endL)
            startBlock(elseL)
            val ev = expr(e.elseValue)
            val eEnd = curLabel
            br(endL)
            startBlock(endL)
            val t = e.type
            if (t === TUnit || t === TNothing) return ""
            val r = tmp()
            ins("$r = phi ${lt(t)} [ $tv, %$tEnd ], [ $ev, %$eEnd ]")
            return r
        }

        fun cast(e: MirExpr.Cast): String {
            val to = e.to
            val o = expr(e.operand)
            externTypeInfo(to)
            return when (e.mode) {
                MirExpr.CastMode.Unchecked -> o
                MirExpr.CastMode.Checked -> {
                    // everything is an Any: only the null check is left (the erased `as E` of every generic getter)
                    if (to is TObject && to.cName == ANY_CNAME) {
                        if (to.nullable) return o
                        return call(TString, "@mkh_nn", listOf(TString), listOf(o))
                    }
                    if (isNullableRef(to)) rt("mk_checked_cast", TString, listOf(TString, TString), listOf(o, typeInfoOf(to)))
                    else call(TString, "@mkh_cast_nn", listOf(TString, TString), listOf(o, typeInfoOf(to)))
                }
                MirExpr.CastMode.Safe -> {
                    val b = rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(o, typeInfoOf(to)))
                    val r = tmp()
                    ins("$r = select i1 $b, ptr $o, ptr null")
                    r
                }
            }
        }

        fun cmpPred(op: BinaryOp, t: MirType): String {
            if (t === TDouble) {
                return when (op) {
                    BinaryOp.Eq -> "fcmp oeq"
                    BinaryOp.Ne -> "fcmp une"
                    BinaryOp.Lt -> "fcmp olt"
                    BinaryOp.Le -> "fcmp ole"
                    BinaryOp.Gt -> "fcmp ogt"
                    else -> "fcmp oge"
                }
            }
            // Char (uint16_t) and Boolean compare as unsigned, Int and Long as signed
            val unsigned = t === TChar || t === TBoolean
            return "icmp " + when (op) {
                BinaryOp.Eq -> "eq"
                BinaryOp.Ne -> "ne"
                BinaryOp.Lt -> if (unsigned) "ult" else "slt"
                BinaryOp.Le -> if (unsigned) "ule" else "sle"
                BinaryOp.Gt -> if (unsigned) "ugt" else "sgt"
                else -> if (unsigned) "uge" else "sge"
            }
        }

        fun binary(e: MirExpr.Binary): String {
            val op = e.op
            val t = e.left.type
            if (op == BinaryOp.IdEq || op == BinaryOp.IdNe) {
                val a = expr(e.left)
                val b = expr(e.right)
                return binop(if (op == BinaryOp.IdEq) "icmp eq" else "icmp ne", "ptr", a, b)
            }
            if (op == BinaryOp.AndAnd || op == BinaryOp.OrOr) {
                val a = expr(e.left)
                val aEnd = curLabel
                val rhsL = newLabel("sc")
                val endL = newLabel("scend")
                if (op == BinaryOp.AndAnd) condBr(a, rhsL, endL) else condBr(a, endL, rhsL)
                startBlock(rhsL)
                val b = expr(e.right)
                val bEnd = curLabel
                br(endL)
                startBlock(endL)
                val r = tmp()
                ins("$r = phi i1 [ ${if (op == BinaryOp.AndAnd) "false" else "true"}, %$aEnd ], [ $b, %$bEnd ]")
                return r
            }
            val a = expr(e.left)
            val b = expr(e.right)
            if (t === TString) {
                return when (op) {
                    BinaryOp.Eq -> rt("mk_string_equals", TBoolean, listOf(TString, TString), listOf(a, b))
                    BinaryOp.Ne -> {
                        val r = rt("mk_string_equals", TBoolean, listOf(TString, TString), listOf(a, b))
                        binop("xor", "i1", r, "true")
                    }
                    else -> throw EmitError("operator $op on String")
                }
            }
            if (op.isComparison) {
                if (t.isRef) throw EmitError("operator $op on references")
                return binop(cmpPred(op, t), lt(t), a, b)
            }
            if (t === TBoolean) {
                return when (op) {
                    BinaryOp.And -> binop("and", "i1", a, b)
                    BinaryOp.Or -> binop("or", "i1", a, b)
                    BinaryOp.Xor -> binop("xor", "i1", a, b)
                    else -> throw EmitError("operator $op on Boolean")
                }
            }
            if (t === TDouble) {
                return when (op) {
                    BinaryOp.Add -> binop("fadd", "double", a, b)
                    BinaryOp.Sub -> binop("fsub", "double", a, b)
                    BinaryOp.Mul -> binop("fmul", "double", a, b)
                    BinaryOp.Div -> binop("fdiv", "double", a, b)
                    BinaryOp.Rem -> binop("frem", "double", a, b)
                    else -> throw EmitError("operator $op on Double")
                }
            }
            if (t !== TInt && t !== TLong) throw EmitError("operator $op on $t")
            val ty = lt(t)
            val mask = if (t === TInt) "31" else "63"
            val suffix = if (t === TInt) "int" else "long"
            return when (op) {
                BinaryOp.Add -> binop("add", ty, a, b)
                BinaryOp.Sub -> binop("sub", ty, a, b)
                BinaryOp.Mul -> binop("mul", ty, a, b)
                BinaryOp.Div -> call(t, "@mkh_div_$suffix", listOf(t, t), listOf(a, b))
                BinaryOp.Rem -> call(t, "@mkh_rem_$suffix", listOf(t, t), listOf(a, b))
                BinaryOp.Shl, BinaryOp.Shr, BinaryOp.Ushr -> {
                    var cnt = binop("and", "i32", b, mask)
                    if (t === TLong) {
                        val z = tmp()
                        ins("$z = zext i32 $cnt to i64")
                        cnt = z
                    }
                    binop(if (op == BinaryOp.Shl) "shl" else if (op == BinaryOp.Shr) "ashr" else "lshr", ty, a, cnt)
                }
                BinaryOp.And -> binop("and", ty, a, b)
                BinaryOp.Or -> binop("or", ty, a, b)
                BinaryOp.Xor -> binop("xor", ty, a, b)
                else -> throw EmitError("operator $op on $t")
            }
        }

        fun unary(e: MirExpr.Unary): String {
            val a = expr(e.operand)
            val t = e.operand.type
            return when (e.op) {
                UnaryOp.Not -> binop("xor", "i1", a, "true")
                UnaryOp.Neg -> when (t) {
                    TDouble -> {
                        val r = tmp()
                        ins("$r = fneg double $a")
                        r
                    }
                    TInt, TLong -> binop("sub", lt(t), "0", a)
                    else -> throw EmitError("negation of $t")
                }
                UnaryOp.Inv -> when (t) {
                    TInt, TLong -> binop("xor", lt(t), a, "-1")
                    else -> throw EmitError("inversion of $t")
                }
            }
        }

        fun cvt(op: String, from: String, a: String, to: String): String {
            val r = tmp()
            ins("$r = $op $from $a to $to")
            return r
        }

        fun d2i(a: String, bits: Int): String {
            val r = tmp()
            ins("$r = call i$bits @llvm.fptosi.sat.i$bits.f64(double $a)")
            externs["llvm.fptosi.sat.i$bits.f64"] = "declare i$bits @llvm.fptosi.sat.i$bits.f64(double) nounwind readnone speculatable willreturn"
            return r
        }

        fun convert(e: MirExpr.Convert): String {
            val a = expr(e.operand)
            val from = e.operand.type
            val to = e.to
            if (from == to) return a
            return when {
                to === TLong && from === TInt -> cvt("sext", "i32", a, "i64")
                to === TLong && from === TChar -> cvt("zext", "i16", a, "i64")
                to === TInt && from === TChar -> cvt("zext", "i16", a, "i32")
                to === TInt && from === TLong -> cvt("trunc", "i64", a, "i32")
                to === TChar && from === TInt -> cvt("trunc", "i32", a, "i16")
                to === TChar && from === TLong -> cvt("trunc", "i64", a, "i16")
                to === TDouble && from === TInt -> cvt("sitofp", "i32", a, "double")
                to === TDouble && from === TLong -> cvt("sitofp", "i64", a, "double")
                to === TDouble && from === TChar -> cvt("uitofp", "i16", a, "double")
                to === TInt && from === TDouble -> d2i(a, 32)
                to === TLong && from === TDouble -> d2i(a, 64)
                to === TChar && from === TDouble -> cvt("trunc", "i32", d2i(a, 32), "i16")
                else -> throw EmitError("conversion $from -> $to")
            }
        }
    }
}
