package validk

@DslMarker
annotation class ValidationDsl

@JvmInline
value class FieldName(val raw: String) {
    val pretty: String get() = raw.replaceFirstChar { it.uppercase() }
    override fun toString() = pretty
}

data class Violation(val field: FieldName, val message: String)

class Report internal constructor(private val items: List<Violation>) : Iterable<Violation> {
    val isValid: Boolean get() = items.isEmpty()
    val size: Int get() = items.size
    override fun iterator(): Iterator<Violation> = items.iterator()
    operator fun contains(field: String): Boolean = items.any { it.field.raw == field }
    fun byField(): Map<FieldName, List<String>> = items.groupBy({ it.field }, { it.message })
    override fun toString(): String = if (isValid) "valid" else items.joinToString("; ") { it.field.toString() + ": " + it.message }
}

fun interface Rule<in T> {
    fun check(value: T): String?
}

infix fun <T> Rule<T>.and(other: Rule<T>): Rule<T> = Rule { v -> check(v) ?: other.check(v) }

fun <T, R> Rule<R>.on(selector: (T) -> R): Rule<T> = Rule { v -> check(selector(v)) }

object Rules {
    val notBlank = Rule<String> { if (it.isBlank()) "must not be blank" else null }
    fun minLength(n: Int) = Rule<String> { if (it.length < n) "needs at least " + n + " characters" else null }
    fun <N : Comparable<N>> between(lo: N, hi: N) = Rule<N> { if (it < lo || it > hi) "must be in " + lo + ".." + hi else null }
    fun <T> oneOf(vararg allowed: T) = Rule<T> { if (it !in allowed) "must be one of " + allowed.toList() else null }
    internal val secret = Rule<String> { if (it == "hunter2") "too well known" else null }
    val password: Rule<String> = minLength(6) and secret
}

@ValidationDsl
class FieldScope<T> internal constructor(private val name: FieldName, private val value: T, private val sink: MutableList<Violation>) {
    fun rule(r: Rule<T>) {
        val m = r.check(value)
        if (m != null) sink.add(Violation(name, m))
    }

    fun require(message: String, ok: (T) -> Boolean) {
        if (!ok(value)) sink.add(Violation(name, message))
    }

    inline fun check(r: Rule<T>, vararg more: Rule<T>) {
        rule(r)
        for (m in more) rule(m)
    }
}

@ValidationDsl
class Validator<T> @PublishedApi internal constructor(private val subject: T) {
    private val violations = ArrayList<Violation>()

    fun <F> field(name: String, selector: (T) -> F, block: FieldScope<F>.() -> Unit) {
        FieldScope(FieldName(name), selector(subject), violations).block()
    }

    inline fun <reified F> typed(name: String, value: Any?, noinline block: FieldScope<F>.() -> Unit) {
        if (value is F) field(name, { _ -> value }, block) else fail(name, "expected " + F::class.simpleName)
    }

    fun fail(name: String, message: String) {
        violations.add(Violation(FieldName(name), message))
    }

    @PublishedApi internal fun build(): Report = Report(violations.toList())
}

inline fun <T> validate(subject: T, block: Validator<T>.() -> Unit): Report {
    val v = Validator(subject)
    v.block()
    return v.build()
}

fun <T> Iterable<T>.validateAll(block: Validator<T>.() -> Unit): List<Report> = map { validate(it, block) }

fun Report.firstOrNull(): Violation? = iterator().let { if (it.hasNext()) it.next() else null }
