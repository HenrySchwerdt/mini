package mini.frontend

/*
 * A compact text encoding of parsed files ([KtFile] trees), for the persistent stub cache (docs/mlib.md, "Stub cache"): the
 * semantic analysis of a program needs the declarations of the standard library, and parsing the stubs of 130 files is the
 * largest single cost of a `minic check`. The encoding is a plain string (so it goes through the ordinary text file I/O):
 * a string table, then the trees. Integers are written in 5-bit groups as printable ASCII, strings are referred to by their
 * table index, so equal names are shared. Decoding yields trees equal to what the parser built (same classes, same
 * offsets); [AstCodec.encode] of a decoded tree is the original encoding, which is how the cache is verified.
 */
object AstCodec {
    /** Bumped with every change of the AST classes or of the encoding: it is part of the cache key. */
    const val VERSION = 1

    fun encode(files: List<KtFile>): String {
        val w = AstWriter()
        w.int(files.size)
        for (f in files) w.node(f)
        return w.finish()
    }

    /** The files of [text]; null when it is not a complete valid encoding (a damaged or truncated cache file). */
    fun decode(text: String, from: Int): List<KtFile>? {
        try {
            val r = AstReader(text, from)
            val n = r.int()
            val out = ArrayList<KtFile>()
            for (i in 0 until n) {
                val f = r.node()
                if (f !is KtFile) return null
                out.add(f)
            }
            if (!r.atEnd()) return null
            return out
        } catch (e: Exception) {
            return null
        }
    }
}

private class AstWriter {
    private val body = StringBuilder()
    private val table = HashMap<String, Int>()
    private val strings = ArrayList<String>()

    fun int(v: Int) = putInt(body, v)

    fun bool(b: Boolean) {
        body.append(if (b) 'T' else 'F')
    }

    fun str(s: String?) {
        if (s == null) {
            int(0)
            return
        }
        var id = table[s]
        if (id == null) {
            id = strings.size
            table[s] = id
            strings.add(s)
        }
        int(id + 1)
    }

    fun finish(): String {
        val sb = StringBuilder()
        putInt(sb, strings.size)
        for (s in strings) {
            putInt(sb, s.length)
            sb.append(s)
        }
        sb.append(body)
        sb.append('$')
        return sb.toString()
    }

    private fun list(l: List<Ast>?) {
        if (l == null) {
            int(0)
            return
        }
        int(l.size + 1)
        for (n in l) node(n)
    }

    private fun strList(l: List<String>) {
        int(l.size)
        for (s in l) str(s)
    }

