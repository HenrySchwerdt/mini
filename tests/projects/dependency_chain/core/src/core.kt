package core

/** Outcome of a check: ok or a list of problems. */
class Report(val problems: List<Problem>) {
    val ok: Boolean get() = problems.isEmpty()
    operator fun plus(o: Report) = Report(problems + o.problems)
    override fun toString() = if (ok) "ok" else problems.joinToString("; ")

    companion object {
        val OK = Report(emptyList())
        fun of(path: String, message: String) = Report(listOf(Problem(path, message)))
    }
}

data class Problem(val path: String, val message: String) {
    override fun toString() = "$path: $message"
}

interface Rule<in T> {
    val name: String
    fun check(value: T, path: String): Report
}

inline fun <T> rule(name: String, crossinline message: (T) -> String, crossinline test: (T) -> Boolean): Rule<T> = object : Rule<T> {
    override val name = name
    override fun check(value: T, path: String) = if (test(value)) Report.OK else Report.of(path, message(value))
}

class All<T>(private val rules: List<Rule<T>>) : Rule<T> {
    override val name = rules.joinToString("+") { it.name }
    override fun check(value: T, path: String): Report = rules.fold(Report.OK) { acc, r -> acc + r.check(value, path) }
}

fun <T> allOf(vararg rules: Rule<T>): Rule<T> = All(rules.toList())

/** Checks a field of a bigger structure. */
inline fun <T, F> Rule<F>.on(crossinline select: (T) -> F, field: String): Rule<T> = object : Rule<T> {
    override val name = "$field:${this@on.name}"
    override fun check(value: T, path: String) = this@on.check(select(value), if (path.isEmpty()) field else "$path.$field")
}

internal const val VERSION = "core-1.0"
fun coreVersion() = VERSION
