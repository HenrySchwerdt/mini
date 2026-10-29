package mini.backend

/*
 * Serialized MIR (bootstrap stage B1, docs/plan-kotlin.md section 10b): the JVM frontend writes a
 * MirModule as text, the native backend reads it back and runs CEmitter.
 *
 * Format (version 1): S-expressions, ASCII only, one class / function / statement per line.
 *
 *   (mini-mir 1 "<sourceName>"
 *     (class "<qualifiedName>" "<cName>" <isInterface> "<super>"|- <external> "<objectCtor>"|-
 *            (fields (f "<name>" <type>)...) (interfaces "<cName>"...) (vtable "<c>"|-...)
 *            (itables (it "<ifaceCName>" "<c>"|-...)...))
 *     (fun "<qualifiedName>" "<cName>" (params <local>...) <type> "<ctorOf>"|-
 *       <statement>...))
 *
 * Atoms: integers, `true`/`false`, `-` (null), enum names, type names. Strings are double-quoted with the
 * escapes \" \\ \n \r \t and \uXXXX (every UTF-16 unit outside 0x20..0x7e, so lone surrogates survive).
 * Doubles are written as `nan`, `inf`, `-inf` or Kotlin's shortest round-trip `toString()` (so -0.0 is kept).
 * Locals and labels are identified by their id within a function: a local is written in full
 * `(l <id> "<name>" <type>)` at its first mention in the function and as `(l <id>)` afterwards
 * (the reader decodes in the same order as the writer wrote).
 *
 * Types: Int Long Boolean Double Char String Unit Nothing String? Null, `(obj "<qn>" "<cName>" <nullable> <isInterface>)`,
 * `(boxed <prim>)`.
 *
 * The `when` expressions over the sealed MIR hierarchies are exhaustive on purpose: adding a node
 * type makes this file fail to compile until the format covers it. Pure Kotlin (no regex, no
 * String.format, no reflection) so that Mini can compile it.
 */

class MirFormatError(message: String) : Exception(message)

const val MIR_FORMAT_VERSION = 1

object MirWriter {
    fun write(module: MirModule): String = MirWriterImpl().run(module)
}

object MirReader {
    fun read(text: String): MirModule = MirReaderImpl().run(text)
}

// ---------------------------------------------------------------------------------------------
// writing

private const val HEX = "0123456789abcdef"

internal fun mirQuote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (i in 0 until s.length) {
        val c = s[i]
        val code = c.code
        if (c == '"') sb.append("\\\"")
        else if (c == '\\') sb.append("\\\\")
        else if (c == '\n') sb.append("\\n")
        else if (c == '\r') sb.append("\\r")
        else if (c == '\t') sb.append("\\t")
        else if (code < 0x20 || code > 0x7e) {
            sb.append("\\u")
            sb.append(HEX[(code shr 12) and 15])
            sb.append(HEX[(code shr 8) and 15])
            sb.append(HEX[(code shr 4) and 15])
            sb.append(HEX[code and 15])
        } else sb.append(c)
    }
    sb.append('"')
}

internal fun mirDouble(d: Double): String {
    if (d != d) return "nan"
    if (d == Double.POSITIVE_INFINITY) return "inf"
    if (d == Double.NEGATIVE_INFINITY) return "-inf"
    return d.toString()
}

private class MirWriterImpl {
    val sb = StringBuilder()
    var locals = HashMap<Int, MirLocal>()

    fun run(m: MirModule): String {
        sb.append("(mini-mir ").append(MIR_FORMAT_VERSION).append(' ')
        str(m.sourceName)
        for (c in m.classes) {
            sb.append("\n  ")
            cls(c)
        }
        for (f in m.functions) {
            sb.append("\n  ")
            fn(f)
        }
        sb.append(")\n")
        return sb.toString()
    }

    fun str(s: String) = mirQuote(s, sb)

    fun optStr(s: String?) {
        if (s == null) sb.append('-') else str(s)
    }

    fun nl(depth: Int) {
        sb.append('\n')
        for (i in 0 until depth) sb.append("  ")
    }

