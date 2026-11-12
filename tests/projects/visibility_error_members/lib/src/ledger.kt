package ledger

private const val LIMIT = 100
private val audit = ArrayList<String>()
private fun record(s: String) { audit.add(s) }

internal typealias Cents = Long
internal val rate: Int = 3
internal fun String.shout() = uppercase() + "!"
internal object Registry { fun size() = audit.size }

open class Account internal constructor(val owner: String) {
    var balance: Long = 0
        private set
    protected open fun fee(): Long = 1
    internal fun audit(): String = owner + ":" + balance

    fun deposit(n: Long): Account {
        if (n > LIMIT) record("big " + n)
        balance += n - fee()
        return this
    }

    class Inner internal constructor(val v: Int)
    private class Secret(val v: Int)

    companion object {
        fun open(owner: String) = Account(owner)
        internal fun hidden() = Account("hidden")
    }
}

enum class Kind { A, B }

fun use(): Int = Registry.size() + rate + "x".shout().length
