// ACCEPT: no @file:MemoryMode means Managed: plain Kotlin, no obligations. Spec 6.1

package memory.ok_managed_default

import mini.memory.*

class Account(var balance: Int)

fun main() {
    val accounts = mutableListOf(Account(10), Account(20))
    accounts.forEach { it.balance += 5 }
    println(accounts.map { it.balance })
    val m = HashMap<String, Account>()
    m["x"] = accounts[0]
    println(m["x"]?.balance)
}