    fun cls(c: MirClass) {
        sb.append("(class ")
        str(c.qualifiedName); sb.append(' ')
        str(c.cName); sb.append(' ')
        sb.append(c.isInterface).append(' ')
        optStr(c.superCName); sb.append(' ')
        sb.append(c.external).append(' ')
        optStr(c.objectCtor)
        sb.append(" (fields")
        for (f in c.fields) {
            sb.append(" (f ")
            str(f.name); sb.append(' ')
            type(f.type)
            sb.append(')')
        }
        sb.append(") (interfaces")
        for (i in c.interfaces) { sb.append(' '); str(i) }
        sb.append(") (vtable")
        for (v in c.vtable) { sb.append(' '); optStr(v) }
        sb.append(") (itables")
        for (t in c.itables) {
            sb.append(" (it ")
            str(t.interfaceCName)
            for (e in t.entries) { sb.append(' '); optStr(e) }
            sb.append(')')
        }
        sb.append("))")
    }

    fun fn(f: MirFunction) {
        locals = HashMap()
        sb.append("(fun ")
        str(f.qualifiedName); sb.append(' ')
        str(f.cName)
        sb.append(" (params")
        for (p in f.params) { sb.append(' '); local(p) }
        sb.append(") ")
        type(f.returnType); sb.append(' ')
        optStr(f.constructorOf)
        for (s in f.body) {
            nl(2)
            stmt(s, 2)
        }
        sb.append(")")
    }

    fun local(l: MirLocal) {
        val known = locals[l.id]
        if (known == null) {
            locals[l.id] = l
            sb.append("(l ").append(l.id).append(' ')
            str(l.name); sb.append(' ')
            type(l.type)
            sb.append(')')
        } else {
            if (known !== l) throw MirFormatError("two different locals share id ${l.id} (${known.name}, ${l.name})")
            sb.append("(l ").append(l.id).append(')')
        }
    }

    fun type(t: MirType) {
        when (t) {
            TInt -> sb.append("Int")
            TLong -> sb.append("Long")
            TBoolean -> sb.append("Boolean")
            TDouble -> sb.append("Double")
            TChar -> sb.append("Char")
            TString -> sb.append("String")
            TUnit -> sb.append("Unit")
            TNothing -> sb.append("Nothing")
            TNString -> sb.append("String?")
            TNull -> sb.append("Null")
            is TObject -> {
                sb.append("(obj ")
                str(t.qualifiedName); sb.append(' ')
                str(t.cName); sb.append(' ')
                sb.append(t.nullable).append(' ').append(t.isInterface).append(')')
            }
            is TBoxed -> {
                sb.append("(boxed ")
                type(t.prim)
                sb.append(')')
            }
        }
    }

    fun optType(t: MirType?) {
        if (t == null) sb.append('-') else type(t)
    }

    fun body(tag: String, stmts: List<MirStmt>, depth: Int) {
        sb.append('(').append(tag)
        for (s in stmts) {
            nl(depth + 1)
            stmt(s, depth + 1)
        }
        sb.append(')')
    }

    fun stmt(s: MirStmt, d: Int) {
        when (s) {
            is MirStmt.Declare -> {
                sb.append("(declare ")
                local(s.local); sb.append(' ')
                optExpr(s.init)
                sb.append(')')
            }
            is MirStmt.Assign -> {
                sb.append("(assign ")
                local(s.local); sb.append(' ')
                expr(s.value)
                sb.append(')')
            }
            is MirStmt.SetField -> {
                sb.append("(setfield ")
                expr(s.obj); sb.append(' ')
                str(s.owner); sb.append(' ')
                str(s.field); sb.append(' ')
                type(s.fieldType); sb.append(' ')
                expr(s.value)
                sb.append(')')
            }
            is MirStmt.Eval -> {
                sb.append("(eval ")
                expr(s.expr)
                sb.append(')')
            }
            is MirStmt.If -> {
                sb.append("(if ")
                expr(s.cond)
                nl(d + 1); body("then", s.thenBody, d + 1)
                nl(d + 1); body("else", s.elseBody, d + 1)
                sb.append(')')
            }
            is MirStmt.Block -> body("block", s.body, d)
            is MirStmt.While -> {
                sb.append("(while ").append(s.label.id).append(' ')
                expr(s.cond)
                nl(d + 1); body("body", s.body, d + 1)
                sb.append(')')
            }
            is MirStmt.Loop -> {
                sb.append("(loop ").append(s.label.id)
                nl(d + 1); body("body", s.body, d + 1)
                sb.append(')')
            }
            is MirStmt.DoWhile -> {
                sb.append("(dowhile ").append(s.label.id).append(' ')
                expr(s.cond)
                nl(d + 1); body("body", s.body, d + 1)
                nl(d + 1); body("condstmts", s.condStmts, d + 1)
                sb.append(')')
            }
            is MirStmt.ForRange -> {
                sb.append("(forrange ").append(s.label.id).append(' ')
                local(s.variable); sb.append(' ')
                expr(s.start); sb.append(' ')
                expr(s.end); sb.append(' ')
                optExpr(s.step); sb.append(' ')
                sb.append(s.ascending).append(' ').append(s.inclusive)
                nl(d + 1); body("body", s.body, d + 1)
                sb.append(')')
            }
            is MirStmt.Break -> sb.append("(break ").append(s.label.id).append(')')
            is MirStmt.Continue -> sb.append("(continue ").append(s.label.id).append(')')
            is MirStmt.Return -> {
                sb.append("(return ")
                optExpr(s.value)
                sb.append(')')
            }
            is MirStmt.Panic -> {
                sb.append("(panic ")
                str(s.message)
                sb.append(')')
            }
            is MirStmt.Throw -> {
                sb.append("(throw ")
                expr(s.value)
                sb.append(')')
            }
            is MirStmt.Try -> {
                sb.append("(try")
                nl(d + 1); body("body", s.body, d + 1)
                for (c in s.catches) {
                    nl(d + 1)
                    sb.append("(catch ")
                    type(c.type)
                    for (x in c.body) {
                        nl(d + 2)
                        stmt(x, d + 2)
                    }
                    sb.append(')')
                }
                sb.append(')')
            }
        }
    }

