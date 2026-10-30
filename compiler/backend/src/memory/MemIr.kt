package mini.backend.memory

/*
 * Memory MIR ("MemIr"): the input of the Strict-mode ownership checker (docs/memory-model.md).
 *
 * It is the MIR-level view of a program that carries exactly what the checker needs and that the
 * C-oriented MIR (mini.backend.Mir) cannot represent yet: source positions, annotations
 * (@Owned/@Borrowed/@MemoryMode/@AllocatedBy/@NoAlloc/@ThreadBoundary), context parameters,
 * allocator objects, calls to free/move/use/close, lambdas (inline, thread, escaping), try/finally.
 * The jvm-bridge produces it from Kotlin IR (MemoryFacts.kt) next to Ir2Mir; the checker itself
 * (this package) is pure Kotlin and builds a CFG from it (Cfg.kt), solves the dataflow problem
 * (Analysis.kt) and renders diagnostics (Diagnostics.kt).
 *
 * This package must stay free of any JVM dependency (docs/plan-kotlin.md section 10a).
 */

data class Pos(val line: Int, val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (line != other.line) line.compareTo(other.line) else col.compareTo(other.col)
    override fun toString(): String = "$line:$col"

    companion object {
        val NONE = Pos(0, 0)
    }
}

enum class MemMode { Strict, Managed }

/** Which allocator class an allocator type is. */
enum class AllocKind { Heap, Arena, Pool, Rc, Debug, Other }

/** Coarse classification of a type for the ownership analysis. */
enum class TK {
    /** Int, Boolean, ...: no ownership. */
    Prim,
    /** String: reference counted, not tracked. */
    Str,
    /** Instance of a class declared in Strict code: tracked. */
    Strict,
    /** Instance of a Managed (or foreign) class: borrowed, lifetime external. */
    Managed,
    /** An allocator object (Heap, Arena, Pool, Rc, DebugAllocator, ...). Arena/Pool are tracked resources. */
    Alloc,
    /** Anything else (function types, Any, generics, Unit). */
    Other,
}

class MClass(
    /** Fully qualified name. */
    val name: String,
    val mode: MemMode,
    /** Fully qualified name of the `@AllocatedBy` allocator class, if any. */
    val allocatedBy: String?,
    val closeable: Boolean,
) {
    val fields: MutableList<MField> = ArrayList()
    override fun toString(): String = name
}

class MField(val name: String, val type: MType, val borrowed: Boolean, val owner: MClass)

class MType(
    val tk: TK,
    val nullable: Boolean = false,
    val cls: MClass? = null,
    val alloc: AllocKind? = null,
    /** Fully qualified class name of the type (for @AllocatedBy matching), if known. */
    val className: String? = null,
    val display: String = "",
) {
    /** Tracked by the ownership analysis (obligations / state). */
    val tracked: Boolean get() = tk == TK.Strict || (tk == TK.Alloc && (alloc == AllocKind.Arena || alloc == AllocKind.Pool))
    val isResource: Boolean get() = tk == TK.Alloc && (alloc == AllocKind.Arena || alloc == AllocKind.Pool)
    override fun toString(): String = display.ifEmpty { tk.name }

    companion object {
        val PRIM = MType(TK.Prim, display = "primitive")
        val STR = MType(TK.Str, display = "String")
        val OTHER = MType(TK.Other, display = "Any")
        val UNIT = MType(TK.Prim, display = "Unit")
    }
}

class MLocal(
    val id: Int,
    val name: String,
    val type: MType,
    val pos: Pos = Pos.NONE,
)

/** Annotation facts of one declaration site. [owned]/[borrowed] hold the position of the annotation if present. */
class MAnnot(val owned: Pos? = null, val borrowed: Pos? = null) {
    companion object {
        val NONE = MAnnot()
    }
}

class MParam(
    val local: MLocal,
    val annot: MAnnot = MAnnot.NONE,
    val isContext: Boolean = false,
    /** Owned without annotation (property parameter of a constructor). */
    val implicitOwned: Boolean = false,
) {
    /** The callee takes ownership of the argument. */
    val owned: Boolean get() = annot.borrowed == null && (annot.owned != null || implicitOwned)
}

