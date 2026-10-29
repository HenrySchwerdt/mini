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
    /** Classes, interfaces and objects. Superclasses and interfaces precede their subtypes. */
    val classes: List<MirClass> = emptyList(),
)

class MirField(val name: String, val type: MirType)

/** The function table a class provides for one interface (see compiler/README.md, itables). */
class MirItable(
    /** C name of the interface ([MirClass.cName]). */
    val interfaceCName: String,
    /** One entry per interface method (in the interface's method-index order); null = abstract. */
    val entries: List<String?>,
)

/**
 * A class, interface or object. [cName] is the C identifier stem: `struct <cName>` is the
 * instance layout and `<cName>_ti` the MkTypeInfo.
 */
class MirClass(
    /** Fully qualified Kotlin name (MkTypeInfo.name). */
    val qualifiedName: String,
    val cName: String,
    val isInterface: Boolean,
    /** Direct superclass (a user class), null when it is kotlin.Any. Always null for interfaces. */
    val superCName: String?,
    /** Fields declared by this class (not inherited ones). */
    val fields: List<MirField>,
    /** All implemented interfaces (transitively, including those of superclasses), by [cName]. */
    val interfaces: List<String>,
    /**
     * Class vtable: three Any slots (toString, equals, hashCode) followed by the user slots.
     * An entry is the C name of the implementation; null means the default Any member
     * (slots 0..2) or abstract (later slots). Empty for interfaces.
     */
    val vtable: List<String?>,
    /** One table per entry of [interfaces], same order. Empty for interfaces. */
    val itables: List<MirItable>,
    /** For `object` declarations: C name of the constructor (initialiser) function. */
    val objectCtor: String? = null,
    /**
     * Defined by the runtime (libminirt, e.g. the built-in exception classes): the emitter emits no
     * struct, type info or functions for it, but user classes may extend it; `struct <cName>` and
     * `<cName>_ti` are declared in minirt.h.
     */
    val external: Boolean = false,
)