    fun optExpr(e: MirExpr?) {
        if (e == null) sb.append('-') else expr(e)
    }

    fun exprs(list: List<MirExpr>) {
        for (a in list) { sb.append(' '); expr(a) }
    }

    fun types(list: List<MirType>) {
        sb.append("(types")
        for (t in list) { sb.append(' '); type(t) }
        sb.append(')')
    }

    fun binaryOp(op: BinaryOp): String = when (op) {
        BinaryOp.Add -> "Add"
        BinaryOp.Sub -> "Sub"
        BinaryOp.Mul -> "Mul"
        BinaryOp.Div -> "Div"
        BinaryOp.Rem -> "Rem"
        BinaryOp.Shl -> "Shl"
        BinaryOp.Shr -> "Shr"
        BinaryOp.Ushr -> "Ushr"
        BinaryOp.And -> "And"
        BinaryOp.Or -> "Or"
        BinaryOp.Xor -> "Xor"
        BinaryOp.Eq -> "Eq"
        BinaryOp.Ne -> "Ne"
        BinaryOp.Lt -> "Lt"
        BinaryOp.Le -> "Le"
        BinaryOp.Gt -> "Gt"
        BinaryOp.Ge -> "Ge"
        BinaryOp.IdEq -> "IdEq"
        BinaryOp.IdNe -> "IdNe"
        BinaryOp.AndAnd -> "AndAnd"
        BinaryOp.OrOr -> "OrOr"
    }

    fun unaryOp(op: UnaryOp): String = when (op) {
        UnaryOp.Neg -> "Neg"
        UnaryOp.Not -> "Not"
        UnaryOp.Inv -> "Inv"
    }

    fun castMode(m: MirExpr.CastMode): String = when (m) {
        MirExpr.CastMode.Unchecked -> "Unchecked"
        MirExpr.CastMode.Checked -> "Checked"
        MirExpr.CastMode.Safe -> "Safe"
    }

