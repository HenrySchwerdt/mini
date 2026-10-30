package mini.backend.llvm

import mini.backend.EmitError

/** A value of the textual IR: its operand text (`%t3`, `@f`, `42`) and its type text (`i32`, `ptr`, `[3 x i8]`). */
class TextVal(val text: String, val ty: String)

/** UTF-8 helpers shared by the IR builders. */
object IrStrings {
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

    /** Kotlin's shortest round-trip text; LLVM rounds a decimal literal to the nearest double (NaN and infinities are hex). */
    fun doubleLiteral(v: Double): String = when {
        v.isNaN() -> "0x7FF8000000000000"
        v == Double.POSITIVE_INFINITY -> "0x7FF0000000000000"
        v == Double.NEGATIVE_INFINITY -> "0xFFF0000000000000"
        else -> {
            val s = v.toString()
            val e = s.indexOf('E')
            if (e < 0) s else s.substring(0, e) + "e" + s.substring(e + 1)
        }
    }
}

/**
 * [IrBuilder] that prints textual LLVM IR (`.ll`): the phase-1 output of the LLVM backend. Operands are [TextVal]s, blocks are
 * their label names. The layout of the file (declarations on top, then the string pools, classes, helpers, functions)
 * is the one `LlvmEmitter` always produced.
 */
class TextIrBuilder : IrBuilder<TextVal, String> {
    override val none: TextVal = TextVal("", "void")

    private var target: LlvmTarget = LlvmTarget.HOST
    private var sourceName: String = ""
    private var links: List<String> = emptyList()
    private val namedStructs = LinkedHashMap<String, String>()
    private val externGlobals = LinkedHashMap<String, String>()
    private val externs = LinkedHashMap<String, String>()
    private val strings = StringBuilder()
    private val literals = StringBuilder()
    private val classes = StringBuilder()
    private val prelude = StringBuilder()
    private val functions = StringBuilder()
    private var usesPure = false

    // the function being defined
    private val body = StringBuilder()
    private val allocas = StringBuilder()
    private var fnHeader = ""
    private var fnSection = IrSection.FUNCTIONS
    private var fnParams = ArrayList<TextVal>()
    private var tmpCounter = 0

    private fun sec(s: IrSection): StringBuilder = when (s) {
        IrSection.STRINGS -> strings
        IrSection.LITERALS -> literals
        IrSection.CLASSES -> classes
        IrSection.PRELUDE -> prelude
        IrSection.FUNCTIONS -> functions
    }

    override fun beginModule(sourceName: String, links: List<String>, target: LlvmTarget) {
        this.sourceName = sourceName
        this.links = links
        this.target = target
    }

    override fun defineNamedStruct(name: String, fields: List<IrType>) {
        namedStructs[name] = "<{ " + fields.joinToString(", ") { tyText(it) } + " }>"
    }

    override fun comment(s: IrSection, text: String) {
        sec(s).append("; ").append(text).append('\n')
    }

    override fun blankLine(s: IrSection) {
        sec(s).append('\n')
    }

    // ---- text helpers --------------------------------------------------------------------------

    private fun tyText(t: IrType): String = when (t) {
        IrType.I1 -> "i1"
        IrType.I8 -> "i8"
        IrType.I16 -> "i16"
        IrType.I32 -> "i32"
        IrType.I64 -> "i64"
        IrType.FLOAT -> "float"
        IrType.DOUBLE -> "double"
        IrType.PTR -> "ptr"
        IrType.VOID -> "void"
    }

    private fun extText(e: IrExt): String = if (e == IrExt.ZERO) " zeroext" else if (e == IrExt.SIGN) " signext" else ""
    private fun rextText(e: IrExt): String = if (e == IrExt.ZERO) "zeroext " else if (e == IrExt.SIGN) "signext " else ""

    private fun attrsText(a: IrAttrs): String = when (a) {
        IrAttrs.NONE -> ""
        IrAttrs.STD -> "#0"
        IrAttrs.INLINE -> "#1"
        IrAttrs.NORETURN -> "#2"
        IrAttrs.PURE -> {
            usesPure = true
            "#3"
        }
    }