class MReturn(
    val type: MType,
    val annot: MAnnot = MAnnot.NONE,
) {
    /** Strict default: returns are owning unless `@Borrowed`. */
    val owned: Boolean get() = type.tracked && annot.borrowed == null
}

/**
 * A function, constructor or lambda-free callee description. Declared functions carry a [body];
 * foreign (stdlib) callees have none and [external] = true.
 */
class MFun(
    val qname: String,
    val mode: MemMode,
    val params: List<MParam>,
    val ret: MReturn,
    val receiver: MParam? = null,
    val noAlloc: Boolean = false,
    val external: Boolean = false,
    /** Known to accept tracked arguments without ownership (println, toString, ...). */
    val borrowsAll: Boolean = false,
    /** For constructors: the constructed class. */
    val ctorOf: MClass? = null,
    val pos: Pos = Pos.NONE,
    /** Always throws or is a require-like call that creates an exception edge. */
    val throwsKnown: Boolean = false,
    /**
     * C interop (docs/plan-kotlin.md 7): an `external fun` annotated `@Owned(free = "fn")`; the result is a C allocation the
     * caller must release by calling the C function `fn` (a null or empty name means: owned, released some other way).
     */
    val cFree: String? = null,
) {
    var body: List<MStmt>? = null
    /** Parameters that are visible allocators (context parameters whose type is an allocator). */
    val contextAllocParams: List<MParam> get() = params.filter { it.isContext && it.local.type.tk == TK.Alloc }
    /** Summary: contains (transitively) a throw. Filled by the checker. */
    var mayThrow: Boolean = false
    /** Summary (filled by the checker): indices into [params] of borrowed parameters the body stores into owned fields (M0109). */
    val storesParams: MutableSet<Int> = HashSet()
    override fun toString(): String = qname
}

enum class FreeKind { Free, Close }

sealed class MExpr {
    abstract val type: MType
    abstract val pos: Pos

    /** Constants, arithmetic... anything without ownership meaning; [children] are still evaluated (uses). */
    class Opaque(override val type: MType, override val pos: Pos, val children: List<MExpr> = emptyList(), val strLiteral: Boolean = false) : MExpr()

    class Null(override val type: MType, override val pos: Pos) : MExpr()

    class Local(val local: MLocal, override val pos: Pos) : MExpr() {
        override val type: MType get() = local.type
    }

    /** Allocation site of a class instance (or allocator object). [args] align with the constructor's parameters. */
    class New(val ctor: MFun, val cls: MClass?, val args: List<MExpr>, override val type: MType, override val pos: Pos) : MExpr()

    /** [args] align with [MFun.params] (context arguments included); defaults omitted are [Opaque]. */
    class Call(
        val fn: MFun,
        val receiver: MExpr?,
        val args: List<MExpr>,
        override val type: MType,
        override val pos: Pos,
    ) : MExpr()

    class FieldGet(val obj: MExpr, val field: MField, override val type: MType, override val pos: Pos) : MExpr()

    class GetGlobal(val name: String, override val type: MType, override val pos: Pos) : MExpr()

    /** `x == null` ([isNull] = true) or `x != null`. Operand is any expression (typically a [Local]). */
    class NullTest(val operand: MExpr, val isNull: Boolean, override val pos: Pos) : MExpr() {
        override val type: MType get() = MType.PRIM
    }

    class Not(val operand: MExpr, override val pos: Pos) : MExpr() {
        override val type: MType get() = MType.PRIM
    }

    /** `x.free()`, `alloc.free(x)` ([alloc] != null) or `x.close()`: ends ownership. */
    class Free(val target: MExpr, val alloc: MExpr?, val kind: FreeKind, override val pos: Pos) : MExpr() {
        override val type: MType get() = MType.UNIT
    }

    /**
     * `x.move()` (explicit) or an implicit transfer of a hoisted result temporary ([implicit]).
     * [pos] is the start of the whole expression (for partial moves: of `s.item`).
     */
    class Move(val target: MExpr, val implicit: Boolean, override val pos: Pos) : MExpr() {
        override val type: MType get() = target.type
    }

    /** `alloc.allocate(...)`: counts as an allocation inside `@NoAlloc` (M0205). */
    class RawAlloc(val alloc: MExpr, override val pos: Pos) : MExpr() {
        override val type: MType get() = MType.OTHER
    }