    fun node(n: Ast?) {
        if (n == null) {
            int(0)
            return
        }
        when (n) {
            is KtFile -> { head(1, n); list(n.fileAnnotations); str(n.packageName); list(n.imports); list(n.declarations) }
            is ImportDirective -> { head(2, n); str(n.path); bool(n.star); str(n.alias) }
            is KeywordMod -> { head(3, n); str(n.text) }
            is AnnotationMod -> { head(4, n); node(n.annotation) }
            is Modifiers -> { head(5, n); list(n.items); list(n.contextParams) }
            is AnnotationEntry -> { head(6, n); str(n.target); node(n.type); list(n.args) }
            is Arg -> { head(7, n); str(n.name); bool(n.spread); node(n.value) }
            is TypeArg -> { head(8, n); bool(n.star); str(n.variance); node(n.type) }
            is TypeSegment -> { head(9, n); str(n.name); list(n.args) }
            is UserType -> { head(10, n); list(n.segments); node(n.mods) }
            is NullableType -> { head(11, n); node(n.inner); node(n.mods) }
            is ParenType -> { head(12, n); node(n.inner); node(n.mods) }
            is FnTypeParam -> { head(13, n); str(n.name); node(n.type) }
            is FunctionType -> { head(14, n); list(n.contextTypes); node(n.receiver); list(n.params); node(n.returnType); node(n.mods) }
            is DynamicType -> { head(15, n); node(n.mods) }
            is IntersectionType -> { head(16, n); node(n.left); node(n.right); node(n.mods) }
            is ErrorType -> { head(17, n); node(n.mods) }
            is TypeParam -> { head(18, n); node(n.mods); str(n.name); node(n.bound) }
            is TypeConstraint -> { head(19, n); str(n.name); node(n.bound) }
            is Param -> { head(20, n); node(n.mods); str(n.valVar); str(n.name); node(n.type); node(n.default) }
            is PrimaryCtor -> { head(21, n); node(n.mods); bool(n.hasKeyword); list(n.params) }
            is SuperType -> { head(22, n); node(n.type) }
            is SuperCall -> { head(23, n); node(n.type); list(n.args) }
            is SuperDelegate -> { head(24, n); node(n.type); node(n.delegate) }
            is ClassDecl -> {
                head(25, n); node(n.mods); int(n.kind.ordinal); str(n.name); list(n.typeParams); node(n.primaryCtor)
                list(n.supers); list(n.constraints); list(n.enumEntries); list(n.members); bool(n.hasBody)
            }
            is EnumEntryDecl -> { head(26, n); node(n.mods); str(n.name); list(n.args); list(n.members) }
            is FunDecl -> {
                head(27, n); node(n.mods); list(n.typeParams); node(n.receiver); str(n.name); list(n.params); node(n.returnType)
                list(n.constraints); node(n.body); bool(n.expressionBody)
            }
            is AccessorDecl -> { head(28, n); node(n.mods); str(n.kind); node(n.param); node(n.returnType); node(n.body); bool(n.expressionBody) }
            is PropertyDecl -> {
                head(29, n); node(n.mods); bool(n.isVar); list(n.typeParams); node(n.receiver); str(n.name); node(n.type)
                list(n.constraints); node(n.initializer); node(n.delegate); node(n.getter); node(n.setter); node(n.backingField)
            }
            is BackingFieldDecl -> { head(30, n); node(n.mods); node(n.type); node(n.initializer) }
            is DestructEntry -> { head(31, n); node(n.mods); bool(n.isVar); str(n.name); node(n.type); str(n.source) }
            is DestructuringDecl -> { head(32, n); node(n.mods); bool(n.isVar); list(n.entries); node(n.initializer); bool(n.bracketed) }
            is TypeAliasDecl -> { head(33, n); node(n.mods); str(n.name); list(n.typeParams); node(n.type) }
            is InitDecl -> { head(34, n); node(n.mods); node(n.body) }
            is CtorDelegation -> { head(35, n); bool(n.isThis); list(n.args) }
            is SecondaryCtor -> { head(36, n); node(n.mods); list(n.params); node(n.delegation); node(n.body) }
            is LiteralExpr -> { head(37, n); int(n.kind.ordinal); str(n.text) }
            is StrText -> { head(38, n); str(n.text) }
            is StrEscape -> { head(39, n); str(n.text) }
            is StrShortRef -> { head(40, n); str(n.name) }
            is StrLongExpr -> { head(41, n); node(n.expr) }
            is StringExpr -> { head(42, n); bool(n.raw); list(n.parts) }
            is NameExpr -> { head(43, n); str(n.name) }
            is ThisExpr -> { head(44, n); str(n.label) }
            is SuperExpr -> { head(45, n); node(n.typeArg); str(n.label) }
            is ParenExpr -> { head(46, n); node(n.inner) }
            is BinaryExpr -> { head(47, n); str(n.op); node(n.left); node(n.right) }
            is IsExpr -> { head(48, n); bool(n.negated); node(n.expr); node(n.type) }
            is AsExpr -> { head(49, n); bool(n.safe); node(n.expr); node(n.type) }
            is PrefixExpr -> { head(50, n); str(n.op); node(n.expr) }
            is PostfixExpr -> { head(51, n); str(n.op); node(n.expr) }
            is AnnotatedExpr -> { head(52, n); list(n.annotations); node(n.expr) }
            is LabeledExpr -> { head(53, n); str(n.label); node(n.expr) }
            is CallExpr -> { head(54, n); node(n.callee); list(n.typeArgs); list(n.args); list(n.lambdas) }
            is IndexExpr -> { head(55, n); node(n.receiver); list(n.indices) }
            is DotExpr -> { head(56, n); node(n.receiver); bool(n.safe); node(n.selector) }
            is CallableRefExpr -> { head(57, n); node(n.receiver); str(n.name); list(n.typeArgs) }
            is LambdaParam -> { head(58, n); node(n.mods); list(n.names); str(n.name); node(n.type) }
            is LambdaExpr -> { head(59, n); list(n.params); bool(n.hasArrow); list(n.body); str(n.explicitLabel) }
            is AnonFunExpr -> { head(60, n); node(n.fn) }
            is BlockExpr -> { head(61, n); list(n.stmts) }
            is IfExpr -> { head(62, n); node(n.cond); node(n.then); node(n.otherwise) }
            is WhenExprCond -> { head(63, n); node(n.expr) }
            is WhenInCond -> { head(64, n); bool(n.negated); node(n.expr) }
            is WhenIsCond -> { head(65, n); bool(n.negated); node(n.type) }
            is WhenEntry -> { head(66, n); list(n.conds); bool(n.isElse); node(n.guard); node(n.body) }
            is WhenSubject -> { head(67, n); str(n.valName); node(n.valType); node(n.expr) }
            is WhenExpr -> { head(68, n); node(n.subject); list(n.entries) }
            is CatchClause -> { head(69, n); node(n.param); node(n.body) }
            is TryExpr -> { head(70, n); node(n.body); list(n.catches); node(n.finallyBlock) }
            is ThrowExpr -> { head(71, n); node(n.expr) }
            is ReturnExpr -> { head(72, n); str(n.label); node(n.expr) }
            is BreakExpr -> { head(73, n); str(n.label) }
            is ContinueExpr -> { head(74, n); str(n.label) }
            is ForExpr -> { head(75, n); list(n.entries); bool(n.destructuring); node(n.iterable); node(n.body) }
            is WhileExpr -> { head(76, n); node(n.cond); node(n.body) }
            is DoWhileExpr -> { head(77, n); node(n.body); node(n.cond) }
            is ObjectLiteral -> { head(78, n); list(n.supers); list(n.members) }
            is CollectionLiteral -> { head(79, n); list(n.elements) }
            is ErrorExpr -> { head(80, n) }
        }
    }

