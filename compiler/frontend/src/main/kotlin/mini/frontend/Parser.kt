package mini.frontend

/** Result of parsing one file: lossless CST, typed AST and all syntax errors (lexical ones included). */
class ParseResult(
    val text: String,
    val tokens: ArrayList<Token>,
    val root: CstNode,
    val file: KtFile,
    val errors: ArrayList<SyntaxError>,
)

/** Parses [text] as a Kotlin file. Never throws; syntax errors are collected in [ParseResult.errors]. */
fun parseKotlin(text: String): ParseResult = Parser(lexKotlin(text)).parseFile()

internal const val CTX_TOP = 0
internal const val CTX_CLASS = 1
internal const val CTX_LOCAL = 2

/** maximal nesting depth of expressions, types and declarations (deeper input is reported as an error) */
internal const val MAX_DEPTH = 250

fun isModifierWord(s: String): Boolean {
    val n = s.length
    if (n < 3 || n > 11) return false
    return when (s[0]) {
        'a' -> s == "abstract" || s == "actual" || s == "annotation"
        'c' -> s == "companion" || s == "const" || s == "crossinline"
        'd' -> s == "data"
        'e' -> s == "enum" || s == "expect" || s == "external"
        'f' -> s == "final"
        'i' -> s == "infix" || s == "inline" || s == "inner" || s == "internal"
        'l' -> s == "lateinit"
        'n' -> s == "noinline"
        'o' -> s == "open" || s == "operator" || s == "out" || s == "override"
        'p' -> s == "private" || s == "protected" || s == "public"
        'r' -> s == "reified"
        's' -> s == "sealed" || s == "suspend"
        't' -> s == "tailrec"
        'v' -> s == "vararg" || s == "value"
        else -> false
    }
}

internal class Snap(val p: Int, val rawPos: Int, val outSize: Int, val errSize: Int, val lastEnd: Int, val nlOn: Boolean)

/**
 * Hand-written recursive descent parser. The grammar follows the official Kotlin grammar (KotlinParser.g4); where the
 * grammar is ambiguous or leaves newline handling open it behaves like the reference PSI parser of the Kotlin compiler
 * (which is also the oracle of the tests).
 *
 * Mechanics: the parser walks the significant tokens ([sig]); trivia is attached to the CST when the next significant
 * token is consumed. All CST output goes to the flat list [out]; a finished construct is created by wrapping the tail
 * of that list from a mark ([mark] / [done]). Speculative parsing uses [snap] / [restore].
 */
class Parser(internal val lex: LexResult) {
    internal val text: String = lex.text
    internal val toks: ArrayList<Token> = lex.tokens
    /** the significant tokens (everything but whitespace and comments), the last one is the EOF token */
    internal val stoks = ArrayList<Token>()
    /** raw token index of each significant token */
    internal var sig = IntArray(0)
    /** is there a line break between the previous significant token and this one? */
    internal var nlBefore = BooleanArray(0)
    /** kind and start offset of the current token, cached for speed */
    internal var cur: Tk = Tk.EOF
    internal var curStart = 0
    internal var p = 0
    internal var rawPos = 0
    internal val out = ArrayList<Cst>()
    internal val errors = ArrayList<SyntaxError>()
    internal var lastEnd = 0
    /** false inside `(...)`, `[...]` and `<...>`: newlines do not terminate expressions there */
    internal var nlOn = true
    internal var opText = ""
    internal var opLevel = 0
    /** the binary expression just parsed ended with the type of an `is` check */
    internal var isClosed = false
    /** set while parsing the delegate of a class delegation: a `{` after it is the class body, not a lambda */
    internal var noLambda = false
    /** current nesting of expressions / types / declarations; bounded so that hostile input cannot exhaust the stack */
    internal var depth = 0
    private val lineStarts = ArrayList<Int>()
    private var lastSigRaw = -1

