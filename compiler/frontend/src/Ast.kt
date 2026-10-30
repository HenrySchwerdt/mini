package mini.frontend

/**
 * The typed AST. Every node knows its source range ([start]/[end] are offsets of the first and last significant
 * character, trivia excluded). The AST is a view that the parser builds next to the lossless CST (see Cst.kt):
 * it keeps the semantic structure only (no parentheses tokens, no commas), the CST keeps every token.
 */
sealed class Ast {
    var start: Int = 0
    var end: Int = 0
}

// ---- file level -------------------------------------------------------------------------------------------------

class KtFile(
    val fileAnnotations: List<AnnotationEntry>,
    val packageName: String?,
    val imports: List<ImportDirective>,
    val declarations: List<Decl>,
) : Ast()

class ImportDirective(val path: String, val star: Boolean, val alias: String?) : Ast()

// ---- modifiers and annotations ----------------------------------------------------------------------------------

sealed class ModItem : Ast()
class KeywordMod(val text: String) : ModItem()
class AnnotationMod(val annotation: AnnotationEntry) : ModItem()

/** Modifier list of a declaration, parameter or type: annotations and modifier keywords in source order. */
class Modifiers(val items: List<ModItem>, val contextParams: List<Param>) : Ast() {
    fun has(keyword: String): Boolean {
        for (m in items) if (m is KeywordMod && m.text == keyword) return true
        return false
    }

    fun keywords(): List<String> {
        val out = ArrayList<String>()
        for (m in items) if (m is KeywordMod) out.add(m.text)
        return out
    }

    fun annotations(): List<AnnotationEntry> {
        val out = ArrayList<AnnotationEntry>()
        for (m in items) if (m is AnnotationMod) out.add(m.annotation)
        return out
    }
}

/** `@target:Type(args)`; [args] is null when there is no argument list. */
class AnnotationEntry(val target: String?, val type: TypeRef, val args: List<Arg>?) : Ast()

class Arg(val name: String?, val spread: Boolean, val value: Expr) : Ast()

// ---- types ------------------------------------------------------------------------------------------------------

sealed class TypeRef : Ast() {
    /** annotations and `suspend` in front of the type */
    var mods: Modifiers? = null
}

class TypeArg(val star: Boolean, val variance: String?, val type: TypeRef?) : Ast()
class TypeSegment(val name: String, val args: List<TypeArg>) : Ast()
class UserType(val segments: List<TypeSegment>) : TypeRef()
class NullableType(val inner: TypeRef) : TypeRef()
class ParenType(val inner: TypeRef) : TypeRef()
class FnTypeParam(val name: String?, val type: TypeRef) : Ast()
class FunctionType(
    val contextTypes: List<TypeRef>,
    val receiver: TypeRef?,
    val params: List<FnTypeParam>,
    val returnType: TypeRef,
) : TypeRef()

class DynamicType : TypeRef()
class IntersectionType(val left: TypeRef, val right: TypeRef) : TypeRef()
class ErrorType : TypeRef()

class TypeParam(val mods: Modifiers?, val name: String, val bound: TypeRef?) : Ast()
class TypeConstraint(val name: String, val bound: TypeRef) : Ast()

class Param(
    val mods: Modifiers?,
    /** "val", "var" or null */
    val valVar: String?,
    val name: String,
    val type: TypeRef?,
    val default: Expr?,
) : Ast()

// ---- statements and declarations --------------------------------------------------------------------------------

/** Everything that can stand in a block: declarations and expressions. */
sealed class Stmt : Ast()

sealed class Decl : Stmt()

enum class ClassKind { CLASS, INTERFACE, OBJECT }

class PrimaryCtor(val mods: Modifiers?, val hasKeyword: Boolean, val params: List<Param>) : Ast()

sealed class SuperEntry : Ast()
class SuperType(val type: TypeRef) : SuperEntry()
class SuperCall(val type: TypeRef, val args: List<Arg>) : SuperEntry()
class SuperDelegate(val type: TypeRef, val delegate: Expr) : SuperEntry()

class ClassDecl(
    val mods: Modifiers?,
    val kind: ClassKind,
    /** null for anonymous companion objects (`companion object {`) and for `object` literals' bodies */
    val name: String?,
    val typeParams: List<TypeParam>,
    val primaryCtor: PrimaryCtor?,
    val supers: List<SuperEntry>,
    val constraints: List<TypeConstraint>,
    val enumEntries: List<EnumEntryDecl>,
    val members: List<Decl>,
    val hasBody: Boolean,
) : Decl()

class EnumEntryDecl(val mods: Modifiers?, val name: String, val args: List<Arg>?, val members: List<Decl>?) : Decl()

class FunDecl(
    val mods: Modifiers?,
    val typeParams: List<TypeParam>,
    val receiver: TypeRef?,
    /** null for anonymous functions */
    val name: String?,
    val params: List<Param>,
    val returnType: TypeRef?,
    val constraints: List<TypeConstraint>,
    /** a [BlockExpr], an expression (`= expr`) or null */
    val body: Expr?,
    val expressionBody: Boolean,
) : Decl()

class AccessorDecl(
    val mods: Modifiers?,
    /** "get" or "set" */
    val kind: String,
    val param: Param?,
    val returnType: TypeRef?,
    val body: Expr?,
    val expressionBody: Boolean,
) : Ast()

class PropertyDecl(
    val mods: Modifiers?,
    val isVar: Boolean,
    val typeParams: List<TypeParam>,
    val receiver: TypeRef?,
    val name: String,
    val type: TypeRef?,
    val constraints: List<TypeConstraint>,
    val initializer: Expr?,
    val delegate: Expr?,
    val getter: AccessorDecl?,
    val setter: AccessorDecl?,
    /** explicit backing field (`field = ...` below the property) */
    val backingField: BackingFieldDecl? = null,
) : Decl()