    private fun linkageText(l: IrLinkage): String = if (l == IrLinkage.INTERNAL) "internal " else if (l == IrLinkage.PRIVATE) "private " else ""

    /** A symbol as an LLVM global name (quoted when it has unusual characters). */
    private fun gname(sym: String): String {
        for (c in sym) if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '$')) {
            if (c < ' ' || c > '~' || c == '"' || c == '\\') throw EmitError("bad C symbol name '$sym'")
            return "@\"$sym\""
        }
        return "@$sym"
    }

    private fun result(name: String): String {
        if (name.isNotEmpty()) return "%$name"
        val r = "%t$tmpCounter"
        tmpCounter++
        return r
    }

    private fun ins(s: String) {
        body.append("  ").append(s).append('\n')
    }

    private fun paramList(params: List<IrType>, exts: List<IrExt>, vararg: Boolean): String {
        val sb = StringBuilder()
        for (k in params.indices) {
            if (k > 0) sb.append(", ")
            sb.append(tyText(params[k])).append(extText(exts[k]))
        }
        if (vararg) sb.append(if (params.isEmpty()) "..." else ", ...")
        return sb.toString()
    }

    // ---- constants -------------------------------------------------------------------------------

    override fun constInt(t: IrType, v: Long): TextVal =
        if (t == IrType.I1) TextVal(if (v != 0L) "true" else "false", "i1") else TextVal(v.toString(), tyText(t))

    override fun constFloat(t: IrType, v: Double): TextVal = TextVal(IrStrings.doubleLiteral(v), tyText(t))

    override fun constNull(): TextVal = TextVal("null", "ptr")

    override fun constArray(elem: IrType, items: List<TextVal>): TextVal {
        val ty = "[" + items.size + " x " + tyText(elem) + "]"
        return TextVal("[" + items.joinToString(", ") { it.ty + " " + it.text } + "]", ty)
    }

    private fun packedType(items: List<TextVal>): String = "<{ " + items.joinToString(", ") { it.ty } + " }>"
    private fun packedText(items: List<TextVal>): String = "<{ " + items.joinToString(", ") { it.ty + " " + it.text } + " }>"

    override fun constPackedStruct(items: List<TextVal>): TextVal = TextVal(packedText(items), packedType(items))

    override fun constNamedStruct(typeName: String, items: List<TextVal>): TextVal = TextVal(packedText(items), "%$typeName")

    override fun constCString(text: String): TextVal =
        TextVal("c\"" + IrStrings.cbytes(text) + "\\00\"", "[" + (IrStrings.utf8(text).size + 1) + " x i8]")

    // ---- globals ---------------------------------------------------------------------------------

    override fun global(name: String): TextVal = TextVal(gname(name), "ptr")

    override fun externTypeInfo(sym: String): TextVal {
        if (!externGlobals.containsKey(sym)) externGlobals[sym] = "@$sym = external global %MkTypeInfo"
        return TextVal(gname(sym), "ptr")
    }

    override fun declareGlobalOfStruct(name: String, typeName: String, linkage: IrLinkage) {}

    override fun defineGlobal(s: IrSection, name: String, init: TextVal, constant: Boolean, linkage: IrLinkage, align: Int, unnamedAddr: Boolean): TextVal {
        val sb = sec(s)
        sb.append(gname(name)).append(" = ").append(linkageText(linkage))
        if (unnamedAddr) sb.append("unnamed_addr ")
        sb.append(if (constant) "constant " else "global ").append(init.ty).append(' ').append(init.text)
        if (align > 0) sb.append(", align ").append(align)
        sb.append('\n')
        return TextVal(gname(name), "ptr")
    }

    // ---- functions -------------------------------------------------------------------------------

    override fun declareExternal(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, vararg: Boolean): TextVal {
        if (!externs.containsKey(name)) {
            val a = attrsText(attrs)
            externs[name] = "declare " + rextText(retExt) + tyText(ret) + " " + gname(name) + "(" + paramList(params, exts, vararg) + ")" + (if (a.isEmpty()) "" else " $a")
        }
        return TextVal(gname(name), "ptr")
    }

    override fun hasFunction(name: String): Boolean = externs.containsKey(name)

    override fun declareDefined(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, linkage: IrLinkage): TextVal =
        TextVal(gname(name), "ptr")

    override fun beginFunction(
        s: IrSection, name: String, ret: IrType, retExt: IrExt, paramNames: List<String>, params: List<IrType>, exts: List<IrExt>,
        attrs: IrAttrs, linkage: IrLinkage,
    ) {
        body.setLength(0)
        allocas.setLength(0)
        tmpCounter = 0
        fnSection = s
        fnParams = ArrayList()
        val ps = StringBuilder()
        for (k in params.indices) {
            if (k > 0) ps.append(", ")
            ps.append(tyText(params[k])).append(extText(exts[k])).append(" %").append(paramNames[k])
            fnParams.add(TextVal("%" + paramNames[k], tyText(params[k])))
        }
        val a = attrsText(attrs)
        fnHeader = "define " + linkageText(linkage) + rextText(retExt) + tyText(ret) + " " + gname(name) + "(" + ps + ")" + (if (a.isEmpty()) "" else " $a") + " {\nentry:\n"
    }

    override fun param(i: Int): TextVal = fnParams[i]

    override fun entryBlock(): String = "entry"

    override fun endFunction() {
        sec(fnSection).append(fnHeader).append(allocas).append(body).append("}\n")
    }

    // ---- blocks and instructions -----------------------------------------------------------------

    override fun newBlock(name: String): String = name

    override fun appendBlock(b: String) {
        body.append(b).append(":\n")
    }

    override fun alloca(t: IrType, name: String): TextVal {
        allocas.append("  %").append(name).append(" = alloca ").append(tyText(t)).append('\n')
        return TextVal("%$name", "ptr")
    }

    override fun load(t: IrType, ptr: TextVal, align: Int, name: String): TextVal {
        val r = result(name)
        ins(r + " = load " + tyText(t) + ", ptr " + ptr.text + (if (align > 0) ", align $align" else ""))
        return TextVal(r, tyText(t))
    }

    override fun store(v: TextVal, ptr: TextVal, align: Int) {
        ins("store " + v.ty + " " + v.text + ", ptr " + ptr.text + (if (align > 0) ", align $align" else ""))
    }

    private val tbaaTags = LinkedHashMap<String, Int>()

    /** The metadata id of the access tag [tag]: tags are `!{!T, !T, i64 0}` over scalar type nodes `!T = !{!"name", !1, i64 0}` of the root `!1`, pairs from `!2` on. */
    private fun tbaaId(tag: String): Int {
        val known = tbaaTags[tag]
        if (known != null) return known
        val id = 3 + 2 * tbaaTags.size
        tbaaTags[tag] = id
        return id
    }

    override fun loadTagged(t: IrType, ptr: TextVal, tag: String, name: String): TextVal {
        val r = result(name)
        ins(r + " = load " + tyText(t) + ", ptr " + ptr.text + ", !tbaa !" + tbaaId(tag))
        return TextVal(r, tyText(t))
    }

    override fun storeTagged(v: TextVal, ptr: TextVal, tag: String) {
        ins("store " + v.ty + " " + v.text + ", ptr " + ptr.text + ", !tbaa !" + tbaaId(tag))
    }

    override fun gep(base: TextVal, offset: TextVal, inbounds: Boolean, name: String): TextVal {
        val r = result(name)
        ins(r + " = getelementptr " + (if (inbounds) "inbounds " else "") + "i8, ptr " + base.text + ", i64 " + offset.text)
        return TextVal(r, "ptr")
    }

    private fun opText(op: IrOp): String = when (op) {
        IrOp.ADD -> "add"
        IrOp.SUB -> "sub"
        IrOp.MUL -> "mul"
        IrOp.SDIV -> "sdiv"
        IrOp.SREM -> "srem"
        IrOp.AND -> "and"
        IrOp.OR -> "or"
        IrOp.XOR -> "xor"
        IrOp.SHL -> "shl"
        IrOp.ASHR -> "ashr"
        IrOp.LSHR -> "lshr"
        IrOp.FADD -> "fadd"
        IrOp.FSUB -> "fsub"
        IrOp.FMUL -> "fmul"
        IrOp.FDIV -> "fdiv"
        IrOp.FREM -> "frem"
    }

    private fun cmpText(p: IrCmp): String = when (p) {
        IrCmp.IEQ -> "icmp eq"
        IrCmp.INE -> "icmp ne"
        IrCmp.SLT -> "icmp slt"
        IrCmp.SLE -> "icmp sle"
        IrCmp.SGT -> "icmp sgt"
        IrCmp.SGE -> "icmp sge"
        IrCmp.ULT -> "icmp ult"
        IrCmp.ULE -> "icmp ule"
        IrCmp.UGT -> "icmp ugt"
        IrCmp.UGE -> "icmp uge"
        IrCmp.FOEQ -> "fcmp oeq"
        IrCmp.FUNE -> "fcmp une"
        IrCmp.FOLT -> "fcmp olt"
        IrCmp.FOLE -> "fcmp ole"
        IrCmp.FOGT -> "fcmp ogt"
        IrCmp.FOGE -> "fcmp oge"
    }

    private fun castText(c: IrCast): String = when (c) {
        IrCast.ZEXT -> "zext"
        IrCast.SEXT -> "sext"
        IrCast.TRUNC -> "trunc"
        IrCast.SITOFP -> "sitofp"
        IrCast.UITOFP -> "uitofp"
        IrCast.FPEXT -> "fpext"
        IrCast.FPTRUNC -> "fptrunc"
        IrCast.INTTOPTR -> "inttoptr"
        IrCast.PTRTOINT -> "ptrtoint"
    }

    override fun bin(op: IrOp, a: TextVal, b: TextVal, name: String): TextVal {
        val r = result(name)
        ins(r + " = " + opText(op) + " " + a.ty + " " + a.text + ", " + b.text)
        return TextVal(r, a.ty)
    }

    override fun cmp(pred: IrCmp, a: TextVal, b: TextVal, name: String): TextVal {
        val r = result(name)
        ins(r + " = " + cmpText(pred) + " " + a.ty + " " + a.text + ", " + b.text)
        return TextVal(r, "i1")
    }

    override fun fneg(a: TextVal): TextVal {
        val r = result("")
        ins(r + " = fneg " + a.ty + " " + a.text)
        return TextVal(r, a.ty)
    }

    override fun cast(op: IrCast, a: TextVal, to: IrType, name: String): TextVal {
        val r = result(name)
        ins(r + " = " + castText(op) + " " + a.ty + " " + a.text + " to " + tyText(to))
        return TextVal(r, tyText(to))
    }

    override fun select(c: TextVal, a: TextVal, b: TextVal): TextVal {
        val r = result("")
        ins(r + " = select i1 " + c.text + ", " + a.ty + " " + a.text + ", " + b.ty + " " + b.text)
        return TextVal(r, a.ty)
    }

    override fun phi(t: IrType, v1: TextVal, b1: String, v2: TextVal, b2: String): TextVal {
        val r = result("")
        ins(r + " = phi " + tyText(t) + " [ " + v1.text + ", %" + b1 + " ], [ " + v2.text + ", %" + b2 + " ]")
        return TextVal(r, tyText(t))
    }

    override fun fptosiSat(a: TextVal, bits: Int): TextVal {
        val r = result("")
        ins(r + " = call i" + bits + " @llvm.fptosi.sat.i" + bits + ".f64(double " + a.text + ")")
        externs["llvm.fptosi.sat.i$bits.f64"] = "declare i$bits @llvm.fptosi.sat.i$bits.f64(double) nounwind readnone speculatable willreturn"
        return TextVal(r, "i$bits")
    }

    override fun call(callee: TextVal, ret: IrType, retExt: IrExt, args: List<TextVal>, exts: List<IrExt>, fixed: Int, name: String): TextVal {
        val sb = StringBuilder()
        for (k in args.indices) {
            if (k > 0) sb.append(", ")
            sb.append(args[k].ty).append(extText(exts[k])).append(' ').append(args[k].text)
        }
        var fnTy = ""
        if (fixed >= 0) {
            val sig = StringBuilder()
            for (k in 0 until fixed) {
                if (k > 0) sig.append(", ")
                sig.append(args[k].ty).append(extText(exts[k]))
            }
            fnTy = "(" + sig + (if (fixed == 0) "..." else ", ...") + ") "
        }
        if (ret == IrType.VOID) {
            ins("call void " + fnTy + callee.text + "(" + sb + ")")
            return none
        }
        val r = result(name)
        ins(r + " = call " + rextText(retExt) + tyText(ret) + " " + fnTy + callee.text + "(" + sb + ")")
        return TextVal(r, tyText(ret))
    }

    override fun br(target: String) {
        ins("br label %$target")
    }

    override fun condBr(c: TextVal, t: String, f: String, cold: Boolean) {
        ins("br i1 " + c.text + ", label %" + t + ", label %" + f + (if (cold) ", !prof !0" else ""))
    }

    override fun ret(v: TextVal) {
        ins("ret " + v.ty + " " + v.text)
    }

    override fun retVoid() {
        ins("ret void")
    }

    override fun unreachable() {
        ins("unreachable")
    }

    // ---- the file --------------------------------------------------------------------------------

    override fun finish(): String {
        val out = StringBuilder()
        out.append("; Generated by minic (LLVM backend) from ${sourceName.replace('\n', ' ').replace('\r', ' ')}. Do not edit.\n")
        for (l in links) out.append("; mini:link ${l.replace('\n', ' ').replace('\r', ' ')}\n")
        out.append("source_filename = \"${IrStrings.cbytes(sourceName)}\"\n")
        if (target.dataLayout != null) out.append("target datalayout = \"${target.dataLayout}\"\n")
        if (target.triple != null) out.append("target triple = \"${target.triple}\"\n")
        out.append("\n")
        for ((n, body) in namedStructs) out.append("%$n = type $body\n")
        out.append("\n")
        if (!target.pendingAccessor) out.append("@mk_pending_exception = external thread_local(initialexec) global ptr\n")
        for ((_, line) in externGlobals) out.append(line).append('\n')
        out.append('\n')
        for ((_, line) in externs) out.append(line).append('\n')
        out.append("\n")
        out.append(strings)
        if (literals.isNotEmpty()) {
            out.append("\n; string literals (UTF-16)\n")
            out.append(literals)
        }
        out.append("\n").append(classes)
        out.append("\n").append(prelude)
        out.append("\n").append(functions)
        // sanitize_address: with -fsanitize=address clang instruments only functions that carry the attribute
        out.append("attributes #0 = { nounwind sanitize_address }\n")
        out.append("attributes #1 = { nounwind alwaysinline sanitize_address }\n")
        out.append("attributes #2 = { noreturn nounwind }\n")
        if (usesPure) out.append("attributes #3 = { nounwind memory(none) }\n")
        out.append("\n!0 = !{!\"branch_weights\", i32 1, i32 2000}\n")
        if (tbaaTags.isNotEmpty()) {
            out.append("!1 = !{!\"mini tbaa\"}\n")
            for ((name, id) in tbaaTags) {
                out.append("!" + (id - 1) + " = !{!\"" + name + "\", !1, i64 0}\n")
                out.append("!" + id + " = !{!" + (id - 1) + ", !" + (id - 1) + ", i64 0}\n")
            }
        }
        return out.toString()
    }
}
