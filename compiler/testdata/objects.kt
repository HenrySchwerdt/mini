object Registry {
    var count = 0
    val label = "registry"
    fun register(name: String): Int {
        count += 1
        println("register " + name + " -> " + count)
        return count
    }
}

object Lazy {
    init {
        println("Lazy initialised")
    }
    fun hello() = "hello from Lazy"
}

interface Greeter {
    fun greet(): String
}

object English : Greeter {
    override fun greet() = "Hello"
}

class Account private constructor(val id: Int, var balance: Int) {
    companion object {
        var created = 0
        const val BANK = "MiniBank"
        fun open(initial: Int): Account {
            created++
            return Account(created, initial)
        }
    }

    fun show() = BANK + " #" + id + ": " + balance
}

class Factory {
    companion object Maker {
        fun make() = Factory()
    }
    val tag = "factory"
}

fun main() {
    println("start")
    println(Registry.label)
    Registry.register("a")
    Registry.register("b")
    println(Registry.count)
    println("before lazy")
    println(Lazy.hello())
    println(Lazy.hello())
    val g: Greeter = English
    println(g.greet())
    val a1 = Account.open(100)
    val a2 = Account.open(250)
    a1.balance += 5
    println(a1.show())
    println(a2.show())
    println(Account.created)
    println(Account.BANK)
    println(Factory.make().tag)
    println(Registry === Registry)
}
