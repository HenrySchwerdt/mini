package mini.backend

/*
 * MIR: the Mini intermediate representation between Kotlin IR (jvm-bridge) and the
 * C emitter. It is deliberately small and structured (no CFG yet): statements nest,
 * loops carry labels, expressions are side-effect-ordered trees.
 *
 * Rules the producer (ir2mir) must obey:
 *  - Expressions are evaluated left to right; every sub-expression that needs
 *    statements is hoisted by the producer into temporaries beforehand.
 *  - Unit-typed expressions are only used as the expression of [MirStmt.Eval].
 *  - Every [MirLocal] is declared by exactly one [MirStmt.Declare] (or is a parameter,
 *    or a loop variable of [MirStmt.ForRange]) before it is referenced.
 *
 * This file must stay free of any JVM dependency (docs/plan-kotlin.md section 10a).
 */

class MirModule(
    /** Name of the source file, used in generated comments only. */
    val sourceName: String,
    val functions: List<MirFunction>,
)

class MirFunction(
    /** Fully qualified Kotlin name for diagnostics, e.g. "a.b.foo". */
    val qualifiedName: String,
    /** C identifier, see [Mangling]. */
    val cName: String,
    val params: List<MirLocal>,
    val returnType: MirType,
    val body: List<MirStmt>,
)

class MirLocal(
    val id: Int,
    /** Kotlin name; only used to make the C identifier readable. */
    val name: String,
    val type: MirType,
)

class MirLabel(val id: Int)

sealed class MirType(val displayName: String) {
    override fun toString(): String = displayName
}

object TInt : MirType("Int")
object TLong : MirType("Long")
object TBoolean : MirType("Boolean")
object TDouble : MirType("Double")
object TChar : MirType("Char")
object TString : MirType("String")
object TUnit : MirType("Unit")
object TNothing : MirType("Nothing")

enum class BinaryOp(val isComparison: Boolean = false, val isShift: Boolean = false) {
    Add, Sub, Mul, Div, Rem,
    Shl(isShift = true), Shr(isShift = true), Ushr(isShift = true),
    And, Or, Xor,
    Eq(true), Ne(true), Lt(true), Le(true), Gt(true), Ge(true),
    /** Short-circuit; the right operand must not need hoisted statements. */
    AndAnd, OrOr,
}

enum class UnaryOp { Neg, Not, Inv }

sealed class MirStmt {
    /** `val`/`var`/temporary declaration. [init] is null for "declared, assigned later". */
    class Declare(val local: MirLocal, val init: MirExpr?) : MirStmt()
    class Assign(val local: MirLocal, val value: MirExpr) : MirStmt()
    /** Expression evaluated for its effect; the value is discarded. */
    class Eval(val expr: MirExpr) : MirStmt()
    class If(val cond: MirExpr, val thenBody: List<MirStmt>, val elseBody: List<MirStmt>) : MirStmt()
    class Block(val body: List<MirStmt>) : MirStmt()
    /** `while (cond) body`; [cond] has no hoisted statements. `continue` re-tests [cond]. */
    class While(val label: MirLabel, val cond: MirExpr, val body: List<MirStmt>) : MirStmt()
    /** Infinite loop; the body is emitted in the loop's own scope, `continue` jumps to the end of it. */
    class Loop(val label: MirLabel, val body: List<MirStmt>) : MirStmt()
    /**
     * `do body while (cond)`. Declarations of [body] are visible in [condStmts] and [cond]
     * (Kotlin scoping); `continue` jumps to the start of [condStmts].
     */
    class DoWhile(
        val label: MirLabel,
        val body: List<MirStmt>,
        val condStmts: List<MirStmt>,
        val cond: MirExpr,
    ) : MirStmt()
    /**
     * Counting loop over an Int, Long or Char progression with exact Kotlin semantics
     * (no overflow, empty when the range is empty). [step] is a positive magnitude
     * (null means 1); [ascending] gives the direction. [inclusive] is false for `until`.
     * [variable] has the element type; start/end have the same type, step is Int or Long.
     */
    class ForRange(
        val label: MirLabel,
        val variable: MirLocal,
        val start: MirExpr,
        val end: MirExpr,
        val step: MirExpr?,
        val ascending: Boolean,
        val inclusive: Boolean,
        val body: List<MirStmt>,
    ) : MirStmt()
    class Break(val label: MirLabel) : MirStmt()
    class Continue(val label: MirLabel) : MirStmt()
    class Return(val value: MirExpr?) : MirStmt()
    /** Aborts through `mk_panic`. [message] must be plain ASCII without quotes or backslashes. */
    class Panic(val message: String) : MirStmt()
}

sealed class MirExpr {
    abstract val type: MirType

    class IntConst(val value: Int) : MirExpr() { override val type: MirType get() = TInt }
    class LongConst(val value: Long) : MirExpr() { override val type: MirType get() = TLong }
    class DoubleConst(val value: Double) : MirExpr() { override val type: MirType get() = TDouble }
    class BooleanConst(val value: Boolean) : MirExpr() { override val type: MirType get() = TBoolean }
    /** [code] is the UTF-16 code unit. */
    class CharConst(val code: Int) : MirExpr() { override val type: MirType get() = TChar }
    class StringConst(val value: String) : MirExpr() { override val type: MirType get() = TString }

    /** The unit value; only valid as operand of [MirStmt.Eval] or as unused result. */
    object UnitValue : MirExpr() { override val type: MirType get() = TUnit }

    class Local(val local: MirLocal) : MirExpr() { override val type: MirType get() = local.type }

    /** Operands have the same type (shift counts are Int). Comparisons yield Boolean. */
    class Binary(val op: BinaryOp, val left: MirExpr, val right: MirExpr) : MirExpr() {
        override val type: MirType get() = if (op.isComparison || op == BinaryOp.AndAnd || op == BinaryOp.OrOr) TBoolean else left.type
    }

    class Unary(val op: UnaryOp, val operand: MirExpr) : MirExpr() {
        override val type: MirType get() = operand.type
    }

    /** Numeric/char conversion between Int, Long, Double and Char with Kotlin semantics. */
    class Convert(val operand: MirExpr, val to: MirType) : MirExpr() {
        override val type: MirType get() = to
    }

    /** Call of a function of this module, by C name. */
    class Call(val cName: String, val args: List<MirExpr>, override val type: MirType) : MirExpr()

    /** Call of a libminirt function declared in minirt.h. */
    class RuntimeCall(val cName: String, val args: List<MirExpr>, override val type: MirType) : MirExpr()

    /** `cond ? a : b`; branches must not need hoisted statements. */
    class Conditional(val cond: MirExpr, val thenValue: MirExpr, val elseValue: MirExpr) : MirExpr() {
        override val type: MirType get() = thenValue.type
    }

    /** `String.length` (Int). */
    class StringLength(val operand: MirExpr) : MirExpr() { override val type: MirType get() = TInt }

    /** A value of [type] that is never actually used (dead code after return/break). */
    class Unreachable(override val type: MirType) : MirExpr()
}