    fun expr(e: MirExpr) {
        when (e) {
            is MirExpr.IntConst -> sb.append("(int ").append(e.value).append(')')
            is MirExpr.LongConst -> sb.append("(long ").append(e.value).append(')')
            is MirExpr.DoubleConst -> sb.append("(double ").append(mirDouble(e.value)).append(')')
            is MirExpr.BooleanConst -> sb.append("(bool ").append(e.value).append(')')
            is MirExpr.CharConst -> sb.append("(char ").append(e.code).append(')')
            is MirExpr.StringConst -> { sb.append("(str "); str(e.value); sb.append(')') }
            MirExpr.UnitValue -> sb.append("unit")
            is MirExpr.Local -> local(e.local)
            is MirExpr.Binary -> {
                sb.append("(bin ").append(binaryOp(e.op)).append(' ')
                expr(e.left); sb.append(' '); expr(e.right); sb.append(')')
            }
            is MirExpr.Unary -> {
                sb.append("(un ").append(unaryOp(e.op)).append(' ')
                expr(e.operand); sb.append(')')
            }
            is MirExpr.Convert -> {
                sb.append("(conv "); expr(e.operand); sb.append(' '); type(e.to); sb.append(')')
            }
            is MirExpr.Call -> {
                sb.append("(call "); str(e.cName); sb.append(' '); type(e.type); exprs(e.args); sb.append(')')
            }
            is MirExpr.RuntimeCall -> {
                sb.append("(rcall "); str(e.cName); sb.append(' '); type(e.type); exprs(e.args); sb.append(')')
            }
            is MirExpr.Conditional -> {
                sb.append("(cond "); expr(e.cond); sb.append(' '); expr(e.thenValue); sb.append(' ')
                expr(e.elseValue); sb.append(' '); optType(e.resultType); sb.append(')')
            }
            is MirExpr.NullConst -> { sb.append("(null "); type(e.type); sb.append(')') }
            is MirExpr.Box -> { sb.append("(box "); expr(e.operand); sb.append(' '); type(e.to); sb.append(')') }
            is MirExpr.Unbox -> { sb.append("(unbox "); expr(e.operand); sb.append(' '); type(e.to); sb.append(')') }
            is MirExpr.Cast -> {
                sb.append("(cast "); expr(e.operand); sb.append(' '); type(e.to); sb.append(' ')
                sb.append(castMode(e.mode)).append(')')
            }
            is MirExpr.IsInstance -> { sb.append("(is "); expr(e.operand); sb.append(' '); type(e.target); sb.append(')') }
            is MirExpr.NotNull -> { sb.append("(nn "); expr(e.operand); sb.append(')') }
            is MirExpr.FieldGet -> {
                sb.append("(get "); expr(e.obj); sb.append(' '); str(e.owner); sb.append(' ')
                str(e.field); sb.append(' '); type(e.type); sb.append(')')
            }
            is MirExpr.VCall -> {
                sb.append("(vcall ").append(e.slot).append(' '); type(e.type); sb.append(' ')
                types(e.paramTypes); sb.append(' '); expr(e.receiver); exprs(e.args); sb.append(')')
            }
            is MirExpr.ICall -> {
                sb.append("(icall "); str(e.interfaceCName); sb.append(' ').append(e.index).append(' ')
                type(e.type); sb.append(' '); types(e.paramTypes); sb.append(' '); expr(e.receiver)
                exprs(e.args); sb.append(')')
            }
            is MirExpr.New -> { sb.append("(new "); str(e.ctorCName); sb.append(' '); type(e.type); exprs(e.args); sb.append(')') }
            is MirExpr.ObjectGet -> { sb.append("(objget "); str(e.classCName); sb.append(' '); type(e.type); sb.append(')') }
            is MirExpr.StringLength -> { sb.append("(slen "); expr(e.operand); sb.append(')') }
            is MirExpr.Unreachable -> { sb.append("(unreachable "); type(e.type); sb.append(')') }
            is MirExpr.CaughtException -> { sb.append("(caught "); type(e.type); sb.append(')') }
            is MirExpr.TypeInfoOf -> { sb.append("(tinfo "); optType(e.target); sb.append(')') }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// reading

private sealed class Sx {
    class Atom(val text: String) : Sx()
    class Str(val value: String) : Sx()
    class Lst(val items: List<Sx>) : Sx()
}

private class SxParser(val text: String) {
    var pos = 0
    var line = 1

    fun fail(msg: String): Nothing = throw MirFormatError("line $line: $msg")

    fun parseDocument(): Sx {
        skipBlank()
        val x = parse()
        skipBlank()
        if (pos < text.length) fail("unexpected text after the module")
        return x
    }

    fun skipBlank() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == '\n') { line++; pos++ }
            else if (c == ' ' || c == '\t' || c == '\r') pos++
            else if (c == ';') { while (pos < text.length && text[pos] != '\n') pos++ }
            else return
        }
    }

