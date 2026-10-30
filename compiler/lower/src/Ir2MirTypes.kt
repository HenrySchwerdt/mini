
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Ir2Mir: source locations, errors and the Kotlin type to MirType mapping. */

// ---- locations / errors -----------------------------------------------------------

internal fun Ir2Mir.location(e: IrElement): String {
    val off = e.startOffset
    if (off < 0) return fileName
    return fileName + ":" + currentFile.lineCol(off)
}

internal fun Ir2Mir.unsupported(e: IrElement, what: String): Nothing =
    throw UnsupportedConstruct("${location(e)}: error: unsupported construct: $what")

internal fun Ir2Mir.describe(e: IrElement): String {
    val n = e::class.simpleName ?: "element"
    return n.removePrefix("Ir")
}

// ---- types ------------------------------------------------------------------------


internal fun Ir2Mir.mirType(t: Type, at: IrElement): MirType {
    val nullable = t.isMarkedNullable()
    // type parameters are erased to their bound (reified ones inside an inline expansion are substituted)
    if (t is TypeParamType) {
        val classifier = t.tp
        val sub = typeSubst[classifier]
        if (sub != null) {
            val m = mirType(sub, at)
            return if (nullable && m.isRef.not() && m != TUnit && m != TNothing) m.asNullable() else if (nullable) m.asNullable() else m
        }
        return erasedBound(classifier, nullable, at)
    }
    val n = t.classFqName
    val prim: MirType? = when (n) {
        "kotlin.Int" -> TInt
        "kotlin.Long" -> TLong
        "kotlin.Boolean" -> TBoolean
        "kotlin.Double" -> TDouble
        "kotlin.Char" -> TChar
        "kotlin.Byte" -> TByte
        "kotlin.Short" -> TShort
        "kotlin.Float" -> TFloat
        else -> null
    }
    if (prim != null) {
        if (prim.isInteropScalar && nullable) unsupported(at, "nullable type '$n?' (Byte and Short cannot be boxed; use a non-null type)")
        return if (nullable) TBoxed(prim) else prim
    }
    val ptrClass = t.classOrNull
    if (ptrClass != null && isPtrClass(ptrClass)) return TPtr(nullable)
    functionArity(t)?.let { return functionInterface(it).let { f -> if (nullable) f.copy(nullable = true) else f } }
    arrayKindOfFq(n)?.let { return arrayType(it, nullable) }
    return when (n) {
        "kotlin.String", "kotlin.CharSequence" -> if (nullable) TNString else TString
        "kotlin.Unit" -> if (nullable) unsupported(at, "nullable type 'Unit?'") else TUnit
        "kotlin.Nothing" -> if (nullable) TNull else TNothing
        // Comparable values are boxed primitives, strings or user objects: represented as Any (compareTo: mk_compare_to)
        "kotlin.Any", "kotlin.Comparable" -> if (nullable) anyNullable else anyType
        else -> {
            val c = t.classOrNull
            if (c != null && (c in classInfos || builtinOf(c) != null)) typeOf(c, nullable) else unsupported(at, "type '${n ?: t}'")
        }
    }
}

/** The erasure of a type parameter: its first supported bound, else `Any?`. */
internal fun Ir2Mir.erasedBound(p: TypeParamSym, nullable: Boolean, at: IrElement): MirType {
    for (b in p.bounds) {
        val bn = b.classFqName
        val bc = b.classOrNull
        if (bc != null && isPtrClass(bc)) return TPtr(nullable || b.isMarkedNullable())
        val supported = b !is TypeParamType &&
            bn != "kotlin.Comparable" && (bn == "kotlin.String" || bn == "kotlin.CharSequence" || arrayKindOfFq(bn) != null || (bc != null && (bc in classInfos || builtinOf(bc) != null)) || functionArity(b) != null)
        if (!supported) continue
        val m = mirType(b, at)
        return if (nullable || b.isMarkedNullable()) m.asNullable() else m
    }
    return anyNullable
}
