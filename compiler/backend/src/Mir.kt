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
    /** C interop (docs/plan-kotlin.md section 7): the C functions called by [MirExpr.CCall], each declared once. */
    val externs: List<MirExtern> = emptyList(),
    /** `@file:CInclude("header.h")`: headers the C backend includes (the LLVM backend needs none). */
    val includes: List<String> = emptyList(),
    /** `@file:CLink("lib")`: libraries the program is linked with (`-l<lib>`). */
    val links: List<String> = emptyList(),
    /** `@WasmExport` functions of a WebAssembly library (WasmLib.kt); empty for programs. */
    val wasmExports: List<MirWasmExport> = emptyList(),
)

/**
 * A C function the program calls (`external fun` + `@CName("symbol")`). [params] are the parameter types of the Kotlin
 * declaration (primitives and [TPtr] only); [fixedParams] is -1 for an ordinary function and the number of leading
 * fixed parameters for a variadic one (`@CVarargs`): the remaining [params] are passed as `...` arguments.
 */
class MirExtern(
    val symbol: String,
    val params: List<MirType>,
    val returnType: MirType,
    val fixedParams: Int = -1,
) {
    val isVariadic: Boolean get() = fixedParams >= 0
    /** The parameters of the C prototype (without the `...` part). */
    val prototypeParams: List<MirType> get() = if (fixedParams >= 0) params.subList(0, fixedParams) else params

    /** Two externs of one symbol with the same C prototype share one declaration. */
    val prototypeKey: String
        get() = symbol + "(" + prototypeParams.joinToString(",") + (if (isVariadic) ",..." else "") + ")" + returnType
}

class MirField(
    val name: String,
    val type: MirType,
    /**
     * `@Borrowed` field (Strict mode): a non-owning back reference. A tracked-class-typed field that is not
     * borrowed is owned: the glue of `free()` releases it (docs/memory-model.md 5.3).
     */
    val borrowed: Boolean = false,
)

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
    /**
     * Declared in a Strict file / Strict class (docs/memory-model.md 6.1). Values of Strict classes are *tracked*:
     * in Strict functions they carry no reference counts (the ownership checker proved who frees them), see [Tracking].
     */
    val strict: Boolean = false,
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
    /** Declared in Strict code: tracked values (instances of Strict classes) are not reference counted here. */
    val strict: Boolean = false,
    /**
     * Requested by the stdlib annotation `kotlin.internal.ReturnsBorrowed`: the result is a borrowed reference (no count
     * for the caller). A request: [Conventions] checks every function of the dispatch group and drops it when one of
     * them cannot honour it.
     */
    val returnsBorrowed: Boolean = false,
    /** Indices into [params] marked `kotlin.internal.TakesOwnership`: the callee owns those references (see [Conventions]). */
    val ownsParams: List<Int> = emptyList(),
    /**
     * `kotlin.internal.NoHeapEffects`: the function does not overwrite a heap reference that a caller's borrowed alias
     * can depend on (it may build and fill objects of its own); calls of it do not end the lifetime of aliases (RcEffects).
     * An assertion of the stdlib author, not checked.
     */
    val noHeapEffects: Boolean = false,
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
 * C interop scalars (docs/plan-kotlin.md section 7). They are plain values, never reference counted and never boxed
 * (a `Byte?` is an unsupported construct). Byte and Short are `int8_t` / `int16_t`, Float is `float`.
 */
object TByte : MirType("Byte")
object TShort : MirType("Short")
object TFloat : MirType("Float")

/**
 * A C pointer (`void *`): `CPointer<T>`, `CPointed` values (`IntVar`, structs) and C function pointers. A value type,
 * not reference counted. A nullable pointer is the same C value, NULL for `null`.
 */
data class TPtr(val nullable: Boolean) : MirType(if (nullable) "Ptr?" else "Ptr") {
    override fun toString(): String = displayName
}

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
    get() = this === TInt || this === TLong || this === TBoolean || this === TDouble || this === TChar || this === TFloat

/** Byte, Short and pointers: the value types that exist for C interop but cannot be boxed. */
val MirType.isInteropScalar: Boolean
    get() = this === TByte || this === TShort || this is TPtr

fun MirType.nonNull(): MirType = when (this) {
    TNString -> TString
    is TPtr -> TPtr(false)
    is TObject -> if (nullable) copy(nullable = false) else this
    is TBoxed -> prim
    else -> this
}