    init {
        var count = 0
        var i = 0
        while (i < toks.size) {
            if (!toks[i].kind.isTrivia()) count++
            i++
        }
        sig = IntArray(count)
        nlBefore = BooleanArray(count)
        var nl = false
        var n = 0
        i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.kind == Tk.WS) {
                var k = t.start
                while (k < t.end) {
                    if (text[k] == '\n') nl = true
                    k++
                }
            } else if (!t.kind.isTrivia()) {
                sig[n] = i
                nlBefore[n] = nl
                stoks.add(t)
                n++
                nl = false
            }
            i++
        }
        cur = stoks[0].kind
        curStart = stoks[0].start
        lineStarts.add(0)
        var k = 0
        while (k < text.length) {
            if (text[k] == '\n') lineStarts.add(k + 1)
            k++
        }
        for (e in lex.errors) errors.add(e)
    }

    // ---- token access ---------------------------------------------------------------------------------------------

    internal fun kind(): Tk = cur

    internal fun la(k: Int): Tk {
        val i = p + k
        return if (i < stoks.size) stoks[i].kind else Tk.EOF
    }

    internal fun tokStart(): Int = curStart

    internal fun tokStartAt(k: Int): Int {
        val i = p + k
        return if (i < stoks.size) stoks[i].start else text.length
    }

    internal fun tokEndAt(k: Int): Int {
        val i = p + k
        return if (i < stoks.size) stoks[i].end else text.length
    }

    internal fun textAt(k: Int): String {
        val i = p + k
        if (i >= stoks.size) return ""
        val t = stoks[i]
        return text.substring(t.start, t.end)
    }

    internal fun tokText(): String = textAt(0)

    /** is the token `k` positions ahead an identifier with exactly this text? */
    internal fun identAt(k: Int, s: String): Boolean {
        val i = p + k
        if (i >= stoks.size) return false
        val t = stoks[i]
        if (t.kind != Tk.IDENT || t.end - t.start != s.length) return false
        var j = 0
        while (j < s.length) {
            if (text[t.start + j] != s[j]) return false
            j++
        }
        return true
    }

    internal fun isIdent(s: String): Boolean = identAt(0, s)

    /** true if token k+1 starts exactly where token k ends (no whitespace or comment between) */
    internal fun adjacent(k: Int): Boolean = tokEndAt(k) == tokStartAt(k + 1)

    internal fun nl(): Boolean = nlBefore[p]

    private fun setCurrent() {
        val t = stoks[p]
        cur = t.kind
        curStart = t.start
    }

    internal fun at(k: Tk): Boolean = kind() == k

    // ---- CST building ---------------------------------------------------------------------------------------------

    internal fun advance() {
        if (cur == Tk.EOF) return
        val ri = sig[p]
        while (rawPos < ri) {
            out.add(CstLeaf(rawPos))
            rawPos++
        }
        out.add(CstLeaf(ri))
        rawPos = ri + 1
        lastEnd = stoks[p].end
        lastSigRaw = ri
        p++
        setCurrent()
    }

    internal fun mark(): Int = out.size

    internal fun done(m: Int, kind: NodeKind) {
        val kids = ArrayList<Cst>()
        var i = m
        while (i < out.size) {
            kids.add(out[i])
            i++
        }
        while (out.size > m) out.removeAt(out.size - 1)
        out.add(CstNode(kind, kids))
    }

    internal fun snap(): Snap = Snap(p, rawPos, out.size, errors.size, lastEnd, nlOn)

    internal fun restore(s: Snap) {
        p = s.p
        setCurrent()
        rawPos = s.rawPos
        lastEnd = s.lastEnd
        nlOn = s.nlOn
        while (out.size > s.outSize) out.removeAt(out.size - 1)
        while (errors.size > s.errSize) errors.removeAt(errors.size - 1)
    }

    /** records the source range [s, lastEnd) in the AST node */
    internal fun <T : Ast> fin(node: T, s: Int): T {
        node.start = s
        node.end = if (lastEnd < s) s else lastEnd
        return node
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    internal fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    internal fun errorAt(offset: Int, msg: String) {
        if (errors.isNotEmpty() && errors[errors.size - 1].offset == offset) return
        val l = lineOf(offset)
        errors.add(SyntaxError(offset, l + 1, offset - lineStarts[l] + 1, msg))
    }

    /** an error at the current token */
    internal fun error(msg: String) = errorAt(tokStart(), msg)

    /** an "expecting" error: reported directly after the previous token, like the reference parser */
    internal fun errorPrev(msg: String) = errorAt(if (lastSigRaw < 0) tokStart() else lastEnd, msg)

    internal fun expect(k: Tk, what: String): Boolean {
        if (kind() == k) {
            advance()
            return true
        }
        errorPrev("Expecting $what")
        return false
    }

    /** wraps the current token as an error node and moves on; guarantees progress */
    internal fun skipError(msg: String) {
        error(msg)
        val m = mark()
        advance()
        done(m, NodeKind.ERROR)
    }

    // ---- newline sensitivity --------------------------------------------------------------------------------------

    /** `?.` and `?:` are two adjacent tokens */
    internal fun atSafeDot(): Boolean = kind() == Tk.QUEST && la(1) == Tk.DOT && adjacent(0)

    internal fun atElvis(): Boolean = kind() == Tk.QUEST && la(1) == Tk.COLON && adjacent(0)

    internal fun atGtEq(): Boolean = kind() == Tk.GT && la(1) == Tk.EQ && adjacent(0)

    /** a newline before the current token ends the expression, except before these operators */
    internal fun interrupted(): Boolean {
        if (!nlOn || !nl()) return false
        val k = kind()
        if (k == Tk.DOT || k == Tk.ANDAND || k == Tk.OROR || k == Tk.AS) return false
        if (k == Tk.QUEST && (atSafeDot() || atElvis())) return false
        return true
    }

    // ---- file -----------------------------------------------------------------------------------------------------

    fun parseFile(): ParseResult {
        val m = mark()
        val s = tokStart()
        val fileAnns = ArrayList<AnnotationEntry>()
        var pkg: String? = null
        val imports = ArrayList<ImportDirective>()
        val decls = ArrayList<Decl>()

        // file annotations: @file:Foo, @file:[A B]
        run {
            val am = mark()
            var any = false
            while (kind() == Tk.AT && isIdentFileTarget()) {
                parseAnnotationInto(fileAnns, false)
                any = true
            }
            if (any) done(am, NodeKind.FILE_ANNOTATIONS)
        }

        // package directive (the reference parser also accepts modifiers in front of it)
        if (kind() == Tk.PACKAGE || (kind() == Tk.IDENT && isModifierWord(tokText()) && la(1) == Tk.PACKAGE)) {
            val pm = mark()
            if (kind() == Tk.IDENT) {
                val mm = mark()
                advance()
                done(mm, NodeKind.MODIFIERS)
            }
            advance()
            pkg = parseQualifiedName("package name")
            if (kind() == Tk.SEMI) advance()
            done(pm, NodeKind.PACKAGE)
        }
        while (isIdent("import")) {
            imports.add(parseImport())
        }

        while (kind() != Tk.EOF) {
            if (kind() == Tk.SEMI) {
                advance()
                continue
            }
            val before = p
            if (isIdent("import")) {
                // imports after declarations are an error in Kotlin, but parse them anyway
                error("imports are only allowed in the beginning of the file")
                imports.add(parseImport())
                continue
            }
            val d = tryParseDeclaration(CTX_TOP)
            if (d != null) {
                decls.add(d)
            } else if (p == before) {
                if (kind() == Tk.RBRACE) skipError("Unmatched closing brace") else skipError("Expecting a top level declaration")
                skipToDeclarationStart()
            }
        }
        // trailing trivia and the EOF token
        val ri = sig[p]
        while (rawPos <= ri) {
            out.add(CstLeaf(rawPos))
            rawPos++
        }
        done(m, NodeKind.FILE)
        val file = KtFile(fileAnns, pkg, imports, decls)
        file.start = s
        file.end = lastEnd
        return ParseResult(text, toks, out[0] as CstNode, file, errors)
    }

    private fun isIdentFileTarget(): Boolean {
        if (la(1) == Tk.LBRACKET) return false
        return identAt(1, "file") && la(2) == Tk.COLON
    }

    /** error recovery at top level / class level: skip to a token that can start a declaration on a new line */
    private fun skipToDeclarationStart() {
        while (kind() != Tk.EOF) {
            if (nl() && startsDeclaration()) return
            if (kind() == Tk.RBRACE && nl()) return
            val m = mark()
            advance()
            done(m, NodeKind.ERROR)
        }
    }

    private fun startsDeclaration(): Boolean {
        val k = kind()
        if (k == Tk.VAL || k == Tk.VAR || k == Tk.FUN || k == Tk.CLASS || k == Tk.INTERFACE || k == Tk.OBJECT || k == Tk.TYPEALIAS || k == Tk.AT) return true
        if (k == Tk.IDENT) {
            val t = tokText()
            return isModifierWord(t) || t == "constructor" || t == "init" || t == "import"
        }
        return false
    }

    internal fun parseQualifiedName(what: String): String {
        val sb = StringBuilder()
        if (kind() != Tk.IDENT) {
            errorPrev("Expecting $what")
            return ""
        }
        sb.append(tokText())
        advance()
        while (kind() == Tk.DOT && la(1) == Tk.IDENT) {
            advance()
            sb.append('.').append(tokText())
            advance()
        }
        return sb.toString()
    }

    private fun parseImport(): ImportDirective {
        val m = mark()
        val s = tokStart()
        advance() // import
        val sb = StringBuilder()
        var star = false
        var alias: String? = null
        if (kind() == Tk.IDENT) {
            sb.append(tokText())
            advance()
            while (kind() == Tk.DOT) {
                if (la(1) == Tk.IDENT) {
                    advance()
                    sb.append('.').append(tokText())
                    advance()
                } else if (la(1) == Tk.STAR) {
                    advance()
                    advance()
                    star = true
                    break
                } else {
                    break
                }
            }
            if (isIdent("as") || kind() == Tk.AS) {
                advance()
                if (kind() == Tk.IDENT) {
                    alias = tokText()
                    advance()
                } else {
                    errorPrev("Expecting identifier")
                }
            }
        } else {
            errorPrev("Expecting qualified name")
        }
        if (kind() == Tk.SEMI) advance()
        done(m, NodeKind.IMPORT)
        return fin(ImportDirective(sb.toString(), star, alias), s)
    }

    // ---- modifiers and annotations --------------------------------------------------------------------------------

    /** can an identifier modifier keyword at the current position be followed by what comes next? */
    private fun modifierContinues(): Boolean {
        val k = la(1)
        return k == Tk.IDENT || k == Tk.AT || k == Tk.VAL || k == Tk.VAR || k == Tk.FUN || k == Tk.CLASS ||
            k == Tk.INTERFACE || k == Tk.OBJECT || k == Tk.TYPEALIAS
    }

    internal fun atModifierKeyword(): Boolean {
        if (kind() == Tk.FUN && la(1) == Tk.INTERFACE) return true
        if (kind() != Tk.IDENT) return false
        val t = tokText()
        if (!isModifierWord(t)) return false
        return modifierContinues()
    }

    /** `context(` ... `)` directly followed by something that starts a declaration */
    private fun atContextClause(): Boolean {
        if (!isIdent("context") || la(1) != Tk.LPAR) return false
        var depth = 0
        var i = 1
        while (p + i < stoks.size) {
            val k = la(i)
            if (k == Tk.LPAR) depth++
            else if (k == Tk.RPAR) {
                depth--
                if (depth == 0) break
            } else if (k == Tk.EOF) return false
            i++
        }
        val n = la(i + 1)
        if (n == Tk.VAL || n == Tk.VAR || n == Tk.FUN || n == Tk.CLASS || n == Tk.INTERFACE || n == Tk.OBJECT || n == Tk.AT) return true
        if (n == Tk.IDENT) {
            val t = textAt(i + 1)
            return isModifierWord(t) || t == "constructor"
        }
        return false
    }

    /** modifier list of a declaration: annotations, modifier keywords and context parameters; null when empty */
    internal fun parseModifierList(): Modifiers? {
        val m = mark()
        val s = tokStart()
        val items = ArrayList<ModItem>()
        var ctx: List<Param> = emptyList()
        while (true) {
            if (kind() == Tk.AT) {
                val anns = ArrayList<AnnotationEntry>()
                val before = p
                parseAnnotationInto(anns, false)
                for (a in anns) items.add(AnnotationMod(a))
                if (p == before) break
            } else if (atModifierKeyword()) {
                val ks = tokStart()
                val t = tokText()
                advance()
                items.add(fin(KeywordMod(t), ks))
            } else if (atContextClause()) {
                ctx = parseContextParams()
            } else {
                break
            }
        }
        if (items.isEmpty() && ctx.isEmpty()) return null
        done(m, NodeKind.MODIFIERS)
        return fin(Modifiers(items, ctx), s)
    }

    private fun parseContextParams(): List<Param> {
        val m = mark()
        advance() // context
        val saved = nlOn
        nlOn = false
        advance() // (
        val list = ArrayList<Param>()
        while (kind() != Tk.RPAR && kind() != Tk.EOF) {
            val before = p
            val pm = mark()
            val ps = tokStart()
            if (kind() == Tk.IDENT && la(1) == Tk.COLON) {
                val name = tokText()
                advance()
                advance()
                val t = parseType()
                done(pm, NodeKind.PARAM)
                list.add(fin(Param(null, null, name, t, null), ps))
            } else if (kind() == Tk.IDENT && la(1) == Tk.AT && adjacent(0)) {
                // `context(label@ Type)`
                advance()
                advance()
                val t = parseType()
                done(pm, NodeKind.PARAM)
                list.add(fin(Param(null, null, "_", t, null), ps))
            } else {
                val t = parseType()
                done(pm, NodeKind.PARAM)
                list.add(fin(Param(null, null, "_", t, null), ps))
            }
            if (kind() == Tk.COMMA) advance() else break
            if (p == before) break
        }
        expect(Tk.RPAR, "')'")
        nlOn = saved
        done(m, NodeKind.CONTEXT_PARAMS)
        return list
    }

    private fun isUseSiteTarget(): Boolean {
        if (kind() != Tk.IDENT || la(1) != Tk.COLON) return false
        return when (tokText()) {
            "file", "field", "get", "set", "property", "receiver", "param", "setparam", "delegate" -> true
            else -> false
        }
    }

    /**
     * At `@`: parses one annotation (`@Foo(args)`, `@field:Foo`) or an annotation group (`@[A B]`) and appends the
     * entries. [typeMode]: a `(` after whitespace starts a function type, not an argument list.
     */
    internal fun parseAnnotationInto(into: ArrayList<AnnotationEntry>, typeMode: Boolean) {
        val m = mark()
        val s = tokStart()
        advance() // @
        if (kind() == Tk.LBRACKET) {
            advance()
            while (kind() == Tk.IDENT) {
                val before = p
                val em = mark()
                val es = tokStart()
                val e = parseAnnotationBody(null, false, es)
                done(em, NodeKind.ANNOTATION)
                into.add(e)
                if (p == before) break
            }
            expect(Tk.RBRACKET, "']'")
            done(m, NodeKind.ANNOTATION_GROUP)
            return
        }
        var target: String? = null
        if (isUseSiteTarget()) {
            target = tokText()
            advance()
            advance()
            if (kind() == Tk.LBRACKET) {
                // @file:[A B]
                advance()
                while (kind() == Tk.IDENT) {
                    val before = p
                    val em = mark()
                    val es = tokStart()
                    val e = parseAnnotationBody(target, false, es)
                    done(em, NodeKind.ANNOTATION)
                    into.add(e)
                    if (p == before) break
                }
                expect(Tk.RBRACKET, "']'")
                done(m, NodeKind.ANNOTATION_GROUP)
                return
            }
        }
        if (kind() != Tk.IDENT) {
            errorPrev("Expecting annotation name")
            done(m, NodeKind.ANNOTATION)
            return
        }
        val e = parseAnnotationBody(target, typeMode, s)
        done(m, NodeKind.ANNOTATION)
        into.add(e)
    }

    private fun parseAnnotationBody(target: String?, typeMode: Boolean, s: Int): AnnotationEntry {
        val t = parseUserType(false, true)
        var args: List<Arg>? = null
        if (kind() == Tk.LPAR) {
            val ws = !adjacentPrevToCurrent()
            if (!(typeMode && ws)) args = parseArgs()
        }
        return fin(AnnotationEntry(target, t, args), s)
    }

    internal fun prevIsSemi(): Boolean = lastSigRaw >= 0 && toks[lastSigRaw].kind == Tk.SEMI

    /** no whitespace or comment between the previous significant token and the current one */
    internal fun adjacentPrevToCurrent(): Boolean = lastSigRaw >= 0 && lastEnd == tokStart()

    // ---- declarations ---------------------------------------------------------------------------------------------

    /**
     * Tries to parse a declaration at the current position (modifiers included). Returns null without consuming
     * anything when the tokens do not start a declaration (in [CTX_LOCAL] that means: parse an expression).
     */
    internal fun tryParseDeclaration(ctx: Int): Decl? {
        if (depth >= MAX_DEPTH) return null
        depth++
        val d = tryParseDeclarationInner(ctx)
        depth--
        return d
    }

    private fun tryParseDeclarationInner(ctx: Int): Decl? {
        val k0 = kind()
        if (ctx == CTX_LOCAL && (k0 == Tk.LPAR || k0 == Tk.LBRACKET) && (la(1) == Tk.VAL || la(1) == Tk.VAR)) {
            return parseShortDestructuring()
        }
        if (k0 != Tk.VAL && k0 != Tk.VAR && k0 != Tk.FUN && k0 != Tk.CLASS && k0 != Tk.INTERFACE && k0 != Tk.OBJECT &&
            k0 != Tk.TYPEALIAS && k0 != Tk.AT && k0 != Tk.IDENT
        ) return null
        if (k0 == Tk.IDENT) {
            val t = tokText()
            val plausible = isModifierWord(t) || t == "context" || (ctx == CTX_CLASS && (t == "init" || t == "constructor"))
            if (!plausible) return null
        }
        val sn = snap()
        val m = mark()
        val s = tokStart()
        val mods = parseModifierList()
        val k = kind()
        var result: Decl? = null
        when (k) {
            Tk.VAL, Tk.VAR -> result = parseProperty(m, s, mods, ctx)
            Tk.FUN -> result = parseFunction(m, s, mods, ctx)
            Tk.CLASS, Tk.INTERFACE -> result = parseClassLike(m, s, mods, false)
            Tk.OBJECT -> {
                if (ctx != CTX_LOCAL || la(1) == Tk.IDENT) result = parseClassLike(m, s, mods, true)
                else if (mods != null && mods.has("companion")) result = parseClassLike(m, s, mods, true)
            }
            Tk.TYPEALIAS -> result = parseTypeAlias(m, s, mods)
            Tk.IDENT -> {
                if (ctx == CTX_CLASS && isIdent("init") && la(1) == Tk.LBRACE) result = parseInit(m, s, mods)
                else if (ctx == CTX_CLASS && isIdent("constructor") && la(1) == Tk.LPAR) result = parseSecondaryCtor(m, s, mods)
            }
            else -> {}
        }
        if (result != null) return result
        if (ctx == CTX_LOCAL || mods == null) {
            restore(sn)
            return null
        }
        // modifiers without a declaration in a place where only declarations are allowed
        errorPrev(if (ctx == CTX_TOP) "Expecting a top level declaration" else "Expecting member declaration")
        done(m, NodeKind.ERROR)
        return null
    }

    /** `(val a, val b) = x` and `[val a, val b] = x` (statement level) */
    private fun parseShortDestructuring(): Decl {
        val m = mark()
        val s = tokStart()
        val bracketed = kind() == Tk.LBRACKET
        val entries = parseDestructEntries()
        var init: Expr? = null
        if (kind() == Tk.EQ) {
            advance()
            init = parseExpression()
        } else {
            errorPrev("Expecting '='")
        }
        if (kind() == Tk.SEMI) advance()
        done(m, NodeKind.DESTRUCTURING)
        return fin(DestructuringDecl(null, false, entries, init, bracketed), s)
    }

    private fun parseTypeAlias(m: Int, s: Int, mods: Modifiers?): Decl {
        advance() // typealias
        val name = parseName("type alias name")
        var tps: List<TypeParam> = emptyList()
        if (kind() == Tk.LT) tps = parseTypeParams()
        var t: TypeRef? = null
        if (kind() == Tk.EQ) {
            advance()
            t = parseType()
        } else {
            errorPrev("Expecting '='")
        }
        if (kind() == Tk.SEMI) advance()
        done(m, NodeKind.TYPEALIAS)
        return fin(TypeAliasDecl(mods, name, tps, t), s)
    }

    internal fun parseName(what: String): String {
        if (kind() == Tk.IDENT) {
            val t = tokText()
            advance()
            return t
        }
        errorPrev("Expecting $what")
        return "<missing>"
    }

    private fun parseInit(m: Int, s: Int, mods: Modifiers?): Decl {
        advance() // init
        val b = parseBlock()
        done(m, NodeKind.INIT)
        return fin(InitDecl(mods, b), s)
    }

    private fun parseSecondaryCtor(m: Int, s: Int, mods: Modifiers?): Decl {
        advance() // constructor
        val params = parseParams(true)
        var deleg: CtorDelegation? = null
        if (kind() == Tk.COLON) {
            val dm = mark()
            val ds = tokStart()
            advance()
            if (kind() == Tk.THIS || kind() == Tk.SUPER) {
                val isThis = kind() == Tk.THIS
                advance()
                var args: List<Arg> = emptyList()
                if (kind() == Tk.LPAR) args = parseArgs() else errorPrev("Expecting '('")
                done(dm, NodeKind.CTOR_DELEGATION)
                deleg = fin(CtorDelegation(isThis, args), ds)
            } else {
                errorPrev("Expecting 'this' or 'super'")
                done(dm, NodeKind.CTOR_DELEGATION)
            }
        }
        var body: BlockExpr? = null
        if (kind() == Tk.LBRACE) body = parseBlock()
        done(m, NodeKind.SECONDARY_CTOR)
        return fin(SecondaryCtor(mods, params, deleg, body), s)
    }

    // ---- type parameters, parameters, constraints -----------------------------------------------------------------

    internal fun parseTypeParams(): List<TypeParam> {
        val m = mark()
        val saved = nlOn
        nlOn = false
        advance() // <
        val list = ArrayList<TypeParam>()
        while (kind() != Tk.GT && kind() != Tk.EOF) {
            val before = p
            val pm = mark()
            val ps = tokStart()
            // modifiers: annotations, in/out/reified
            val mm = mark()
            val ms = tokStart()
            val items = ArrayList<ModItem>()
            while (true) {
                if (kind() == Tk.AT) {
                    val anns = ArrayList<AnnotationEntry>()
                    val b2 = p
                    parseAnnotationInto(anns, false)
                    for (a in anns) items.add(AnnotationMod(a))
                    if (p == b2) break
                } else if (kind() == Tk.IN) {
                    val ks = tokStart()
                    advance()
                    items.add(fin(KeywordMod("in"), ks))
                } else if (kind() == Tk.IDENT && la(1) == Tk.IDENT && isModifierWord(tokText())) {
                    val ks = tokStart()
                    val t = tokText()
                    advance()
                    items.add(fin(KeywordMod(t), ks))
                } else {
                    break
                }
            }
            var mods: Modifiers? = null
            if (items.isNotEmpty()) {
                done(mm, NodeKind.MODIFIERS)
                mods = fin(Modifiers(items, emptyList()), ms)
            }
            val name = parseName("type parameter name")
            var bound: TypeRef? = null
            if (kind() == Tk.COLON) {
                advance()
                bound = parseType()
            }
            done(pm, NodeKind.TYPE_PARAM)
            list.add(fin(TypeParam(mods, name, bound), ps))
            if (kind() == Tk.COMMA) advance() else break
            if (p == before) break
        }
        expect(Tk.GT, "'>'")
        nlOn = saved
        done(m, NodeKind.TYPE_PARAMS)
        return list
    }

    internal fun parseConstraints(): List<TypeConstraint> {
        if (!isIdent("where")) return emptyList()
        val m = mark()
        advance()
        val list = ArrayList<TypeConstraint>()
        while (kind() == Tk.IDENT) {
            val cm = mark()
            val cs = tokStart()
            val name = tokText()
            advance()
            var b: TypeRef = ErrorType()
            if (kind() == Tk.COLON) {
                advance()
                b = parseType()
            } else {
                errorPrev("Expecting ':'")
            }
            done(cm, NodeKind.CONSTRAINT)
            list.add(fin(TypeConstraint(name, b), cs))
            if (kind() == Tk.COMMA) advance() else break
        }
        done(m, NodeKind.CONSTRAINTS)
        return list
    }

    /** `( param, ... )`; [full]: parameters may carry `val`/`var` and default values (constructors, functions) */
    internal fun parseParams(full: Boolean): List<Param> {
        val m = mark()
        val list = ArrayList<Param>()
        if (kind() != Tk.LPAR) {
            errorPrev("Expecting '('")
            done(m, NodeKind.PARAMS)
            return list
        }
        val saved = nlOn
        nlOn = false
        advance()
        while (kind() != Tk.RPAR && kind() != Tk.EOF) {
            val before = p
            list.add(parseParam(full))
            if (kind() == Tk.COMMA) {
                advance()
            } else if (kind() != Tk.RPAR) {
                break
            }
            if (p == before) break
        }
        if (!expect(Tk.RPAR, "')'")) {
            // recovery: skip to the closing parenthesis on the same logical line
            while (kind() != Tk.RPAR && kind() != Tk.LBRACE && kind() != Tk.EOF && !nl()) {
                val em = mark()
                advance()
                done(em, NodeKind.ERROR)
            }
            if (kind() == Tk.RPAR) advance()
        }
        nlOn = saved
        done(m, NodeKind.PARAMS)
        return list
    }

    internal fun parseParam(full: Boolean): Param {
        val m = mark()
        val s = tokStart()
        val mods = parseModifierList()
        var vv: String? = null
        if (kind() == Tk.VAL || kind() == Tk.VAR) {
            vv = tokText()
            advance()
        }
        val name = parseName("parameter name")
        var t: TypeRef? = null
        if (kind() == Tk.COLON) {
            advance()
            t = parseType()
        }
        var def: Expr? = null
        if (kind() == Tk.EQ) {
            advance()
            def = parseExpression()
        }
        done(m, NodeKind.PARAM)
        return fin(Param(mods, vv, name, t, def), s)
    }

    // ---- functions ------------------------------------------------------------------------------------------------

    /** at `fun`; returns null (nothing consumed beyond the caller's snapshot) for anonymous functions in local context */
    private fun parseFunction(m: Int, s: Int, mods: Modifiers?, ctx: Int): Decl? {
        if (ctx == CTX_LOCAL && la(1) == Tk.LPAR) return null
        advance() // fun
        var tps: List<TypeParam> = emptyList()
        if (kind() == Tk.LT) tps = parseTypeParams()
        var receiver: TypeRef? = null
        if (!(kind() == Tk.IDENT && la(1) == Tk.LPAR)) {
            // try a receiver type
            val sn = snap()
            val t = parseType(true)
            if (kind() == Tk.DOT && la(1) == Tk.LPAR && ctx == CTX_LOCAL) {
                return null // anonymous function with receiver: an expression
            } else if (kind() == Tk.DOT && (la(1) == Tk.IDENT || la(1) == Tk.LPAR)) {
                advance()
                receiver = t
            } else {
                restore(sn)
                if (ctx == CTX_LOCAL && kind() != Tk.IDENT) return null
            }
        }
        // a nameless function parses (the reference compiler reports it later, during analysis)
        val name: String? = if (kind() == Tk.LPAR) null else parseName("function name")
        val params = parseParams(true)
        var ret: TypeRef? = null
        if (kind() == Tk.COLON) {
            advance()
            ret = parseType()
        }
        val cons = parseConstraints()
        var body: Expr? = null
        var exprBody = false
        if (kind() == Tk.LBRACE) {
            body = parseBlock()
        } else if (kind() == Tk.EQ) {
            advance()
            exprBody = true
            body = parseExpression()
        }
        if (kind() == Tk.SEMI) advance()
        done(m, NodeKind.FUN)
        return fin(FunDecl(mods, tps, receiver, name, params, ret, cons, body, exprBody), s)
    }

    // ---- properties -----------------------------------------------------------------------------------------------

    private fun parseProperty(m: Int, s: Int, mods: Modifiers?, ctx: Int): Decl {
        val isVar = kind() == Tk.VAR
        advance()
        var tps: List<TypeParam> = emptyList()
        if (kind() == Tk.LT) tps = parseTypeParams()
        if (kind() == Tk.LPAR || kind() == Tk.LBRACKET) {
            // destructuring declaration
            if (ctx != CTX_LOCAL) error("Destructuring declarations are only allowed for local variables/values")
            val bracketed = kind() == Tk.LBRACKET
            val entries = parseDestructEntries()
            var init: Expr? = null
            if (kind() == Tk.EQ) {
                advance()
                init = parseExpression()
            }
            if (kind() == Tk.SEMI) advance()
            done(m, NodeKind.DESTRUCTURING)
            return fin(DestructuringDecl(mods, isVar, entries, init, bracketed), s)
        }
        var receiver: TypeRef? = null
        val quick = kind() == Tk.IDENT && la(1) != Tk.DOT && la(1) != Tk.LT && la(1) != Tk.QUEST
        if (!quick) {
            val sn = snap()
            val t = parseType(true)
            if (kind() == Tk.DOT && la(1) == Tk.IDENT) {
                advance()
                receiver = t
            } else {
                restore(sn)
            }
        }
        val name = parseName("property name")
        var type: TypeRef? = null
        if (kind() == Tk.COLON) {
            advance()
            type = parseType()
        }
        val cons = parseConstraints()
        var init: Expr? = null
        var delegate: Expr? = null
        if (kind() == Tk.EQ) {
            advance()
            init = parseExpression()
        } else if (isIdent("by")) {
            advance()
            delegate = parseExpression()
        }
        var getter: AccessorDecl? = null
        var setter: AccessorDecl? = null
        var backing: BackingFieldDecl? = null
        if (ctx != CTX_LOCAL) {
            // accessors and the explicit backing field, possibly after a `;` and on following lines, in any order
            while (true) {
                val sn = snap()
                if (kind() == Tk.SEMI && accessorAheadAfterSemi()) advance()
                val bf = tryParseBackingField()
                if (bf != null) {
                    backing = bf
                    continue
                }
                val a = tryParseAccessor()
                if (a == null) {
                    restore(sn)
                    break
                }
                if (a.kind == "get") getter = a else setter = a
            }
        }
        if (kind() == Tk.SEMI) {
            advance()
        } else if (ctx != CTX_LOCAL && !nl() && kind() != Tk.RBRACE && kind() != Tk.EOF) {
            errorPrev("Property getter or setter expected")
        }
        done(m, NodeKind.PROPERTY)
        return fin(PropertyDecl(mods, isVar, tps, receiver, name, type, cons, init, delegate, getter, setter, backing), s)
    }

    private fun accessorAheadAfterSemi(): Boolean {
        var i = 1
        // skip modifiers of the accessor
        while (true) {
            val k = la(i)
            if (k == Tk.IDENT) {
                val t = textAt(i)
                if (t == "get" || t == "set") return true
                if (isModifierWord(t)) {
                    i++
                    continue
                }
                return false
            }
            if (k == Tk.AT) return true
            return false
        }
    }

    /** explicit backing field: `field [: Type] [= init]` on its own (modifiers allowed) */
    private fun tryParseBackingField(): BackingFieldDecl? {
        var i = 0
        while (la(i) == Tk.IDENT && isModifierWord(textAt(i))) i++
        if (!(la(i) == Tk.IDENT && textAt(i) == "field" && (la(i + 1) == Tk.EQ || la(i + 1) == Tk.COLON))) return null
        val m = mark()
        val s = tokStart()
        val mods = parseModifierList()
        if (!isIdent("field")) return null
        advance()
        var t: TypeRef? = null
        if (kind() == Tk.COLON) {
            advance()
            t = parseType()
        }
        var init: Expr? = null
        if (kind() == Tk.EQ) {
            advance()
            init = parseExpression()
        }
        done(m, NodeKind.BACKING_FIELD)
        return fin(BackingFieldDecl(mods, t, init), s)
    }

    private fun tryParseAccessor(): AccessorDecl? {
        // quick reject
        var i = 0
        while (la(i) == Tk.IDENT && isModifierWord(textAt(i))) i++
        if (la(i) == Tk.AT) {
            // annotated accessor: let the modifier parser decide
        } else if (!(la(i) == Tk.IDENT && (textAt(i) == "get" || textAt(i) == "set"))) {
            return null
        }
        val m = mark()
        val s = tokStart()
        val mods = parseModifierList()
        if (!(isIdent("get") || isIdent("set"))) return null
        val kindText = tokText()
        advance()
        var param: Param? = null
        var ret: TypeRef? = null
        var body: Expr? = null
        var exprBody = false
        if (kind() == Tk.LPAR) {
            val saved = nlOn
            nlOn = false
            advance()
            if (kind() != Tk.RPAR) param = parseParam(false)
            if (kind() == Tk.COMMA) advance()
            expect(Tk.RPAR, "')'")
            nlOn = saved
            if (kind() == Tk.COLON) {
                advance()
                ret = parseType()
            }
            if (kind() == Tk.LBRACE) {
                body = parseBlock()
            } else if (kind() == Tk.EQ) {
                advance()
                exprBody = true
                body = parseExpression()
            }
        }
        done(m, NodeKind.ACCESSOR)
        return fin(AccessorDecl(mods, kindText, param, ret, body, exprBody), s)
    }

    /** `( entries )` or `[ entries ]` of a destructuring declaration, lambda parameter or loop variable */
    internal fun parseDestructEntries(): List<DestructEntry> {
        val m = mark()
        val saved = nlOn
        nlOn = false
        val close = if (kind() == Tk.LBRACKET) Tk.RBRACKET else Tk.RPAR
        advance() // ( or [
        val list = ArrayList<DestructEntry>()
        while (kind() != close && kind() != Tk.EOF) {
            val before = p
            val em = mark()
            val es = tokStart()
            val mods = parseModifierList()
            var isVar = false
            if (kind() == Tk.VAL || kind() == Tk.VAR) {
                isVar = kind() == Tk.VAR
                advance()
            }
            val name = parseName("name")
            var t: TypeRef? = null
            if (kind() == Tk.COLON) {
                advance()
                t = parseType()
            }
            var source: String? = null
            if (kind() == Tk.EQ && la(1) == Tk.IDENT) {
                advance()
                source = tokText()
                advance()
            }
            done(em, NodeKind.DESTRUCT_ENTRY)
            list.add(fin(DestructEntry(mods, isVar, name, t, source), es))
            if (kind() == Tk.COMMA) advance() else break
            if (p == before) break
        }
        expect(close, if (close == Tk.RBRACKET) "']'" else "')'")
        nlOn = saved
        done(m, NodeKind.DESTRUCTURING)
        return list
    }

    // ---- classes --------------------------------------------------------------------------------------------------

    private fun parseClassLike(m: Int, s: Int, mods: Modifiers?, isObject: Boolean): Decl {
        val kw = kind()
        advance() // class | interface | object
        val kindEnum = if (isObject) ClassKind.OBJECT else if (kw == Tk.INTERFACE) ClassKind.INTERFACE else ClassKind.CLASS
        var name: String? = null
        if (kind() == Tk.IDENT) {
            name = tokText()
            advance()
        } else if (!isObject || mods == null || !mods.has("companion")) {
            errorPrev("Expecting name")
        }
        var tps: List<TypeParam> = emptyList()
        if (!isObject && kind() == Tk.LT) tps = parseTypeParams()
        var ctor: PrimaryCtor? = null
        if (!isObject) {
            val sn = snap()
            val cm = mark()
            val cs = tokStart()
            var cmods: Modifiers? = null
            var noKeyword = false
            if (kind() == Tk.IDENT && isModifierWord(tokText()) && la(1) == Tk.LPAR) {
                noKeyword = true
                // `class A private (x: Int)`: the reference parser accepts a constructor modifier without the keyword
                val mm = mark()
                val ms = tokStart()
                val kw = tokText()
                advance()
                val item = fin(KeywordMod(kw), ms)
                done(mm, NodeKind.MODIFIERS)
                cmods = fin(Modifiers(listOf(item), emptyList()), ms)
            } else if (kind() == Tk.AT || atModifierKeyword()) {
                cmods = parseModifierList()
            }
            if (isIdent("constructor") && la(1) == Tk.LPAR) {
                advance()
                val ps = parseParams(true)
                done(cm, NodeKind.PRIMARY_CTOR)
                ctor = fin(PrimaryCtor(cmods, true, ps), cs)
            } else if ((cmods == null || noKeyword) && kind() == Tk.LPAR) {
                val ps = parseParams(true)
                done(cm, NodeKind.PRIMARY_CTOR)
                ctor = fin(PrimaryCtor(cmods, false, ps), cs)
            } else {
                restore(sn)
            }
        }
        var supers: List<SuperEntry> = emptyList()
        if (kind() == Tk.COLON) {
            advance()
            supers = parseSuperEntries()
        }
        val cons = parseConstraints()
        val isEnum = mods != null && mods.has("enum")
        var entries: List<EnumEntryDecl> = emptyList()
        var members: List<Decl> = emptyList()
        var hasBody = false
        if (kind() == Tk.LBRACE) {
            hasBody = true
            val bm = mark()
            val saved = nlOn
            nlOn = true
            advance()
            val ms = ArrayList<Decl>()
            if (isEnum) entries = parseEnumEntries()
            parseMembers(ms)
            expect(Tk.RBRACE, "'}'")
            nlOn = saved
            done(bm, NodeKind.CLASS_BODY)
            members = ms
        }
        done(m, if (isObject) NodeKind.OBJECT else NodeKind.CLASS)
        return fin(ClassDecl(mods, kindEnum, name, tps, ctor, supers, cons, entries, members, hasBody), s)
    }

    internal fun parseSuperEntries(): List<SuperEntry> {
        val m = mark()
        val list = ArrayList<SuperEntry>()
        while (true) {
            val before = p
            val em = mark()
            val es = tokStart()
            val t = parseType()
            if (kind() == Tk.LPAR) {
                val args = parseArgs()
                done(em, NodeKind.SUPER_CALL)
                list.add(fin(SuperCall(t, args), es))
            } else if (isIdent("by")) {
                advance()
                val savedNoLambda = noLambda
                noLambda = true
                val e = parseExpression()
                noLambda = savedNoLambda
                done(em, NodeKind.SUPER_DELEGATE)
                list.add(fin(SuperDelegate(t, e), es))
            } else {
                done(em, NodeKind.SUPER_TYPE)
                list.add(fin(SuperType(t), es))
            }
            if (kind() == Tk.COMMA) advance() else break
            if (p == before) break
        }
        done(m, NodeKind.SUPER_LIST)
        return list
    }

    private fun parseEnumEntries(): List<EnumEntryDecl> {
        val list = ArrayList<EnumEntryDecl>()
        while (true) {
            if (kind() == Tk.SEMI) {
                advance()
                break
            }
            if (kind() == Tk.RBRACE || kind() == Tk.EOF) break
            // an entry starts with annotations and an identifier that is not a declaration modifier
            val isEntry = if (kind() == Tk.AT) true else atEnumEntryStart()
            if (!isEntry) break
            val before = p
            val m = mark()
            val s = tokStart()
            val mods = parseModifierList()
            val name = parseName("enum entry name")
            var args: List<Arg>? = null
            if (kind() == Tk.LPAR) args = parseArgs()
            var body: List<Decl>? = null
            if (kind() == Tk.LBRACE) {
                val bm = mark()
                advance()
                val ms = ArrayList<Decl>()
                parseMembers(ms)
                expect(Tk.RBRACE, "'}'")
                done(bm, NodeKind.CLASS_BODY)
                body = ms
            }
            done(m, NodeKind.ENUM_ENTRY)
            list.add(fin(EnumEntryDecl(mods, name, args, body), s))
            if (kind() == Tk.COMMA) {
                advance()
            } else {
                if (kind() == Tk.SEMI) advance()
                break
            }
            if (p == before) break
        }
        return list
    }

    /** `[modifiers] Name` followed by something that ends or continues an entry (not a member declaration) */
    private fun atEnumEntryStart(): Boolean {
        var j = 0
        while (la(j) == Tk.IDENT && isModifierWord(textAt(j)) && la(j + 1) == Tk.IDENT) j++
        if (la(j) != Tk.IDENT) return false
        if (j == 0) return !isIdent("init") && !isIdent("constructor") && !isIdent("context") && !atModifierKeyword()
        val n = la(j + 1)
        return n == Tk.COMMA || n == Tk.SEMI || n == Tk.LPAR || n == Tk.LBRACE || n == Tk.RBRACE || n == Tk.EOF
    }

    /** members of a class body up to (excluding) the closing brace */
    internal fun parseMembers(into: ArrayList<Decl>) {
        while (kind() != Tk.RBRACE && kind() != Tk.EOF) {
            if (kind() == Tk.SEMI) {
                advance()
                continue
            }
            val before = p
            val d = tryParseDeclaration(CTX_CLASS)
            if (d != null) {
                into.add(d)
            } else if (p == before) {
                skipError("Expecting member declaration")
                // skip the rest of the broken member up to a plausible start
                while (kind() != Tk.EOF && kind() != Tk.RBRACE && !(nl() && startsDeclaration())) {
                    val m = mark()
                    advance()
                    done(m, NodeKind.ERROR)
                }
            }
        }
    }
}
