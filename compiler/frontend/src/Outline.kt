package mini.frontend

/**
 * Canonical, line-oriented rendering of the AST: one line per top-level declaration with everything nested inline
 * (declarations, parameters, types, and every expression as a fully parenthesised S-expression, so operator
 * precedence and associativity show up in the text). The oracle tests render the PSI tree of the K2 compiler with the
 * same grammar of strings and require equality; the native self-compilation test compares the output of the driver on
 * the JVM and natively.
 *
 * Counters: [decls], [exprs] and [types] count the rendered nodes.
 */
class Outline {
    var decls = 0
    var exprs = 0
    var types = 0

    fun file(f: KtFile): List<String> {
        val out = ArrayList<String>()
        out.add("package " + (f.packageName ?: "-"))
        for (a in f.fileAnnotations) out.add("fileann " + annotation(a))
        for (i in f.imports) {
            out.add("import " + i.path + (if (i.star) ".*" else "") + (if (i.alias != null) " as " + i.alias else ""))
        }
        for (d in f.declarations) out.add(decl(d))
        return out
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private fun join(parts: List<String>, sep: String): String {
        val sb = StringBuilder()
        var first = true
        for (p in parts) {
            if (!first) sb.append(sep)
            first = false
            sb.append(p)
        }
        return sb.toString()
    }

    fun mods(m: Modifiers?): String {
        if (m == null) return ""
        val parts = ArrayList<String>()
        if (m.contextParams.isNotEmpty()) {
            val ps = ArrayList<String>()
            for (p in m.contextParams) ps.add(param(p))
            parts.add("ctx(" + join(ps, ",") + ")")
        }
        for (i in m.items) {
            when (i) {
                is KeywordMod -> parts.add(i.text)
                is AnnotationMod -> parts.add(annotation(i.annotation))
            }
        }
        if (parts.isEmpty()) return ""
        return " [" + join(parts, " ") + "]"
    }

    private fun annotation(a: AnnotationEntry): String {
        val sb = StringBuilder("@")
        if (a.target != null) sb.append(a.target).append(':')
        sb.append(type(a.type))
        val args = a.args
        if (args != null) sb.append('(').append(args(args)).append(')')
        return sb.toString()
    }

    private fun args(list: List<Arg>): String {
        val parts = ArrayList<String>()
        for (a in list) {
            val sb = StringBuilder()
            if (a.name != null) sb.append(a.name).append('=')
            if (a.spread) sb.append('*')
            sb.append(expr(a.value))
            parts.add(sb.toString())
        }
        return join(parts, ",")
    }

    // ---- types ------------------------------------------------------------------------------------------------

    fun type(t: TypeRef?): String {
        if (t == null) return "_"
        types++
        val sb = StringBuilder()
        val m = t.mods
        if (m != null) {
            for (i in m.items) {
                when (i) {
                    is KeywordMod -> sb.append(i.text).append(' ')
                    is AnnotationMod -> sb.append(annotation(i.annotation)).append(' ')
                }
            }
        }
        sb.append(typeElement(t))
        return sb.toString()
    }

    private fun typeElement(t: TypeRef): String = when (t) {
        is UserType -> {
            val segs = ArrayList<String>()
            for (s in t.segments) {
                if (s.args.isEmpty()) {
                    segs.add(s.name)
                } else {
                    val a = ArrayList<String>()
                    for (x in s.args) {
                        if (x.star) a.add("*") else a.add((if (x.variance != null) x.variance + " " else "") + type(x.type))
                    }
                    segs.add(s.name + "<" + join(a, ",") + ">")
                }
            }
            join(segs, ".")
        }
        is NullableType -> typeElementOf(t.inner) + "?"
        is ParenType -> typeElementOf(t.inner)
        is FunctionType -> {
            val sb = StringBuilder()
            if (t.contextTypes.isNotEmpty()) {
                val c = ArrayList<String>()
                for (x in t.contextTypes) c.add(type(x))
                sb.append("context(").append(join(c, ",")).append(") ")
            }
            if (t.receiver != null) sb.append(type(t.receiver)).append('.')
            val ps = ArrayList<String>()
            for (p in t.params) ps.add((if (p.name != null) p.name + ":" else "") + type(p.type))
            sb.append('(').append(join(ps, ",")).append(")->").append(type(t.returnType))
            sb.toString()
        }
        is DynamicType -> "dynamic"
        is IntersectionType -> type(t.left) + "&" + type(t.right)
        is ErrorType -> "<error>"
    }

    /** the element of a nested type: annotations of the nested type are part of the rendering */
    private fun typeElementOf(t: TypeRef): String {
        return type(t)
    }

    // ---- declarations -----------------------------------------------------------------------------------------

    private fun typeParams(list: List<TypeParam>): String {
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (p in list) {
            val mm = mods(p.mods)
            parts.add((if (mm.isEmpty()) "" else mm.substring(1) + " ") + p.name + (if (p.bound != null) ":" + type(p.bound) else ""))
        }
        return " <" + join(parts, ", ") + ">"
    }

    private fun constraints(list: List<TypeConstraint>): String {
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (c in list) parts.add(c.name + ":" + type(c.bound))
        return " where " + join(parts, ", ")
    }

    fun param(p: Param): String {
        val sb = StringBuilder()
        val mm = mods(p.mods)
        if (mm.isNotEmpty()) sb.append(mm.substring(1)).append(' ')
        if (p.valVar != null) sb.append(p.valVar).append(' ')
        sb.append(p.name)
        if (p.type != null) sb.append(':').append(type(p.type))
        if (p.default != null) sb.append('=').append(expr(p.default))
        return sb.toString()
    }

    private fun params(list: List<Param>): String {
        val parts = ArrayList<String>()
        for (p in list) parts.add(param(p))
        return "(" + join(parts, ", ") + ")"
    }

    private fun destruct(list: List<DestructEntry>, inheritVar: Boolean, bracketed: Boolean): String {
        val parts = ArrayList<String>()
        for (e in list) {
            val mm = mods(e.mods)
            parts.add(
                (if (mm.isEmpty()) "" else mm.substring(1) + " ") + (if (e.isVar || inheritVar) "var " else "") + e.name +
                    (if (e.type != null) ":" + type(e.type) else "") + (if (e.source != null) "=" + e.source else ""),
            )
        }
        return (if (bracketed) "[" else "(") + join(parts, ", ") + (if (bracketed) "]" else ")")
    }

    private fun superEntries(list: List<SuperEntry>): String {
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (s in list) {
            when (s) {
                is SuperType -> parts.add(type(s.type))
                is SuperCall -> parts.add(type(s.type) + "(" + args(s.args) + ")")
                is SuperDelegate -> parts.add(type(s.type) + " by " + expr(s.delegate))
            }
        }
        return " : " + join(parts, ", ")
    }

    private fun members(list: List<Decl>): String {
        val parts = ArrayList<String>()
        for (d in list) parts.add(decl(d))
        return join(parts, "; ")
    }

    fun decl(d: Decl): String {
        decls++
        return when (d) {
            is ClassDecl -> {
                val sb = StringBuilder()
                sb.append(if (d.kind == ClassKind.CLASS) "class" else if (d.kind == ClassKind.INTERFACE) "interface" else "object")
                sb.append(' ').append(d.name ?: "_").append(mods(d.mods)).append(typeParams(d.typeParams))
                val c = d.primaryCtor
                if (c != null) sb.append(" ctor").append(mods(c.mods)).append(params(c.params))
                sb.append(superEntries(d.supers)).append(constraints(d.constraints))
                if (d.hasBody) {
                    val parts = ArrayList<String>()
                    for (e in d.enumEntries) parts.add(decl(e))
                    for (m in d.members) parts.add(decl(m))
                    sb.append(" { ").append(join(parts, "; ")).append(" }")
                }
                sb.toString()
            }
            is EnumEntryDecl -> {
                val sb = StringBuilder("entry ")
                sb.append(d.name).append(mods(d.mods))
                if (d.args != null) sb.append('(').append(args(d.args)).append(')')
                if (d.members != null) sb.append(" { ").append(members(d.members)).append(" }")
                sb.toString()
            }
            is FunDecl -> fn(d)
            is PropertyDecl -> {
                val sb = StringBuilder(if (d.isVar) "var" else "val")
                sb.append(mods(d.mods)).append(typeParams(d.typeParams)).append(' ')
                if (d.receiver != null) sb.append(type(d.receiver)).append('.')
                sb.append(d.name)
                if (d.type != null) sb.append(':').append(type(d.type))
                sb.append(constraints(d.constraints))
                if (d.initializer != null) sb.append(" = ").append(expr(d.initializer))
                if (d.delegate != null) sb.append(" by ").append(expr(d.delegate))
                val bf = d.backingField
                if (bf != null) {
                    sb.append(" field").append(mods(bf.mods))
                    if (bf.type != null) sb.append(':').append(type(bf.type))
                    if (bf.initializer != null) sb.append(" = ").append(expr(bf.initializer))
                }
                if (d.getter != null) sb.append(' ').append(accessor(d.getter))
                if (d.setter != null) sb.append(' ').append(accessor(d.setter))
                sb.toString()
            }
            is DestructuringDecl -> {
                val sb = StringBuilder(if (d.isVar) "var" else "val")
                sb.append(mods(d.mods)).append(' ').append(destruct(d.entries, d.isVar, d.bracketed))
                if (d.initializer != null) sb.append(" = ").append(expr(d.initializer))
                sb.toString()
            }
            is TypeAliasDecl -> "typealias " + d.name + mods(d.mods) + typeParams(d.typeParams) + " = " + type(d.type)
            is InitDecl -> "init " + (if (d.body != null) expr(d.body) else "_")
            is SecondaryCtor -> {
                val sb = StringBuilder("constructor")
                sb.append(mods(d.mods)).append(params(d.params))
                val dg = d.delegation
                if (dg != null) sb.append(if (dg.isThis) " : this(" else " : super(").append(args(dg.args)).append(')')
                if (d.body != null) sb.append(' ').append(expr(d.body))
                sb.toString()
            }
        }
    }

    private fun accessor(a: AccessorDecl): String {
        val sb = StringBuilder(a.kind)
        sb.append(mods(a.mods))
        if (a.param != null) sb.append('(').append(param(a.param)).append(')') else sb.append("()")
        if (a.returnType != null) sb.append(':').append(type(a.returnType))
        if (a.body != null) sb.append(if (a.expressionBody) " = " else " ").append(expr(a.body))
        return sb.toString()
    }

    private fun fn(d: FunDecl): String {
        val sb = StringBuilder("fun")
        sb.append(mods(d.mods)).append(typeParams(d.typeParams)).append(' ')
        if (d.receiver != null) sb.append(type(d.receiver)).append('.')
        sb.append(d.name ?: "").append(params(d.params))
        if (d.returnType != null) sb.append(':').append(type(d.returnType))
        sb.append(constraints(d.constraints))
        if (d.body != null) sb.append(if (d.expressionBody) " = " else " ").append(expr(d.body))
        return sb.toString()
    }

    // ---- statements and expressions ---------------------------------------------------------------------------

    private fun stmts(list: List<Stmt>): String {
        val parts = ArrayList<String>()
        for (s in list) parts.add(stmt(s))
        return join(parts, "; ")
    }

    private fun stmt(s: Stmt): String = when (s) {
        is Decl -> decl(s)
        is Expr -> expr(s)
    }

    fun expr(e: Expr?): String {
        if (e == null) return "_"
        if (e !is ParenExpr) exprs++
        return when (e) {
            is LiteralExpr -> e.text
            is StringExpr -> {
                val sb = StringBuilder(if (e.raw) "\"\"\"" else "\"")
                for (p in e.parts) {
                    when (p) {
                        is StrText -> sb.append(p.text)
                        is StrEscape -> sb.append(p.text)
                        is StrShortRef -> sb.append('$').append(p.name)
                        is StrLongExpr -> sb.append("\${").append(expr(p.expr)).append('}')
                    }
                }
                sb.append(if (e.raw) "\"\"\"" else "\"")
                sb.toString()
            }
            is NameExpr -> e.name
            is ThisExpr -> "this" + (if (e.label != null) "@" + e.label else "")
            is SuperExpr -> "super" + (if (e.typeArg != null) "<" + type(e.typeArg) + ">" else "") + (if (e.label != null) "@" + e.label else "")
            is ParenExpr -> expr(e.inner)
            is BinaryExpr -> "(" + e.op + " " + expr(e.left) + " " + expr(e.right) + ")"
            is IsExpr -> "(" + (if (e.negated) "!is " else "is ") + expr(e.expr) + " " + type(e.type) + ")"
            is AsExpr -> "(" + (if (e.safe) "as? " else "as ") + expr(e.expr) + " " + type(e.type) + ")"
            is PrefixExpr -> "(pre" + e.op + " " + expr(e.expr) + ")"
            is PostfixExpr -> "(post" + e.op + " " + expr(e.expr) + ")"
            is AnnotatedExpr -> {
                val parts = ArrayList<String>()
                for (a in e.annotations) parts.add(annotation(a))
                "(" + join(parts, " ") + " " + expr(e.expr) + ")"
            }
            is LabeledExpr -> "(" + e.label + "@ " + expr(e.expr) + ")"
            is CallExpr -> {
                val sb = StringBuilder("(call ").append(expr(e.callee))
                if (e.typeArgs.isNotEmpty()) {
                    val a = ArrayList<String>()
                    for (x in e.typeArgs) {
                        if (x.star) a.add("*") else a.add((if (x.variance != null) x.variance + " " else "") + type(x.type))
                    }
                    sb.append(" <").append(join(a, ",")).append('>')
                }
                if (e.args != null) sb.append(" (").append(args(e.args)).append(')')
                for (l in e.lambdas) sb.append(' ').append(expr(l))
                sb.append(')')
                sb.toString()
            }
            is IndexExpr -> {
                val parts = ArrayList<String>()
                for (i in e.indices) parts.add(expr(i))
                "(idx " + expr(e.receiver) + " " + join(parts, " ") + ")"
            }
            is DotExpr -> "(" + (if (e.safe) "?." else ".") + " " + expr(e.receiver) + " " + expr(e.selector) + ")"
            is CallableRefExpr -> "(:: " + (if (e.receiver != null) expr(e.receiver) else "_") + " " + e.name + ")"
            is LambdaExpr -> {
                val ps = ArrayList<String>()
                for (p in e.params) {
                    val mm = mods(p.mods)
                    val sb = StringBuilder()
                    if (mm.isNotEmpty()) sb.append(mm.substring(1)).append(' ')
                    if (p.names != null) sb.append(destruct(p.names, false, false)) else sb.append(p.name)
                    if (p.type != null) sb.append(':').append(type(p.type))
                    ps.add(sb.toString())
                }
                "(lam " + join(ps, ",") + " | " + stmts(e.body) + ")"
            }
            is AnonFunExpr -> {
                decls++
                fn(e.fn)
            }
            is BlockExpr -> "{" + stmts(e.stmts) + "}"
            is IfExpr -> "(if " + expr(e.cond) + " " + expr(e.then) + (if (e.otherwise != null) " " + expr(e.otherwise) else "") + ")"
            is WhenExpr -> {
                val sb = StringBuilder("(when ")
                val sj = e.subject
                if (sj == null) {
                    sb.append('_')
                } else if (sj.valName != null) {
                    sb.append("val ").append(sj.valName).append(if (sj.valType != null) ":" + type(sj.valType) else "").append('=').append(expr(sj.expr))
                } else {
                    sb.append(expr(sj.expr))
                }
                for (en in e.entries) {
                    sb.append(" (")
                    if (en.isElse) {
                        sb.append("else")
                    } else {
                        val cs = ArrayList<String>()
                        for (c in en.conds) {
                            when (c) {
                                is WhenExprCond -> cs.add(expr(c.expr))
                                is WhenInCond -> cs.add((if (c.negated) "!in " else "in ") + expr(c.expr))
                                is WhenIsCond -> cs.add((if (c.negated) "!is " else "is ") + type(c.type))
                            }
                        }
                        sb.append(join(cs, ","))
                        if (en.guard != null) sb.append(" if ").append(expr(en.guard))
                    }
                    sb.append(" -> ").append(expr(en.body)).append(')')
                }
                sb.append(')')
                sb.toString()
            }
            is TryExpr -> {
                val sb = StringBuilder("(try ").append(expr(e.body))
                for (c in e.catches) sb.append(" (catch ").append(param(c.param)).append(' ').append(expr(c.body)).append(')')
                if (e.finallyBlock != null) sb.append(" (finally ").append(expr(e.finallyBlock)).append(')')
                sb.append(')')
                sb.toString()
            }
            is ThrowExpr -> "(throw " + expr(e.expr) + ")"
            is ReturnExpr -> "(return" + (if (e.label != null) "@" + e.label else "") + (if (e.expr != null) " " + expr(e.expr) else "") + ")"
            is BreakExpr -> "(break" + (if (e.label != null) "@" + e.label else "") + ")"
            is ContinueExpr -> "(continue" + (if (e.label != null) "@" + e.label else "") + ")"
            is ForExpr -> {
                val v = if (e.destructuring) destruct(e.entries, false, false) else (e.entries[0].name + (if (e.entries[0].type != null) ":" + type(e.entries[0].type) else ""))
                "(for " + v + " in " + expr(e.iterable) + " " + expr(e.body) + ")"
            }
            is WhileExpr -> "(while " + expr(e.cond) + " " + expr(e.body) + ")"
            is DoWhileExpr -> "(do " + expr(e.body) + " while " + expr(e.cond) + ")"
            is ObjectLiteral -> "(object" + superEntries(e.supers) + " { " + members(e.members) + " })"
            is CollectionLiteral -> {
                val parts = ArrayList<String>()
                for (x in e.elements) parts.add(expr(x))
                "[" + join(parts, ",") + "]"
            }
            is ErrorExpr -> "<error>"
        }
    }
}

/** Convenience: the outline of a source text, one declaration per line. */
fun outlineOf(text: String): String {
    val r = parseKotlin(text)
    val sb = StringBuilder()
    for (l in Outline().file(r.file)) sb.append(l).append('\n')
    return sb.toString()
}
