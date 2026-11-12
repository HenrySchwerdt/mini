import ledger.*

fun main() {
    val a = Account.open("ann")
    a.deposit(5)
    println(a.balance)
    println(LIMIT)
    record("x")
    println(audit)
    val b = Account("bob")
    a.balance = 7
    println(a.audit())
    println("s".shout())
    println(rate)
    println(Registry.size())
    val c: Cents = 5L
    println(Account.Secret(3).v)
    println(a.fee())
}