    fun parse(): Sx {
        if (pos >= text.length) fail("unexpected end of input")
        val c = text[pos]
        if (c == '(') {
            pos++
            val items = ArrayList<Sx>()
            while (true) {
                skipBlank()
                if (pos >= text.length) fail("missing ')'")
                if (text[pos] == ')') { pos++; break }
                items.add(parse())
            }
            return Sx.Lst(items)
        }
        if (c == ')') fail("unexpected ')'")
        if (c == '"') return parseString()
        val start = pos
        while (pos < text.length) {
            val d = text[pos]
            if (d == ' ' || d == '\n' || d == '\t' || d == '\r' || d == '(' || d == ')' || d == '"' || d == ';') break
            pos++
        }
        return Sx.Atom(text.substring(start, pos))
    }

    fun hexValue(c: Char): Int {
        if (c >= '0' && c <= '9') return c.code - '0'.code
        if (c >= 'a' && c <= 'f') return c.code - 'a'.code + 10
        if (c >= 'A' && c <= 'F') return c.code - 'A'.code + 10
        fail("bad hex digit '$c'")
    }

    fun parseString(): Sx {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            if (c == '"') break
            if (c == '\n') line++
            if (c != '\\') { sb.append(c); continue }
            if (pos >= text.length) fail("unterminated string")
            val e = text[pos++]
            if (e == '"') sb.append('"')
            else if (e == '\\') sb.append('\\')
            else if (e == 'n') sb.append('\n')
            else if (e == 'r') sb.append('\r')
            else if (e == 't') sb.append('\t')
            else if (e == 'u') {
                if (pos + 4 > text.length) fail("truncated \\u escape")
                var v = 0
                for (k in 0 until 4) v = v * 16 + hexValue(text[pos + k])
                pos += 4
                sb.append(v.toChar())
            } else fail("bad escape \\$e")
        }
        return Sx.Str(sb.toString())
    }
}

private class MirReaderImpl {
    var locals = HashMap<Int, MirLocal>()
    var labels = HashMap<Int, MirLabel>()

    fun fail(msg: String): Nothing = throw MirFormatError(msg)

    fun run(text: String): MirModule {
        val root = SxParser(text).parseDocument()
        val items = lst(root, "mini-mir")
        if (items.size < 3) fail("truncated module header")
        val version = int(items[1])
        if (version != MIR_FORMAT_VERSION) fail("unsupported MIR format version $version (expected $MIR_FORMAT_VERSION)")
        val sourceName = str(items[2])
        val classes = ArrayList<MirClass>()
        val functions = ArrayList<MirFunction>()
        for (i in 3 until items.size) {
            val form = items[i]
            val tag = tagOf(form)
            if (tag == "class") classes.add(cls(form))
            else if (tag == "fun") functions.add(fn(form))
            else fail("unexpected top-level form '$tag'")
        }
        return MirModule(sourceName, functions, classes)
    }

    // -- generic helpers

    fun tagOf(x: Sx): String {
        if (x is Sx.Lst && x.items.isNotEmpty()) {
            val h = x.items[0]
            if (h is Sx.Atom) return h.text
        }
        fail("expected a (tag ...) form")
    }

    /** The items of a list whose tag is [tag]; item 0 is the tag itself. */
    fun lst(x: Sx, tag: String): List<Sx> {
        if (tagOf(x) != tag) fail("expected ($tag ...) but found (${tagOf(x)} ...)")
        return (x as Sx.Lst).items
    }

    fun need(items: List<Sx>, n: Int, tag: String) {
        if (items.size < n) fail("($tag ...) needs at least ${n - 1} operands")
    }

    fun atom(x: Sx): String {
        if (x is Sx.Atom) return x.text
        fail("expected an atom")
    }

    fun str(x: Sx): String {
        if (x is Sx.Str) return x.value
        fail("expected a string")
    }

    fun optStr(x: Sx): String? {
        if (x is Sx.Atom && x.text == "-") return null
        return str(x)
    }

    fun int(x: Sx): Int {
        val t = atom(x)
        return t.toIntOrNull() ?: fail("bad integer '$t'")
    }

    fun bool(x: Sx): Boolean {
        val t = atom(x)
        if (t == "true") return true
        if (t == "false") return false
        fail("bad boolean '$t'")
    }

    // -- module parts

