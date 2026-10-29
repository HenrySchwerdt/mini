package mini.frontend

/**
 * The lossless concrete syntax tree. Leaves are tokens (by index into the token list, trivia included), inner nodes
 * group them. Printing the leaves in order reproduces the source byte for byte; this is checked for every file in the
 * repository by the tests. Whitespace and comments sit in the node whose first following token they precede, trailing
 * trivia at the end of the file sits in the root.
 */
sealed class Cst

class CstLeaf(val tokenIndex: Int) : Cst()

class CstNode(val kind: NodeKind, val children: List<Cst>) : Cst()

enum class NodeKind {
    FILE, FILE_ANNOTATIONS, PACKAGE, IMPORT,
    MODIFIERS, ANNOTATION, ANNOTATION_GROUP, CONTEXT_PARAMS,
    // declarations
    CLASS, OBJECT, ENUM_ENTRY, PRIMARY_CTOR, SECONDARY_CTOR, CTOR_DELEGATION, SUPER_LIST, SUPER_TYPE, SUPER_CALL, SUPER_DELEGATE,
    CLASS_BODY, FUN, PROPERTY, DESTRUCTURING, DESTRUCT_ENTRY, ACCESSOR, BACKING_FIELD, TYPEALIAS, INIT,
    TYPE_PARAMS, TYPE_PARAM, CONSTRAINTS, CONSTRAINT, PARAMS, PARAM,
    // types
    TYPE_USER, TYPE_SEGMENT, TYPE_ARGS, TYPE_ARG, TYPE_NULLABLE, TYPE_PAREN, TYPE_FUNCTION, TYPE_FN_PARAM, TYPE_DYNAMIC,
    TYPE_INTERSECTION,
    // expressions
    LITERAL, STRING, STR_SHORT, STR_LONG, NAME, THIS, SUPER, PAREN, BINARY, IS, AS, PREFIX, POSTFIX, ANNOTATED, LABELED,
    CALL, ARGS, ARG, LAMBDA_ARG, INDEX, DOT, CALLABLE_REF, LAMBDA, LAMBDA_PARAMS, LAMBDA_PARAM, ANON_FUN, BLOCK,
    IF, WHEN, WHEN_SUBJECT, WHEN_ENTRY, WHEN_COND_EXPR, WHEN_COND_IN, WHEN_COND_IS, TRY, CATCH, FINALLY, THROW, RETURN,
    BREAK, CONTINUE, FOR, WHILE, DO_WHILE, OBJECT_LITERAL, COLLECTION_LITERAL, LABEL,
    ERROR,
}

/** Reproduces the source text from the tree. */
fun printCst(root: CstNode, tokens: List<Token>, text: String): String {
    val sb = StringBuilder()
    printInto(root, tokens, text, sb)
    return sb.toString()
}

private fun printInto(node: Cst, tokens: List<Token>, text: String, sb: StringBuilder) {
    when (node) {
        is CstLeaf -> {
            val t = tokens[node.tokenIndex]
            sb.append(text.substring(t.start, t.end))
        }
        is CstNode -> {
            for (c in node.children) printInto(c, tokens, text, sb)
        }
    }
}

/** A readable dump of the tree (node kinds, tokens without trivia), used by tests and the driver. */
fun dumpCst(node: Cst, tokens: List<Token>, text: String, indent: Int, sb: StringBuilder) {
    when (node) {
        is CstLeaf -> {
            val t = tokens[node.tokenIndex]
            if (t.kind.isTrivia() || t.kind == Tk.EOF) return
            var i = 0
            while (i < indent) {
                sb.append(' ')
                i++
            }
            sb.append(t.kind.toString()).append(' ').append(text.substring(t.start, t.end)).append('\n')
        }
        is CstNode -> {
            var i = 0
            while (i < indent) {
                sb.append(' ')
                i++
            }
            sb.append(node.kind.toString()).append('\n')
            for (c in node.children) dumpCst(c, tokens, text, indent + 2, sb)
        }
    }
}
