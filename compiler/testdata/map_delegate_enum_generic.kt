class User(val map: Map<String, Any?>) {
    val name: String by map
    val age: Int by map
}

class MUser(val map: MutableMap<String, Any?>) {
    var name: String by map
    var age: Int by map
}

enum class E { A, B }
fun <T : Enum<T>> nm(e: T): String = e.name + e.ordinal

fun main() {
    val u = User(mapOf("name" to "Ann", "age" to 30))
    println(u.name + u.age)
    val m = mutableMapOf<String, Any?>("name" to "Bob", "age" to 1)
    val mu = MUser(m)
    mu.age = 5
    mu.name = "Zed"
    println(mu.name + mu.age + m)
    println(nm(E.B))
    try { val bad: String by mapOf<String, Any?>(); println(bad) } catch (e: NoSuchElementException) { println(e.message) }
    val lz by lazy { 42 }
    println(lz)
}