class BackingFieldDecl(val mods: Modifiers?, val type: TypeRef?, val initializer: Expr?) : Ast()

/** [source]: the property the entry reads in a name-based destructuring (`(val a = prop) = x`) */
class DestructEntry(val mods: Modifiers?, val isVar: Boolean, val name: String, val type: TypeRef?, val source: String? = null) : Ast()

/** `val (a, b) = x`, `val [a, b] = x` and the short forms `(val a, val b) = x` / `[val a, val b] = x` */
class DestructuringDecl(
    val mods: Modifiers?,
    val isVar: Boolean,
    val entries: List<DestructEntry>,
    val initializer: Expr?,
    val bracketed: Boolean = false,
) : Decl()

class TypeAliasDecl(
    val mods: Modifiers?,
    val name: String,
    val typeParams: List<TypeParam>,
    val type: TypeRef?,
) : Decl()

class InitDecl(val mods: Modifiers?, val body: BlockExpr?) : Decl()

class CtorDelegation(val isThis: Boolean, val args: List<Arg>) : Ast()

class SecondaryCtor(
    val mods: Modifiers?,
    val params: List<Param>,
    val delegation: CtorDelegation?,
    val body: BlockExpr?,
) : Decl()

// ---- expressions ------------------------------------------------------------------------------------------------

sealed class Expr : Stmt()

enum class LitKind { INT, REAL, CHAR, TRUE, FALSE, NULL }

class LiteralExpr(val kind: LitKind, val text: String) : Expr()

sealed class StrPart : Ast()
class StrText(val text: String) : StrPart()
class StrEscape(val text: String) : StrPart()
class StrShortRef(val name: String) : StrPart()
class StrLongExpr(val expr: Expr) : StrPart()

class StringExpr(val raw: Boolean, val parts: List<StrPart>) : Expr()

class NameExpr(val name: String) : Expr()
class ThisExpr(val label: String?) : Expr()
class SuperExpr(val typeArg: TypeRef?, val label: String?) : Expr()
class ParenExpr(val inner: Expr) : Expr()

/** Binary operator, infix call, `?:`, ranges, `in`/`!in` and all assignments. [op] is the operator text. */
class BinaryExpr(val op: String, val left: Expr, val right: Expr) : Expr()
class IsExpr(val negated: Boolean, val expr: Expr, val type: TypeRef) : Expr()
class AsExpr(val safe: Boolean, val expr: Expr, val type: TypeRef) : Expr()
class PrefixExpr(val op: String, val expr: Expr) : Expr()
class PostfixExpr(val op: String, val expr: Expr) : Expr()
class AnnotatedExpr(val annotations: List<AnnotationEntry>, val expr: Expr) : Expr()
class LabeledExpr(val label: String, val expr: Expr) : Expr()

class CallExpr(
    val callee: Expr,
    val typeArgs: List<TypeArg>,
    /** null when the call consists of trailing lambdas only */
    val args: List<Arg>?,
    val lambdas: List<Expr>,
) : Expr()

class IndexExpr(val receiver: Expr, val indices: List<Expr>) : Expr()

/** `a.b` ([safe] false) or `a?.b`; [selector] is a [NameExpr] or a [CallExpr] */
class DotExpr(val receiver: Expr, val safe: Boolean, val selector: Expr) : Expr()

/** `X::name`, `::name` (receiver null) and `X::class` (name "class") */
class CallableRefExpr(val receiver: Expr?, val name: String) : Expr()

class LambdaParam(val mods: Modifiers?, val names: List<DestructEntry>?, val name: String?, val type: TypeRef?) : Ast()
class LambdaExpr(val params: List<LambdaParam>, val hasArrow: Boolean, val body: List<Stmt>) : Expr()
class AnonFunExpr(val fn: FunDecl) : Expr()
class BlockExpr(val stmts: List<Stmt>) : Expr()

class IfExpr(val cond: Expr, val then: Expr?, val otherwise: Expr?) : Expr()

sealed class WhenCond : Ast()
class WhenExprCond(val expr: Expr) : WhenCond()
class WhenInCond(val negated: Boolean, val expr: Expr) : WhenCond()
class WhenIsCond(val negated: Boolean, val type: TypeRef) : WhenCond()

class WhenEntry(val conds: List<WhenCond>, val isElse: Boolean, val guard: Expr?, val body: Expr) : Ast()
class WhenSubject(val valName: String?, val valType: TypeRef?, val expr: Expr) : Ast()
class WhenExpr(val subject: WhenSubject?, val entries: List<WhenEntry>) : Expr()

class CatchClause(val param: Param, val body: BlockExpr) : Ast()
class TryExpr(val body: BlockExpr, val catches: List<CatchClause>, val finallyBlock: BlockExpr?) : Expr()
class ThrowExpr(val expr: Expr?) : Expr()
class ReturnExpr(val label: String?, val expr: Expr?) : Expr()
class BreakExpr(val label: String?) : Expr()
class ContinueExpr(val label: String?) : Expr()

class ForExpr(val entries: List<DestructEntry>, val destructuring: Boolean, val iterable: Expr, val body: Expr?) : Expr()
class WhileExpr(val cond: Expr, val body: Expr?) : Expr()
class DoWhileExpr(val body: Expr?, val cond: Expr) : Expr()

class ObjectLiteral(val supers: List<SuperEntry>, val members: List<Decl>) : Expr()
class CollectionLiteral(val elements: List<Expr>) : Expr()

/** Placeholder after a syntax error. */
class ErrorExpr : Expr()