fun MirType.asNullable(): MirType = when (this) {
    TString -> TNString
    is TPtr -> TPtr(true)
    is TObject -> if (nullable) this else copy(nullable = true)
    TInt, TLong, TBoolean, TDouble, TChar, TFloat -> TBoxed(this)
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
    /**
     * Where the statement came from (1-based line in [srcFile]); 0 when unknown. Only set when the producer is asked for
     * line information (`--line-directives`): the C backend then writes `#line` directives. Not part of the MIR text.
     */
    var srcLine: Int = 0
    var srcFile: String? = null

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
    /** `*(T *)ptr = value` where `T` is the C type of the value's type (C interop; any alignment, no aliasing rules). */
    class Store(val ptr: MirExpr, val value: MirExpr) : MirStmt()
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

    /** Cache of [mirChildren] (the analyses walk the same trees many times; computing the operand list allocates). */
    var childCache: List<MirExpr>? = null

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

    /**
     * Allocates an instance and runs the constructor [ctorCName]. Owned result (reference counted) unless the
     * allocation happens in a Strict function and the class is Strict (a tracked value, see [Tracking]).
     * [allocator]: an expression of type Long (the address of the `MkAllocator`) the object is allocated from
     * (`mk_alloc_with`); null = the default (reference counting) allocator. [auto]: Strict code, no allocator is
     * visible and the value does not escape (docs/memory-model.md 2.1, "stack"): the local that receives the value
     * frees it at the end of its scope.
     */
    class New(
        val ctorCName: String,
        val args: List<MirExpr>,
        override val type: MirType,
        val allocator: MirExpr? = null,
        val auto: Boolean = false,
    ) : MirExpr()

    /** The singleton of an `object`, created on first access. Borrowed (the singleton is immortal). */
    class ObjectGet(val classCName: String, override val type: MirType) : MirExpr()

    /** `String.length` (Int). */
    class StringLength(val operand: MirExpr) : MirExpr() { override val type: MirType get() = TInt }

    /** A value of [type] that is never actually used (dead code after return/break). */
    class Unreachable(override val type: MirType) : MirExpr()

    /** The exception being handled; only valid as the first `Declare` of a [MirCatch] body. Owned. */
    class CaughtException(override val type: MirType) : MirExpr()

    /** `*(T *)ptr` for the scalar type [type] (C interop; any alignment, no aliasing rules). */
    class Load(val ptr: MirExpr, override val type: MirType) : MirExpr()

    /** `(char *)ptr + bytes`; [bytes] is a Long. The result has the type of [ptr]. */
    class PtrAdd(val ptr: MirExpr, val bytes: MirExpr) : MirExpr() { override val type: MirType get() = ptr.type }

    /** Call of the C function [extern] (a function of [MirModule.externs]); the type is its return type. */
    class CCall(val extern: MirExtern, val args: List<MirExpr>) : MirExpr() {
        override val type: MirType get() = extern.returnType
    }

    /** Call through a C function pointer [callee] (`CPointer<CFunction<...>>.invoke`); the types are those of the C signature. */
    class CCallIndirect(
        val callee: MirExpr,
        val args: List<MirExpr>,
        val paramTypes: List<MirType>,
        override val type: MirType,
    ) : MirExpr()

    /** The address of the function [cName] of this module as a C function pointer (`staticCFunction`). */
    class FuncAddr(val cName: String) : MirExpr() { override val type: MirType get() = TPtr(false) }

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
    is MirExpr.RuntimeCall -> !NativeConventions.resultBorrowed(cName)
    is MirExpr.Call, is MirExpr.VCall, is MirExpr.ICall, is MirExpr.New, is MirExpr.Box,
    is MirExpr.CaughtException -> true
    else -> false
}

/**
 * Ownership conventions of runtime functions that differ from the default (borrowed arguments, owned result):
 * `mk_ref_array_get_b` returns a borrowed reference, `mk_ref_array_set_o` takes ownership of the stored value.
 */
object NativeConventions {
    fun resultBorrowed(name: String): Boolean = name == "mk_ref_array_get_b"

    /** Indices of the arguments the runtime function takes ownership of (a +1 reference the caller hands over), or null. */
    fun ownedArgs(name: String): IntArray? = if (name == "mk_ref_array_set_o") OWNED_SET else null

    private val OWNED_SET = intArrayOf(2)
}
