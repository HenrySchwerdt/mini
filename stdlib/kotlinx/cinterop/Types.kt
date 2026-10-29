/*
 * C interop, the builtin types (docs/plan-kotlin.md section 7, compiler/README.md "C interop").
 *
 * The API follows Kotlin/Native's kotlinx.cinterop. Everything declared here lives in C memory and is represented by
 * its address: `CPointer<T>`, `NativePtr`, every `CPointed` (`IntVar`, a `@CStruct` class ...) and `CFunction` are one
 * machine pointer in the generated code (MIR type `Ptr`), never an object, never reference counted. The compiler knows
 * these classes by name (the file is a builtin file: it has no bodies and is never translated).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "NON_MEMBER_FUNCTION_NO_BODY", "EXPECTED_DECLARATION_WITH_BODY")
@file:kotlin.internal.Builtin

package kotlinx.cinterop

/** A raw machine address. */
public class NativePtr private constructor()

/** Something that lives in C memory; represented by its address (`CPointed.rawPtr`). */
public abstract class CPointed

/** Alias of the Kotlin/Native name of [CPointed]. */
public typealias NativePointed = CPointed

/** A typed C pointer (`T *`); `null` is the null pointer. */
public class CPointer<out T : CPointed> private constructor()

/** `void *`. */
public typealias COpaquePointer = CPointer<CPointed>

/** A pointer to the first of several adjacent elements (`T *` used as an array). */
public typealias CArrayPointer<T> = CPointer<T>

/** The pointee of a `COpaquePointer`. */
public abstract class COpaque : CPointed()

/** A C function; `CPointer<CFunction<(Int) -> Int>>` is a function pointer (`int (*)(int)`). */
public class CFunction<T : Function<*>> : CPointed()

/** A variable in C memory: its address is the address of the value. */
public abstract class CVariable : CPointed()

public abstract class CPrimitiveVar : CVariable()

/** A C `struct`: the fields are the properties of the (external) subclass, see `@CStruct`. */
public abstract class CStructVar : CVariable()

/** `int8_t` / `char`. */
public class ByteVar : CPrimitiveVar()
/** `int16_t`. */
public class ShortVar : CPrimitiveVar()
/** `int32_t` / `int`. */
public class IntVar : CPrimitiveVar()
/** `int64_t` / `long long`. */
public class LongVar : CPrimitiveVar()
/** `float`. */
public class FloatVar : CPrimitiveVar()
/** `double`. */
public class DoubleVar : CPrimitiveVar()
/** C `bool` / `_Bool`: one byte. */
public class BooleanVar : CPrimitiveVar()
/** `T *` stored in memory (a pointer variable): `.value` is the pointer. */
public class CPointerVar<T : CPointed> : CPrimitiveVar()

public typealias COpaquePointerVar = CPointerVar<CPointed>
public typealias CArrayPointerVar<T> = CPointerVar<T>
