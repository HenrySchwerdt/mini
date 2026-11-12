import validk.*

data class User(val name: String, val age: Int, val password: String, val role: String)

val ageRule: Rule<Int> = Rules.between(18, 99)

fun checkUser(u: User): Report = validate(u) {
    field("name", { it.name }) {
        rule(Rules.notBlank and Rules.minLength(3))
    }
    field("age", { it.age }) {
        rule(ageRule)
        require("must be even") { it % 2 == 0 }
    }
    field("password", { it.password }) {
        check(Rules.password, Rules.notBlank)
    }
    field("role", { it.role }) {
        rule(Rules.oneOf("admin", "dev", "guest"))
    }
}

fun main() {
    val users = listOf(
        User("alice", 30, "s3cret-pw", "dev"),
        User("al", 17, "hunter2", "root"),
        User("", 101, "abc", "admin"),
    )
    for (u in users) {
        val r = checkUser(u)
        println(u.name.ifEmpty { "<empty>" } + " -> " + r)
        println("  valid=" + r.isValid + " size=" + r.size + " hasAge=" + ("age" in r) + " hasRole=" + ("role" in r))
    }
    val reports = users.validateAll { field("name", { it.name }) { rule(Rules.minLength(3)) } }
    println(reports.map { it.size })
    println(checkUser(users[2]).byField())
    println(checkUser(users[1]).firstOrNull())
    println(checkUser(users[0]).firstOrNull())

    val lenRule = Rules.minLength(2).on<List<String>, String> { it.first() }
    println(lenRule.check(listOf("a")))
    println(lenRule.check(listOf("ab")))

    val rep = validate(mapOf("n" to 5, "s" to "x")) {
        typed<Int>("n", 5) { rule(Rules.between(1, 3)) }
        typed<String>("s", 42) { rule(Rules.notBlank) }
        fail("custom", "always")
    }
    println(rep)
    for (v in rep) println(v.field.raw + "/" + v.field)
    println(FieldName("zip"))
    println(Rules.between("b", "d").check("e"))
}
