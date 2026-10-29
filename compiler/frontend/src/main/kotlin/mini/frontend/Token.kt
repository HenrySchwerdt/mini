package mini.frontend

/**
 * Token kinds of the Kotlin lexer. Soft keywords and modifier keywords (`by`, `open`, `data`, ...) are plain
 * [IDENT] tokens; the parser decides by text and context. Hard keywords have their own kinds.
 *
 * Design notes (kept close to the official lexer, KotlinLexer.g4):
 *  - `>` is always a single token (`List<List<Int>>`); `>=` is `>` immediately followed by `=` (the parser joins them).
 *  - `?.`, `?:` and `as?` are not single tokens either (`String?.foo()` is a type followed by a dot); the parser
 *    recognises them by adjacency. `!in` and `!is` are single tokens unless an identifier part follows (`!isFoo`).
 *  - String literals are token sequences: [OPEN_QUOTE] ([STR_PART] | [STR_ESCAPE] | [TEMPLATE_SHORT] [IDENT] |
 *    [TEMPLATE_LONG_START] tokens... [TEMPLATE_LONG_END])* [CLOSE_QUOTE].
 */
enum class Tk {
    EOF,

    // trivia
    WS, LINE_COMMENT, BLOCK_COMMENT, KDOC,

    // literals and names
    IDENT, INT_LIT, REAL_LIT, CHAR_LIT,

    // string literals
    OPEN_QUOTE, CLOSE_QUOTE, STR_PART, STR_ESCAPE, TEMPLATE_SHORT, TEMPLATE_LONG_START, TEMPLATE_LONG_END,

    // hard keywords
    AS, BREAK, CLASS, CONTINUE, DO, ELSE, FALSE, FOR, FUN, IF, IN, INTERFACE, IS, NULL, OBJECT, PACKAGE,
    RETURN, SUPER, THIS, THROW, TRUE, TRY, TYPEALIAS, TYPEOF, VAL, VAR, WHEN, WHILE,
    NOT_IN, NOT_IS,

    // punctuation and operators
    LPAR, RPAR, LBRACKET, RBRACKET, LBRACE, RBRACE,
    DOT, DOTDOT, DOTDOTLT, COMMA, SEMI, COLON, COLONCOLON, QUEST, EXCL, EXCLEXCL,
    EQ, EQEQ, EQEQEQ, NEQ, NEQEQ,
    PLUS, PLUSPLUS, PLUSEQ, MINUS, MINUSMINUS, MINUSEQ, ARROW,
    STAR, STAREQ, SLASH, SLASHEQ, PERC, PERCEQ,
    LT, LTEQ, GT, ANDAND, OROR, AMP, AT, HASH,

    /** A character the lexer cannot make sense of (also unterminated char literals). */
    BAD,
}

fun Tk.isTrivia(): Boolean = this == Tk.WS || this == Tk.LINE_COMMENT || this == Tk.BLOCK_COMMENT || this == Tk.KDOC

/** One token with its position: [start]/[end] are offsets into the source text, [line]/[col] are 1-based. */
class Token(val kind: Tk, val start: Int, val end: Int, val line: Int, val col: Int) {
    override fun toString(): String = "$kind@$line:$col[$start,$end)"
}

/** A lexical or syntactic error. [offset] is a source offset, [line]/[col] are 1-based. */
class SyntaxError(val offset: Int, val line: Int, val col: Int, val message: String) {
    override fun toString(): String = "$line:$col: $message"
}