    /** An escaping lambda (not inline, not a thread boundary). Its body is analysed as a separate unit. */
    class Lambda(
        val id: Int,
        val params: List<MLocal>,
        val body: List<MStmt>,
        val captures: List<MLocal>,
        override val pos: Pos,
    ) : MExpr() {
        override val type: MType get() = MType.OTHER
    }
}

enum class ScopeKind {
    /** `x.use { p -> ... }`: [MStmt.Scope.target] is closed on every exit; the parameter is declared by the producer as an alias of it. */
    Use,
    /** `context(a, b) { ... }`: [MStmt.Scope.ctx] become the innermost allocator level. */
    Context,
    /** Inline lambda that runs exactly once (run, let, apply, with, ...). */
    Once,
    /** Inline lambda that runs zero or more times (repeat, forEach, ...). */
    Repeat,
    /** Lambda of a thread boundary (@ThreadBoundary): runs once, captured owners move into it. */
    Thread,
}

sealed class MStmt {
    abstract val pos: Pos

    /**
     * Declaration. [ownedAnnot]: `val b: @Owned T = a` / `@Owned val`; [borrowedAnnot]: `@Borrowed val`.
     */
    class Decl(val local: MLocal, val init: MExpr?, val annot: MAnnot = MAnnot.NONE, override val pos: Pos) : MStmt()

    class Assign(val local: MLocal, val value: MExpr, override val pos: Pos) : MStmt()

    /** [obj.field = value] ; [pos] is the start of the assignment (the target). */
    class SetField(val obj: MExpr, val field: MField, val value: MExpr, override val pos: Pos) : MStmt()

    class SetGlobal(val name: String, val type: MType, val value: MExpr, val managedOwner: Boolean, override val pos: Pos) : MStmt()

    /** Expression evaluated for effect; a fresh owned result is discarded (leak). */
    class Eval(val expr: MExpr, override val pos: Pos) : MStmt()

    class If(val cond: MExpr, val thenBody: List<MStmt>, val elseBody: List<MStmt>, override val pos: Pos) : MStmt()

    class Block(val body: List<MStmt>, override val pos: Pos) : MStmt()

    /** `while`: [condStmts] and [cond] are evaluated before each iteration; [cond] == null means loop forever. */
    class While(val label: Int, val condStmts: List<MStmt>, val cond: MExpr?, val body: List<MStmt>, override val pos: Pos) : MStmt()

    /** `do body while (cond)`; `continue` jumps to [condStmts]. */
    class DoWhile(val label: Int, val body: List<MStmt>, val condStmts: List<MStmt>, val cond: MExpr, override val pos: Pos) : MStmt()

    class Break(val label: Int, override val pos: Pos) : MStmt()
    class Continue(val label: Int, override val pos: Pos) : MStmt()

    /** [target] is a lambda id of an enclosing [Scope] or 0 for the function itself. */
    class Return(val value: MExpr?, val target: Int, override val pos: Pos) : MStmt()

    class Throw(val value: MExpr, override val pos: Pos) : MStmt()

    class Catch(val local: MLocal?, val body: List<MStmt>)

    class Try(val body: List<MStmt>, val catches: List<Catch>, val finallyBody: List<MStmt>?, override val pos: Pos) : MStmt()

    /**
     * A lambda that the analysis reads like code at this place. [id] is the lambda id used by
     * [Return.target]. Lambda parameters are declared by the producer as the first [Decl]s of [body];
     * the value of the lambda is stored into a result local (declared by the producer before the scope)
     * followed by `Return(null, id)`.
     */
    class Scope(
        val kind: ScopeKind,
        val id: Int,
        val target: MLocal?,
        val ctx: List<MLocal>,
        val body: List<MStmt>,
        val captures: List<MLocal>,
        val lambdaPos: Pos,
        override val pos: Pos,
    ) : MStmt()
}

class MFunction(val fn: MFun, val body: List<MStmt>, val declared: Boolean = true, val file: String = "")

class MModule(
    /** The first user file (the default file of diagnostics). */
    val fileName: String,
    val functions: List<MFunction>,
    val classes: List<MClass>,
    /** Source lines by file name (for diagnostic snippets); optional. */
    val sources: Map<String, List<String>> = emptyMap(),
)
