
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
    // the companion objects of the primitive types and String are not classes of the program: such a value is a null reference
    if (n != null && isPrimitiveCompanion(n)) return anyNullable
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
        return if (nullable) TBoxed(prim) else prim
    }
    val ptrClass = t.classOrNull
    if (ptrClass != null && isPtrClass(ptrClass)) return TPtr(nullable)
    functionArity(t)?.let { return functionInterface(it).let { f -> if (nullable) f.copy(nullable = true) else f } }
    val arrKind = arrayKindOfFq(n)
    if (arrKind != null) return arrayTypeOf(arrKind, t, nullable, at)
    return when (n) {
        "kotlin.String" -> if (nullable) TNString else TString
        "kotlin.Unit" -> if (nullable || at is IrValueParameter || at is IrField) anyNullable else TUnit // a Unit parameter or field holds null
        "kotlin.Nothing" -> if (nullable) TNull else TNothing
        // Comparable values are boxed primitives, strings or user objects: represented as Any (compareTo: mk_compare_to)
        // CharSequence values are a String or an object of a class that implements the interface (mk_cs_*): Any as well
        // (an `Enum<*>` value is an object of some enum class: its members read the hidden fields every enum class starts with)
        "kotlin.Any", "kotlin.Comparable", "kotlin.Number", "kotlin.CharSequence", "kotlin.Enum" -> if (nullable) anyNullable else anyType
        else -> {
            val c = t.classOrNull
            val inl = if (c != null && c in classInfos) inlineTypeOf(c) else null
            if (inl != null) (if (nullable) TBoxed(inl) else inl)
            else if (c != null && (c in classInfos || builtinOf(c) != null)) typeOf(c, nullable) else unsupported(at, "type '${n ?: t}'")
        }
    }
}

/**
 * What an `Array<E>` type knows about the element type its array was created with (see [TObject.elem]): the element's MIR type
 * and whether the view is covariant (`Array<out E>`), or null when the element type is not one of a class, interface, `String` or a boxed
 * primitive, or is not known: a type parameter that is not substituted (the array may have been created for any subtype), a nested array,
 * a star or contravariant projection.
 */
internal fun Ir2Mir.arrayTypeOf(k: ArrayKind, t: Type, nullable: Boolean, at: IrElement): TObject {
    val plain = arrayType(k, nullable)
    if (k.elem != null || t !is ClassType || t.args.isEmpty()) return plain
    val arg = t.args[0]
    val et = arg.type
    if (et == null || arg.variance == Variance.IN || substituted(et) is TypeParamType) return plain
    val m: MirType? = try {
        mirType(et, at)
    } catch (e: UnsupportedConstruct) {
        null
    }
    val tracked = when (m) {
        null -> false
        is TObject -> arrayKindOf(m) == null
        TString, TNString -> true
        is TBoxed -> m.prim !is TInline
        TInt, TLong, TBoolean, TDouble, TChar, TByte, TShort, TFloat -> true
        else -> false
    }
    return if (tracked) plain.copy(elem = m, elemOut = arg.variance == Variance.OUT) else plain
}

/** The erasure of a type parameter: its first supported bound, else `Any?`. */
internal fun Ir2Mir.erasedBound(p: TypeParamSym, nullable: Boolean, at: IrElement): MirType {
    for (b in p.bounds) {
        val bn = b.classFqName
        val bc = b.classOrNull
        if (bc != null && isPtrClass(bc)) return TPtr(nullable || b.isMarkedNullable())
        val supported = b !is TypeParamType &&
            bn != "kotlin.Comparable" && bn != "kotlin.CharSequence" && (bn == "kotlin.String" || arrayKindOfFq(bn) != null || (bc != null && (bc in classInfos || builtinOf(bc) != null)) || functionArity(b) != null)
        if (!supported) continue
        val m = mirType(b, at)
        return if (nullable || b.isMarkedNullable()) m.asNullable() else m
    }
    return anyNullable
}

private val primitiveCompanionNames = setOf("Int", "Long", "Short", "Byte", "Char", "Boolean", "Double", "Float", "String", "Enum").map { "kotlin.$it.Companion" }.toSet()

/** `kotlin.Int.Companion` and the like: declared in the built-in sources, never translated. */
internal fun isPrimitiveCompanion(fq: String): Boolean = fq in primitiveCompanionNames
