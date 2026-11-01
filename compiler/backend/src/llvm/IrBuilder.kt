package mini.backend.llvm

/*
 * The small builder interface the LLVM lowering ([Lowering]) is written against (docs/plan-kotlin.md section 10g).
 *
 * Two implementations: [TextIrBuilder] prints textual IR (`LlvmEmitter.emit`, the `.ll` of phase 1) and the in-process
 * one calls the LLVM C API (compiler/llvm-c, `ApiIrBuilder`; phase 2). The interface is what both can do with the same
 * calls: scalar types, constants, globals, functions, blocks and the dozen instruction kinds the lowering uses. Values
 * ([V]) and basic blocks ([B]) are opaque to the lowering. Pure Kotlin like the rest of the backend: the API
 * implementation lives outside of this module because it needs C interop.
 *
 * Instruction methods take a result `name`; "" means "a fresh temporary" (`%tN` in the text, nameless in LLVM).
 */

/** The scalar IR types. `PTR` is the opaque `ptr`. */
enum class IrType { I1, I8, I16, I32, I64, FLOAT, DOUBLE, PTR, VOID }

/** Parameter / result attribute of the C ABI: `bool` and `uint16_t` are zero-extended, `int8_t` / `int16_t` sign-extended. */
enum class IrExt { NONE, ZERO, SIGN }

/** Function attributes. READONLY is reserved for exact runtime declarations proven to only read memory. */
enum class IrAttrs { NONE, STD, INLINE, NORETURN, PURE, PURE_INLINE, READONLY }

enum class IrLinkage { EXTERNAL, INTERNAL, PRIVATE }

/** Which part of the module a definition goes to (only the text output is ordered by it; the C strings and string literals come first). */
enum class IrSection { STRINGS, LITERALS, CLASSES, PRELUDE, FUNCTIONS }

enum class IrOp { ADD, SUB, MUL, SDIV, SREM, AND, OR, XOR, SHL, ASHR, LSHR, FADD, FSUB, FMUL, FDIV, FREM }

/** Integer (I...) and ordered / unordered floating point (F...) comparisons. */
enum class IrCmp { IEQ, INE, SLT, SLE, SGT, SGE, ULT, ULE, UGT, UGE, FOEQ, FUNE, FOLT, FOLE, FOGT, FOGE }

enum class IrCast { ZEXT, SEXT, TRUNC, SITOFP, UITOFP, FPEXT, FPTRUNC, INTTOPTR, PTRTOINT }

interface IrBuilder<V : Any, B : Any> {
    /** The value of an expression of type Unit / Nothing (never used as an operand). */
    val none: V

    // ---- module ----------------------------------------------------------------------------

    fun beginModule(sourceName: String, links: List<String>, target: LlvmTarget)

    /** `%name = type <{ fields }>`: a packed struct type that constants refer to by name. */
    fun defineNamedStruct(name: String, fields: List<IrType>)

    fun comment(s: IrSection, text: String)
    fun blankLine(s: IrSection)

    // ---- constants ---------------------------------------------------------------------------

    fun constInt(t: IrType, v: Long): V
    fun constFloat(t: IrType, v: Double): V
    fun constNull(): V
    fun constArray(elem: IrType, items: List<V>): V
    fun constPackedStruct(items: List<V>): V
    fun constNamedStruct(typeName: String, items: List<V>): V

    /** `[n x i8] c"text\00"`: the UTF-8 bytes of [text] and a NUL. */
    fun constCString(text: String): V

    // ---- globals -----------------------------------------------------------------------------

    /** The address of the global variable or function [name]; it has been declared or defined before. */
    fun global(name: String): V

    /** `@sym = external global %MkTypeInfo` (idempotent). */
    fun externTypeInfo(sym: String): V

    /** Declares the global `name` of the named struct type, defined later by [defineGlobal] (forward references). */
    fun declareGlobalOfStruct(name: String, typeName: String, linkage: IrLinkage)

    /** Defines a global (or completes a forward declaration): `constant` or variable, of the initializer's type, aligned (0: default). Returns its address. */
    fun defineGlobal(s: IrSection, name: String, init: V, constant: Boolean, linkage: IrLinkage, align: Int, unnamedAddr: Boolean): V

    // ---- functions -----------------------------------------------------------------------------

    /** Declares a function of the runtime / C (idempotent: the first declaration wins). */
    fun declareExternal(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, vararg: Boolean): V

    /** True if a function [name] has been declared with [declareExternal] or [declareDefined]. */
    fun hasFunction(name: String): Boolean

    /** Declares a function this module defines (idempotent), so that it can be referenced before its definition. */
    fun declareDefined(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, linkage: IrLinkage): V

    /** Starts the definition of [name]; the entry block is current. */
    fun beginFunction(
        s: IrSection, name: String, ret: IrType, retExt: IrExt, paramNames: List<String>, params: List<IrType>, exts: List<IrExt>,
        attrs: IrAttrs, linkage: IrLinkage,
    )

    /** The function being defined is exported from the wasm module under [name] (`"wasm-export-name"`); only the text builder does it. */
    fun exportCurrentFunction(name: String)

    fun param(i: Int): V
    fun entryBlock(): B
    fun endFunction()

    // ---- blocks -------------------------------------------------------------------------------

    fun newBlock(name: String): B

    /** Appends [b] to the function and makes it current. */
    fun appendBlock(b: B)

    // ---- instructions -------------------------------------------------------------------------

    /** A stack slot in the entry block (all slots are created there, whatever the current block is). */
    fun alloca(t: IrType, name: String): V

    fun load(t: IrType, ptr: V, align: Int, name: String): V
    fun store(v: V, ptr: V, align: Int)

    /**
     * [load] / [store] with a type-based alias tag (TBAA): accesses with different [tag]s never alias (the tags are flat, no
     * subtype relation), so the optimizer can keep a field, an array length or elements in registers across stores of other tags.
     * Natural alignment.
     */
    fun loadTagged(t: IrType, ptr: V, tag: String, name: String): V
    fun storeTagged(v: V, ptr: V, tag: String)

    /** `getelementptr i8, ptr base, i64 offset`. */
    fun gep(base: V, offset: V, inbounds: Boolean, name: String): V

    fun bin(op: IrOp, a: V, b: V, name: String): V
    fun cmp(pred: IrCmp, a: V, b: V, name: String): V
    fun fneg(a: V): V
    fun cast(op: IrCast, a: V, to: IrType, name: String): V
    fun select(c: V, a: V, b: V): V
    fun phi(t: IrType, v1: V, b1: B, v2: V, b2: B): V

    /** `llvm.fptosi.sat` from double to `i<bits>`. */
    fun fptosiSat(a: V, bits: Int): V

    /**
     * A call. The function type is `ret (types of the first [fixed] args or of all args if fixed < 0 [, ...])`; [exts] are the
     * ABI attributes of the arguments, [retExt] the one of the result. A void call returns [none].
     */
    fun call(callee: V, ret: IrType, retExt: IrExt, args: List<V>, exts: List<IrExt>, fixed: Int, name: String): V

    fun br(target: B)
    fun condBr(c: V, t: B, f: B, cold: Boolean)
    fun ret(v: V)
    fun retVoid()
    fun unreachable()

    // ---- the end -------------------------------------------------------------------------------

    /** The textual IR (text builder only; the API builder has no text and returns ""). */
    fun finish(): String
}