    fun cls(x: Sx): MirClass {
        val it = lst(x, "class")
        need(it, 11, "class")
        val fields = ArrayList<MirField>()
        for (f in lst(it[7], "fields").drop(1)) {
            val fi = lst(f, "f")
            fields.add(MirField(str(fi[1]), type(fi[2])))
        }
        val interfaces = ArrayList<String>()
        for (s in lst(it[8], "interfaces").drop(1)) interfaces.add(str(s))
        val vtable = ArrayList<String?>()
        for (s in lst(it[9], "vtable").drop(1)) vtable.add(optStr(s))
        val itables = ArrayList<MirItable>()
        for (t in lst(it[10], "itables").drop(1)) {
            val ti = lst(t, "it")
            val entries = ArrayList<String?>()
            for (k in 2 until ti.size) entries.add(optStr(ti[k]))
            itables.add(MirItable(str(ti[1]), entries))
        }
        return MirClass(
            qualifiedName = str(it[1]),
            cName = str(it[2]),
            isInterface = bool(it[3]),
            superCName = optStr(it[4]),
            fields = fields,
            interfaces = interfaces,
            vtable = vtable,
            itables = itables,
            objectCtor = optStr(it[6]),
            external = bool(it[5]),
        )
    }

    fun fn(x: Sx): MirFunction {
        val it = lst(x, "fun")
        need(it, 6, "fun")
        locals = HashMap()
        labels = HashMap()
        val params = ArrayList<MirLocal>()
        for (p in lst(it[3], "params").drop(1)) params.add(local(p))
        val ret = type(it[4])
        val ctor = optStr(it[5])
        val body = ArrayList<MirStmt>()
        for (k in 6 until it.size) body.add(stmt(it[k]))
        return MirFunction(str(it[1]), str(it[2]), params, ret, body, ctor)
    }

    fun local(x: Sx): MirLocal {
        val it = lst(x, "l")
        need(it, 2, "l")
        val id = int(it[1])
        if (it.size >= 4) {
            val l = MirLocal(id, str(it[2]), type(it[3]))
            locals[id] = l
            return l
        }
        return locals[id] ?: fail("local $id used before its definition")
    }

    fun label(x: Sx): MirLabel {
        val id = int(x)
        var l = labels[id]
        if (l == null) {
            l = MirLabel(id)
            labels[id] = l
        }
        return l
    }

    fun primType(t: String): MirType? = when (t) {
        "Int" -> TInt
        "Long" -> TLong
        "Boolean" -> TBoolean
        "Double" -> TDouble
        "Char" -> TChar
        "String" -> TString
        "Unit" -> TUnit
        "Nothing" -> TNothing
        "String?" -> TNString
        "Null" -> TNull
        else -> null
    }

    fun type(x: Sx): MirType {
        if (x is Sx.Atom) return primType(x.text) ?: fail("unknown type '${x.text}'")
        val tag = tagOf(x)
        val it = (x as Sx.Lst).items
        if (tag == "obj") {
            need(it, 5, "obj")
            return TObject(str(it[1]), str(it[2]), bool(it[3]), bool(it[4]))
        }
        if (tag == "boxed") {
            need(it, 2, "boxed")
            return TBoxed(type(it[1]))
        }
        fail("unknown type form '$tag'")
    }

    fun optType(x: Sx): MirType? {
        if (x is Sx.Atom && x.text == "-") return null
        return type(x)
    }

    fun types(x: Sx): List<MirType> {
        val out = ArrayList<MirType>()
        for (t in lst(x, "types").drop(1)) out.add(type(t))
        return out
    }

    fun stmts(x: Sx, tag: String): List<MirStmt> {
        val out = ArrayList<MirStmt>()
        for (s in lst(x, tag).drop(1)) out.add(stmt(s))
        return out
    }

    fun binaryOp(s: String): BinaryOp = when (s) {
        "Add" -> BinaryOp.Add
        "Sub" -> BinaryOp.Sub
        "Mul" -> BinaryOp.Mul
        "Div" -> BinaryOp.Div
        "Rem" -> BinaryOp.Rem
        "Shl" -> BinaryOp.Shl
        "Shr" -> BinaryOp.Shr
        "Ushr" -> BinaryOp.Ushr
        "And" -> BinaryOp.And
        "Or" -> BinaryOp.Or
        "Xor" -> BinaryOp.Xor
        "Eq" -> BinaryOp.Eq
        "Ne" -> BinaryOp.Ne
        "Lt" -> BinaryOp.Lt
        "Le" -> BinaryOp.Le
        "Gt" -> BinaryOp.Gt
        "Ge" -> BinaryOp.Ge
        "IdEq" -> BinaryOp.IdEq
        "IdNe" -> BinaryOp.IdNe
        "AndAnd" -> BinaryOp.AndAnd
        "OrOr" -> BinaryOp.OrOr
        else -> fail("unknown binary operator '$s'")
    }

