package mini.internal

/** Fully qualified Kotlin name of the runtime type of [o] (`MkTypeInfo.name`). */
@CSymbol("mk_type_name")
public external fun typeName(o: Any): String
