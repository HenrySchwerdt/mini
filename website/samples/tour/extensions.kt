// Extension functions and properties, null safety, safe calls, elvis, smart casts, lazy.

fun Int.isEven(): Boolean = this % 2 == 0
fun String.shout(): String = uppercase() + "!"
fun String?.orDash(): String = this ?: "-"

val Int.squared: Int get() = this * this

class User(val name: String, val email: String?)

fun domain(u: User): String? = u.email?.substringAfter('@')

fun describe(x: Any?): String = when (x) {
    null -> "null"
    is Int -> "int ${x + 1}"            // smart cast to Int
    is String -> "string of length ${x.length}"
    else -> "something else"
}

val expensive: Int by lazy {
    println("computing...")
    42
}

fun main() {
    println(4.isEven())
    println("hey".shout())
    println(null.orDash())
    println(7.squared)

    val alice = User("alice", "alice@example.com")
    val bob = User("bob", null)
    println(domain(alice))
    println(domain(bob))
    println(bob.email ?: "no email")
    println(bob.email?.length)

    println(describe(null))
    println(describe(41))
    println(describe("kotlin"))
    println(describe(1.5))

    println("before")
    println(expensive + expensive)
}
