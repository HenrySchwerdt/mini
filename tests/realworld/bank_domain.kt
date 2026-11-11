// A banking domain model: interfaces with default methods, abstract classes, generics with bounds, custom exceptions,
// value classes for money, a transaction journal and invariants checked with `require` / `check`.

@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(o: Money) = Money(cents + o.cents)
    operator fun minus(o: Money) = Money(cents - o.cents)
    operator fun times(n: Int) = Money(cents * n)
    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)
    override fun toString(): String = (if (cents < 0) "-" else "") + "$" + (kotlin.math.abs(cents) / 100) + "." + (kotlin.math.abs(cents) % 100).toString().padStart(2, '0')

    companion object {
        val ZERO = Money(0)
        fun of(dollars: Int, cents: Int = 0) = Money(dollars * 100L + cents)
    }
}

class InsufficientFunds(val needed: Money, val available: Money) : Exception("need $needed but only $available available")
class AccountFrozen(id: String) : Exception("account $id is frozen")

interface Auditable {
    val journal: MutableList<String>
    fun record(msg: String) {
        journal.add("#${journal.size + 1} $msg")
    }
}

sealed class Tx(val amount: Money) {
    class Deposit(amount: Money) : Tx(amount)
    class Withdrawal(amount: Money) : Tx(amount)
    class Transfer(amount: Money, val to: String) : Tx(amount)
    class Fee(amount: Money, val reason: String) : Tx(amount)
}

abstract class Account(val id: String, val owner: String) : Auditable {
    override val journal = ArrayList<String>()
    protected var balanceCents = 0L
    var frozen = false
    val history = ArrayList<Tx>()
    val balance: Money get() = Money(balanceCents)

    abstract val overdraftLimit: Money
    abstract fun monthlyFee(): Money

    fun deposit(m: Money) {
        require(m > Money.ZERO) { "deposit must be positive: $m" }
        if (frozen) throw AccountFrozen(id)
        balanceCents += m.cents
        history.add(Tx.Deposit(m))
        record("deposit $m -> balance $balance")
    }

    fun withdraw(m: Money) {
        require(m > Money.ZERO) { "withdrawal must be positive: $m" }
        if (frozen) throw AccountFrozen(id)
        val available = balance + overdraftLimit
        if (m > available) throw InsufficientFunds(m, available)
        balanceCents -= m.cents
        history.add(Tx.Withdrawal(m))
        record("withdraw $m -> balance $balance")
    }

    fun charge(m: Money, reason: String) {
        balanceCents -= m.cents
        history.add(Tx.Fee(m, reason))
        record("fee $m ($reason)")
    }

    fun endOfMonth() {
        val fee = monthlyFee()
        if (fee > Money.ZERO) charge(fee, "monthly fee")
        onMonthEnd()
    }

    protected open fun onMonthEnd() {}
    override fun toString() = "${this::class.simpleName}($id, $owner, $balance)"
}

class Checking(id: String, owner: String) : Account(id, owner) {
    override val overdraftLimit = Money.of(100)
    override fun monthlyFee() = if (balance < Money.of(500)) Money.of(5) else Money.ZERO
}

class Savings(id: String, owner: String, private val ratePermille: Int) : Account(id, owner) {
    override val overdraftLimit = Money.ZERO
    override fun monthlyFee() = Money.ZERO
    override fun onMonthEnd() {
        val interest = Money(balanceCents * ratePermille / 1000)
        if (interest > Money.ZERO) {
            balanceCents += interest.cents
            record("interest $interest")
        }
    }
}

interface Repository<K : Comparable<K>, V> {
    fun save(key: K, value: V)
    fun find(key: K): V?
    fun all(): List<V>
}

class MemoryRepository<K : Comparable<K>, V> : Repository<K, V> {
    private val items = HashMap<K, V>()
    override fun save(key: K, value: V) {
        items[key] = value
    }

    override fun find(key: K): V? = items[key]
    override fun all(): List<V> = items.toSortedMap().values.toList()
}

class Bank(private val repo: Repository<String, Account>) {
    fun open(account: Account) = account.also { repo.save(it.id, it) }

    fun transfer(from: String, to: String, m: Money) {
        val a = repo.find(from) ?: throw NoSuchElementException("no account $from")
        val b = repo.find(to) ?: throw NoSuchElementException("no account $to")
        a.withdraw(m)
        try {
            b.deposit(m)
        } catch (e: AccountFrozen) {
            a.deposit(m)
            throw e
        }
        a.history[a.history.size - 1] = Tx.Transfer(m, to)
    }

    fun total(): Money = repo.all().fold(Money.ZERO) { acc, a -> acc + a.balance }
    fun report(): String = repo.all().joinToString("\n") { "  $it" }
}

fun attempt(label: String, block: () -> Unit) {
    try {
        block()
        println("ok: $label")
    } catch (e: InsufficientFunds) {
        println("insufficient funds: $label (${e.message})")
    } catch (e: AccountFrozen) {
        println("frozen: $label (${e.message})")
    } catch (e: IllegalArgumentException) {
        println("invalid: $label (${e.message})")
    } catch (e: NoSuchElementException) {
        println("missing: $label (${e.message})")
    }
}

fun main() {
    val bank = Bank(MemoryRepository())
    val alice = bank.open(Checking("CH-1", "alice"))
    val bob = bank.open(Savings("SV-1", "bob", 15))
    val carol = bank.open(Checking("CH-2", "carol"))
    alice.deposit(Money.of(250))
    bob.deposit(Money.of(1000, 50))
    attempt("transfer 300 alice->bob") { bank.transfer("CH-1", "SV-1", Money.of(300)) }
    attempt("transfer 100 alice->bob") { bank.transfer("CH-1", "SV-1", Money.of(100)) }
    attempt("withdraw 1 from savings overdraft") { bob.withdraw(Money.of(5000)) }
    attempt("negative deposit") { carol.deposit(Money(-5)) }
    attempt("unknown account") { bank.transfer("CH-1", "XX", Money.of(1)) }
    carol.frozen = true
    attempt("transfer into frozen account") { bank.transfer("SV-1", "CH-2", Money.of(10)) }
    println("alice after refund: ${alice.balance}, bob: ${bob.balance}")
    for (a in listOf(alice, bob, carol)) a.endOfMonth()
    println(bank.report())
    println("total: ${bank.total()}")
    println("alice journal:")
    alice.journal.forEach { println("  $it") }
    println("bob journal:")
    bob.journal.forEach { println("  $it") }
    println("transactions: " + alice.history.joinToString { it::class.simpleName + ":" + it.amount })
    println(Money.of(3, 5) * 4 - Money.of(1) > Money.of(10))
    println(listOf(Money.of(5), Money.of(1), Money.of(3)).sorted().joinToString())
}
