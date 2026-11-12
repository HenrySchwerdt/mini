import core.*
import rules.*

data class Signup(val user: String, val mail: String, val age: Int, val tags: List<String>)

val signupRule: Rule<Signup> = allOf(
    username.on({ s: Signup -> s.user }, "user"),
    email.on({ s: Signup -> s.mail }, "mail"),
    inRange(13, 120).on({ s: Signup -> s.age }, "age"),
    noDuplicates<String>().on({ s: Signup -> s.tags }, "tags"),
)

data class Wrapper(val signup: Signup, val note: String)

fun main() {
    println(coreVersion() + " " + signupRule.name)
    println(email.describe())
    val cases = listOf(
        Signup("ann_1", "ann@example.org", 30, listOf("a", "b")),
        Signup("A", "nope", 12, listOf("x", "x", "y", "y", "z")),
        Signup("averyverylongname", "a@b.c", 200, emptyList()),
        Signup("bob", "bob@site.com", 13, listOf("one")),
    )
    for (c in cases) {
        val r = signupRule.check(c, "")
        println("${c.user}: $r")
    }
    val nested = allOf(signupRule.on({ w: Wrapper -> w.signup }, "signup"), notBlank().on({ w: Wrapper -> w.note }, "note"))
    println(nested.check(Wrapper(cases[1], " "), ""))
    println(nested.check(Wrapper(cases[0], "fine"), "").ok)
    println(atLeast("m").check("a", "letter"))
    println((Report.OK + Report.of("x", "bad")).problems)
}
