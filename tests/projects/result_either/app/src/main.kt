import either.*

sealed class ValidationError(val message: String) {
    class Empty(field: String) : ValidationError("$field must not be empty")
    class TooLong(field: String, val max: Int) : ValidationError("$field is longer than $max")
    class NotNumber(field: String, val text: String) : ValidationError("$field is not a number: '$text'")
    class Range(field: String, val lo: Int, val hi: Int) : ValidationError("$field must be in $lo..$hi")
}

data class User(val name: String, val age: Int, val email: String)

fun nonEmpty(field: String, s: String): Either<ValidationError, String> =
    if (s.isBlank()) ValidationError.Empty(field).left() else s.trim().right()

fun maxLen(field: String, max: Int, s: String): Either<ValidationError, String> =
    if (s.length > max) ValidationError.TooLong(field, max).left() else s.right()

fun number(field: String, s: String): Either<ValidationError, Int> = s.trim().toIntOrNull().toEither { ValidationError.NotNumber(field, s) }

fun ranged(field: String, lo: Int, hi: Int, n: Int): Either<ValidationError, Int> = if (n in lo..hi) n.right() else ValidationError.Range(field, lo, hi).left()

fun parseUser(name: String, age: String, email: String): Either<ValidationError, User> =
    zip(
        nonEmpty("name", name).flatMap { maxLen("name", 10, it) },
        zip(number("age", age).flatMap { ranged("age", 0, 150, it) }, nonEmpty("email", email)) { a, e -> a to e },
    ) { n, (a, e) -> User(n, a, e) }

fun startRight(): Either<String, Int> = 40.right()

fun main() {
    val inputs = listOf(
        Triple("ann", "31", "ann@example.org"),
        Triple("", "20", "x@y"),
        Triple("bartholomew the third", "20", "b@c"),
        Triple("cy", "abc", "c@d"),
        Triple("di", "200", "d@e"),
        Triple("ed", "40", ""),
    )
    val results = inputs.map { (n, a, e) -> parseUser(n, a, e) }
    for ((i, r) in results.withIndex()) {
        println("$i: " + r.fold({ "invalid: ${it.message}" }, { "ok ${it.name} (${it.age}) <${it.email}>" }))
    }
    val (errors, users) = results.partitionEithers()
    println("${users.size} valid, ${errors.size} invalid: " + errors.map { it::class.simpleName })
    println(results.sequence().fold({ "first error: ${it.message}" }, { "all valid" }))
    println(results.take(1).sequence().map { it.map { u -> u.name } })
    val parsed = listOf("1", "2", "x", "4").traverse { number("n", it) }
    println(parsed.leftOrNull()?.message)
    println(listOf("10", "20", "30").traverse { number("n", it) }.map { it.sum() })
    val safe = Either.catching { "12x".toInt() }
    println(safe.mapLeft { it::class.simpleName }.getOrElse { -1 }.toString())
    val half = { n: Int -> if (n % 2 == 0) (n / 2).right() else "odd $n".left() }
    println((1..4).map { half(it).flatMap(half) })
    println(startRight().flatMap(half).flatMap(half).flatMap(half).onRight { println("reached $it") }.onLeft { println("stopped at $it") })
    val e: Either<String, Int> = "bad".left()
    println(e.getOrNull().toString() + " " + e.getOrElse { it.length } + " " + e.isLeft + " " + 5.right().isRight)
}