class MirFunction(
    /** Fully qualified Kotlin name for diagnostics, e.g. "a.b.foo". */
    val qualifiedName: String,
    /** C identifier, see [Mangling]. */
    val cName: String,
    val params: List<MirLocal>,
    val returnType: MirType,
    val body: List<MirStmt>,
    /**
     * Non-null for constructors: [cName] is the initialiser `void f(MkObject *this, ...)`;
     * the value is the [MirClass.cName]. The emitter adds an allocating wrapper `<cName>_new`.
     */
    val constructorOf: String? = null,
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

/*
 * Reference types. All of them are C pointers (`MkObject *`, except strings which are
 * `const MkString *`) and may be NULL at the C level; nullability is a Kotlin-level fact.
 */
object TNString : MirType("String?")

/** The type of the `null` literal. */
object TNull : MirType("Nothing?")

const val ANY_CNAME = "mk_Any"

/** `kotlin.Any` has [cName] [ANY_CNAME]; user classes use [MirClass.cName]. */
data class TObject(
    val qualifiedName: String,
    val cName: String,
    val nullable: Boolean,
    val isInterface: Boolean = false,
) : MirType(if (nullable) "$qualifiedName?" else qualifiedName) {
    val typeInfo: String get() = if (cName == ANY_CNAME) "mk_type_Any" else "${cName}_ti"
}

/** `Int?`, `Long?`, `Boolean?`, `Double?`, `Char?`: a boxed primitive (a reference). */
data class TBoxed(val prim: MirType) : MirType(prim.displayName + "?")

val MirType.isRef: Boolean
    get() = this === TString || this === TNString || this is TObject || this is TBoxed || this === TNull

val MirType.isPrimitive: Boolean
    get() = this === TInt || this === TLong || this === TBoolean || this === TDouble || this === TChar

fun MirType.nonNull(): MirType = when (this) {
    TNString -> TString
    is TObject -> if (nullable) copy(nullable = false) else this
    is TBoxed -> prim
    else -> this
}

fun MirType.asNullable(): MirType = when (this) {
    TString -> TNString
    is TObject -> if (nullable) this else copy(nullable = true)
    TInt, TLong, TBoolean, TDouble, TChar -> TBoxed(this)
    else -> this
}

enum class BinaryOp(val isComparison: Boolean = false, val isShift: Boolean = false) {
    Add, Sub, Mul, Div, Rem,
    Shl(isShift = true), Shr(isShift = true), Ushr(isShift = true),
    And, Or, Xor,
    Eq(true), Ne(true), Lt(true), Le(true), Gt(true), Ge(true),
    /** Reference identity (`===`, null checks); operands are any reference types. */
    IdEq(true), IdNe(true),
    /** Short-circuit; the right operand must not need hoisted statements. */
    AndAnd, OrOr,
}

enum class UnaryOp { Neg, Not, Inv }

sealed class MirStmt {
    /** `val`/`var`/temporary declaration. [init] is null for "declared, assigned later". */
    class Declare(val local: MirLocal, val init: MirExpr?) : MirStmt()
    class Assign(val local: MirLocal, val value: MirExpr) : MirStmt()
    /** `obj.field = value`; [owner] is the [MirClass.cName] that declares the field. */
    class SetField(
        val obj: MirExpr,
        val owner: String,
        val field: String,
        val fieldType: MirType,
        val value: MirExpr,
    ) : MirStmt()
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

    /** `throw value`: makes the exception pending and leaves through the exceptional path. */
    class Throw(val value: MirExpr) : MirStmt()

    /**
     * `try { body } catch ...`. An exception raised in [body] (directly or in any callee) releases the
     * locals of the scopes it leaves and runs the first clause whose type matches; without a match it
     * propagates. Exceptions raised inside a clause propagate outward. `finally` is lowered by the
     * producer into this construct (catch-all clause that runs the finalizer and rethrows, plus
     * finalizer copies on the normal and jump paths).
     */
    class Try(val body: List<MirStmt>, val catches: List<MirCatch>) : MirStmt()
}

/**
 * A catch clause. [type] is the exception class (a [TObject]); [body] must start with
 * `Declare(local, CaughtException(...))`, which binds the exception (ownership moves to the local).
 */
class MirCatch(val type: MirType, val body: List<MirStmt>)

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

    /** `cond ? a : b`; branches must not need hoisted statements. [resultType] defaults to the then type. */
    class Conditional(
        val cond: MirExpr,
        val thenValue: MirExpr,
        val elseValue: MirExpr,
        val resultType: MirType? = null,
    ) : MirExpr() {
        override val type: MirType get() = resultType ?: thenValue.type
    }

    /** The `null` literal retyped to [type] (a nullable reference type, or [TNull]). */
    class NullConst(override val type: MirType = TNull) : MirExpr()

    /** Boxes a primitive into [to] (a [TBoxed] or `Any`/`Any?`). Owned result. */
    class Box(val operand: MirExpr, val to: MirType) : MirExpr() { override val type: MirType get() = to }

    /** Unboxes a [TBoxed] (NullPointerException on null) into the primitive [to]. */
    class Unbox(val operand: MirExpr, val to: MirType) : MirExpr() { override val type: MirType get() = to }

    enum class CastMode {
        /** Statically known to succeed (upcast, smart cast): only a C-level retyping. */
        Unchecked,
        /** `as`: ClassCastException / NullPointerException on failure. */
        Checked,
        /** `as?`: null on failure. `to` must be nullable. */
        Safe,
    }

    /** Reference cast to [to]. Borrowed result (the same reference). */
    class Cast(val operand: MirExpr, val to: MirType, val mode: CastMode) : MirExpr() {
        override val type: MirType get() = to
    }

    /** `operand is target` (null gives false). Boxed types test for the box. */
    class IsInstance(val operand: MirExpr, val target: MirType) : MirExpr() {
        override val type: MirType get() = TBoolean
    }

    /** `operand!!`: NullPointerException when null. Borrowed. */
    class NotNull(val operand: MirExpr) : MirExpr() { override val type: MirType get() = operand.type.nonNull() }

    /** Reads a field; [owner] is the [MirClass.cName] that declares it. Borrowed result. */
    class FieldGet(val obj: MirExpr, val owner: String, val field: String, override val type: MirType) : MirExpr()

    /**
     * Virtual call through class vtable slot [slot] (index into the whole vtable, so >= 3).
     * [paramTypes] are the declared parameter types without the receiver. Owned result if reference.
     */
    class VCall(
        val slot: Int,
        val receiver: MirExpr,
        val args: List<MirExpr>,
        val paramTypes: List<MirType>,
        override val type: MirType,
    ) : MirExpr()

    /** Interface call: method [index] of the table of interface [interfaceCName]. Owned result if reference. */
    class ICall(
        val interfaceCName: String,
        val index: Int,
        val receiver: MirExpr,
        val args: List<MirExpr>,
        val paramTypes: List<MirType>,
        override val type: MirType,
    ) : MirExpr()

    /** Allocates an instance and runs the constructor [ctorCName]. Owned result. */
    class New(val ctorCName: String, val args: List<MirExpr>, override val type: MirType) : MirExpr()

    /** The singleton of an `object`, created on first access. Borrowed (the singleton is immortal). */
    class ObjectGet(val classCName: String, override val type: MirType) : MirExpr()

    /** `String.length` (Int). */
    class StringLength(val operand: MirExpr) : MirExpr() { override val type: MirType get() = TInt }

    /** A value of [type] that is never actually used (dead code after return/break). */
    class Unreachable(override val type: MirType) : MirExpr()

    /** The exception being handled; only valid as the first `Declare` of a [MirCatch] body. Owned. */
    class CaughtException(override val type: MirType) : MirExpr()

    /** `&<type info of target>` (NULL for a null target), as an argument of runtime calls. */
    class TypeInfoOf(val target: MirType?) : MirExpr() {
        override val type: MirType get() = TObject("mk.TypeInfo", "mk_TypeInfo", false)
    }
}

/**
 * True for expressions that produce a new reference the consumer must release (+1). The
 * producer guarantees that such an expression only appears as the initialiser of a
 * [MirStmt.Declare], the value of [MirStmt.Assign] / [MirStmt.SetField] / [MirStmt.Return],
 * or the operand of [MirStmt.Eval]; every other position sees a [MirExpr.Local] holding it.
 */
fun MirExpr.isOwned(): Boolean = type.isRef && when (this) {
    is MirExpr.Call, is MirExpr.RuntimeCall, is MirExpr.VCall, is MirExpr.ICall, is MirExpr.New, is MirExpr.Box,
    is MirExpr.CaughtException -> true
    else -> false
}
