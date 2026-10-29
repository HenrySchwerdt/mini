// No @file:MemoryMode: this is Managed code, plain Kotlin. Objects are reference counted and
// released automatically; collections, lambdas and shared references just work.

class Account(val owner: String, var balance: Int)

fun main() {
    val accounts = mutableListOf(Account("ann", 10), Account("bo", 20))
    accounts.forEach { it.balance += 5 }
    println(accounts.map { it.balance })

    val byOwner = HashMap<String, Account>()
    for (a in accounts) byOwner[a.owner] = a      // two references to every Account
    println(byOwner["bo"]?.balance)

    accounts.clear()                              // still alive: byOwner holds them
    println(byOwner.size)
}
