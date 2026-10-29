package mini.frontend

/** Result of lexing: every character of the source belongs to exactly one token (trivia included), ending in [Tk.EOF]. */
class LexResult(val text: String, val tokens: ArrayList<Token>, val errors: ArrayList<SyntaxError>) {
    fun text(t: Token): String = text.substring(t.start, t.end)
}

fun isIdentStart(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c.code > 127

fun isIdentPart(c: Char): Boolean = isIdentStart(c) || (c >= '0' && c <= '9')

private fun isDigit(c: Char): Boolean = c >= '0' && c <= '9'

private fun isHexDigit(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

fun keywordKind(s: String): Tk? = when (s) {
    "as" -> Tk.AS
    "break" -> Tk.BREAK
    "class" -> Tk.CLASS
    "continue" -> Tk.CONTINUE
    "do" -> Tk.DO
    "else" -> Tk.ELSE
    "false" -> Tk.FALSE
    "for" -> Tk.FOR
    "fun" -> Tk.FUN
    "if" -> Tk.IF
    "in" -> Tk.IN
    "interface" -> Tk.INTERFACE
    "is" -> Tk.IS
    "null" -> Tk.NULL
    "object" -> Tk.OBJECT
    "package" -> Tk.PACKAGE
    "return" -> Tk.RETURN
    "super" -> Tk.SUPER
    "this" -> Tk.THIS
    "throw" -> Tk.THROW
    "true" -> Tk.TRUE
    "try" -> Tk.TRY
    "typealias" -> Tk.TYPEALIAS
    "typeof" -> Tk.TYPEOF
    "val" -> Tk.VAL
    "var" -> Tk.VAR
    "when" -> Tk.WHEN
    "while" -> Tk.WHILE
    else -> null
}

/**
 * Hand-written lexer for Kotlin 2.x. It is a small state machine with a mode stack so that string templates nest
 * (`"a ${ "b ${c}" } d"`): mode 0 is code, 1 a regular string, 2 a raw string and 3 the code inside `${ }`.
 * Lexing never fails: malformed input produces [Tk.BAD] tokens or unterminated literals plus an entry in `errors`.
 */
class Lexer(private val src: String) {
    private val n = src.length
    private var pos = 0
    private val toks = ArrayList<Token>()
    private val errors = ArrayList<SyntaxError>()
    private var line = 1
    private var lineStart = 0

    private val modeStack = ArrayList<Int>()
    private val braceStack = ArrayList<Int>()
    private var mode = 0
    private var brace = 0
    private var identNext = false

    fun lex(): LexResult {
        if (n >= 2 && src[0] == '#' && src[1] == '!') {
            var e = 0
            while (e < n && src[e] != '\n') e++
            emit(Tk.LINE_COMMENT, 0, e)
            pos = e
        }
        while (pos < n) {
            if (mode == 1 || mode == 2) lexString() else lexCode()
        }
        if (mode != 0) error(pos, "unterminated string template")
        emit(Tk.EOF, n, n)
        return LexResult(src, toks, errors)
    }

    private fun push(m: Int) {
        modeStack.add(mode)
        braceStack.add(brace)
        mode = m
        brace = 0
    }

    private fun pop() {
        mode = modeStack.removeAt(modeStack.size - 1)
        brace = braceStack.removeAt(braceStack.size - 1)
    }

    private fun error(offset: Int, msg: String) {
        // position of an arbitrary offset: only used for rare lexical errors, so a scan is fine
        var l = 1
        var ls = 0
        var i = 0
        while (i < offset && i < n) {
            if (src[i] == '\n') {
                l++
                ls = i + 1
            }
            i++
        }
        errors.add(SyntaxError(offset, l, offset - ls + 1, msg))
    }

    private fun emit(kind: Tk, start: Int, end: Int) {
        toks.add(Token(kind, start, end, line, start - lineStart + 1))
        var i = start
        while (i < end) {
            if (src[i] == '\n') {
                line++
                lineStart = i + 1
            }
            i++
        }
    }

    private fun at(i: Int): Char = if (i < n) src[i] else '\u0000'

    private fun lexCode() {
        val c = src[pos]
        val s = pos
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000c') {
            var e = pos + 1
            while (e < n && (src[e] == ' ' || src[e] == '\t' || src[e] == '\r' || src[e] == '\n' || src[e] == '\u000c')) e++
            pos = e
            emit(Tk.WS, s, e)
            return
        }
        if (isIdentStart(c)) {
            var e = pos + 1
            while (e < n && isIdentPart(src[e])) e++
            val text = src.substring(s, e)
            val kw = keywordKind(text)
            pos = e
            if (kw == null) {
                emit(Tk.IDENT, s, e)
                return
            }
            // `!in` / `!is`: a '!' directly before the keyword was already consumed as part of the operator token
            emit(kw, s, e)
            return
        }
        if (isDigit(c)) {
            lexNumber()
            return
        }
        when (c) {
            '`' -> {
                var e = pos + 1
                while (e < n && src[e] != '`' && src[e] != '\n') e++
                if (e < n && src[e] == '`') {
                    e++
                } else {
                    error(s, "unterminated backtick identifier")
                }
                pos = e
                emit(Tk.IDENT, s, e)
            }
            '"' -> {
                if (at(pos + 1) == '"' && at(pos + 2) == '"') {
                    pos += 3
                    emit(Tk.OPEN_QUOTE, s, pos)
                    push(2)
                } else {
                    pos += 1
                    emit(Tk.OPEN_QUOTE, s, pos)
                    push(1)
                }
            }
            '\'' -> lexChar()
            '/' -> {
                val d = at(pos + 1)
                if (d == '/') {
                    var e = pos + 2
                    while (e < n && src[e] != '\n') e++
                    pos = e
                    emit(Tk.LINE_COMMENT, s, e)
                } else if (d == '*') {
                    val kdoc = at(pos + 2) == '*' && at(pos + 3) != '/'
                    var e = pos + 2
                    var depth = 1
                    while (e < n && depth > 0) {
                        if (src[e] == '/' && at(e + 1) == '*') {
                            depth++
                            e += 2
                        } else if (src[e] == '*' && at(e + 1) == '/') {
                            depth--
                            e += 2
                        } else {
                            e++
                        }
                    }
                    if (depth > 0) error(s, "unterminated block comment")
                    pos = e
                    emit(if (kdoc) Tk.KDOC else Tk.BLOCK_COMMENT, s, e)
                } else if (d == '=') {
                    op(Tk.SLASHEQ, 2)
                } else {
                    op(Tk.SLASH, 1)
                }
            }
            '(' -> op(Tk.LPAR, 1)
            ')' -> op(Tk.RPAR, 1)
            '[' -> op(Tk.LBRACKET, 1)
            ']' -> op(Tk.RBRACKET, 1)
            '{' -> {
                if (mode == 3) brace++
                op(Tk.LBRACE, 1)
            }
            '}' -> {
                if (mode == 3) {
                    if (brace == 0) {
                        op(Tk.TEMPLATE_LONG_END, 1)
                        pop()
                    } else {
                        brace--
                        op(Tk.RBRACE, 1)
                    }
                } else {
                    op(Tk.RBRACE, 1)
                }
            }
            '.' -> {
                if (at(pos + 1) == '.') {
                    if (at(pos + 2) == '<') op(Tk.DOTDOTLT, 3) else op(Tk.DOTDOT, 2)
                } else {
                    op(Tk.DOT, 1)
                }
            }
            ',' -> op(Tk.COMMA, 1)
            ';' -> op(Tk.SEMI, 1)
            ':' -> if (at(pos + 1) == ':') op(Tk.COLONCOLON, 2) else op(Tk.COLON, 1)
            '?' -> op(Tk.QUEST, 1)
            '!' -> {
                if (at(pos + 1) == '!') {
                    op(Tk.EXCLEXCL, 2)
                } else if (at(pos + 1) == '=') {
                    if (at(pos + 2) == '=') op(Tk.NEQEQ, 3) else op(Tk.NEQ, 2)
                } else if (at(pos + 1) == 'i' && at(pos + 2) == 'n' && !isIdentPart(at(pos + 3))) {
                    op(Tk.NOT_IN, 3)
                } else if (at(pos + 1) == 'i' && at(pos + 2) == 's' && !isIdentPart(at(pos + 3))) {
                    op(Tk.NOT_IS, 3)
                } else {
                    op(Tk.EXCL, 1)
                }
            }
            '=' -> {
                if (at(pos + 1) == '=') {
                    if (at(pos + 2) == '=') op(Tk.EQEQEQ, 3) else op(Tk.EQEQ, 2)
                } else {
                    op(Tk.EQ, 1)
                }
            }
            '+' -> if (at(pos + 1) == '+') op(Tk.PLUSPLUS, 2) else if (at(pos + 1) == '=') op(Tk.PLUSEQ, 2) else op(Tk.PLUS, 1)
            '-' -> {
                if (at(pos + 1) == '-') op(Tk.MINUSMINUS, 2)
                else if (at(pos + 1) == '=') op(Tk.MINUSEQ, 2)
                else if (at(pos + 1) == '>') op(Tk.ARROW, 2)
                else op(Tk.MINUS, 1)
            }
            '*' -> if (at(pos + 1) == '=') op(Tk.STAREQ, 2) else op(Tk.STAR, 1)
            '%' -> if (at(pos + 1) == '=') op(Tk.PERCEQ, 2) else op(Tk.PERC, 1)
            '<' -> if (at(pos + 1) == '=') op(Tk.LTEQ, 2) else op(Tk.LT, 1)
            '>' -> op(Tk.GT, 1)
            '&' -> if (at(pos + 1) == '&') op(Tk.ANDAND, 2) else op(Tk.AMP, 1)
            '|' -> if (at(pos + 1) == '|') op(Tk.OROR, 2) else bad()
            '@' -> op(Tk.AT, 1)
            '#' -> op(Tk.HASH, 1)
            else -> bad()
        }
    }

    private fun bad() {
        error(pos, "unexpected character '" + src[pos] + "'")
        op(Tk.BAD, 1)
    }

    private fun op(kind: Tk, len: Int) {
        val s = pos
        pos += len
        emit(kind, s, pos)
    }

    private fun lexNumber() {
        val s = pos
        var e = pos
        var real = false
        if (src[e] == '0' && (at(e + 1) == 'x' || at(e + 1) == 'X')) {
            e += 2
            while (e < n && (isHexDigit(src[e]) || src[e] == '_')) e++
        } else if (src[e] == '0' && (at(e + 1) == 'b' || at(e + 1) == 'B')) {
            e += 2
            while (e < n && (src[e] == '0' || src[e] == '1' || src[e] == '_')) e++
        } else {
            while (e < n && (isDigit(src[e]) || src[e] == '_')) e++
            if (at(e) == '.' && isDigit(at(e + 1))) {
                real = true
                e++
                while (e < n && (isDigit(src[e]) || src[e] == '_')) e++
            }
            val x = at(e)
            if ((x == 'e' || x == 'E') && (isDigit(at(e + 1)) || ((at(e + 1) == '+' || at(e + 1) == '-') && isDigit(at(e + 2))))) {
                real = true
                e += 2
                while (e < n && (isDigit(src[e]) || src[e] == '_')) e++
            }
            if (at(e) == 'f' || at(e) == 'F') {
                real = true
                e++
            }
        }
        if (!real) {
            // integer suffixes: L, u, U, uL, UL
            if (at(e) == 'L') {
                e++
            } else if ((at(e) == 'u' || at(e) == 'U') && !isIdentPart(at(e + 1)) ) {
                e++
            } else if ((at(e) == 'u' || at(e) == 'U') && at(e + 1) == 'L') {
                e += 2
            }
        }
        pos = e
        emit(if (real) Tk.REAL_LIT else Tk.INT_LIT, s, e)
    }

    private fun lexChar() {
        val s = pos
        var e = pos + 1
        if (at(e) == '\\') {
            if (at(e + 1) == 'u') {
                e += 2
                var k = 0
                while (k < 4 && isHexDigit(at(e))) {
                    e++
                    k++
                }
            } else {
                e += 2
            }
        } else if (e < n && src[e] != '\n' && src[e] != '\'') {
            e++
        }
        if (at(e) == '\'') {
            e++
            pos = e
            emit(Tk.CHAR_LIT, s, e)
        } else {
            error(s, "unterminated character literal")
            pos = s + 1
            emit(Tk.BAD, s, s + 1)
        }
    }

    private fun lexString() {
        val raw = mode == 2
        val c = src[pos]
        val s = pos
        if (identNext) {
            identNext = false
            if (isIdentStart(c)) {
                var e = pos + 1
                while (e < n && isIdentPart(src[e])) e++
                val kw = keywordKind(src.substring(s, e))
                pos = e
                emit(if (kw == Tk.THIS) Tk.THIS else Tk.IDENT, s, e)
                return
            }
        }
        if (c == '"') {
            if (!raw) {
                pos++
                emit(Tk.CLOSE_QUOTE, s, pos)
                pop()
                return
            }
            var q = 0
            while (at(pos + q) == '"') q++
            if (q >= 3) {
                if (q > 3) emit(Tk.STR_PART, s, s + q - 3)
                pos = s + q
                emit(Tk.CLOSE_QUOTE, s + q - 3, pos)
                pop()
            } else {
                pos = s + q
                emit(Tk.STR_PART, s, pos)
            }
            return
        }
        if (c == '$') {
            if (isIdentStart(at(pos + 1))) {
                pos++
                emit(Tk.TEMPLATE_SHORT, s, pos)
                identNext = true
                return
            }
            if (at(pos + 1) == '{') {
                pos += 2
                emit(Tk.TEMPLATE_LONG_START, s, pos)
                push(3)
                return
            }
        }
        if (c == '\\' && !raw) {
            var e = pos + 2
            if (at(pos + 1) == 'u') {
                e = pos + 2
                var k = 0
                while (k < 4 && isHexDigit(at(e))) {
                    e++
                    k++
                }
            }
            if (e > n) e = n
            if (at(pos + 1) == '\n' || pos + 1 >= n) e = pos + 1
            pos = e
            emit(Tk.STR_ESCAPE, s, e)
            return
        }
        if (c == '\n' && !raw) {
            error(pos, "unterminated string literal")
            pop()
            return
        }
        var e = pos + 1
        while (e < n) {
            val d = src[e]
            if (d == '"' || (d == '\\' && !raw) || (d == '\n' && !raw)) break
            if (d == '$' && (isIdentStart(at(e + 1)) || at(e + 1) == '{')) break
            e++
        }
        pos = e
        emit(Tk.STR_PART, s, e)
    }
}

fun lexKotlin(text: String): LexResult = Lexer(text).lex()