    fun unaryOp(s: String): UnaryOp = when (s) {
        "Neg" -> UnaryOp.Neg
        "Not" -> UnaryOp.Not
        "Inv" -> UnaryOp.Inv
        else -> fail("unknown unary operator '$s'")
    }

    fun castMode(s: String): MirExpr.CastMode = when (s) {
        "Unchecked" -> MirExpr.CastMode.Unchecked
        "Checked" -> MirExpr.CastMode.Checked
        "Safe" -> MirExpr.CastMode.Safe
        else -> fail("unknown cast mode '$s'")
    }

    // -- statements (decoded in the order the writer wrote them: it matters for first-mention locals)

    fun stmt(x: Sx): MirStmt {
        val tag = tagOf(x)
        val it = (x as Sx.Lst).items
        when (tag) {
            "declare" -> {
                need(it, 3, tag)
                val l = local(it[1])
                return MirStmt.Declare(l, optExpr(it[2]))
            }
            "assign" -> {
                need(it, 3, tag)
                val l = local(it[1])
                return MirStmt.Assign(l, expr(it[2]))
            }
            "setfield" -> {
                need(it, 6, tag)
                val obj = expr(it[1])
                val owner = str(it[2])
                val field = str(it[3])
                val ft = type(it[4])
                return MirStmt.SetField(obj, owner, field, ft, expr(it[5]))
            }
            "eval" -> { need(it, 2, tag); return MirStmt.Eval(expr(it[1])) }
            "if" -> {
                need(it, 4, tag)
                val c = expr(it[1])
                val t = stmts(it[2], "then")
                return MirStmt.If(c, t, stmts(it[3], "else"))
            }
            "block" -> return MirStmt.Block(stmts(x, "block"))
            "while" -> {
                need(it, 4, tag)
                val lab = label(it[1])
                val c = expr(it[2])
                return MirStmt.While(lab, c, stmts(it[3], "body"))
            }
            "loop" -> { need(it, 3, tag); return MirStmt.Loop(label(it[1]), stmts(it[2], "body")) }
            "dowhile" -> {
                need(it, 5, tag)
                val lab = label(it[1])
                val c = expr(it[2])
                val body = stmts(it[3], "body")
                return MirStmt.DoWhile(lab, body, stmts(it[4], "condstmts"), c)
            }
            "forrange" -> {
                need(it, 9, tag)
                val lab = label(it[1])
                val v = local(it[2])
                val start = expr(it[3])
                val end = expr(it[4])
                val step = optExpr(it[5])
                val asc = bool(it[6])
                val incl = bool(it[7])
                return MirStmt.ForRange(lab, v, start, end, step, asc, incl, stmts(it[8], "body"))
            }
            "break" -> { need(it, 2, tag); return MirStmt.Break(label(it[1])) }
            "continue" -> { need(it, 2, tag); return MirStmt.Continue(label(it[1])) }
            "return" -> { need(it, 2, tag); return MirStmt.Return(optExpr(it[1])) }
            "panic" -> { need(it, 2, tag); return MirStmt.Panic(str(it[1])) }
            "throw" -> { need(it, 2, tag); return MirStmt.Throw(expr(it[1])) }
            "try" -> {
                need(it, 2, tag)
                val body = stmts(it[1], "body")
                val catches = ArrayList<MirCatch>()
                for (k in 2 until it.size) {
                    val c = lst(it[k], "catch")
                    need(c, 2, "catch")
                    val cb = ArrayList<MirStmt>()
                    for (j in 2 until c.size) cb.add(stmt(c[j]))
                    catches.add(MirCatch(type(c[1]), cb))
                }
                return MirStmt.Try(body, catches)
            }
            else -> fail("unknown statement '$tag'")
        }
    }

    // -- expressions

    fun optExpr(x: Sx): MirExpr? {
        if (x is Sx.Atom && x.text == "-") return null
        return expr(x)
    }

    fun exprsFrom(it: List<Sx>, from: Int): List<MirExpr> {
        val out = ArrayList<MirExpr>()
        for (k in from until it.size) out.add(expr(it[k]))
        return out
    }