    private fun head(tag: Int, n: Ast) {
        int(tag)
        int(n.start)
        int(n.end)
    }
}

private fun putInt(sb: StringBuilder, v: Int) {
    var x = (v shl 1) xor (v shr 31)   // zigzag: small magnitudes stay short
    while (true) {
        val low = x and 31
        x = x ushr 5
        if (x == 0) {
            sb.append((0x40 + low).toChar())
            return
        }
        sb.append((0x60 + low).toChar())
    }
}

private class AstReader(private val text: String, from: Int) {
    private val buf = text.toCharArray()
    private var pos = from
    private val strings = ArrayList<String>()

    init {
        val n = int()
        for (i in 0 until n) {
            val len = int()
            if (len < 0 || pos + len > text.length) throw IllegalStateException("bad string table")
            strings.add(text.substring(pos, pos + len))
            pos += len
        }
    }

    fun atEnd(): Boolean = pos == buf.size - 1 && buf[pos] == '$'

    fun int(): Int {
        var x = 0
        var shift = 0
        while (true) {
            val c = buf[pos++].code
            if (c >= 0x60) {
                x = x or ((c - 0x60) shl shift)
                shift += 5
            } else {
                x = x or ((c - 0x40) shl shift)
                break
            }
        }
        return (x ushr 1) xor -(x and 1)
    }

    private fun bool(): Boolean = buf[pos++] == 'T'

    private fun str(): String? {
        val id = int()
        if (id == 0) return null
        return strings[id - 1]
    }

    private fun s(): String = str()!!

    @Suppress("UNCHECKED_CAST")
    private fun <T> listN(): List<T>? {
        val n = int()
        if (n == 0) return null
        val out = ArrayList<T>(n - 1)
        for (i in 0 until n - 1) out.add(node() as T)
        return out
    }

    private fun <T> list(): List<T> = listN<T>()!!

    private fun mods(): Modifiers? = node() as Modifiers?
    private fun type(): TypeRef? = node() as TypeRef?
    private fun expr(): Expr? = node() as Expr?
    private fun block(): BlockExpr? = node() as BlockExpr?

    private fun <T : TypeRef> withMods(t: T): T {
        t.mods = mods()
        return t
    }

