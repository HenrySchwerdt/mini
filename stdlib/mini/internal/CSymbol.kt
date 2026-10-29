package mini.internal

import kotlin.annotation.AnnotationTarget.*

/**
 * Intrinsic convention (docs/stdlib.md): a function, member function or property getter annotated with
 * `@CSymbol("mk_name")` is implemented by the C function `mk_name` of libminirt. The compiler turns
 * every call into a plain C call of that symbol:
 *  - receiver first (if any), then the parameters, in order;
 *  - Int/Long/Boolean/Double/Char map to MkInt/MkLong/MkBoolean/MkDouble/MkChar;
 *  - String is `const MkString *`, every other reference type `MkObject *`;
 *  - arguments are borrowed; a reference result is owned (+1) and must be returned retained;
 *  - the declaration is `external` (no body) or sits in a `@file:kotlin.internal.Builtin` file.
 */
@Target(FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, PROPERTY, CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
public annotation class CSymbol(val name: String)