    fun parseDouble(t: String): Double {
        if (t == "nan") return Double.NaN
        if (t == "inf") return Double.POSITIVE_INFINITY
        if (t == "-inf") return Double.NEGATIVE_INFINITY
        return t.toDoubleOrNull() ?: fail("bad double '$t'")
    }

    fun expr(x: Sx): MirExpr {
        if (x is Sx.Atom) {
            if (x.text == "unit") return MirExpr.UnitValue
            fail("unknown expression '${x.text}'")
        }
        val tag = tagOf(x)
        val it = (x as Sx.Lst).items
        when (tag) {
            "int" -> { need(it, 2, tag); return MirExpr.IntConst(int(it[1])) }
            "long" -> { need(it, 2, tag); val t = atom(it[1]); return MirExpr.LongConst(t.toLongOrNull() ?: fail("bad long '$t'")) }
            "double" -> { need(it, 2, tag); return MirExpr.DoubleConst(parseDouble(atom(it[1]))) }
            "bool" -> { need(it, 2, tag); return MirExpr.BooleanConst(bool(it[1])) }
            "char" -> { need(it, 2, tag); return MirExpr.CharConst(int(it[1])) }
            "str" -> { need(it, 2, tag); return MirExpr.StringConst(str(it[1])) }
            "l" -> return MirExpr.Local(local(x))
            "bin" -> {
                need(it, 4, tag)
                val op = binaryOp(atom(it[1]))
                val l = expr(it[2])
                return MirExpr.Binary(op, l, expr(it[3]))
            }
            "un" -> { need(it, 3, tag); return MirExpr.Unary(unaryOp(atom(it[1])), expr(it[2])) }
            "conv" -> { need(it, 3, tag); return MirExpr.Convert(expr(it[1]), type(it[2])) }
            "call" -> { need(it, 3, tag); val t = type(it[2]); return MirExpr.Call(str(it[1]), exprsFrom(it, 3), t) }
            "rcall" -> { need(it, 3, tag); val t = type(it[2]); return MirExpr.RuntimeCall(str(it[1]), exprsFrom(it, 3), t) }
            "cond" -> {
                need(it, 5, tag)
                val c = expr(it[1])
                val a = expr(it[2])
                val b = expr(it[3])
                return MirExpr.Conditional(c, a, b, optType(it[4]))
            }
            "null" -> { need(it, 2, tag); return MirExpr.NullConst(type(it[1])) }
            "box" -> { need(it, 3, tag); return MirExpr.Box(expr(it[1]), type(it[2])) }
            "unbox" -> { need(it, 3, tag); return MirExpr.Unbox(expr(it[1]), type(it[2])) }
            "cast" -> { need(it, 4, tag); return MirExpr.Cast(expr(it[1]), type(it[2]), castMode(atom(it[3]))) }
            "is" -> { need(it, 3, tag); return MirExpr.IsInstance(expr(it[1]), type(it[2])) }
            "nn" -> { need(it, 2, tag); return MirExpr.NotNull(expr(it[1])) }
            "get" -> { need(it, 5, tag); return MirExpr.FieldGet(expr(it[1]), str(it[2]), str(it[3]), type(it[4])) }
            "vcall" -> {
                need(it, 5, tag)
                val slot = int(it[1])
                val t = type(it[2])
                val pts = types(it[3])
                val recv = expr(it[4])
                return MirExpr.VCall(slot, recv, exprsFrom(it, 5), pts, t)
            }
            "icall" -> {
                need(it, 6, tag)
                val iface = str(it[1])
                val index = int(it[2])
                val t = type(it[3])
                val pts = types(it[4])
                val recv = expr(it[5])
                return MirExpr.ICall(iface, index, recv, exprsFrom(it, 6), pts, t)
            }
            "new" -> { need(it, 3, tag); val t = type(it[2]); return MirExpr.New(str(it[1]), exprsFrom(it, 3), t) }
            "objget" -> { need(it, 3, tag); return MirExpr.ObjectGet(str(it[1]), type(it[2])) }
            "slen" -> { need(it, 2, tag); return MirExpr.StringLength(expr(it[1])) }
            "unreachable" -> { need(it, 2, tag); return MirExpr.Unreachable(type(it[1])) }
            "caught" -> { need(it, 2, tag); return MirExpr.CaughtException(type(it[1])) }
            "tinfo" -> { need(it, 2, tag); return MirExpr.TypeInfoOf(optType(it[1])) }
            else -> fail("unknown expression '$tag'")
        }
    }
}
