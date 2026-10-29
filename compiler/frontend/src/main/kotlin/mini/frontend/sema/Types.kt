package mini.frontend.sema

/*
 * The semantic type model of bootstrap stage B2-2. Types are immutable trees; equality is structural and decided by
 * [typeEquals] (no equals/hashCode overrides, so nothing depends on identity hashing). The rendering ([renderType]) is
 * the canonical text used by the oracle comparison: fully qualified class names, `<a,b>` without spaces, `?` for
 * nullability, function types as `(A, B) -> R` / `R.(A) -> R`.
 */

enum class Variance { INV, IN, OUT }

/** A type argument; [type] == null is the star projection. */
class TArg(val type: Type?, val variance: Variance) {
    val isStar: Boolean get() = type == null
}

sealed class Type {
    abstract val nullable: Boolean
    abstract fun withNullable(n: Boolean): Type
}

class ClassType(val cls: ClassSym, val args: List<TArg>, override val nullable: Boolean) : Type() {
    override fun withNullable(n: Boolean): Type = if (n == nullable) this else ClassType(cls, args, n)
}

class TypeParamType(val tp: TypeParamSym, override val nullable: Boolean, val definitelyNotNull: Boolean) : Type() {
    override fun withNullable(n: Boolean): Type = if (n == nullable) this else TypeParamType(tp, n, definitelyNotNull && !n)
}

/** `(P1, P2) -> R`, `R.(P) -> R2`, `suspend () -> R`. Structural; its supertypes are `Function<R>` and `Any`. */
class FunType(val receiver: Type?, val params: List<Type>, val ret: Type, override val nullable: Boolean, val suspend: Boolean) : Type() {
    override fun withNullable(n: Boolean): Type = if (n == nullable) this else FunType(receiver, params, ret, n, suspend)
    val arity: Int get() = params.size + (if (receiver != null) 1 else 0)
}

/** Intersection of types, from smart casts. Never nullable itself. */
class IsectType(val parts: List<Type>) : Type() {
    override val nullable: Boolean get() = false
    override fun withNullable(n: Boolean): Type = this
}

/** An unresolved or unsupported construct. Compatible with everything, so errors do not cascade. */
class ErrType(val reason: String) : Type() {
    override val nullable: Boolean get() = true
    override fun withNullable(n: Boolean): Type = this
}

/** An inference variable (see Infer.kt). */
class VarType(val v: TypeVariable, override val nullable: Boolean) : Type() {
    override fun withNullable(n: Boolean): Type = if (n == nullable) this else VarType(v, n)
}

/** Type of an integer literal before its final type is known: a subtype of Int, Long, Short and Byte as the value permits. */
class IntLiteralType(val value: Long, val fitsInt: Boolean) : Type() {
    override val nullable: Boolean get() = false
    override fun withNullable(n: Boolean): Type = this
}

// ---- rendering ----------------------------------------------------------------------------------------------------

fun renderArg(a: TArg): String {
    val t = a.type ?: return "*"
    return when (a.variance) {
        Variance.IN -> "in " + renderType(t)
        Variance.OUT -> "out " + renderType(t)
        Variance.INV -> renderType(t)
    }
}

fun renderType(t: Type): String {
    when (t) {
        is ClassType -> {
            val sb = StringBuilder(t.cls.renderName())
            if (t.args.isNotEmpty()) {
                sb.append('<')
                var first = true
                for (a in t.args) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(renderArg(a))
                }
                sb.append('>')
            }
            if (t.nullable) sb.append('?')
            return sb.toString()
        }
        is TypeParamType -> {
            val s = if (t.definitelyNotNull) t.tp.name + " & Any" else t.tp.name
            return if (t.nullable) s + "?" else s
        }
        is FunType -> {
            val sb = StringBuilder()
            if (t.suspend) sb.append("suspend ")
            val recv = t.receiver
            if (recv != null) {
                sb.append(renderType(recv)).append('.')
            }
            sb.append('(')
            var first = true
            for (p in t.params) {
                if (!first) sb.append(", ")
                first = false
                sb.append(renderType(p))
            }
            sb.append(") -> ").append(renderType(t.ret))
            return if (t.nullable) "(" + sb.toString() + ")?" else sb.toString()
        }
        is IsectType -> {
            val parts = ArrayList<String>()
            for (p in t.parts) parts.add(renderType(p))
            parts.sort()
            val sb = StringBuilder("(")
            var first = true
            for (p in parts) {
                if (!first) sb.append(" & ")
                first = false
                sb.append(p)
            }
            return sb.append(')').toString()
        }
        is ErrType -> return "<error>"
        is VarType -> return "<tv:" + t.v.tp.name + ">" + (if (t.nullable) "?" else "")
        is IntLiteralType -> return "ILT(" + t.value + ")"
    }
}

fun typeListEquals(a: List<Type>, b: List<Type>): Boolean {
    if (a.size != b.size) return false
    var i = 0
    while (i < a.size) {
        if (!typeEquals(a[i], b[i])) return false
        i++
    }
    return true
}

fun typeEquals(a: Type, b: Type): Boolean {
    if (a === b) return true
    if (a.nullable != b.nullable) return false
    when (a) {
        is ClassType -> {
            if (b !is ClassType || a.cls !== b.cls || a.args.size != b.args.size) return false
            var i = 0
            while (i < a.args.size) {
                if (!argEquals(a.args[i], b.args[i])) return false
                i++
            }
            return true
        }
        is TypeParamType -> return b is TypeParamType && a.tp === b.tp && a.definitelyNotNull == b.definitelyNotNull
        is FunType -> {
            if (b !is FunType || a.suspend != b.suspend) return false
            val ra = a.receiver
            val rb = b.receiver
            if ((ra == null) != (rb == null)) return false
            if (ra != null && rb != null && !typeEquals(ra, rb)) return false
            return typeListEquals(a.params, b.params) && typeEquals(a.ret, b.ret)
        }
        is IsectType -> {
            if (b !is IsectType || a.parts.size != b.parts.size) return false
            for (p in a.parts) {
                var found = false
                for (q in b.parts) if (typeEquals(p, q)) found = true
                if (!found) return false
            }
            return true
        }
        is ErrType -> return b is ErrType
        is VarType -> return b is VarType && a.v === b.v
        is IntLiteralType -> return b is IntLiteralType && a.value == b.value
    }
}

fun argEquals(a: TArg, b: TArg): Boolean {
    val x = a.type
    val y = b.type
    if (x == null || y == null) return x == null && y == null
    return a.variance == b.variance && typeEquals(x, y)
}

fun containsError(t: Type): Boolean {
    when (t) {
        is ErrType -> return true
        is ClassType -> {
            for (a in t.args) {
                val x = a.type
                if (x != null && containsError(x)) return true
            }
            return false
        }
        is FunType -> {
            val r = t.receiver
            if (r != null && containsError(r)) return true
            for (p in t.params) if (containsError(p)) return true
            return containsError(t.ret)
        }
        is IsectType -> {
            for (p in t.parts) if (containsError(p)) return true
            return false
        }
        else -> return false
    }
}
