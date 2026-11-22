fun parse(s: String): Result<Int> = runCatching { s.toInt() }

fun main() {
    val ok = parse("12")
    val bad = parse("zz")
    println(ok.isSuccess to bad.isFailure)
    println(ok.getOrNull() to bad.getOrNull())
    println(ok.getOrDefault(0) + bad.getOrDefault(-1))
    println(ok.getOrElse { -5 } + bad.getOrElse { -5 })
    println(ok.map { it * 2 }.getOrNull())
    println(bad.map { it * 2 }.isFailure)
    println(bad.recover { 100 }.getOrNull())
    println(bad.exceptionOrNull() is NumberFormatException)
    println(ok.fold({ "v$it" }, { "e" }))
    println(bad.fold({ "v$it" }, { "e:" + it.javaClass2() }))
    println(ok.onSuccess { println("succ $it") }.onFailure { println("fail") }.isSuccess)
    bad.onFailure { println("failed ${it::class.simpleName}") }
    println(runCatching { error("boom") }.exceptionOrNull()?.message)
    println(Result.success(1).isSuccess)
    println(Result.failure<Int>(IllegalStateException("f")).isFailure)
    val r = runCatching { listOf(1, 2, 3).first { it > 5 } }
    println(r.exceptionOrNull() is NoSuchElementException)
    println(parse("5").mapCatching { it / 0 }.isFailure)
    try { bad.getOrThrow() } catch (e: NumberFormatException) { println("thrown") }
    println(listOf("1", "x", "3").map { parse(it) }.filter { it.isSuccess }.map { it.getOrThrow() })
    println(listOf("1", "2").mapNotNull { it.toIntOrNull() }.sum())
}

fun Throwable.javaClass2() = this::class.simpleName