    fun node(): Ast? {
        val tag = int()
        if (tag == 0) return null
        val start = int()
        val end = int()
        val n: Ast = when (tag) {
            1 -> KtFile(list(), str(), list(), list())
            2 -> ImportDirective(s(), bool(), str())
            3 -> KeywordMod(s())
            4 -> AnnotationMod(node() as AnnotationEntry)
            5 -> Modifiers(list(), list())
            6 -> AnnotationEntry(str(), node() as TypeRef, listN())
            7 -> Arg(str(), bool(), node() as Expr)
            8 -> TypeArg(bool(), str(), type())
            9 -> TypeSegment(s(), list())
            10 -> withMods(UserType(list()))
            11 -> withMods(NullableType(node() as TypeRef))
            12 -> withMods(ParenType(node() as TypeRef))
            13 -> FnTypeParam(str(), node() as TypeRef)
            14 -> withMods(FunctionType(list(), type(), list(), node() as TypeRef))
            15 -> withMods(DynamicType())
            16 -> withMods(IntersectionType(node() as TypeRef, node() as TypeRef))
            17 -> withMods(ErrorType())
            18 -> TypeParam(mods(), s(), type())
            19 -> TypeConstraint(s(), node() as TypeRef)
            20 -> Param(mods(), str(), s(), type(), expr())
            21 -> PrimaryCtor(mods(), bool(), list())
            22 -> SuperType(node() as TypeRef)
            23 -> SuperCall(node() as TypeRef, list())
            24 -> SuperDelegate(node() as TypeRef, node() as Expr)
            25 -> {
                val m = mods()
                val kind = when (int()) { 0 -> ClassKind.CLASS; 1 -> ClassKind.INTERFACE; else -> ClassKind.OBJECT }
                ClassDecl(m, kind, str(), list(), node() as PrimaryCtor?, list(), list(), list(), list(), bool())
            }
            26 -> EnumEntryDecl(mods(), s(), listN(), listN())
            27 -> FunDecl(mods(), list(), type(), str(), list(), type(), list(), expr(), bool())
            28 -> AccessorDecl(mods(), s(), node() as Param?, type(), expr(), bool())
            29 -> PropertyDecl(
                mods(), bool(), list(), type(), s(), type(), list(), expr(), expr(),
                node() as AccessorDecl?, node() as AccessorDecl?, node() as BackingFieldDecl?,
            )
            30 -> BackingFieldDecl(mods(), type(), expr())
            31 -> DestructEntry(mods(), bool(), s(), type(), str())
            32 -> DestructuringDecl(mods(), bool(), list(), expr(), bool())
            33 -> TypeAliasDecl(mods(), s(), list(), type())
            34 -> InitDecl(mods(), block())
            35 -> CtorDelegation(bool(), list())
            36 -> SecondaryCtor(mods(), list(), node() as CtorDelegation?, block())
            37 -> {
                val k = when (int()) {
                    0 -> LitKind.INT; 1 -> LitKind.REAL; 2 -> LitKind.CHAR; 3 -> LitKind.TRUE; 4 -> LitKind.FALSE; else -> LitKind.NULL
                }
                LiteralExpr(k, s())
            }
            38 -> StrText(s())
            39 -> StrEscape(s())
            40 -> StrShortRef(s())
            41 -> StrLongExpr(node() as Expr)
            42 -> StringExpr(bool(), list())
            43 -> NameExpr(s())
            44 -> ThisExpr(str())
            45 -> SuperExpr(type(), str())
            46 -> ParenExpr(node() as Expr)
            47 -> BinaryExpr(s(), node() as Expr, node() as Expr)
            48 -> IsExpr(bool(), node() as Expr, node() as TypeRef)
            49 -> AsExpr(bool(), node() as Expr, node() as TypeRef)
            50 -> PrefixExpr(s(), node() as Expr)
            51 -> PostfixExpr(s(), node() as Expr)
            52 -> AnnotatedExpr(list(), node() as Expr)
            53 -> LabeledExpr(s(), node() as Expr)
            54 -> CallExpr(node() as Expr, list(), listN(), list())
            55 -> IndexExpr(node() as Expr, list())
            56 -> DotExpr(node() as Expr, bool(), node() as Expr)
            57 -> {
                val c = CallableRefExpr(expr(), s())
                c.typeArgs = list()
                c
            }
            58 -> LambdaParam(mods(), listN(), str(), type())
            59 -> {
                val l = LambdaExpr(list(), bool(), list())
                l.explicitLabel = str()
                l
            }
            60 -> AnonFunExpr(node() as FunDecl)
            61 -> BlockExpr(list())
            62 -> IfExpr(node() as Expr, expr(), expr())
            63 -> WhenExprCond(node() as Expr)
            64 -> WhenInCond(bool(), node() as Expr)
            65 -> WhenIsCond(bool(), node() as TypeRef)
            66 -> WhenEntry(list(), bool(), expr(), node() as Expr)
            67 -> WhenSubject(str(), type(), node() as Expr)
            68 -> WhenExpr(node() as WhenSubject?, list())
            69 -> CatchClause(node() as Param, node() as BlockExpr)
            70 -> TryExpr(node() as BlockExpr, list(), block())
            71 -> ThrowExpr(expr())
            72 -> ReturnExpr(str(), expr())
            73 -> BreakExpr(str())
            74 -> ContinueExpr(str())
            75 -> ForExpr(list(), bool(), node() as Expr, expr())
            76 -> WhileExpr(node() as Expr, expr())
            77 -> DoWhileExpr(expr(), node() as Expr)
            78 -> ObjectLiteral(list(), list())
            79 -> CollectionLiteral(list())
            80 -> ErrorExpr()
            else -> throw IllegalStateException("bad node tag " + tag)
        }
        n.start = start
        n.end = end
        return n
    }
}
