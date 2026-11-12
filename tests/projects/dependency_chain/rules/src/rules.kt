package rules

import core.*

fun notBlank(): Rule<String> = rule("notBlank", { "must not be blank" }) { it.isNotBlank() }
fun minLength(n: Int): Rule<String> = rule("minLength($n)", { "needs at least $n characters, has ${it.length}" }) { it.length >= n }
fun maxLength(n: Int): Rule<String> = rule("maxLength($n)", { "allows at most $n characters, has ${it.length}" }) { it.length <= n }
fun matches(name: String, regex: Regex): Rule<String> = rule(name, { "'$it' does not look like $name" }) { regex.matches(it) }
fun inRange(lo: Int, hi: Int): Rule<Int> = rule("range($lo..$hi)", { "$it is outside $lo..$hi" }) { it in lo..hi }
fun <T : Comparable<T>> atLeast(min: T): Rule<T> = rule("atLeast($min)", { "$it is below $min" }) { it >= min }
fun <T> noDuplicates(): Rule<List<T>> = rule("unique", { l -> "duplicates: " + l.groupingBy { it }.eachCount().filter { it.value > 1 }.keys }) { it.toSet().size == it.size }

val email: Rule<String> = allOf(notBlank(), matches("an e-mail address", Regex("[^@\\s]+@[^@\\s]+\\.[a-z]{2,}")))
val username: Rule<String> = allOf(minLength(3), maxLength(12), matches("a user name", Regex("[a-z][a-z0-9_]*")))

fun <T> Rule<T>.describe(): String = "rule ${name} (from ${coreVersion()})"

internal fun helperNotExported() = "internal helper"
