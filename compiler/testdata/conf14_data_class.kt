data class User(val id: Int, val name: String, val tags: List<String> = emptyList(), val email: String? = null)

data class Pt(val x: Double, val y: Double)

@JvmInline value class Meters(val v: Double) {
    operator fun plus(o: Meters) = Meters(v + o.v)
}

fun main() {
    val u = User(1, "ann", listOf("a"))
    println(u)
    val u2 = u.copy(name = "bob", email = "b@x")
    println(u2)
    println(u == u.copy())
    println(u == u2)
    println(u.hashCode() == u.copy().hashCode())
    val (id, name, tags, email) = u2
    println("$id $name $tags $email")
    println(u.component2() + u.component1())
    println(Pt(1.0, 2.0).copy(y = 5.0))
    println(Pt(0.0, -0.0) == Pt(0.0, 0.0))
    println(setOf(u, u.copy(), u2).size)
    println(mapOf(u to 1)[u.copy()])
    println(Meters(2.0) + Meters(3.5))
    println(listOf(u, u2).map(User::name))
    println(listOf(u, u2).sortedByDescending { it.id }.first().name)
    println(u.toString().length)
    println(User(3, "c").tags.isEmpty())
    val nested = User(4, "n", listOf("x", "y"), null)
    println(nested.copy(tags = nested.tags + "z"))
}
