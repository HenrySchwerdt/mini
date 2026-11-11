/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * KType, KTypeProjection, KVariance, KTypeParameter, KClassifier and typeOf (kotlin-stdlib, package kotlin.reflect).
 * Mini has no reflection: `typeOf<T>()` is lowered by the compiler to a KTypeImpl built from the static type, which is
 * spelled as text (the format of KType.toString of kotlin-stdlib: `kotlin.collections.Map<kotlin.String, *>?`).
 */
package kotlin.reflect

/** A classifier of types: a class (KClass) or a type parameter (KTypeParameter). */
public interface KClassifier

public interface KType : KAnnotatedElement {
    /** The classifier of this type: the class (or type parameter) named by it, for `kotlin.collections.List<kotlin.Int>?` the class `List`. */
    public val classifier: KClassifier?

    /** The type arguments of this type, `[kotlin.Int]` for `List<Int>`. */
    public val arguments: List<KTypeProjection>

    /** Is this type marked nullable (`Int?`)? */
    public val isMarkedNullable: Boolean
}

public enum class KVariance {
    INVARIANT,
    IN,
    OUT,
}

public data class KTypeProjection constructor(
    public val variance: KVariance?,
    public val type: KType?
) {
    init {
        require((variance == null) == (type == null)) {
            if (variance == null) "Star projection must have no type specified."
            else "The projection variance $variance requires type to be specified."
        }
    }

    override fun toString(): String = when (variance) {
        null -> "*"
        KVariance.INVARIANT -> type.toString()
        KVariance.IN -> "in $type"
        KVariance.OUT -> "out $type"
    }

    public companion object {
        internal val star: KTypeProjection = KTypeProjection(null, null)

        /** The star projection, denoted `*` in the source. */
        public val STAR: KTypeProjection get() = star

        public fun invariant(type: KType): KTypeProjection = KTypeProjection(KVariance.INVARIANT, type)

        public fun contravariant(type: KType): KTypeProjection = KTypeProjection(KVariance.IN, type)

        public fun covariant(type: KType): KTypeProjection = KTypeProjection(KVariance.OUT, type)
    }
}

public interface KTypeParameter : KClassifier {
    public val name: String
    public val upperBounds: List<KType>
    public val variance: KVariance
    public val isReified: Boolean
}

/** Returns a runtime representation of the type [T]; lowered by the compiler (the text of the static type of `T` is spelled in). */
public inline fun <reified T> typeOf(): KType = throw UnsupportedOperationException("typeOf is lowered by the compiler")

/** `cast` / `safeCast` of a class: the value if it is an instance of the class, else a ClassCastException / null. */
@Suppress("UNCHECKED_CAST")
public fun <T : Any> KClass<T>.cast(value: Any?): T {
    if (!isInstance(value)) throw ClassCastException("Value cannot be cast to " + qualifiedName)
    return value as T
}

@Suppress("UNCHECKED_CAST")
public fun <T : Any> KClass<T>.safeCast(value: Any?): T? = if (isInstance(value)) value as T else null

// ---- the implementation of typeOf -------------------------------------------------------------------------------------------

/**
 * What `typeOf<T>()` evaluates to. [text] is the type in the format of KType.toString (`kotlin.collections.Map<kotlin.String, *>?`),
 * a type parameter that the call could not substitute is spelled `@T`. Function types are `(A, B) -> R`, nullable ones `((A) -> R)?`.
 */
internal class KTypeImpl(private val text: String) : KType {
    private var nullableCache = -1
    private var base: String? = null
    private var args: List<KTypeProjection>? = null

    private fun nullablePart(): Boolean {
        if (nullableCache < 0) {
            var t = text
            var nullable = false
            if (t.endsWith("?")) {
                nullable = true
                t = t.substring(0, t.length - 1)
                // `((A) -> R)?`: the parentheses only delimit the function type
                if (t.startsWith("(") && matching(t, 0) == t.length - 1) t = t.substring(1, t.length - 1)
            }
            nullableCache = if (nullable) 1 else 0
            base = t
        }
        return nullableCache == 1
    }

    override val isMarkedNullable: Boolean
        get() = nullablePart()

    override val classifier: KClassifier?
        get() {
            nullablePart()
            val b = base!!
            if (b.startsWith("(")) return KClassImpl<Any>("kotlin.Function" + (arguments.size - 1))
            val lt = typeArgsStart(b)
            val name = if (lt < 0) b else b.substring(0, lt)
            if (name.startsWith("@")) return KTypeParameterImpl(name.substring(1))
            return KClassImpl<Any>(name)
        }

    override val arguments: List<KTypeProjection>
        get() {
            val cached = args
            if (cached != null) return cached
            nullablePart()
            val b = base!!
            val result = ArrayList<KTypeProjection>()
            if (b.startsWith("(")) {
                val close = matching(b, 0)
                for (p in splitTop(b.substring(1, close))) result.add(KTypeProjection.invariant(KTypeImpl(p)))
                result.add(KTypeProjection.invariant(KTypeImpl(b.substring(close + 5))))   // " -> "
            } else {
                val lt = typeArgsStart(b)
                if (lt >= 0) {
                    for (p in splitTop(b.substring(lt + 1, b.length - 1))) {
                        result.add(
                            when {
                                p == "*" -> KTypeProjection.STAR
                                p.startsWith("in ") -> KTypeProjection.contravariant(KTypeImpl(p.substring(3)))
                                p.startsWith("out ") -> KTypeProjection.covariant(KTypeImpl(p.substring(4)))
                                else -> KTypeProjection.invariant(KTypeImpl(p))
                            }
                        )
                    }
                }
            }
            args = result
            return result
        }

    override fun equals(other: Any?): Boolean = other is KTypeImpl && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = if (text.indexOf('@') < 0) text else text.replace("@", "")

    private companion object {
        /** The index of the `<` that opens the type arguments of [s] (`-1` when there are none). */
        fun typeArgsStart(s: String): Int = s.indexOf('<')

        /** The index of the `)` that closes the `(` at [open]. */
        fun matching(s: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (c == '(') depth++
                else if (c == ')') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** [s] split at the commas that are not inside `<>` or `()`. */
        fun splitTop(s: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var start = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '<' || c == '(') depth++
                else if (c == ')') depth--
                else if (c == '>' && (i == 0 || s[i - 1] != '-')) depth--
                else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i).trim())
                    start = i + 1
                }
                i++
            }
            val last = s.substring(start).trim()
            if (last.isNotEmpty()) parts.add(last)
            return parts
        }
    }
}

/** The classifier of a type parameter that `typeOf` could not substitute (`typeOf<List<T>>()` in a function that is not inline). */
internal class KTypeParameterImpl(override val name: String) : KTypeParameter {
    override val upperBounds: List<KType>
        get() = listOf(KTypeImpl("kotlin.Any?"))

    override val variance: KVariance
        get() = KVariance.INVARIANT

    override val isReified: Boolean
        get() = false

    override fun equals(other: Any?): Boolean = other is KTypeParameterImpl && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name
}
